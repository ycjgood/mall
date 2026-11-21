package com.macro.mall.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQ 消息转换器配置：规定消息一律用「JSON 文本」来传，取代 Java 默认的二进制序列化。
 * <p>
 * 【这个类是干什么的、为什么必须有它】
 * 后台(mall-admin) 改了商品后，要通过 RabbitMQ（消息队列，可理解成「传纸条的中转站」：
 * A 把消息放进去、B 稍后取走处理，两边不用同时在线）通知搜索服务更新 ES 索引。
 * 代码里发的是一个 Java 对象，但网络里只能传字节，所以发送前要把对象「打包」成字节、
 * 接收后再「拆包」还原成对象——这个「打包 / 拆包」就叫「序列化 / 反序列化」，
 * 具体用哪种格式，由本类注册的消息转换器说了算。
 * <p>
 * 【为什么必须配 Jackson2JsonMessageConverter（换成 JSON）】
 * 如果什么都不配，Spring AMQP 默认用 JDK 自带的那套序列化。消费端拆包时，
 * Spring 为了安全会做一道「类白名单」检查：只允许还原白名单里列出的类，
 * 我们自定义的消息体（如 {@code ProductSyncMessage}）不在名单里，会被直接拒绝、
 * 抛出 {@code SecurityException}（安全异常）。
 * 有人想「把白名单放开不就行了？」——放开等于允许还原任意类，是典型的反序列化安全漏洞，绝不能这么干。
 * <p>
 * 【最坑的一点：报错发生在进入业务方法之前，业务 catch 兜不住】
 * 上面这个「拆包失败」是框架在把消息交给我们的业务代码「之前」就发生的，
 * 也就是根本还没走进 {@code @RabbitListener} / {@code @RabbitHandler} 标注的方法，
 * 所以业务方法里写的 try/catch 一行都执行不到、完全兜不住。
 * 现象就是消息不断报错/重投，却查不到任何业务日志，非常难排查。换成 JSON 后不走 JDK 那套白名单，
 * 从根上绕开这个问题。
 * <p>
 * 【额外好处】JSON 是纯文本，在 RabbitMQ 管理控制台里能直接看懂消息内容，排查时不用再拆二进制；
 * 而且生产端(本模块)和消费端(mall-search)两端都用 JSON，格式就不会对不上。
 * <p>
 * 原技术要点：默认的 {@code SimpleMessageConverter} 在消费端会走 Spring AMQP 的类白名单校验，
 * 自定义类型（如 {@code ProductSyncMessage}）会被 {@code SecurityException} 拒绝。
 * 白名单可以放开，但等于允许任意类反序列化，属于典型的反序列化攻击面，不如直接换 JSON。
 */
@Configuration
public class RabbitMQConfig {

    /**
     * 注册一个「JSON 消息转换器」交给 Spring 管理。
     * 只要装了它，本服务发出去的消息就会按 JSON 打包，和消费端 mall-search 约定一致。
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
