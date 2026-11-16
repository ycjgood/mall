package com.macro.mall.common.stock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 库存预扣组件单元测试
 * <p>
 * 重点验证四种返回语义，调用方据此决定「继续下单 / 直接拒绝 / 降级数据库」：
 * 扣减成功、库存不足、未预热、Redis 故障。
 */
class RedisStockSupportTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final RedisStockSupport support = new RedisStockSupport(redisTemplate);

    @Test
    @DisplayName("扣减成功：返回 SUCCESS，剩余数量只做日志不参与判断")
    void shouldDeductSuccessfully() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(7L);

        assertThat(support.tryDeduct(99L, 1)).isEqualTo(RedisStockSupport.DeductResult.SUCCESS);
    }

    @Test
    @DisplayName("库存不足：返回 INSUFFICIENT，调用方直接拒绝，不再访问数据库")
    void shouldRejectWhenInsufficient() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(-2L);

        assertThat(support.tryDeduct(99L, 5)).isEqualTo(RedisStockSupport.DeductResult.INSUFFICIENT);
    }

    @Test
    @DisplayName("未预热：返回 NOT_PREHEATED，调用方降级到数据库")
    void shouldFallbackWhenNotPreheated() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(-1L);

        assertThat(support.tryDeduct(99L, 1)).isEqualTo(RedisStockSupport.DeductResult.NOT_PREHEATED);
    }

    @Test
    @DisplayName("Redis 故障：降级为 NOT_PREHEATED，不影响功能可用性")
    void shouldFallbackWhenRedisDown() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(support.tryDeduct(99L, 1)).isEqualTo(RedisStockSupport.DeductResult.NOT_PREHEATED);
    }

    @Test
    @DisplayName("非法入参：数量为 0 直接判库存不足，不访问 Redis")
    void shouldRejectInvalidQuantity() {
        assertThat(support.tryDeduct(99L, 0)).isEqualTo(RedisStockSupport.DeductResult.INSUFFICIENT);
        verify(redisTemplate, org.mockito.Mockito.never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("回补：key 不存在时不累加，避免凭空造出库存")
    void shouldNotRestoreWhenKeyMissing() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(-1L);

        assertThat(support.restore(99L, 1)).isFalse();
    }

    @Test
    @DisplayName("回补：key 存在时累加成功")
    void shouldRestoreWhenKeyExists() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(11L);

        assertThat(support.restore(99L, 1)).isTrue();
    }

    @Test
    @DisplayName("预热：overwrite=true 覆盖写入，false 用 setIfAbsent 避免覆盖正在扣减的计数")
    void shouldPreheatWithCorrectSemantics() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        support.preheat(99L, 100, true);
        verify(valueOps).set("mall:stock:sku:99", "100");

        support.preheat(99L, 50, false);
        verify(valueOps).setIfAbsent("mall:stock:sku:99", "50");
    }

    @Test
    @DisplayName("查询当前值：未预热返回 null")
    void shouldReturnNullWhenNotPreheated() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        assertThat(support.current(99L)).isNull();
    }
}
