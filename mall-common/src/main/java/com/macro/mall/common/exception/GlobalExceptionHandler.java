package com.macro.mall.common.exception;

import cn.hutool.core.util.StrUtil;
import com.macro.mall.common.api.CommonResult;
import com.macro.mall.common.api.IErrorCode;
import com.macro.mall.common.api.ResultCode;
import com.macro.mall.common.log.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLSyntaxErrorException;
import java.util.stream.Collectors;

/**
 * 全局异常处理类
 * <p>
 * 设计原则（企业实践）：
 * <ol>
 *     <li><b>分类处理</b>：业务异常（预期内）用 WARN 且不打堆栈；系统异常用 ERROR 打全栈</li>
 *     <li><b>不漏细节给调用方</b>：未知异常统一返回"系统繁忙"，绝不把堆栈/SQL/表名暴露给前端</li>
 *     <li><b>可定位</b>：所有异常响应都带 traceId，运维凭它去日志里搜完整现场</li>
 *     <li><b>错误码稳定</b>：调用方按 code 分支，具体文案可改，code 不改</li>
 * </ol>
 * Created by macro on 2020/2/27.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    /**
     * 未知异常对外的统一话术，避免泄露内部实现细节
     */
    private static final String GENERIC_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    /**
     * 业务异常：可预期，记录 WARN 且不打印堆栈，避免污染错误日志
     */
    @ExceptionHandler(value = ApiException.class)
    public CommonResult<Object> handleApiException(ApiException e, HttpServletRequest request) {
        IErrorCode errorCode = e.getErrorCode();
        if (errorCode != null) {
            LOGGER.warn("[业务异常] {} {} code={} message={}",
                    request.getMethod(), request.getRequestURI(), errorCode.getCode(), e.getMessage());
            return trace(CommonResult.failed(errorCode, e.getMessage()));
        }
        LOGGER.warn("[业务异常] {} {} message={}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return trace(CommonResult.failed(e.getMessage()));
    }

    /**
     * @Validated 校验 RequestBody 失败
     */
    @ExceptionHandler(value = MethodArgumentNotValidException.class)
    public CommonResult<Object> handleValidException(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = resolveFieldError(e.getBindingResult());
        LOGGER.warn("[参数校验失败] {} {} message={}", request.getMethod(), request.getRequestURI(), message);
        return trace(CommonResult.validateFailed(message));
    }

    /**
     * 表单/查询参数绑定校验失败
     */
    @ExceptionHandler(value = BindException.class)
    public CommonResult<Object> handleValidException(BindException e, HttpServletRequest request) {
        String message = resolveFieldError(e.getBindingResult());
        LOGGER.warn("[参数校验失败] {} {} message={}", request.getMethod(), request.getRequestURI(), message);
        return trace(CommonResult.validateFailed(message));
    }

    /**
     * 方法参数上的约束校验失败（@Validated 在类上 + 方法参数加 @NotBlank 等）
     */
    @ExceptionHandler(value = ConstraintViolationException.class)
    public CommonResult<Object> handleConstraintViolation(ConstraintViolationException e, HttpServletRequest request) {
        String message = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + violation.getMessage())
                .collect(Collectors.joining("; "));
        LOGGER.warn("[参数校验失败] {} {} message={}", request.getMethod(), request.getRequestURI(), message);
        return trace(CommonResult.validateFailed(message));
    }

    /**
     * 缺少必填参数
     */
    @ExceptionHandler(value = MissingServletRequestParameterException.class)
    public CommonResult<Object> handleMissingParameter(MissingServletRequestParameterException e) {
        String message = "缺少必要参数: " + e.getParameterName();
        LOGGER.warn("[参数缺失] {}", message);
        return trace(CommonResult.failed(ResultCode.BAD_REQUEST, message));
    }

    /**
     * 参数类型不匹配，例如 id 传了 "abc"
     */
    @ExceptionHandler(value = MethodArgumentTypeMismatchException.class)
    public CommonResult<Object> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = "参数类型错误: " + e.getName();
        LOGGER.warn("[参数类型错误] {} value={}", e.getName(), e.getValue());
        return trace(CommonResult.failed(ResultCode.BAD_REQUEST, message));
    }

    /**
     * 请求体无法解析（JSON 格式错误、必填字段缺失等）
     */
    @ExceptionHandler(value = HttpMessageNotReadableException.class)
    public CommonResult<Object> handleNotReadable(HttpMessageNotReadableException e) {
        LOGGER.warn("[报文解析失败] {}", e.getMessage());
        return trace(CommonResult.failed(ResultCode.BAD_REQUEST, "请求报文格式错误"));
    }

    /**
     * 请求方法不支持（例如该用 POST 却用了 GET）
     */
    @ExceptionHandler(value = HttpRequestMethodNotSupportedException.class)
    public CommonResult<Object> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        LOGGER.warn("[请求方法不支持] method={}", e.getMethod());
        return trace(CommonResult.failed(ResultCode.BAD_REQUEST, "不支持的请求方法: " + e.getMethod()));
    }

    /**
     * 上传文件超过大小限制
     */
    @ExceptionHandler(value = MaxUploadSizeExceededException.class)
    public CommonResult<Object> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        LOGGER.warn("[上传超限] maxUploadSize={}", e.getMaxUploadSize());
        return trace(CommonResult.failed(ResultCode.BAD_REQUEST, "上传文件超过大小限制"));
    }

    /**
     * 访问了不存在的接口（Spring 6.1+ 静态资源未命中抛此异常）
     */
    @ExceptionHandler(value = NoResourceFoundException.class)
    public CommonResult<Object> handleNoResourceFound(NoResourceFoundException e) {
        LOGGER.debug("[资源不存在] path={}", e.getResourcePath());
        return trace(CommonResult.failed(ResultCode.NOT_FOUND));
    }

    /**
     * 保留原有演示环境的特殊提示，但改为 ERROR 级别（这确实是一次数据库异常）
     */
    @ExceptionHandler(value = SQLSyntaxErrorException.class)
    public CommonResult<Object> handleSQLSyntaxErrorException(SQLSyntaxErrorException e) {
        String message = e.getMessage();
        if (StrUtil.isNotEmpty(message) && message.contains("denied")) {
            LOGGER.warn("[数据库权限不足] {}", message);
            return trace(CommonResult.failed("演示环境暂无修改权限，如需修改数据可本地搭建后台服务！"));
        }
        LOGGER.error("[数据库语法错误] {}", message, e);
        return trace(CommonResult.failed(ResultCode.SERVICE_UNAVAILABLE));
    }

    /**
     * 兜底：所有未被上面捕获的异常。
     * 不把异常信息返回给调用方（防信息泄露），但必须完整记录堆栈 + traceId。
     */
    @ExceptionHandler(value = Exception.class)
    public CommonResult<Object> handleException(Exception e, HttpServletRequest request) {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        LOGGER.error("[系统异常] traceId={} {} {} message={}",
                traceId, request.getMethod(), request.getRequestURI(), e.getMessage(), e);
        return CommonResult.<Object>failed(ResultCode.FAILED, GENERIC_ERROR_MESSAGE).withTraceId(traceId);
    }

    /**
     * 统一把当前线程的 traceId 塞进响应体
     */
    private <T> CommonResult<T> trace(CommonResult<T> result) {
        return result.withTraceId(MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY));
    }

    private String resolveFieldError(BindingResult bindingResult) {
        if (bindingResult != null && bindingResult.hasErrors()) {
            FieldError fieldError = bindingResult.getFieldError();
            if (fieldError != null) {
                return fieldError.getField() + fieldError.getDefaultMessage();
            }
        }
        return "参数校验失败";
    }
}
