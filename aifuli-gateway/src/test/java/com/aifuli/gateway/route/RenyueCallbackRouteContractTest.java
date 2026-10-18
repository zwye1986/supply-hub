package com.aifuli.gateway.route;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 仁跃临时合同只开放原样转发的单版本 POST，见详细设计 §5.1.3。 */
class RenyueCallbackRouteContractTest {
    @Test
    void onlySingleVersionPostIsRoutedWithoutRewriting() {
        var loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yaml"));
        var properties = loader.getObject();
        String prefix = properties.stringPropertyNames().stream()
                .filter(key -> key.endsWith(".id") && "supply-chain-renyue-callback".equals(properties.getProperty(key)))
                .findFirst().orElseThrow().replaceFirst("\\.id$", "");
        assertThat(properties.getProperty(prefix + ".uri"))
                .isEqualTo("${AIFULI_GATEWAY_SUPPLY_CHAIN_URI:grayLb://supply-chain-server}");
        assertThat(properties.getProperty(prefix + ".predicates[0]"))
                .isEqualTo("Path=/callbacks/suppliers/renyue/{connectorVersionId}");
        assertThat(properties.getProperty(prefix + ".predicates[1]")).isEqualTo("Method=POST");
        assertThat(properties.stringPropertyNames()).noneMatch(key -> key.startsWith(prefix + ".filters"));
        var pathConfig = new PathRoutePredicateFactory.Config();
        pathConfig.setPatterns(List.of(properties.getProperty(prefix + ".predicates[0]").substring(5)));
        var pathPredicate = new PathRoutePredicateFactory().apply(pathConfig);
        var methodConfig = new MethodRoutePredicateFactory.Config();
        methodConfig.setMethods(HttpMethod.valueOf(properties.getProperty(prefix + ".predicates[1]").substring(7)));
        var predicate = pathPredicate.and(new MethodRoutePredicateFactory().apply(methodConfig));
        assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.post("/callbacks/suppliers/renyue/101")))).isTrue();
        assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.get("/callbacks/suppliers/renyue/101")))).isFalse();
        for (String path : new String[]{"/callbacks/suppliers/renyue", "/callbacks/suppliers/renyue/101/extra",
                "/callbacks/suppliers/renyue-other/101", "/callbacks/suppliers/other/101"}) {
            assertThat(predicate.test(MockServerWebExchange.from(MockServerHttpRequest.post(path)))).as(path).isFalse();
        }
    }
}
