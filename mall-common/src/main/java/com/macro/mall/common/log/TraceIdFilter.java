package com.macro.mall.common.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路追踪过滤器
 * <p>
 * 为每个进入应用的 HTTP 请求分配唯一 traceId，写入 MDC 供日志输出，
 * 同时回写到响应头 X-Trace-Id，方便前端/运维把一次报错与后端日志对应起来。
 * <p>
 * 约定：上游（网关、前端）如果已经带了 X-Trace-Id 则沿用，实现跨服务串联。
 * 注意：必须放在过滤器链最前面（最高优先级），保证后续所有日志都带 traceId。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /**
     * 请求头/响应头名称
     */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    /**
     * MDC 中的键名，与 logback 配置里的 %X{traceId} 对应
     */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理，否则线程复用会把 traceId 串到下一个请求上
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId != null && !traceId.isBlank() && traceId.length() <= 64) {
            return traceId.trim();
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
