package com.macro.mall.security.config;

import com.macro.mall.security.component.DynamicAuthorizationManager;
import com.macro.mall.security.component.JwtAuthenticationTokenFilter;
import com.macro.mall.security.component.RestAuthenticationEntryPoint;
import com.macro.mall.security.component.RestfulAccessDeniedHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;


/**
 * ============================================================
 * 【模块职责】mall-security：整个商城的「门禁/保安系统」，专门管「你是谁、能不能访问这个接口」。
 * 可以把它想成公司门口的保安+工牌机：登录成功后发一张电子工牌（JWT），
 * 之后每次请求都亮工牌，保安先验真伪、再看工牌上的权限决定放不放行。
 * 本类 SecurityConfig 就是这套门禁的「总开关面板」：规定哪些地址不用登录（白名单）、
 * 其余地址一律要验工牌，并把「JWT 校验过滤器」插到检查流程的最前面。
 * 说明：JWT 可以简单理解为「一张写了用户身份、带防伪签名的小纸条」，
 * 服务器不用记住谁登录过，只要验签名就能认出这个人。
 *
 * 【对外提供】
 * - 给 mall-admin(后台 8080)、mall-portal(买家端 8085) 复用，两个服务都靠它做登录鉴权。
 * - 提供：白名单配置(IgnoreUrlsConfig)、JWT 生成/解析工具(JwtTokenUtil)、
 *   登录校验过滤器(JwtAuthenticationTokenFilter)、未登录/无权限时的统一返回等。
 * - 它不是一个独立运行的服务，没有自己的端口，是「被其它模块引入的安全配置库」。
 *
 * 【依赖什么】
 * - Redis：配合缓存用户/资源权限，做动态鉴权时读取。
 * - 依赖 mall-common（统一返回结构等）、mall-mbg（读取后台用户/角色/权限/资源表）。
 * - 不直接依赖 RabbitMQ / Elasticsearch / Milvus 等中间件。
 *
 * 【数据流向】以「管理员访问后台某个接口」为例：
 * 1) 先调登录接口，校验账号密码通过后由 JwtTokenUtil 签发一张 JWT 工牌交回前端；
 * 2) 之后前端每次请求都在请求头带上工牌，JwtAuthenticationTokenFilter 先验签名、还原出用户身份；
 * 3) 到本类这道关卡：白名单地址直接放行，其余地址判断「登录了吗 / 有这个资源权限吗」，
 *    通过才进真正的 Controller，否则返回「未登录/无权限」。
 *
 * 【注意事项】
 * 1) 白名单里放的是登录、文档等「不用工牌也能进」的地址，千万别把敏感接口误放进去。
 * 2) 这里采用「无状态会话」(STATELESS)：服务器不保存登录状态，全靠 JWT，
 *    工牌一旦签发在过期前一直有效，签名密钥务必保管好。
 * 3) 它是被 admin/portal 共用的底层模块，改动会同时影响两边登录，且不要反向依赖上层模块。
 * ============================================================
 * SpringSecurity相关配置，仅用于配置SecurityFilterChain
 * Created by macro on 2019/11/5.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private IgnoreUrlsConfig ignoreUrlsConfig;
    @Autowired
    private RestfulAccessDeniedHandler restfulAccessDeniedHandler;
    @Autowired
    private RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    @Autowired
    private JwtAuthenticationTokenFilter jwtAuthenticationTokenFilter;
    @Autowired(required = false)
    private DynamicAuthorizationManager dynamicAuthorizationManager;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
        httpSecurity.authorizeHttpRequests(registry -> {
            //不需要保护的资源路径允许访问
            for (String url : ignoreUrlsConfig.getUrls()) {
                registry.requestMatchers(url).permitAll();
            }
            //允许跨域请求的OPTIONS请求
            registry.requestMatchers(HttpMethod.OPTIONS).permitAll();
            //任何请求需要身份认证
        })
        //任何请求需要身份认证
        .authorizeHttpRequests(registry-> registry.anyRequest()
            //有动态权限配置时添加动态权限管理器
            .access(dynamicAuthorizationManager==null? AuthenticatedAuthorizationManager.authenticated():dynamicAuthorizationManager)
        )
        //关闭跨站请求防护
        .csrf(AbstractHttpConfigurer::disable)
        //修改Session生成策略为无状态会话
        .sessionManagement(configurer -> configurer.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        //自定义权限拒绝处理类
        .exceptionHandling(configurer -> configurer.accessDeniedHandler(restfulAccessDeniedHandler).authenticationEntryPoint(restAuthenticationEntryPoint))
        //自定义权限拦截器JWT过滤器
        .addFilterBefore(jwtAuthenticationTokenFilter, UsernamePasswordAuthenticationFilter.class);
        return httpSecurity.build();
    }

}
