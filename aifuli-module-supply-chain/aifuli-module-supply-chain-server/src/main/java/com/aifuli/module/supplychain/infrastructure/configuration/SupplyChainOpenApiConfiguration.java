package com.aifuli.module.supplychain.infrastructure.configuration;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 供应链中台 OpenAPI 分组和组合部署可见性规则。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupplyChainOpenApiProperties.class)
@ConditionalOnProperty(prefix = "aifuli.supply-chain", name = "enabled", havingValue = "true")
public class SupplyChainOpenApiConfiguration {

    private static final List<String> SUPPLY_CHAIN_PATH_PREFIXES = List.of(
            "/admin-api/supply-chain/",
            "/rpc-api/supply-chain/",
            "/callbacks/suppliers/");

    @Bean
    @ConditionalOnProperty(prefix = "aifuli.supply-chain.openapi", name = "enabled", havingValue = "true")
    GroupedOpenApi supplyChainAdminOpenApi() {
        return group("supply-chain-admin", "/admin-api/supply-chain/**");
    }

    @Bean
    @ConditionalOnProperty(prefix = "aifuli.supply-chain.openapi", name = "enabled", havingValue = "true")
    GroupedOpenApi supplyChainBuyerOpenApi() {
        return group("supply-chain-buyer", "/rpc-api/supply-chain/**");
    }

    @Bean
    @ConditionalOnProperty(prefix = "aifuli.supply-chain.openapi", name = "enabled", havingValue = "true")
    GroupedOpenApi supplyChainCallbackOpenApi() {
        return group("supply-chain-callback", "/callbacks/suppliers/**");
    }

    /**
     * 组合部署时宿主应用仍可能提供全局 all 分组，因此关闭供应链文档后还要主动移除供应链路径。
     */
    @Bean
    GlobalOpenApiCustomizer supplyChainVisibilityCustomizer(SupplyChainOpenApiProperties properties) {
        return openApi -> {
            if (properties.isEnabled() || openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().keySet().removeIf(path ->
                    SUPPLY_CHAIN_PATH_PREFIXES.stream().anyMatch(path::startsWith));
        };
    }

    private static GroupedOpenApi group(String name, String path) {
        return GroupedOpenApi.builder()
                .group(name)
                .pathsToMatch(path)
                .build();
    }
}
