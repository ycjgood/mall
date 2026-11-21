package com.macro.mall.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ============================================================
 * 【模块职责】mall-portal：商城的「买家端接口服务」，也就是面向普通顾客（C 端）的那套服务端。
 * 可以把它想成商店的「前台营业厅」：顾客在商城网页上注册登录、逛商品、加购物车、下单、
 * 抢秒杀，这些动作全由本模块接待；它和 mall-admin（后台办公室）是两套独立服务、各管一头。
 * 本类是这个服务的「开机按钮」，main 方法一运行就把整个买家端服务启动起来。
 *
 * 【对外提供】
 * - 给商城前端网页（本地开发地址端口 5174）提供各种 HTTP 接口（Controller，
 *   可以理解成「一扇扇接待请求的窗口」）。
 * - 涵盖会员登录注册、商品浏览、购物车、下单支付、秒杀等买家能力。
 * - 本服务监听端口 8085。
 *
 * 【依赖什么】
 * - MySQL：存会员、购物车、订单等数据。
 * - Redis：存登录令牌、库存预扣、幂等令牌等临时数据（读写快）。
 * - RabbitMQ（消息队列，「传纸条的中转站」）：下单后发一条「延迟消息」，
 *   到点还没付款就自动取消订单、归还库存。
 * - 依赖 mall-common、mall-security（登录鉴权）、mall-mbg（数据库实体与 Mapper）；
 *   搜索能力交给 mall-search，本模块不直接连 Elasticsearch。
 *
 * 【数据流向】以「顾客下单买一件秒杀商品」为例：
 * 1) 前端(5174) 带着登录凭证请求本服务(8085)，先过 JWT 登录校验（JWT 可理解为
 *    「把身份写在带防伪签名的小纸条里，服务器验签名即可，不用自己记」）；
 * 2) 下单接口先校验「幂等令牌」（防重复提交的号码牌：同一个号码牌只能用一次，
 *    这样用户连点两次也只会生成一张订单）；
 * 3) 预扣库存、生成订单写入 MySQL；同时往 RabbitMQ 发一条「延迟消息」，
 *    若超过指定时间仍未支付，消费者会把订单自动取消并归还库存。
 *
 * 【注意事项】
 * 1) 启动前先起好虚拟机 192.168.150.101 上的 MySQL、Redis、RabbitMQ；秒杀强依赖 Redis 库存预扣。
 * 2) 端口是 8085，别和后台 8080、搜索 8081 弄混；前端页面是 5174。
 * 3) 下单链路里的「事务」（一批数据库操作要么全成功、要么全撤销，不允许只做一半）和
 *    消息发送时机紧密相关，改动时务必小心。
 * ============================================================
 * 应用启动入口
 */
@SpringBootApplication(scanBasePackages = "com.macro.mall")
public class MallPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallPortalApplication.class, args);
    }

}
