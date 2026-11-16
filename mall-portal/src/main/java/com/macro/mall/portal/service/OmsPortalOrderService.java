package com.macro.mall.portal.service;

import com.macro.mall.common.api.CommonPage;
import com.macro.mall.model.OmsOrder;
import com.macro.mall.portal.domain.ConfirmOrderResult;
import com.macro.mall.portal.domain.OmsOrderDetail;
import com.macro.mall.portal.domain.OrderParam;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 前台订单管理Service
 * Created by macro on 2018/8/30.
 */
public interface OmsPortalOrderService {
    /**
     * 根据用户购物车信息生成确认单信息
     */
    ConfirmOrderResult generateConfirmOrder(List<Long> cartIds);

    /**
     * 生成下单幂等令牌
     * <p>
     * 前端在进入结算页时调用一次，提交订单时把令牌放到请求头 X-Order-Token。
     * 令牌一次性有效（10 分钟），重复提交会被服务端拒绝。
     */
    String generateOrderToken();

    /**
     * 根据提交信息生成订单
     *
     * @param orderParam 下单参数
     * @param orderToken 幂等令牌，必须与当前会员匹配且未使用过
     */
    @Transactional
    Map<String, Object> generateOrder(OrderParam orderParam, String orderToken);

    /**
     * 下单前置预检（<b>故意不加 @Transactional</b>）
     * <p>
     * 放在事务之外执行，这样"库存不足"的请求连事务都不会开启，
     * 数据库侧不产生任何事务开销（Com_begin / Com_rollback）。
     * 只对 Redis 预扣模式生效，其他模式直接返回。
     *
     * @throws com.macro.mall.common.exception.ApiException 库存明显不足时抛出
     */
    void precheckStock(OrderParam orderParam);

    /**
     * 支付成功后的回调
     */
    @Transactional
    Integer paySuccess(Long orderId, Integer payType);

    /**
     * 自动取消超时订单
     */
    @Transactional
    Integer cancelTimeOutOrder();

    /**
     * 取消单个超时订单
     */
    @Transactional
    void cancelOrder(Long orderId);

    /**
     * 发送延迟消息取消订单
     */
    void sendDelayMessageCancelOrder(Long orderId);

    /**
     * 确认收货
     */
    void confirmReceiveOrder(Long orderId);

    /**
     * 分页获取用户订单
     */
    CommonPage<OmsOrderDetail> list(Integer status, Integer pageNum, Integer pageSize);

    /**
     * 根据订单ID获取订单详情
     */
    OmsOrderDetail detail(Long orderId);

    /**
     * 用户根据订单ID删除订单
     */
    void deleteOrder(Long orderId);

    /**
     * 根据orderSn来实现的支付成功逻辑
     */
    @Transactional
    void paySuccessByOrderSn(String orderSn, Integer payType);

    /**
     * 根据orderSn来获取订单
     */
    OmsOrder getOrderByOrderSn(String orderSn);
}
