package com.aifuli.module.supplychain.infrastructure.configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 供应链 OpenAPI 分组与组合部署隐藏规则单测。
 *
 * <p>详见 {@code docs/superpowers/plans/2026-08-11-supply-chain-m1-qnm-vertical-slice.md#task-3}。</p>
 */
class SupplyChainOpenApiConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SupplyChainOpenApiConfiguration.class))
            .withPropertyValues("aifuli.supply-chain.enabled=true");

    @Test
    void exposesThreeGroupedOpenApiBeansWhenDocumentationEnabled() {
        contextRunner
                .withPropertyValues("aifuli.supply-chain.openapi.enabled=true")
                .run(context -> {
                    Map<String, Set<String>> groups = context.getBeansOfType(GroupedOpenApi.class).values().stream()
                            .collect(Collectors.toMap(
                                    GroupedOpenApi::getGroup,
                                    grouped -> Set.copyOf(grouped.getPathsToMatch())));

                    assertThat(groups).containsEntry(
                            "supply-chain-admin", Set.of("/admin-api/supply-chain/**"));
                    assertThat(groups).containsEntry(
                            "supply-chain-buyer", Set.of("/rpc-api/supply-chain/**"));
                    assertThat(groups).containsEntry(
                            "supply-chain-callback", Set.of("/callbacks/suppliers/**"));
                    assertThat(groups).hasSize(3);
                });
    }

    @Test
    void hidesGroupedOpenApiWhenDocumentationDisabled() {
        contextRunner
                .withPropertyValues("aifuli.supply-chain.openapi.enabled=false")
                .run(context -> assertThat(context.getBeansOfType(GroupedOpenApi.class)).isEmpty());
    }

    @Test
    void removesSupplyChainPathsFromHostAllDocumentWhenDocumentationDisabled() {
        contextRunner
                .withPropertyValues("aifuli.supply-chain.openapi.enabled=false")
                .run(context -> {
                    GlobalOpenApiCustomizer customizer =
                            context.getBean("supplyChainVisibilityCustomizer", GlobalOpenApiCustomizer.class);

                    OpenAPI openApi = new OpenAPI();
                    Paths paths = new Paths();
                    paths.addPathItem("/admin-api/supply-chain/orders", new PathItem());
                    paths.addPathItem("/rpc-api/supply-chain/fulfillment/accept", new PathItem());
                    paths.addPathItem("/callbacks/suppliers/qnm/payment", new PathItem());
                    paths.addPathItem("/rpc-api/member/profile", new PathItem());
                    openApi.setPaths(paths);

                    customizer.customise(openApi);

                    assertThat(openApi.getPaths().keySet()).containsExactly("/rpc-api/member/profile");
                });
    }

    @Test
    void keepsSupplyChainPathsWhenDocumentationEnabled() {
        contextRunner
                .withPropertyValues("aifuli.supply-chain.openapi.enabled=true")
                .run(context -> {
                    GlobalOpenApiCustomizer customizer =
                            context.getBean("supplyChainVisibilityCustomizer", GlobalOpenApiCustomizer.class);

                    OpenAPI openApi = new OpenAPI();
                    Paths paths = new Paths();
                    paths.addPathItem("/admin-api/supply-chain/orders", new PathItem());
                    paths.addPathItem("/rpc-api/member/profile", new PathItem());
                    openApi.setPaths(paths);

                    customizer.customise(openApi);

                    assertThat(openApi.getPaths().keySet()).containsExactlyInAnyOrder(
                            "/admin-api/supply-chain/orders",
                            "/rpc-api/member/profile");
                });
    }
}
