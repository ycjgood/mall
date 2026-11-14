package com.macro.mall.common.ratelimit;

import com.macro.mall.common.api.ResultCode;
import com.macro.mall.common.exception.ApiException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 限流切面单元测试
 * <p>
 * 验证三条关键行为：
 * 1. 配额未超时正常放行
 * 2. 超限时抛出 429 业务异常，并带上剩余秒数
 * 3. Redis 故障时降级放行（限流不能成为业务的新单点）
 */
class RateLimitAspectTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final RateLimitAspect aspect = new RateLimitAspect(redisTemplate, true);

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("未超限：放行并执行业务方法")
    void shouldPassWhenUnderLimit() throws Throwable {
        prepareRequest();
        ProceedingJoinPoint joinPoint = mockJoinPoint();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.around(joinPoint, rateLimitOf("doSomething"));

        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("已超限：抛出 429，提示剩余秒数，不执行业务方法")
    void shouldThrowWhenOverLimit() throws Throwable {
        prepareRequest();
        ProceedingJoinPoint joinPoint = mockJoinPoint();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(42L);

        assertThatThrownBy(() -> aspect.around(joinPoint, rateLimitOf("doSomething")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiException = (ApiException) e;
                    assertThat(apiException.getErrorCode().getCode()).isEqualTo(ResultCode.TOO_MANY_REQUESTS.getCode());
                    assertThat(apiException.getMessage()).contains("42");
                });
    }

    @Test
    @DisplayName("Redis 故障：降级放行，不影响业务")
    void shouldFailOpenWhenRedisDown() throws Throwable {
        prepareRequest();
        ProceedingJoinPoint joinPoint = mockJoinPoint();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.around(joinPoint, rateLimitOf("doSomething"));

        assertThat(result).isEqualTo("ok");
    }

    @Test
    @DisplayName("开关关闭：直接放行，不访问 Redis（供压测/联调使用）")
    void shouldBypassWhenDisabled() throws Throwable {
        prepareRequest();
        ProceedingJoinPoint joinPoint = mockJoinPoint();
        when(joinPoint.proceed()).thenReturn("ok");
        RateLimitAspect disabledAspect = new RateLimitAspect(redisTemplate, false);

        Object result = disabledAspect.around(joinPoint, rateLimitOf("doSomething"));

        assertThat(result).isEqualTo("ok");
        org.mockito.Mockito.verifyNoInteractions(redisTemplate);
    }

    private void prepareRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.20.30.40");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ProceedingJoinPoint mockJoinPoint() {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getDeclaringType()).thenReturn((Class) DemoService.class);
        when(signature.getName()).thenReturn("doSomething");
        when(joinPoint.getSignature()).thenReturn(signature);
        return joinPoint;
    }

    private RateLimit rateLimitOf(String methodName) throws NoSuchMethodException {
        Method method = DemoService.class.getMethod(methodName);
        return method.getAnnotation(RateLimit.class);
    }

    /**
     * 被限流的目标方法（仅用于取注解元数据）
     */
    static class DemoService {
        @RateLimit(key = "demo", permits = 3, window = 60, dimension = RateLimit.Dimension.IP,
                message = "请求过于频繁，请 %d 秒后重试")
        public String doSomething() {
            return "ok";
        }
    }
}
