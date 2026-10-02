package com.aifuli.module.supplychain.infrastructure.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.Properties;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 项目应用认证配置单测：验证配置契约、默认值与 enabled 模式下主密钥失败关闭。
 *
 * <p>详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#66-配置项}。</p>
 */
class ProjectAppAuthConfigurationTest {

    private static final String VALID_KEY = "MDEyMzQ1Njc4OWFiY2RlZmdoaWprbG1ub3BxcnN0dXY=";
    private static final String AUTO_CONFIGURATION_CLASS_NAME = ProjectAppAuthConfiguration.class.getName();
    private static final String OTHER_AUTO_CONFIGURATIONS = ImportCandidates
            .load(AutoConfiguration.class, ProjectAppAuthConfiguration.class.getClassLoader())
            .getCandidates()
            .stream()
            .filter(candidate -> !AUTO_CONFIGURATION_CLASS_NAME.equals(candidate))
            .collect(Collectors.joining(","));

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("spring.autoconfigure.exclude=" + OTHER_AUTO_CONFIGURATIONS)
            .withUserConfiguration(AutoConfigurationRegistration.class, FilterDependenciesConfiguration.class);

    @Test
    void resolverIsDiscoveredThroughBootAutoConfigurationImports() {
        assertThat(ImportCandidates.load(
                AutoConfiguration.class, ProjectAppAuthConfiguration.class.getClassLoader()).getCandidates())
                .contains(AUTO_CONFIGURATION_CLASS_NAME);
        assertThat(ProjectAppAuthConfiguration.class).hasAnnotation(AutoConfiguration.class);

        contextRunner.withPropertyValues("aifuli.supply-chain.auth.master-key=" + VALID_KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AppKeySecretResolver.class);
                    assertThat(context).hasSingleBean(AppKeySecretIssuer.class);
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                });
    }

    @Test
    void contextRegistersSingleDefaultResolverAndBindsProperties() {
        contextRunner.withPropertyValues(
                        "aifuli.supply-chain.auth.master-key=" + VALID_KEY,
                        "aifuli.supply-chain.auth.timestamp-skew-minutes=7",
                        "aifuli.supply-chain.auth.nonce-retention-hours=2",
                        "aifuli.supply-chain.auth.max-request-body-bytes=4096")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AppKeySecretResolver.class);
                    assertThat(context.getBean(AppKeySecretResolver.class))
                            .isInstanceOf(LocalEnvelopeAppKeySecretResolver.class);

                    ProjectAppAuthProperties properties = context.getBean(ProjectAppAuthProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.getMasterKey()).isEqualTo(VALID_KEY);
                    assertThat(properties.getTimestampSkewMinutes()).isEqualTo(7);
                    assertThat(properties.getNonceRetentionHours()).isEqualTo(2);
                    assertThat(properties.getMaxRequestBodyBytes()).isEqualTo(4096);
                });
    }

    @Test
    void contextFailsClosedWhenEnabledAndMasterKeyMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("aifuli.supply-chain.auth.master-key 未配置，项目认证失败关闭");
        });
    }

    @Test
    void customResolverPreventsDefaultRegistrationWithoutLocalMasterKey() {
        contextRunner.withUserConfiguration(CustomResolverConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AppKeySecretResolver.class);
            assertThat(context.getBean(AppKeySecretResolver.class))
                    .isSameAs(context.getBean("customAppKeySecretResolver"));
        });
    }

    @Test
    void contextStartsWithoutMasterKeyWhenDisabledAndKeepsUnusedDefaultResolver() {
        contextRunner.withPropertyValues("aifuli.supply-chain.auth.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AppKeySecretResolver.class);
            assertThat(context.getBean(AppKeySecretResolver.class))
                    .isInstanceOf(LocalEnvelopeAppKeySecretResolver.class);
            assertThat(context.getBean(ProjectAppAuthProperties.class).isEnabled()).isFalse();
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
        });
    }

    @Test
    void enabledFilterRegistrationCoversAllProtectedPrefixesBeforeSecurity() {
        contextRunner.withPropertyValues("aifuli.supply-chain.auth.master-key=" + VALID_KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.getFilter()).isInstanceOf(ProjectAppAuthFilter.class);
                    assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
                    assertThat(registration.isAsyncSupported()).isFalse();
                    assertThat(registration.getUrlPatterns()).containsExactlyInAnyOrder(
                            "/rpc-api/supply-chain/instore/channel-command/*",
                            "/rpc-api/supply-chain/instore/channel-member/*",
                            "/rpc-api/supply-chain/instore/buyer-entry/*",
                            "/rpc-api/supply-chain/buyer/instore/payment/*",
                            "/open-api/supply-chain/project/v1/orders/*");
                    assertThat(registration.getUrlPatterns()).containsExactlyInAnyOrderElementsOf(
                            com.aifuli.module.supplychain.api.auth.ProjectAuthOperations.PROTECTED_PREFIXES.stream()
                                    .map(prefix -> prefix + "/*").toList());
                });
    }

    @Test
    void enabledContextFailsClosedForNonPositiveOrAboveHardMaximumBodyLimit() {
        for (int invalid : new int[]{0, -1, ProjectAppAuthProperties.HARD_MAX_REQUEST_BODY_BYTES + 1}) {
            contextRunner.withPropertyValues(
                            "aifuli.supply-chain.auth.master-key=" + VALID_KEY,
                            "aifuli.supply-chain.auth.max-request-body-bytes=" + invalid)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                                .rootCause().hasMessageContaining("max-request-body-bytes", "1..1048576");
                    });
        }
    }

    @Test
    void enabledContextFailsClosedForNonPositiveOrOverflowingTimestampSkew() {
        for (long invalid : new long[]{0, -1, Long.MAX_VALUE}) {
            contextRunner.withPropertyValues(
                            "aifuli.supply-chain.auth.master-key=" + VALID_KEY,
                            "aifuli.supply-chain.auth.timestamp-skew-minutes=" + invalid)
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .hasStackTraceContaining("timestamp-skew-minutes");
                        if (invalid == Long.MAX_VALUE) {
                            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(ArithmeticException.class);
                        } else {
                            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                        }
                    });
        }
    }

    @Test
    void disabledContextSkipsBodyAndTimestampValidationTogetherWithFilter() {
        contextRunner.withPropertyValues(
                        "aifuli.supply-chain.auth.enabled=false",
                        "aifuli.supply-chain.auth.max-request-body-bytes=0",
                        "aifuli.supply-chain.auth.timestamp-skew-minutes=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                });
    }

    @Test
    void applicationYamlMergesAllProtectedPrefixesIntoSecurityPermitUrls() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        Properties properties = yaml.getObject();

        Set<String> permitted = IntStream.range(0, 16)
                .mapToObj(index -> properties.getProperty("aifuli.security.permit-all-urls[" + index + "]"))
                .filter(value -> value != null)
                .collect(Collectors.toSet());
        assertThat(permitted).contains(
                "/rpc-api/supply-chain/instore/channel-command/*",
                "/rpc-api/supply-chain/instore/channel-member/*",
                "/rpc-api/supply-chain/instore/buyer-entry/*",
                "/rpc-api/supply-chain/buyer/instore/payment/*",
                "/v3/api-docs",
                "/v3/api-docs/**",
                "/webjars/**",
                "/swagger-ui",
                "/swagger-ui/**",
                "/admin-api/supply-chain/v3/api-docs",
                "/admin-api/supply-chain/v3/api-docs/**");
        assertThat(properties.getProperty("aifuli.supply-chain.auth.max-request-body-bytes"))
                .isEqualTo("${AIFULI_SUPPLY_CHAIN_AUTH_MAX_REQUEST_BODY_BYTES:1048576}");
    }

    @Test
    void propertiesExposeExpectedPrefixAndDefaults() {
        ConfigurationProperties annotation = ProjectAppAuthProperties.class
                .getAnnotation(ConfigurationProperties.class);
        ProjectAppAuthProperties properties = new ProjectAppAuthProperties();

        assertThat(annotation.prefix()).isEqualTo("aifuli.supply-chain.auth");
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getMasterKey()).isNull();
        assertThat(properties.getTimestampSkewMinutes()).isEqualTo(5);
        assertThat(properties.getNonceRetentionHours()).isEqualTo(1);
        assertThat(properties.getMaxRequestBodyBytes())
                .isEqualTo(ProjectAppAuthProperties.HARD_MAX_REQUEST_BODY_BYTES);
    }

    @Test
    void propertiesToStringExcludesMasterKeyButRetainsSafeDiagnostics() {
        String sentinelMasterKey = "SENTINEL_MASTER_KEY_MUST_NEVER_APPEAR";
        ProjectAppAuthProperties properties = new ProjectAppAuthProperties();
        properties.setEnabled(false);
        properties.setMasterKey(sentinelMasterKey);
        properties.setTimestampSkewMinutes(9);

        assertThat(properties.getMasterKey()).isEqualTo(sentinelMasterKey);
        assertThat(properties.toString())
                .doesNotContain(sentinelMasterKey)
                .contains("enabled=false", "timestampSkewMinutes=9");
    }

    @Test
    void decodeMasterKeyReturnsBytesWhenValid() {
        ProjectAppAuthProperties properties = new ProjectAppAuthProperties();
        properties.setMasterKey(VALID_KEY);

        assertThat(ProjectAppAuthConfiguration.decodeMasterKey(properties))
                .isEqualTo(Base64.getDecoder().decode(VALID_KEY));
    }

    @Test
    void decodeMasterKeyFailsWhenMissing() {
        ProjectAppAuthProperties properties = new ProjectAppAuthProperties();

        assertThatThrownBy(() -> ProjectAppAuthConfiguration.decodeMasterKey(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("master-key");
    }

    @Test
    void decodeMasterKeyFailsWhenBlank() {
        ProjectAppAuthProperties properties = new ProjectAppAuthProperties();
        properties.setMasterKey("  ");

        assertThatThrownBy(() -> ProjectAppAuthConfiguration.decodeMasterKey(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("master-key");
    }

    @Test
    void decodeMasterKeyFailsWhenNotBase64AndPreservesCause() {
        ProjectAppAuthProperties properties = new ProjectAppAuthProperties();
        properties.setMasterKey("not-base64!!!");

        assertThatThrownBy(() -> ProjectAppAuthConfiguration.decodeMasterKey(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodeMasterKeyFailsWhenWrongLength() {
        ProjectAppAuthProperties properties = new ProjectAppAuthProperties();
        properties.setMasterKey(Base64.getEncoder().encodeToString(new byte[16]));

        assertThatThrownBy(() -> ProjectAppAuthConfiguration.decodeMasterKey(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }

    @Test
    void decodeMasterKeySkipsValidationWhenDisabled() {
        ProjectAppAuthProperties properties = new ProjectAppAuthProperties();
        properties.setEnabled(false);
        properties.setMasterKey("not-base64!!!");

        assertThat(ProjectAppAuthConfiguration.decodeMasterKey(properties)).isEmpty();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class AutoConfigurationRegistration {
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomResolverConfiguration {

        @Bean
        AppKeySecretResolver customAppKeySecretResolver() {
            return (appId, keyVersion, secretRef) -> "custom-app-key";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FilterDependenciesConfiguration {

        @Bean
        ProjectAppCredentialService projectAppCredentialService() {
            return mock(ProjectAppCredentialService.class);
        }

        @Bean
        NonceStore nonceStore() {
            return mock(NonceStore.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
