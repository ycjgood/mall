package com.macro.mall.search.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 消息转换器配置：和「发消息的一方」mall-admin 保持一致，都用 JSON 文本传消息。
 * <p>
 * 【这个类是干什么的】本服务(mall-search)是收消息的一方：后台把「哪个商品变了」的消息
 * 放进 RabbitMQ（消息队列，可理解成「传纸条的中转站」），本服务取走后去更新 ES 索引。
 * 消息在网络里是字节，收到后要「拆包」还原成 Java 对象，这一步叫「反序列化」，
 * 用哪种格式拆，由本类注册的消息转换器决定。
 * <p>
 * 【为什么必须和发送端配成同一个转换器】
 * 打包/拆包（序列化/反序列化）的规则两端必须一致，否则消费端在「拆包」这步就会失败。
 * 若沿用 Java 默认的 JDK 序列化，还会撞上 Spring AMQP 的「类白名单」安全检查：
 * 框架只允许还原白名单里的类，自定义消息体（如 {@code ProductSyncMessage}）不在名单里，
 * 会被拒绝并抛 {@code SecurityException}（安全异常）。放开白名单又等于允许还原任意类，
 * 有反序列化安全漏洞，所以正解是两端统一换成 JSON。
 * <p>
 * 【最容易踩的坑】上面这些失败都发生在框架把消息交给业务代码「之前」，
 * 也就是还没进入 {@code @RabbitHandler} 方法，业务代码里的 try/catch 根本兜不住——
 * 表现为消息反复报错/重投，却看不到任何业务日志。两端都用 JSON 就能从根上避免。
 * <p>
 * 【额外好处】JSON 是纯文本，RabbitMQ 控制台里能直接看懂消息内容，排查问题不用再拆二进制。
 */
@Configuration
public class RabbitMQConfig {

    /**
     * 注册「JSON 消息转换器」交给 Spring 管理。
     * 装上它，本服务收消息时就按 JSON 拆包，和 mall-admin 发消息时的 JSON 打包规则对得上。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
