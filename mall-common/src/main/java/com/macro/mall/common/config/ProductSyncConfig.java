package com.macro.mall.common.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 商品变更同步的 MQ 拓扑声明（交换机 + 队列 + 绑定）。
 * <p>
 * 为什么由应用自己声明而不是手工建：生产端（mall-admin）与消费端（mall-search）都要保证
 * 拓扑存在。若只靠人工在 RabbitMQ 上创建，一旦重建环境就会出现「交换机不存在 → 消息静默丢弃」，
 * 而这类丢失没有任何异常可捕获，排查成本极高。由 {@code RabbitAdmin} 声明是幂等的，
 * 两端重复声明没有副作用。
 * <p>
 * 使用 {@link ConditionalOnClass} 而非直接引入依赖：mall-common 是各业务模块共用的，
 * 未引入 amqp 的模块（如 mall-mbg 之类）不应被迫创建 MQ 连接。
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.amqp.core.Declarables")
public class ProductSyncConfig {

    /**
     * 商品变更交换机
     */
    public static final String PRODUCT_SYNC_EXCHANGE = "mall.product.direct";

    /**
     * 商品同步队列
     */
    public static final String PRODUCT_SYNC_QUEUE = "mall.product.sync";

    /**
     * 路由键，与队列同名便于排查
     */
    public static final String PRODUCT_SYNC_ROUTING_KEY = "mall.product.sync";

    @Bean
    public DirectExchange productSyncExchange() {
        // durable=true 保证 broker 重启后交换机仍在
        return new DirectExchange(PRODUCT_SYNC_EXCHANGE, true, false);
    }

    @Bean
    public Queue productSyncQueue() {
        // mall-search 是独立服务，不一定常驻。若队列无上限，长期不消费会把 broker 内存吃光。
        // 溢出策略选 drop-head（丢最老的消息）而不是 reject-publish：
        // 商品消息只带 ID、消费时回查数据库，所以「最新一条」才是有效信息，
        // 丢掉旧消息只会少一次无意义的重复同步，而拒绝新消息会让索引永久滞后。
        // 注意：队列参数变更后必须先在 broker 上删除旧队列，否则声明会因 PRECONDITION_FAILED 失败。
        return QueueBuilder.durable(PRODUCT_SYNC_QUEUE)
                .maxLength(50000)
                .overflow(QueueBuilder.Overflow.dropHead)
                .build();
    }

    @Bean
    public Binding productSyncBinding(Queue productSyncQueue, DirectExchange productSyncExchange) {
        return BindingBuilder.bind(productSyncQueue).to(productSyncExchange).with(PRODUCT_SYNC_ROUTING_KEY);
    }
}
