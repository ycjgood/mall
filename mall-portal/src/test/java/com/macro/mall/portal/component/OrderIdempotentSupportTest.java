package com.macro.mall.portal.component;

import com.macro.mall.common.exception.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 下单幂等令牌单元测试
 * <p>
 * 覆盖：正常生成与消费、令牌为空、令牌已被消费（重复提交）、Redis 故障降级。
 */
@ExtendWith(MockitoExtension.class)
class OrderIdempotentSupportTest {

    private static final String SAMPLED_TOKEN = "7a7f63db42794c1e9d5b1c2f3a4b5c6d";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private OrderIdempotentSupport idempotentSupport;

    @Test
    @DisplayName("生成令牌：返回 32 位十六进制串，写入 Redis 且 TTL 为 10 分钟、value 为会员 ID")
    void generateToken_shouldStoreUuidWith10MinutesTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String token = idempotentSupport.generateToken(9L);

        assertEquals(32, token.length(), "令牌应为去横线的 UUID");
        assertTrue(token.matches("[0-9a-f]{32}"), "令牌应为十六进制字符串");
        verify(valueOperations).set(eq("mall:order:token:" + token), eq("9"), eq(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("消费令牌：令牌为空时直接拒绝，不访问 Redis")
    void consume_blankToken_shouldRejectWithoutRedis() {
        ApiException ex = assertThrows(ApiException.class,
                () -> idempotentSupport.consume(1L, "  "));
        assertTrue(ex.getMessage().contains("令牌缺失"));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("消费令牌：脚本返回 0（已消费/过期）时拒绝重复提交")
    void consume_usedToken_shouldRejectDuplicate() {
        when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any()))
                .thenReturn(0L);

        ApiException ex = assertThrows(ApiException.class,
                () -> idempotentSupport.consume(1L, SAMPLED_TOKEN));
        assertTrue(ex.getMessage().contains("重复提交"));
    }

    @Test
    @DisplayName("消费令牌：脚本返回 1 时放行，key 带前缀、arg 为会员 ID")
    void consume_validToken_shouldPass() {
        when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any()))
                .thenReturn(1L);

        assertDoesNotThrow(() -> idempotentSupport.consume(1L, SAMPLED_TOKEN));

        verify(redisTemplate).execute(
                ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of("mall:order:token:" + SAMPLED_TOKEN)),
                eq("1"));
    }

    @Test
    @DisplayName("消费令牌：Redis 不可用时 fail-open 放行，不阻断交易")
    void consume_redisDown_shouldFailOpen() {
        when(redisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any()))
                .thenThrow(new QueryTimeoutException("redis timeout"));

        assertDoesNotThrow(() -> idempotentSupport.consume(1L, SAMPLED_TOKEN),
                "Redis 故障时应放行下单");
    }

    @Test
    @DisplayName("生成令牌：Redis 不可用时仍返回令牌，降级为不做幂等保护")
    void generateToken_redisDown_shouldStillReturnToken() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new QueryTimeoutException("redis timeout"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        String token = idempotentSupport.generateToken(1L);

        assertNotNull(token);
        assertEquals(32, token.length());
    }
}
