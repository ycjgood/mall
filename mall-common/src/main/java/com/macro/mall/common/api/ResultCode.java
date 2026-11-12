package com.macro.mall.common.api;

/**
 * API返回码封装类
 * <p>
 * 编码规范（与前端约定一致，前端按 code 分支处理）：
 * <ul>
 *     <li>2xx 成功</li>
 *     <li>4xx 调用方问题：参数错误、未登录、无权限、限流</li>
 *     <li>5xx 服务端问题：业务失败、依赖不可用</li>
 * </ul>
 * Created by macro on 2019/4/19.
 */
public enum ResultCode implements IErrorCode {
    SUCCESS(200, "操作成功"),
    FAILED(500, "操作失败"),
    VALIDATE_FAILED(404, "参数检验失败"),
    UNAUTHORIZED(401, "暂未登录或token已经过期"),
    FORBIDDEN(403, "没有相关权限"),
    /**
     * 请求本身不合法（参数缺失、类型不匹配、报文无法解析等）
     */
    BAD_REQUEST(400, "请求参数错误"),
    /**
     * 触发限流，调用方应在稍后重试
     */
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试"),
    /**
     * 请求的资源不存在（例如访问了不存在的接口）
     */
    NOT_FOUND(404, "请求的资源不存在"),
    /**
     * 依赖（数据库/缓存/第三方）不可用，属于可重试的临时故障
     */
    SERVICE_UNAVAILABLE(503, "服务暂时不可用，请稍后重试");

    private long code;
    private String message;

    private ResultCode(long code, String message) {
        this.code = code;
        this.message = message;
    }

    public long getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
