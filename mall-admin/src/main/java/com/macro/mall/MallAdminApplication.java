package com.macro.mall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ============================================================
 * 【模块职责】mall-admin：商城的「后台管理系统」，也就是给运营/管理员用的那套服务端。
 * 可以把它想成商店的「仓库管理办公室」：店员在后台网页上上架商品、改价格、处理订单、
 * 配置促销活动，这些操作最终都由本模块接收并写进数据库；普通顾客（买家）不走这里。
 * 本类只是这个服务的「开机按钮」——main 方法一运行，Spring Boot 就把整个后台服务启动起来。
 *
 * 【对外提供】
 * - 给管理后台前端网页（本地开发地址端口 5173）提供各种 HTTP 接口（Controller，
 *   可以理解成「一扇扇接待请求的窗口」）。
 * - 涵盖商品管理、订单管理、促销/秒杀活动管理、权限菜单等后台能力。
 * - 本服务监听端口 8080；后台默认账号 admin / 密码 macro123。
 *
 * 【依赖什么】
 * - MySQL：存放商品、订单、促销等核心数据。
 * - Redis：做缓存（例如后台登录、权限缓存），读写快，适合放临时数据。
 * - RabbitMQ（消息队列：可以理解成一个「传纸条的中转站」，A 把消息放进去，B 稍后取走处理，
 *   两边不用同时在线）：商品信息改动后，往里发一条「商品同步」消息通知搜索服务。
 * - 依赖 mall-common（公共工具）、mall-security（登录鉴权）、mall-mbg（数据库实体与 Mapper）。
 *
 * 【数据流向】以「管理员改了一个商品价格」为例：
 * 1) 后台前端(5173) 发请求到本服务(8080) 的 Controller；
 * 2) 先过 mall-security 的登录校验（没登录会被拦下），再由 Service 把新价格写进 MySQL；
 * 3) 写库成功后，向 RabbitMQ 发一条「这个商品变了」的消息，搜索服务(mall-search)收到后
 *    更新 Elasticsearch 里的商品索引，保证前台搜索到的价格是最新的。
 *
 * 【注意事项】
 * 1) 启动前要先起好虚拟机 192.168.150.101 上的 MySQL、Redis、RabbitMQ，否则连不上会报运行错误。
 * 2) 它只负责「后台管理」，买家下单、购物车等请走 mall-portal(8085)，两者端口不要搞混。
 * 3) 模块之间只能「单向依赖」（如 admin 依赖 common），不要互相依赖形成环，否则工程无法启动。
 * ============================================================
 * 应用启动入口
 * Created by macro on 2018/4/26.
 */
@SpringBootApplication
public class MallAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(MallAdminApplication.class, args);
    }
}
