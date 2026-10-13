package com.aifuli.gateway.route;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** 精确签名入口配置契约；见 docs/superpowers/specs/2026-10-12-platform-sku-order-contract-design.md#7-端到端安全接线。 */
class StandardOrderRouteContractTest {
    @Test
    void configuredRouteAcceptsOnlyRegisteredPathsWithoutTrailingSlash() {
        var loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yaml"));
        var properties = loader.getObject();
        String prefix = properties.stringPropertyNames().stream()
                .filter(key -> key.endsWith(".id") && "supply-chain-standard-orders".equals(properties.getProperty(key)))
                .findFirst().orElseThrow().replaceFirst("\\.id$", "");
        var config = new PathRoutePredicateFactory.Config();
        String patterns = properties.getProperty(prefix + ".predicates[0].args.patterns");
        if (patterns == null) {
            patterns = properties.getProperty(prefix + ".predicates[0]").substring("Path=".length());
        }
        config.setPatterns(Arrays.stream(patterns.split(",")).map(String::trim).toList());
        String trailing = properties.getProperty(prefix + ".predicates[0].args.matchTrailingSlash");
        if (trailing != null) { config.setMatchTrailingSlash(Boolean.parseBoolean(trailing)); }
        var predicate = new PathRoutePredicateFactory().apply(config);
        for (String version : new String[]{"v1", "v2"}) {
            for (String suffix : new String[]{"", "/query", "/assets/query"}) {
                String path = "/open-api/supply-chain/project/" + version + "/orders" + suffix;
                assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.post(path)))).as(path).isTrue();
                assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.post(path + "/")))).as("尾斜杠不能匹配：" + path).isFalse();
                assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.post(path + "/child")))).isFalse();
            }
        }
        assertThat(properties.getProperty(prefix + ".predicates[1]")).isEqualTo("Method=POST");
        assertThat(properties.stringPropertyNames()).noneMatch(key -> key.startsWith(prefix + ".filters"));
    }
}
