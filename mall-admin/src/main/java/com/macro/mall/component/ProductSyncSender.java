package com.macro.mall.component;

import com.macro.mall.common.config.ProductSyncConfig;
import com.macro.mall.common.domain.ProductSyncMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 商品变更消息发送者：把「写商品」和「更新 ES」解耦。
 *
 * <h3>为什么不在 admin 里直接调 ES</h3>
 * 直接调用会让商品保存的耗時、失败率绑定到搜索引擎上——ES 抖动一次，后台连商品都改不了。
 * 发消息则让写入只依赖 MySQL，ES 端的延迟可以接受（搜索场景秒级延迟不影响业务）。
 *
 * <h3>为什么必须事务提交后再发</h3>
 * 消费端是拿 ID 回查数据库的。若在事务提交前发消息，消费端很可能读不到这条还没提交的数据
 * （读已提交隔离级别），于是查不到商品 → 被判定为「已下架」→ 从索引里删掉，
 * 结果就是商品明明上架了、搜索却搜不到。这里用 {@code afterCommit} 保证消费端一定看得到数据。
 *
 * <h3>失败怎么办</h3>
 * 发送失败只记 WARN 不抛异常：MQ 故障不应该让运营改不了商品。
 * 丢失的索引更新由 {@code POST /esProduct/importAll} 全量重建兜底。
 */
@Component
public class ProductSyncSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProductSyncSender.class);

    @Autowired
    private AmqpTemplate amqpTemplate;

    /**
     * 关闭后不再投递消息，用于排查「怀疑是 MQ 导致的问题」时快速隔离
     */
    @Value("${mall.es-sync.enabled:true}")
    private boolean enabled;

    /**
     * 在当前事务提交后投递商品变更消息；无事务时立即投递。
     *
     * @param productIds 变更的商品 ID，允许为空
     * @param reason     变更来源，仅用于日志追踪
     */
    public void syncAfterCommit(List<Long> productIds, String reason) {
        if (!enabled || productIds == null || productIds.isEmpty()) {
            return;
        }
        // 拷贝一份：调用方后续可能复用/修改这个 list
        final ProductSyncMessage message = new ProductSyncMessage(new java.util.ArrayList<>(productIds), reason);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doSend(message);
                }
            });
        } else {
            doSend(message);
        }
    }

    private void doSend(ProductSyncMessage message) {
        try {
            amqpTemplate.convertAndSend(ProductSyncConfig.PRODUCT_SYNC_EXCHANGE,
                    ProductSyncConfig.PRODUCT_SYNC_ROUTING_KEY, message);
            LOGGER.info("send product sync: {}", message);
        } catch (Exception e) {
            // 不能因为 MQ 不可用就让商品保存失败，最坏情况是索引短暂滞后
            LOGGER.warn("send product sync failed, fallback to manual importAll. message:{}", message, e);
        }
    }
}
