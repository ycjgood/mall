package com.macro.mall.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 消息转换器配置：用 JSON 取代默认的 JDK 序列化。
 * <p>
 * 默认的 {@code SimpleMessageConverter} 在消费端会走 Spring AMQP 的类白名单校验，
 * 自定义类型（如 {@code ProductSyncMessage}）会被 {@code SecurityException} 拒绝。
 * 白名单可以放开，但等于允许任意类反序列化，属于典型的反序列化攻击面，不如直接换 JSON——
 * 顺带还让消息在 RabbitMQ 控制台里可读，排查问题时不用反序列化二进制。
 */
@Configuration
public class RabbitMQConfig {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
