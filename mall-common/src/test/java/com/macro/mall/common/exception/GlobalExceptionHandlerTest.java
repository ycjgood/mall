package com.macro.mall.common.exception;

import com.macro.mall.common.api.CommonResult;
import com.macro.mall.common.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.core.MethodParameter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常处理器单元测试
 * <p>
 * 重点验证三条企业级约定：
 * 1. 业务异常透传自己的错误码与提示
 * 2. 未知异常不向前端泄露内部细节，但仍可通过 traceId 定位
 * 3. 参数校验失败返回字段级提示
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("业务异常：透传错误码与提示，并带上 traceId")
    void shouldReturnBusinessErrorCode() {
        MDC.put("traceId", "trace-abc-001");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/admin/login");

        ApiException exception = new ApiException(ResultCode.TOO_MANY_REQUESTS, "请求过于频繁，请 30 秒后重试");
        CommonResult<Object> result = handler.handleApiException(exception, request);

        assertThat(result.getCode()).isEqualTo(429L);
        assertThat(result.getMessage()).isEqualTo("请求过于频繁，请 30 秒后重试");
        assertThat(result.getTraceId()).isEqualTo("trace-abc-001");
    }

    @Test
    @DisplayName("未知异常：返回通用话术，隐藏堆栈与 SQL 细节")
    void shouldHideInternalDetailForUnknownException() {
        MDC.put("traceId", "trace-abc-002");

        Exception exception = new RuntimeException("Table 'mall.pms_brand' doesn't exist");
        CommonResult<Object> result = handler.handleException(exception, request);

        assertThat(result.getCode()).isEqualTo(500L);
        assertThat(result.getMessage()).isEqualTo("系统繁忙，请稍后重试");
        assertThat(result.getMessage()).doesNotContain("pms_brand");
        assertThat(result.getTraceId()).isEqualTo("trace-abc-002");
    }

    @Test
    @DisplayName("参数校验失败：返回 参数名+校验提示")
    void shouldReturnFieldErrorMessage() {
        BindingResult bindingResult = mock(BindingResult.class);
        when(bindingResult.hasErrors()).thenReturn(true);
        when(bindingResult.getFieldError()).thenReturn(new FieldError("loginParam", "username", "不能为空"));

        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(mock(MethodParameter.class), bindingResult);
        CommonResult<Object> result = handler.handleValidException(exception, request);

        assertThat(result.getCode()).isEqualTo(404L);
        assertThat(result.getMessage()).isEqualTo("username不能为空");
    }
}
