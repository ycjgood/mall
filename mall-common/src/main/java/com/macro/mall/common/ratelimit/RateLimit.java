package com.macro.mall.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解
 * <p>
 * 用法示例：
 * <pre>{@code
 * @RateLimit(key = "admin:login", permits = 5, window = 60, dimension = RateLimit.Dimension.IP)
 * public CommonResult login(...) { ... }
 * }</pre>
 * 基于 Redis + Lua 的固定窗口计数，天然支持多实例集群限流（计数在 Redis 里共享）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 限流业务标识，不同接口需使用不同 key；为空时默认取 "类名.方法名"
     */
    String key() default "";

    /**
     * 窗口内允许的最大请求次数
     */
    int permits() default 10;

    /**
     * 窗口长度（秒）
     */
    int window() default 60;

    /**
     * 限流维度
     */
    Dimension dimension() default Dimension.IP;

    /**
     * 触发限流时的提示语，支持 %d 占位符表示剩余秒数；
     * 为空时使用 "请求过于频繁，请 %d 秒后重试"
     */
    String message() default "";

    /**
     * 限流维度
     */
    enum Dimension {
        /**
         * 按客户端 IP 限流，适合登录、注册、短信验证码等未登录接口
         */
        IP,
        /**
         * 按登录用户限流，适合下单、支付等需要身份的接口
         */
        USER,
        /**
         * 全局限流，适合保护昂贵的下游资源（如导出、搜索）
         */
        GLOBAL
    }
}
