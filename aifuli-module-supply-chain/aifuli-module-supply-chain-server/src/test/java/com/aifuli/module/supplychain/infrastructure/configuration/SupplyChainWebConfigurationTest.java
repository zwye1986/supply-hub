package com.aifuli.module.supplychain.infrastructure.configuration;

import com.aifuli.module.supplychain.interfaceapi.admin.ProjectAdminController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 供应链管理端 Web 前缀配置契约，防止 {@code interfaceapi.admin} 偏离框架默认包名后丢失
 * {@code /admin-api} 前缀。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#31-路径与鉴权}。</p>
 */
class SupplyChainWebConfigurationTest {

    private static final String ADMIN_CONTROLLER_PATTERN =
            "aifuli.web.admin-api.controller";

    @Test
    void adminControllerPackageIsIncludedInWebPrefixConfiguration() throws IOException {
        List<PropertySource<?>> propertySources = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));

        Object configuredPattern = propertySources.stream()
                .filter(source -> source.containsProperty(ADMIN_CONTROLLER_PATTERN))
                .map(source -> source.getProperty(ADMIN_CONTROLLER_PATTERN))
                .findFirst()
                .orElse(null);

        assertThat(configuredPattern).isInstanceOf(String.class);
        // 与 AifuliWebAutoConfiguration 一致，按 Java 包名的点号而非 URL 斜杠匹配。
        assertThat(new AntPathMatcher(".").match(
                (String) configuredPattern, ProjectAdminController.class.getPackageName()))
                .as("管理端 Controller 包必须获得 /admin-api 前缀")
                .isTrue();
    }
}
