package com.macro.mall.common.log;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.json.JSONUtil;
import com.macro.mall.common.domain.WebLog;
import com.macro.mall.common.util.RequestUtil;
import io.swagger.v3.oas.annotations.Operation;
import net.logstash.logback.marker.Markers;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 统一日志处理切面
 * <p>
 * 在原实现基础上补充了三件企业环境必需的事：
 * <ol>
 *     <li><b>敏感信息脱敏</b>：密码、token、密钥等字段写入日志前替换为 ***，避免明文落盘（等保/审计要求）</li>
 *     <li><b>日志瘦身</b>：超长报文截断，防止大字段撑爆日志文件</li>
 *     <li><b>慢请求告警</b>：耗时超过阈值的请求单独打 WARN，便于巡检与后续接监控</li>
 * </ol>
 * Created by macro on 2018/4/26.
 */
@Aspect
@Component
@Order(1)
public class WebLogAspect {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebLogAspect.class);

    /**
     * 需要脱敏的字段名（JSON 键名维度匹配，忽略大小写）
     */
    private static final Pattern SENSITIVE_FIELD_PATTERN = Pattern.compile(
            "(\"(?:password|oldPassword|newPassword|confirmPassword|payPassword|passwordConfirm|token|accessToken|refreshToken|secret|accessKeySecret|accessKeyId|idCard|bankCard)\"\\s*:\\s*\")[^\"]*(\")",
            Pattern.CASE_INSENSITIVE);

    /**
     * 单条日志中参数/结果的最大长度，超出截断
     */
    private static final int MAX_LOG_LENGTH = 2000;

    /**
     * 慢请求阈值（毫秒）
     */
    private static final long SLOW_REQUEST_MILLIS = 500L;

    @Pointcut("execution(public * com.macro.mall.controller.*.*(..))||execution(public * com.macro.mall.*.controller.*.*(..))")
    public void webLog() {
    }

    @Before("webLog()")
    public void doBefore(JoinPoint joinPoint) throws Throwable {
    }

    @AfterReturning(value = "webLog()", returning = "ret")
    public void doAfterReturning(Object ret) throws Throwable {
    }

    @Around("webLog()")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        //获取当前请求对象
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes != null ? attributes.getRequest() : null;
        //记录请求信息(通过Logstash传入Elasticsearch)
        WebLog webLog = new WebLog();
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable throwable) {
            // 异常也要留下访问记录，否则出错的请求在访问日志里"消失"了
            logAccess(joinPoint, request, webLog, startTime, null, throwable);
            throw throwable;
        }
        logAccess(joinPoint, request, webLog, startTime, result, null);
        return result;
    }

    private void logAccess(ProceedingJoinPoint joinPoint, HttpServletRequest request, WebLog webLog,
                           long startTime, Object result, Throwable throwable) {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        Signature signature = joinPoint.getSignature();
        MethodSignature methodSignature = (MethodSignature) signature;
        Method method = methodSignature.getMethod();
        if (method.isAnnotationPresent(Operation.class)) {
            Operation log = method.getAnnotation(Operation.class);
            webLog.setDescription(log.summary());
        }
        long endTime = System.currentTimeMillis();
        webLog.setUsername(request == null ? null : request.getRemoteUser());
        webLog.setIp(request == null ? null : RequestUtil.getRequestIp(request));
        webLog.setMethod(request == null ? null : request.getMethod());
        webLog.setParameter(truncate(desensitize(JSONUtil.toJsonStr(getParameter(method, joinPoint.getArgs())))));
        webLog.setResult(truncate(desensitize(JSONUtil.toJsonStr(result))));
        webLog.setSpendTime((int) (endTime - startTime));
        webLog.setStartTime(startTime);
        if (request != null) {
            String urlStr = request.getRequestURL().toString();
            webLog.setBasePath(StrUtil.removeSuffix(urlStr, URLUtil.url(urlStr).getPath()));
            webLog.setUri(request.getRequestURI());
            webLog.setUrl(urlStr);
        }

        Map<String, Object> logMap = new HashMap<>();
        logMap.put("traceId", traceId == null ? "" : traceId);
        logMap.put("url", webLog.getUrl());
        logMap.put("method", webLog.getMethod());
        logMap.put("ip", webLog.getIp());
        logMap.put("parameter", webLog.getParameter());
        logMap.put("spendTime", webLog.getSpendTime());
        logMap.put("description", webLog.getDescription());
        if (throwable != null) {
            logMap.put("error", throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
        LOGGER.info(Markers.appendEntries(logMap), JSONUtil.parse(webLog).toString());

        //慢请求单独告警，便于日报巡检/接入监控告警规则
        if (webLog.getSpendTime() >= SLOW_REQUEST_MILLIS) {
            LOGGER.warn("[慢请求] uri={} method={} spendTime={}ms traceId={}",
                    webLog.getUri(), webLog.getMethod(), webLog.getSpendTime(), traceId);
        }
    }

    /**
     * 敏感字段脱敏：把 JSON 中 password/token/secret 一类的值替换为 ***
     */
    private String desensitize(String json) {
        if (StrUtil.isBlank(json) || "null".equals(json)) {
            return json;
        }
        return SENSITIVE_FIELD_PATTERN.matcher(json).replaceAll("$1***$2");
    }

    /**
     * 超长内容截断，避免日志文件被大报文撑爆
     */
    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        if (text.length() <= MAX_LOG_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_LOG_LENGTH) + "...(truncated, total " + text.length() + " chars)";
    }

    /**
     * 根据方法和传入的参数获取请求参数
     */
    private Object getParameter(Method method, Object[] args) {
        List<Object> argList = new ArrayList<>();
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            //将RequestBody注解修饰的参数作为请求参数
            RequestBody requestBody = parameters[i].getAnnotation(RequestBody.class);
            if (requestBody != null) {
                argList.add(args[i]);
            }
            //将RequestParam注解修饰的参数作为请求参数
            RequestParam requestParam = parameters[i].getAnnotation(RequestParam.class);
            if (requestParam != null) {
                Map<String, Object> map = new HashMap<>();
                String key = parameters[i].getName();
                if (!StrUtil.isEmpty(requestParam.value())) {
                    key = requestParam.value();
                }
                if (args[i] != null) {
                    map.put(key, args[i]);
                    argList.add(map);
                }
            }
        }
        if (argList.size() == 0) {
            return null;
        } else if (argList.size() == 1) {
            return argList.get(0);
        } else {
            return argList;
        }
    }
}
