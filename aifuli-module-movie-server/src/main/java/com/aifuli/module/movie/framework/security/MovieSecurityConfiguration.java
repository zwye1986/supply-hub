package com.aifuli.module.movie.framework.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 电影工程骨架的临时访问边界，仅允许读取无详情健康状态。
 *
 * <p>TODO：业务 RPC 开发时补齐系统间鉴权后，再开放明确的接口，不能直接放行 RPC 通配路径。
 * 设计：docs/business/电影子系统与中台履约详细设计.md#51-工程初始化落地约定2026-09-07</p>
 */
@Configuration(proxyBeanMethods = false)
public class MovieSecurityConfiguration {

    /**
     * 建立独立安全链，未实现的业务接口一律拒绝访问，不创建登录会话。
     *
     * @param http 电影进程的 HTTP 安全配置器
     * @return 仅开放健康检查的过滤器链
     * @throws Exception 安全链配置失败时保留异常并阻止启动
     */
    @Bean
    public SecurityFilterChain movieSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
