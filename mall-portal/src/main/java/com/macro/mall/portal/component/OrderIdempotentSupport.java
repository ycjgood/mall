package com.macro.mall.portal.component;

import cn.hutool.core.util.StrUtil;
import com.macro.mall.common.exception.Asserts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * 下单幂等令牌
 * <p>
 * 为什么需要：用户手抖连点、网络重试、脚本重放都会让同一个下单请求到达多次，
 * 结果是扣两次库存、生成两张订单。仅靠前端禁用按钮不可靠（接口可被直接调用）。
 * <p>
 * 实现方式（一次性令牌 + Lua 原子消费）：
 * <ol>
 *     <li>前端进入结算页先调 GET /order/token 领取令牌，服务端写入 Redis（10 分钟有效）</li>
 *     <li>提交订单时带上令牌，服务端用 Lua 脚本「比对 + 删除」原子完成消费</li>
 *     <li>同一个令牌第二次提交时，GET 已为空，脚本返回 0，直接拒绝</li>
 * </ol>
 * 用 Lua 而不是 GET 后再 DEL，是为了避免「两个请求同时 GET 到值」的竞态。
 * <p>
 * 故障策略：Redis 不可用时 <b>放行</b>（fail-open）并打 WARN。
 * 理由：幂等保护不能成为下单链路上的新单点，Redis 挂掉时优先保证交易可用，
 * 重复下单的风险由数据库层的唯一约束/人工对账兜底。
 */
@Component
public class OrderIdempotentSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderIdempotentSupport.class);

    /** Redis key 前缀，与限流 key 分开，便于排查与批量清理 */
    private static final String KEY_PREFIX = "mall:order:token:";

    /** 令牌有效期：够用户填写收货信息，又不会长期占用内存 */
    private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    /**
     * 原子消费脚本：value 等于当前会员 ID 才删除并返回 1，否则返回 0。
     * 绑定会员 ID 是为了防止 A 用户拿到 B 用户的令牌替其下单。
     */
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 生成一次性下单令牌
     */
    public String generateToken(Long memberId) {
        String token = UUID.randomUUID().toString().replace("-", "");
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + token, String.valueOf(memberId), TOKEN_TTL);
        } catch (DataAccessException e) {
            LOGGER.warn("生成下单令牌失败，Redis 不可用，本次下单不做幂等保护", e);
            return token;
        }
        return token;
    }

    /**
     * 校验并消费令牌，失败直接抛业务异常中断下单
     *
     * @param memberId 当前登录会员
     * @param token    前端提交的令牌（请求头 X-Order-Token）
     */
    public void consume(Long memberId, String token) {
        if (StrUtil.isBlank(token)) {
            Asserts.fail("下单令牌缺失，请刷新结算页后重试");
        }
        Long result;
        try {
            result = redisTemplate.execute(CONSUME_SCRIPT,
                    Collections.singletonList(KEY_PREFIX + token),
                    String.valueOf(memberId));
        } catch (DataAccessException e) {
            LOGGER.warn("下单令牌校验失败，Redis 不可用，放行本次请求", e);
            return;
        }
        if (result == null || result == 0L) {
            Asserts.fail("订单已提交或令牌已过期，请勿重复提交");
        }
    }
}
