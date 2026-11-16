package com.macro.mall.search.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 消息转换器配置：与生产端（mall-admin）保持一致使用 JSON。
 * <p>
 * 两端必须用同一种格式，否则消费端在反序列化阶段就会失败——而且失败发生在进入
 * {@code @RabbitHandler} 方法之前，业务代码里的 try/catch 根本兜不住。
 */
@Configuration
public class RabbitMQConfig {

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
