package com.macro.mall;

import org.mybatis.generator.api.MyBatisGenerator;
import org.mybatis.generator.config.Configuration;
import org.mybatis.generator.config.xml.ConfigurationParser;
import org.mybatis.generator.internal.DefaultShellCallback;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================
 * 【模块职责】mall-mbg：整个商城的「数据库翻译层 / 自动代码车间」。
 * 数据库里是一张张表（商品表、订单表……），Java 想操作它们，得有对应的「实体类(model)」
 * 和「Mapper 接口」（可以理解为「帮你拼好并执行 SQL 语句的现成工具」）。
 * 这类文件数量成百上千、格式高度雷同，所以不手写，而是用 MyBatis Generator
 * （简称 MBG，一个代码生成器）照着数据库表自动批量造出来。
 * 本类 Generator 就是「开始生产」的按钮：运行它的 main 方法，读取 generatorConfig.xml 配置，
 * 重新生成 model 实体类、Mapper 接口、Example 查询条件类等一大堆文件。
 *
 * 【对外提供】
 * - 给 mall-admin / mall-portal / mall-search 等所有模块提供数据库实体类(model)与 Mapper 接口，
 *   大家读写 MySQL 全靠它，不用各自手写数据库映射。
 * - 它不是独立服务、没有端口，是「被别人依赖的代码库」+ 一个开发阶段才用的生成工具。
 *
 * 【依赖什么】
 * - MySQL：生成时要连数据库读表结构；生成出来的代码运行时也面向 MySQL。
 * - 依赖 MyBatis Generator 这个第三方生成工具；产出被上层所有业务模块依赖。
 * - 不直接依赖 Redis / RabbitMQ / Elasticsearch / Milvus 等中间件。
 *
 * 【数据流向】以「数据库新加了一张表，想让 Java 能用它」为例：
 * 1) 先在 MySQL 建表，并在 generatorConfig.xml 里登记要生成哪些表；
 * 2) 运行本类 main 方法，MBG 读表结构，自动产出 model、Mapper、Example 等文件，
 *    并覆盖旧文件（overwrite = true）；
 * 3) 其它业务模块直接注入这些 Mapper 就能增删改查，无需自己写映射。
 *
 * 【注意事项】
 * 1) 生成的 model / mapper 代码是「自动产物」，不要手动改！下次重新生成就会被覆盖，手写内容全丢。
 *    要加自定义 SQL，请放到各模块自己的 dao / 自定义 Mapper 里。
 * 2) 本类日常跑业务时完全用不到，只在表结构变化、需要重新生成代码时才手动运行。
 * 3) 它是最底层模块，绝不能反向依赖任何业务模块，否则会形成循环依赖、工程起不来。
 * ============================================================
 * MBG代码生成工具
 * Created by macro on 2018/4/26.
 */
public class Generator {
    public static void main(String[] args) throws Exception {
        //MBG 执行过程中的警告信息
        List<String> warnings = new ArrayList<String>();
        //当生成的代码重复时，覆盖原代码
        boolean overwrite = true;
        //读取我们的 MBG 配置文件
        InputStream is = Generator.class.getResourceAsStream("/generatorConfig.xml");
        ConfigurationParser cp = new ConfigurationParser(warnings);
        Configuration config = cp.parseConfiguration(is);
        is.close();

        DefaultShellCallback callback = new DefaultShellCallback(overwrite);
        //创建 MBG
        MyBatisGenerator myBatisGenerator = new MyBatisGenerator(config, callback, warnings);
        //执行生成代码
        myBatisGenerator.generate(null);
        //输出警告信息
        for (String warning : warnings) {
            System.out.println(warning);
        }
    }
}
