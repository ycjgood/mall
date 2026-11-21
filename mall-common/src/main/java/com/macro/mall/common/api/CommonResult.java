package com.macro.mall.common.api;

/**
 * ============================================================
 * 【模块职责】mall-common：整个商城的「公共工具箱 / 共享零件包」。
 * 它自己不会启动、不对外开窗口，而是被 admin、portal、search 等所有业务模块一起引用。
 * 可以把它想成工地上人人都能来领螺丝、扳手、标准包装盒的「杂物间」：
 * 本类 CommonResult 就是其中最常被领用的「统一包装盒」——不管哪个接口返回数据，
 * 都装进同一个盒子（状态码 code + 提示 message + 数据 data + 链路追踪 traceId），
 * 前端只要认这一种盒子就行，不用为每个接口单独拆包装。
 * 除它以外，本模块还放着：工具类、通用配置、商品同步消息体(ProductSyncMessage) 以及
 * 消息队列的「拓扑声明」(ProductSyncConfig，即约定好队列/交换机叫什么名字、消息长什么样)。
 *
 * 【对外提供】
 * - 给所有业务模块提供统一返回结构（本类）、统一错误码、全局异常处理、Redis 操作封装、
 *   限流、链路追踪、库存工具等公共能力。
 * - 它不是独立服务，没有端口，也不直接提供 HTTP 接口，是「被别人 import 的代码库」。
 *
 * 【依赖什么】
 * - 底层依赖 Redis 相关封装、以及 RabbitMQ（消息队列，「传纸条的中转站」）的消息体与拓扑定义，
 *   但这些只是「定义/约定」，不负责真正连中间件跑起来。
 * - 它是最底层模块，被 mall-admin / mall-portal / mall-search / mall-security 等依赖；
 *   自己不反过来依赖这些上层业务模块。
 *
 * 【数据流向】以「一次接口返回」为例：
 * 1) 某业务模块 Controller 处理完请求，调用 CommonResult.success(data) 把结果装进统一盒子；
 * 2) 出错时由全局异常处理器调用 CommonResult.failed(...) 装成带错误码的盒子，并附 traceId
 *    （一串编号，用于在日志里把同一次请求的前后足迹串起来，方便排错）；
 * 3) 前端按固定格式读 code/message/data，成功失败用同一套逻辑处理。
 *    另一条流向：后台改商品 → 按本模块约定的消息体发 RabbitMQ 消息 → 搜索服务按同一格式解读。
 *
 * 【注意事项】
 * 1) 这里是公共代码，改一处会影响所有模块，务必保持向后兼容、别轻易改字段含义。
 * 2) 只放通用能力，别把某个业务模块特有的逻辑塞进来。
 * 3) common 必须保持「被依赖的底层」位置，绝不能反向依赖 admin/portal 等，避免循环依赖起不来。
 * ============================================================
 * 通用返回结果封装类
 * Created by macro on 2019/4/19.
 */
public class CommonResult<T> {
    /**
     * 状态码
     */
    private long code;
    /**
     * 提示信息
     */
    private String message;
    /**
     * 数据封装
     */
    private T data;
    /**
     * 链路追踪ID，出现异常时用于在日志中定位整条调用链（同时通过响应头 X-Trace-Id 返回）
     */
    private String traceId;

    protected CommonResult() {
    }

    protected CommonResult(long code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /**
     * 成功返回结果
     *
     * @param data 获取的数据
     */
    public static <T> CommonResult<T> success(T data) {
        return new CommonResult<T>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /**
     * 成功返回结果
     *
     * @param data 获取的数据
     * @param  message 提示信息
     */
    public static <T> CommonResult<T> success(T data, String message) {
        return new CommonResult<T>(ResultCode.SUCCESS.getCode(), message, data);
    }

    /**
     * 失败返回结果
     * @param errorCode 错误码
     */
    public static <T> CommonResult<T> failed(IErrorCode errorCode) {
        return new CommonResult<T>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 失败返回结果
     * @param errorCode 错误码
     * @param message 错误信息
     */
    public static <T> CommonResult<T> failed(IErrorCode errorCode,String message) {
        return new CommonResult<T>(errorCode.getCode(), message, null);
    }

    /**
     * 失败返回结果
     * @param message 提示信息
     */
    public static <T> CommonResult<T> failed(String message) {
        return new CommonResult<T>(ResultCode.FAILED.getCode(), message, null);
    }

    /**
     * 失败返回结果
     */
    public static <T> CommonResult<T> failed() {
        return failed(ResultCode.FAILED);
    }

    /**
     * 参数验证失败返回结果
     */
    public static <T> CommonResult<T> validateFailed() {
        return failed(ResultCode.VALIDATE_FAILED);
    }

    /**
     * 参数验证失败返回结果
     * @param message 提示信息
     */
    public static <T> CommonResult<T> validateFailed(String message) {
        return new CommonResult<T>(ResultCode.VALIDATE_FAILED.getCode(), message, null);
    }

    /**
     * 未登录返回结果
     */
    public static <T> CommonResult<T> unauthorized(T data) {
        return new CommonResult<T>(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMessage(), data);
    }

    /**
     * 未授权返回结果
     */
    public static <T> CommonResult<T> forbidden(T data) {
        return new CommonResult<T>(ResultCode.FORBIDDEN.getCode(), ResultCode.FORBIDDEN.getMessage(), data);
    }

    public long getCode() {
        return code;
    }

    public void setCode(long code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    /**
     * 链式设置 traceId，便于在异常处理器中一行完成赋值
     */
    public CommonResult<T> withTraceId(String traceId) {
        this.traceId = traceId;
        return this;
    }
}
