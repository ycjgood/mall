package com.macro.mall.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ============================================================
 * 【模块职责】mall-search：商城的「商品搜索服务」，专门负责让顾客能快速搜到商品。
 * 可以把它想成商店里那台「商品查询机」：MySQL 适合存账本，但按关键词模糊搜索很慢，
 * 所以把商品数据另外抄一份到 Elasticsearch（下面简称 ES，可理解为一张专门用来「查得快」的
 * 超级索引卡柜）里，本模块就是维护这台查询机、并提供搜索接口的服务。
 * 本类是它的「开机按钮」。
 *
 * 【对外提供】
 * - 提供商品搜索、按条件筛选、相关商品推荐等 HTTP 接口（Controller），供商城前端调用。
 * - 监听端口 8081。
 * - 另外还默默在后台「监听消息队列」，收到商品变更消息就增量更新自己的 ES 数据。
 *
 * 【依赖什么】
 * - Elasticsearch（ES）：真正存商品索引、执行搜索的地方，运行在虚拟机 192.168.150.101。
 *   「索引」可以类比成图书目录卡片：先按关键词查目录，再拿到对应商品，比逐页翻书快得多。
 * - MySQL：读取商品原始数据（通过 mall-mbg 的 Mapper），用来重建/同步索引。
 * - RabbitMQ（消息队列，「传纸条的中转站」）：监听名为 mall.product.sync 的队列，
 *   后台(mall-admin)改了商品就往这里发消息。
 * - 依赖 mall-common（含消息体与队列拓扑定义）、mall-mbg（读数据库）。
 *
 * 【数据流向】以「后台上架了一件新商品，顾客能搜到它」为例：
 * 1) 后台 mall-admin 把商品写进 MySQL 后，向 RabbitMQ 的 mall.product.sync 队列发一条消息，
 *    内容大致是「商品 id=123 变了」；
 * 2) 本服务的消费者收到消息，拿着这个 id 回 MySQL 查最新商品数据；
 * 3) 查得到就把这条商品写进/更新到 ES 索引（这一步叫「写索引」），查不到（比如已下架）
 *    就把 ES 里对应文档删掉，这样顾客搜索的结果始终和真实在售商品一致。
 *
 * 【注意事项】
 * 1) 启动前先起好虚拟机 192.168.150.101 上的 Elasticsearch、MySQL、RabbitMQ，缺一个都用不了。
 * 2) 端口 8081；它不直接改 MySQL，商品数据的唯一来源仍是后台服务，本模块只做「同步」和「查询」。
 * 3) ES 索引与数据库是两份数据，靠消息保持一致，可能有极短暂延迟，属正常现象。
 * ============================================================
 * 应用启动入口
 */
@SpringBootApplication(scanBasePackages = "com.macro.mall")
public class MallSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallSearchApplication.class, args);
    }
}
