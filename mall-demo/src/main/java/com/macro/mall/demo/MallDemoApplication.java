package com.macro.mall.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ============================================================
 * 【模块职责】mall-demo：原作者留下来做「演示/练习」的示例模块，没有任何真实业务。
 * 可以把它想成买电器附赠的「样品机」：能开机、能看结构，但商店真正营业并不靠它。
 * 本类只是这个示例工程的「开机按钮」。
 *
 * 【对外提供】
 * - 只有几个用来示范写法（如简单接口、组件用法）的示例代码，不参与商城正式业务。
 * - 没有约定固定对外端口，平时也不用启动它。
 *
 * 【依赖什么】
 * - 基本只依赖 Spring Boot 基础框架；不强制依赖 MySQL / Redis / RabbitMQ /
 *   Elasticsearch / Milvus，也不承担 mall-common、mall-mbg 之类的模块协作职责。
 *
 * 【数据流向】没有正式业务流程，大致是：
 * 1) 运行本类 main 方法启动示例服务；
 * 2) 访问示例 Controller 看看效果；
 * 3) 到此为止，不写库、不发消息、不参与其它模块。
 *
 * 【注意事项】
 * 1) 这是「学习用」模块，别把真正的业务逻辑写在这里，也别让其它模块依赖它。
 * 2) 日常开发/部署可以直接忽略本模块，它不影响商城运行。
 * 3) 同理，模块之间不要形成循环依赖。
 * ============================================================
 * 应用启动入口
 */
@SpringBootApplication
public class MallDemoApplication{
	public static void main(String[] args) {
		SpringApplication.run(MallDemoApplication.class, args);
	}
}
