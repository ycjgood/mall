package com.macro.mall.config;

import com.alibaba.druid.pool.DruidDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Druid 连接池指标暴露
 * <p>
 * 背景：druid-spring-boot-3-starter 1.2.x 没有内置 Micrometer 支持，
 * 而 mall 使用 Druid 而非 HikariCP，Grafana 里查 hikaricp_* 永远没有数据。
 * 这里手动把 Druid 连接池状态注册成 Gauge，指标名对齐 HikariCP 的语义：
 * <ul>
 *     <li>druid_connections_active   活跃连接数（正在执行 SQL）</li>
 *     <li>druid_connections_idle     空闲连接数（池内 - 活跃）</li>
 *     <li>druid_connections_max      最大连接数上限</li>
 *     <li>druid_connections_waiting  等待获取连接的线程数（&gt; 0 说明连接池吃紧）</li>
 * </ul>
 * 所有指标自动带上 management.metrics.tags.application 标签，
 * Prometheus 抓取后可在 Grafana 直接查询。
 */
@Configuration
public class DruidMetricsConfig {

    @Bean
    public MeterBinder druidDataSourceMetrics(DataSource dataSource) {
        return registry -> {
            if (!(dataSource instanceof DruidDataSource druid)) {
                // 只有 Druid 数据源才暴露；未来换成 Hikari 时此 Bean 自动退化为空操作
                return;
            }
            Gauge.builder("druid_connections_active", druid, d -> d.getActiveCount())
                    .description("Druid 当前活跃连接数")
                    .register(registry);
            Gauge.builder("druid_connections_idle", druid, d -> d.getPoolingCount() - d.getActiveCount())
                    .description("Druid 当前空闲连接数")
                    .register(registry);
            Gauge.builder("druid_connections_max", druid, d -> d.getMaxActive())
                    .description("Druid 最大连接数上限")
                    .register(registry);
            Gauge.builder("druid_connections_waiting", druid, d -> d.getWaitThreadCount())
                    .description("等待获取连接的线程数")
                    .register(registry);
        };
    }
}
