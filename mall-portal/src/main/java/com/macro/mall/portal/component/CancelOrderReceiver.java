package com.macro.mall.portal.component;

import com.macro.mall.portal.service.OmsPortalOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 *  取消订单消息的接收者
 * Created by macro on 2018/9/14.
 */
@Component
@RabbitListener(queues = "mall.order.cancel")
public class CancelOrderReceiver {
    private static final Logger LOGGER = LoggerFactory.getLogger(CancelOrderReceiver.class);
    @Autowired
    private OmsPortalOrderService portalOrderService;
    @RabbitHandler
    public void handle(Long orderId){
        try {
            portalOrderService.cancelOrder(orderId);
            LOGGER.info("process orderId:{}",orderId);
        } catch (Exception e) {
            // 异常不能冒泡：Spring AMQP 默认把异常消息重新入队，
            // 一条永远处理不了的消息会无限重投并堵住消费者。
            // 订单取消本身已是幂等的（条件更新），这里记录后丢弃，
            // 没能关闭的订单由 OrderTimeOutCancelTask 定时兜底。
            LOGGER.error("cancel order failed, orderId:{}", orderId, e);
        }
    }
}
