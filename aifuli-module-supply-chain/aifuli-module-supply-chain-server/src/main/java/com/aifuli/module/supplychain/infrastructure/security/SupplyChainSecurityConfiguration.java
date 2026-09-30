package com.aifuli.module.supplychain.infrastructure.security;

import com.aifuli.framework.security.config.AuthorizeRequestsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * 供应链独立应用的 Spring Security 扩展点装配。
 *
 * <p>安全 starter 以必填集合注入 {@link AuthorizeRequestsCustomizer}，因此独立 server 至少需要
 * 一个 Bean 才能启动。项目应用认证四前缀只由 {@code application.yaml} 的
 * {@code aifuli.security.permit-all-urls} 免除登录 token 前置要求，随后仍由 HMAC Filter 失败关闭。
 * OpenAPI 文档路径在此放行，供网关 Knife4j 聚合；业务 {@code /admin-api/supply-chain/**} 仍须登录。
 * 详见 {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#9-测试门禁}、
 * {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#36-openapi-swagger}。</p>
 */
@Configuration(proxyBeanMethods = false)
public class SupplyChainSecurityConfiguration {

    /**
     * HMAC 精确放行仍只写在 application.yaml；此处仅放行 OpenAPI 静态文档路径。
     */
    @Bean("supplyChainAuthorizeRequestsCustomizer")
    public AuthorizeRequestsCustomizer supplyChainAuthorizeRequestsCustomizer() {
        return new AuthorizeRequestsCustomizer() {

            @Override
            public void customize(
                    AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
                            registry) {
                registry.requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/webjars/**").permitAll()
                        .requestMatchers("/swagger-ui").permitAll()
                        .requestMatchers("/swagger-ui/**").permitAll();
            }
        };
    }
}
