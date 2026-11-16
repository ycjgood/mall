package com.macro.mall.common.stock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * Redis 库存预扣组件（秒杀级流量的第一道闸门）
 *
 * <h3>为什么需要它</h3>
 * 数据库的条件原子更新（{@code WHERE stock - lock_stock >= ?}）能保证不超卖，但每笔请求都要
 * 开事务、加行锁、写 undo log。秒杀场景下 99% 的请求其实注定失败（库存早被抢光），
 * 让它们打到数据库是纯粹的浪费：既拖慢成功请求，也可能把连接池打满。
 * <p>
 * 把"可售数量"预热到 Redis 后，先用一条 Lua 脚本做原子「校验 + 扣减」，
 * 扣不到的请求直接返回，<b>一次数据库访问都不会产生</b>。
 *
 * <h3>一致性设计（重要）</h3>
 * <ul>
 *     <li><b>Redis 是拦截层，数据库是权威</b>：预扣成功后仍会执行数据库原子更新，
 *         Redis 只决定"要不要放行"，不决定最终库存数字。</li>
 *     <li><b>未预热不阻断</b>：key 不存在时返回 {@link DeductResult#NOT_PREHEATED}，
 *         调用方降级走数据库，功能可用性优先于性能。</li>
 *     <li><b>Redis 故障不阻断</b>：捕获异常同样返回 {@code NOT_PREHEATED}，
 *         避免缓存故障演变成业务不可用。</li>
 *     <li><b>回滚要补偿</b>：Redis 扣减成功后若数据库事务回滚（订单落库失败等），
 *         调用方需在事务回滚时调用 {@link #restore(Long, int)} 还回去，
 *         否则 Redis 计数会越跑越少（俗称"库存蒸发"）。</li>
 * </ul>
 *
 * <h3>已知取舍</h3>
 * Redis 重启或主从切换可能丢计数。生产应开启 AOF 持久化；丢失后由预热接口
 * （mall-admin 的 {@code POST /skuStock/preheat}）以数据库为准重建。
 */
@Component
@ConditionalOnClass(StringRedisTemplate.class)
public class RedisStockSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisStockSupport.class);

    /**
     * 单个 SKU 可售库存的 key 前缀，值为"可售数量" = stock - lock_stock
     */
    public static final String KEY_PREFIX = "mall:stock:sku:";

    /**
     * Lua 原子脚本：读取 → 判断 → 扣减，三步在一次执行内完成。
     * <p>
     * 返回值约定：
     * <ul>
     *     <li>{@code -1}：key 不存在（未预热），调用方应降级到数据库</li>
     *     <li>{@code -2}：库存不足，直接拒绝</li>
     *     <li>{@code >=0}：扣减成功，值为扣减后的剩余可售数量</li>
     * </ul>
     * 不能拆成多次 Redis 命令：GET 与 DECRBY 之间如果被并发穿插，就会超扣。
     */
    private static final String LUA_DEDUCT = """
            local cur = redis.call('GET', KEYS[1])
            if not cur then return -1 end
            local available = tonumber(cur)
            local need = tonumber(ARGV[1])
            if available < need then return -2 end
            return redis.call('DECRBY', KEYS[1], need)
            """;

    /**
     * Lua 原子脚本：仅当 key 存在时累加。
     * <p>
     * 不能用 {@code INCRBY} 直接加：Redis 的 INCR 系列在 key 不存在时会创建并从 0 开始，
     * 那就等于凭空变出了库存（例如取消了一笔未走预扣模式的历史订单）。
     */
    private static final String LUA_RESTORE = """
            local cur = redis.call('GET', KEYS[1])
            if not cur then return -1 end
            return redis.call('INCRBY', KEYS[1], ARGV[1])
            """;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> deductScript;
    private final DefaultRedisScript<Long> restoreScript;

    public RedisStockSupport(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.deductScript = new DefaultRedisScript<>(LUA_DEDUCT, Long.class);
        this.restoreScript = new DefaultRedisScript<>(LUA_RESTORE, Long.class);
    }

    /**
     * 预扣减库存
     *
     * @param skuId    SKU 主键
     * @param quantity 购买数量
     * @return 扣减结果，调用方据 {@link DeductResult#NOT_PREHEATED} 决定是否降级
     */
    public DeductResult tryDeduct(Long skuId, int quantity) {
        if (skuId == null || quantity <= 0) {
            return DeductResult.INSUFFICIENT;
        }
        try {
            Long remaining = redisTemplate.execute(deductScript,
                    Collections.singletonList(key(skuId)), String.valueOf(quantity));
            if (remaining == null) {
                return DeductResult.NOT_PREHEATED;
            }
            if (remaining == -1L) {
                return DeductResult.NOT_PREHEATED;
            }
            if (remaining == -2L) {
                return DeductResult.INSUFFICIENT;
            }
            return DeductResult.SUCCESS;
        } catch (Exception e) {
            // 降级：Redis 异常不能让下单整体不可用，交给数据库兜底
            LOGGER.error("[库存预扣降级] Redis 异常，改走数据库 skuId={} quantity={}", skuId, quantity, e);
            return DeductResult.NOT_PREHEATED;
        }
    }

    /**
     * 只读探测：当前是否还够扣（不扣减）
     * <p>
     * 用于下单入口的「快速失败」：库存明显不足时直接返回，
     * 不必再走促销计算、优惠券、积分等十几条数据库查询。
     * <p>
     * 这里的判断<b>不保证</b>真的能扣到（并发下会被别人抢先），
     * 它只负责把明显不可能成功的请求早点挡掉，正确性仍由随后的原子扣减保证。
     *
     * @return true=可以继续（含未预热/Redis 异常等需要降级的情况）；false=确定库存不足
     */
    public boolean isAvailable(Long skuId, int quantity) {
        if (skuId == null || quantity <= 0) {
            return false;
        }
        try {
            String value = redisTemplate.opsForValue().get(key(skuId));
            if (value == null) {
                // 未预热：不阻断，交给数据库判断
                return true;
            }
            return Integer.parseInt(value) >= quantity;
        } catch (Exception e) {
            LOGGER.error("[库存探测降级] Redis 异常，按可用处理 skuId={}", skuId, e);
            return true;
        }
    }

    /**
     * 回补库存（订单取消、超时关闭、下单事务回滚时调用）
     * <p>
     * 只在 key 存在时累加：如果 Redis 里本来就没有这个 SKU 的计数，
     * 说明当前不是预扣模式或数据已被清理，此时加一个值反而会造出"凭空多出来的库存"。
     *
     * @return true 表示已回补；false 表示 key 不存在，无需回补
     */
    public boolean restore(Long skuId, int quantity) {
        if (skuId == null || quantity <= 0) {
            return false;
        }
        try {
            Long after = redisTemplate.execute(restoreScript,
                    Collections.singletonList(key(skuId)), String.valueOf(quantity));
            if (after == null || after < 0) {
                LOGGER.debug("[库存回补跳过] skuId={} 未预热，无需回补", skuId);
                return false;
            }
            LOGGER.info("[库存回补] skuId={} quantity={} 当前可售={}", skuId, quantity, after);
            return true;
        } catch (Exception e) {
            // 回补失败只记录：数据库侧仍是权威数据，可通过预热接口重建
            LOGGER.error("[库存回补失败] skuId={} quantity={}，请用预热接口重建", skuId, quantity, e);
            return false;
        }
    }

    /**
     * 预热：把数据库的可售数量写入 Redis
     *
     * @param overwrite true=强制覆盖（运营主动重置）；false=仅当 key 不存在时写入（避免覆盖正在扣减的计数）
     */
    public void preheat(Long skuId, int available, boolean overwrite) {
        if (skuId == null || available < 0) {
            return;
        }
        String key = key(skuId);
        String value = String.valueOf(available);
        if (overwrite) {
            redisTemplate.opsForValue().set(key, value);
        } else {
            redisTemplate.opsForValue().setIfAbsent(key, value);
        }
    }

    /**
     * 查询当前可售数量，null 表示未预热
     */
    public Integer current(Long skuId) {
        String value = redisTemplate.opsForValue().get(key(skuId));
        return value == null ? null : Integer.valueOf(value);
    }

    private String key(Long skuId) {
        return KEY_PREFIX + skuId;
    }

    /**
     * 预扣结果
     */
    public enum DeductResult {
        /**
         * 扣减成功
         */
        SUCCESS,
        /**
         * 库存不足（Redis 侧已拒绝，无需访问数据库）
         */
        INSUFFICIENT,
        /**
         * 未预热或 Redis 异常，调用方应降级到数据库
         */
        NOT_PREHEATED
    }
}
