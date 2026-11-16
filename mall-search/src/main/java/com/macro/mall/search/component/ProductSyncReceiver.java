package com.macro.mall.search.component;

import com.macro.mall.common.config.ProductSyncConfig;
import com.macro.mall.common.domain.ProductSyncMessage;
import com.macro.mall.search.service.EsProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 商品变更消息消费者：把商品 ID 落成 ES 索引变更。
 * <p>
 * 幂等性说明：消息里只有 ID，消费者每次都回查数据库最新状态重建文档，
 * 因此重复消费、消息乱序都不会把索引写成过期数据——这比「消息里带全量快照」健壮得多。
 */
@Component
@RabbitListener(queues = ProductSyncConfig.PRODUCT_SYNC_QUEUE)
public class ProductSyncReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProductSyncReceiver.class);

    @Autowired
    private EsProductService esProductService;

    @RabbitHandler
    public void handle(ProductSyncMessage message) {
        try {
            int indexed = esProductService.sync(message.getProductIds());
            LOGGER.info("process product sync, reason:{}, ids:{}, indexed:{}",
                    message.getReason(), message.getProductIds(), indexed);
        } catch (Exception e) {
            // 异常不冒泡：否则 Spring AMQP 会把消息重新入队，一条坏消息能堵死整个消费者。
            // 索引偏差由全量重建接口兜底，代价远小于消费停滞。
            LOGGER.error("process product sync failed, message:{}", message, e);
        }
    }
}
