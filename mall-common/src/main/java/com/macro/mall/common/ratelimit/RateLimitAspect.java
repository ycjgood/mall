package com.macro.mall.common.ratelimit;

import cn.hutool.core.util.StrUtil;
import com.macro.mall.common.api.ResultCode;
import com.macro.mall.common.exception.ApiException;
import com.macro.mall.common.util.RequestUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;

/**
 * 限流切面
 * <p>
 * 实现要点：
 * <ol>
 *     <li><b>原子性</b>：INCR + EXPIRE + TTL 在 Lua 里一次执行，避免"计数成功但过期没设上"导致 key 永不过期</li>
 *     <li><b>集群一致</b>：计数存在 Redis，多实例部署时是同一个计数器</li>
 *     <li><b>故障降级（fail-open）</b>：Redis 挂了只记录日志并放行，限流组件不能成为业务的新单点</li>
 *     <li><b>返回剩余时间</b>：把 TTL 返回给调用方，前端可据此提示"X 秒后重试"</li>
 * </ol>
 * <p>
 * 条件装配：mall-search 等模块在 pom 中排除了 spring-boot-starter-data-redis，
 * 若不加此条件，@Aspect 被扫描时字节码解析会触发 NoClassDefFoundError: RedisScript。
 */
@Aspect
@Component
@Order(2)
@ConditionalOnClass(StringRedisTemplate.class)
public class RateLimitAspect {
    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitAspect.class);
    /**
     * Redis key 前缀，统一收敛便于在 Redis 里批量查看/清理
     */
    private static final String KEY_PREFIX = "mall:rate:";

    /**
     * 返回值语义：0 表示放行；大于 0 表示被限流，值为剩余秒数
     */
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local current = redis.call('INCR', key)
            if current == 1 then
                redis.call('EXPIRE', key, window)
            end
            if current > limit then
                local ttl = redis.call('TTL', key)
                if ttl < 0 then
                    redis.call('EXPIRE', key, window)
                    ttl = window
                end
                return ttl
            end
            return 0
            """;

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;
    /**
     * 全局开关：压测、本地联调时用 --mall.rate-limit.enabled=false 关闭，
     * 避免限流把 TPS 压成 0 而无法测出真实容量。生产环境保持默认开启。
     */
    private final boolean enabled;

    public RateLimitAspect(StringRedisTemplate stringRedisTemplate,
                           @Value("${mall.rate-limit.enabled:true}") boolean enabled) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rateLimitScript = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
        this.enabled = enabled;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        if (!enabled) {
            return joinPoint.proceed();
        }
        String redisKey = buildKey(joinPoint, rateLimit);
        Long ttl;
        try {
            ttl = stringRedisTemplate.execute(rateLimitScript, Collections.singletonList(redisKey),
                    String.valueOf(rateLimit.permits()), String.valueOf(rateLimit.window()));
        } catch (Exception e) {
            // 降级放行：限流依赖故障时优先保证业务可用
            LOGGER.error("[限流降级] Redis 不可用，本次请求放行 key={}", redisKey, e);
            return joinPoint.proceed();
        }
        if (ttl != null && ttl > 0) {
            LOGGER.warn("[触发限流] key={} permits={}/{}s remain={}s", redisKey,
                    rateLimit.permits(), rateLimit.window(), ttl);
            throw new ApiException(ResultCode.TOO_MANY_REQUESTS, buildMessage(rateLimit.message(), ttl));
        }
        return joinPoint.proceed();
    }

    private String buildMessage(String template, long ttl) {
        String text = StrUtil.isNotBlank(template) ? template : "请求过于频繁，请 %d 秒后重试";
        return text.contains("%d") ? String.format(text, ttl) : text;
    }

    private String buildKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        String bizKey = rateLimit.key();
        if (StrUtil.isBlank(bizKey)) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            bizKey = signature.getDeclaringType().getSimpleName() + "." + signature.getName();
        }
        String dimensionValue;
        switch (rateLimit.dimension()) {
            case USER:
                dimensionValue = resolveUser();
                break;
            case GLOBAL:
                dimensionValue = "global";
                break;
            case IP:
            default:
                dimensionValue = resolveIp();
                break;
        }
        return KEY_PREFIX + bizKey + ":" + dimensionValue;
    }

    private String resolveIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return "no-request";
        }
        String ip = RequestUtil.getRequestIp(request);
        return StrUtil.isBlank(ip) ? "unknown" : ip;
    }

    private String resolveUser() {
        HttpServletRequest request = currentRequest();
        if (request != null && StrUtil.isNotBlank(request.getRemoteUser())) {
            return request.getRemoteUser();
        }
        // 未登录时退化为 IP 维度，避免所有匿名用户共用一个计数器被互相影响
        return resolveIp();
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getRequest();
    }
}
