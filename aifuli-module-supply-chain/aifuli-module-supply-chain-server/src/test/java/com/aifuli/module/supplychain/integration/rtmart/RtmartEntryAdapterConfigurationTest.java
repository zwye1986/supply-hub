package com.aifuli.module.supplychain.integration.rtmart;

import com.aifuli.module.supplychain.integration.configuration.InstoreEntryPortConfiguration;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.ConfigurableApplicationContext;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 旅通入口真实 Adapter 自动配置测试，覆盖启用态 fail-fast 与未启用态失败关闭。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#33-bean-启用与失败关闭}
 * 与 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#7-配置与秘密管理}。</p>
 */
class RtmartEntryAdapterConfigurationTest {

    private static final String PREFIX = "aifuli.supply-chain.instore.rtmart.";
    private static final String SECRET_SENTINEL = "rtmart-private-key-secret-sentinel";
    private static final String VALID_PRIVATE_KEY = generatePrivateKey();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RtmartEntryAdapterConfiguration.class, InstoreEntryPortConfiguration.class,
                    JacksonAutoConfiguration.class));

    @Test
    void autoConfigurationIsDiscoveredAndOrderedBeforeFallbackConfiguration() {
        List<String> candidates = ImportCandidates.load(
                        AutoConfiguration.class, RtmartEntryAdapterConfiguration.class.getClassLoader())
                .getCandidates();
        int adapterConfigurationIndex = candidates.indexOf(RtmartEntryAdapterConfiguration.class.getName());
        int fallbackConfigurationIndex = candidates.indexOf(InstoreEntryPortConfiguration.class.getName());

        assertThat(adapterConfigurationIndex).isGreaterThanOrEqualTo(0);
        assertThat(fallbackConfigurationIndex).isGreaterThan(adapterConfigurationIndex);
        assertThat(RtmartEntryAdapterConfiguration.class.getAnnotation(AutoConfiguration.class).before())
                .contains(InstoreEntryPortConfiguration.class);
    }

    @Test
    void disabledOrUnconfiguredAdapterLeavesOnlyFailClosedFallbackPort() {
        assertFallbackOnly(contextRunner);
        assertFallbackOnly(contextRunner.withPropertyValues(PREFIX + "enabled=false"));
    }

    @Test
    void enabledAdapterBindsPropertiesParsesPrivateKeyAndUsesDedicatedHttpClient() {
        contextRunner.withPropertyValues(enabledProperties().toArray(String[]::new))
                .run(context -> {
                    assertThat(context).hasSingleBean(RtmartEntryUrlPort.class);
                    assertThat(context.getBean(RtmartEntryUrlPort.class))
                            .isInstanceOf(RtmartEntryUrlHttpAdapter.class);
                    assertThat(context.getBeansOfType(RtmartEntryUrlPort.class))
                            .containsOnlyKeys("rtmartEntryUrlHttpAdapter");
                    assertThat(context).hasSingleBean(RtmartRequestSigner.class);
                    assertThat(context).hasSingleBean(OkHttpClient.class);

                    RtmartEntryProperties properties = context.getBean(RtmartEntryProperties.class);
                    assertThat(properties.getAppKey()).isEqualTo("rtmart-app-key");
                    assertThat(properties.getPrivateKey()).isEqualTo(VALID_PRIVATE_KEY);
                    assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofMillis(123));
                    assertThat(properties.getWriteTimeout()).isEqualTo(Duration.ofMillis(456));
                    assertThat(properties.getReadTimeout()).isEqualTo(Duration.ofMillis(789));

                    OkHttpClient client = context.getBean("rtmartEntryOkHttpClient", OkHttpClient.class);
                    assertThat(client.connectTimeoutMillis()).isEqualTo(123);
                    assertThat(client.writeTimeoutMillis()).isEqualTo(456);
                    assertThat(client.readTimeoutMillis()).isEqualTo(789);
                    assertThat(client.retryOnConnectionFailure()).isFalse();
                    assertThat(client.followRedirects()).isFalse();
                    assertThat(client.followSslRedirects()).isFalse();
                });
    }

    @Test
    void enabledAdapterFailsClosedForInvalidConfigurationWithoutLeakingSecrets() {
        assertConfigurationFailure(PREFIX + "enabled=true", PREFIX + "base-url=https://test.ltsw.cn",
                PREFIX + "private-key=" + VALID_PRIVATE_KEY);
        assertConfigurationFailure(PREFIX + "enabled=true", PREFIX + "base-url=https://test.ltsw.cn",
                PREFIX + "app-key=rtmart-app-key");
        assertConfigurationFailure(PREFIX + "enabled=true", PREFIX + "base-url=https://test.ltsw.cn",
                PREFIX + "app-key=rtmart-app-key", PREFIX + "private-key=" + SECRET_SENTINEL);
        assertConfigurationFailure(PREFIX + "enabled=true", PREFIX + "base-url=http://localhost:8080",
                PREFIX + "app-key=rtmart-app-key", PREFIX + "private-key=" + VALID_PRIVATE_KEY);
        assertConfigurationFailure(PREFIX + "enabled=true", PREFIX + "home-path=https://untrusted.example/path",
                PREFIX + "app-key=rtmart-app-key", PREFIX + "private-key=" + VALID_PRIVATE_KEY);
        assertConfigurationFailure(PREFIX + "enabled=true", PREFIX + "connect-timeout=999us",
                PREFIX + "app-key=rtmart-app-key", PREFIX + "private-key=" + VALID_PRIVATE_KEY);
    }

    @Test
    void customPortSkipsEntireAdapterConfigurationAndDoesNotRequireRtmartSecrets() {
        contextRunner.withUserConfiguration(CustomPortConfiguration.class)
                .withPropertyValues(PREFIX + "enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(RtmartEntryUrlPort.class);
                    assertThat(context.getBeansOfType(RtmartEntryUrlPort.class))
                            .containsOnlyKeys("customRtmartEntryUrlPort");
                    assertThat(context).doesNotHaveBean(RtmartEntryUrlHttpAdapter.class);
                    assertThat(context).doesNotHaveBean(RtmartRequestSigner.class);
                    assertThat(context).doesNotHaveBean(OkHttpClient.class);
                    assertThat(context).doesNotHaveBean(RtmartEntryProperties.class);
                });
    }

    @Test
    void dedicatedClientDoesNotCompeteWithSharedClientForUnqualifiedConsumers() {
        contextRunner.withUserConfiguration(SharedOkHttpClientConfiguration.class)
                .withPropertyValues(enabledProperties().toArray(String[]::new))
                .run(context -> {
                    OkHttpClient sharedClient = context.getBean("sharedOkHttpClient", OkHttpClient.class);
                    OkHttpClient rtmartClient = context.getBean("rtmartEntryOkHttpClient", OkHttpClient.class);

                    assertThat(context.getBean(UnqualifiedOkHttpClientConsumer.class).client()).isSameAs(sharedClient);
                    assertThat(rtmartClient).isNotSameAs(sharedClient);
                    assertThat(context.getBean(RtmartEntryUrlPort.class))
                            .isInstanceOf(RtmartEntryUrlHttpAdapter.class);
                });
    }

    @Test
    void lazySpringApplicationFailsDuringStartupForInvalidPrivateKeyWithoutLeakingSecret() {
        Throwable startupFailure = catchThrowable(() -> startLazySpringApplication(SECRET_SENTINEL));

        assertThat(startupFailure).isNotNull();
        assertThat(hasConfigurationCause(startupFailure)).isTrue();
        assertThat(exceptionTreeContains(startupFailure, SECRET_SENTINEL)).isFalse();
    }

    @Test
    void lazySpringApplicationCreatesAdapterSignerAndClientBeforeStartupCompletes() {
        try (ConfigurableApplicationContext context = startLazySpringApplication(VALID_PRIVATE_KEY)) {
            assertThat(context.getBeanFactory().containsSingleton("rtmartEntryOkHttpClient")).isTrue();
            assertThat(context.getBeanFactory().containsSingleton("rtmartRequestSigner")).isTrue();
            assertThat(context.getBeanFactory().containsSingleton("rtmartEntryUrlHttpAdapter")).isTrue();
            assertThat(context.getBean(RtmartEntryUrlPort.class)).isInstanceOf(RtmartEntryUrlHttpAdapter.class);
        }
    }

    private static void assertFallbackOnly(ApplicationContextRunner runner) {
        runner.run(context -> {
            assertThat(context).hasSingleBean(RtmartEntryUrlPort.class);
            assertThat(context.getBeansOfType(RtmartEntryUrlPort.class)).containsOnlyKeys("rtmartEntryUrlPort");
            assertThatThrownBy(() -> context.getBean(RtmartEntryUrlPort.class).getHomeUrl(
                    new RtmartEntryUrlPort.HomeUrlCommand(1L, "RT_MART", "COMPANY", "121.47", "31.23", "token")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Rtmart entry URL adapter is not configured");
            assertThat(context).doesNotHaveBean(RtmartEntryUrlHttpAdapter.class);
            assertThat(context).doesNotHaveBean(RtmartRequestSigner.class);
            assertThat(context).doesNotHaveBean(OkHttpClient.class);
            assertThat(context).doesNotHaveBean(RtmartEntryProperties.class);
        });
    }

    private void assertConfigurationFailure(String... properties) {
        contextRunner.withPropertyValues(properties).run(context -> {
                    Throwable startupFailure = context.getStartupFailure();
                    assertThat(startupFailure).isNotNull();
                    assertThat(hasConfigurationCause(startupFailure)).isTrue();
                    assertThat(exceptionTreeContains(startupFailure, SECRET_SENTINEL)).isFalse();
                });
    }

    private static boolean hasConfigurationCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RtmartEntryException exception
                    && exception.getReason() == RtmartEntryException.Reason.CONFIGURATION) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean exceptionTreeContains(Throwable throwable, String text) {
        Deque<Throwable> remaining = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        remaining.add(throwable);
        while (!remaining.isEmpty()) {
            Throwable current = remaining.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
            if (current.getCause() != null) {
                remaining.addLast(current.getCause());
            }
            Collections.addAll(remaining, current.getSuppressed());
        }
        return false;
    }

    private static ConfigurableApplicationContext startLazySpringApplication(String privateKey) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.main.lazy-initialization", true);
        properties.put(PREFIX + "enabled", true);
        properties.put(PREFIX + "base-url", "https://test.ltsw.cn");
        properties.put(PREFIX + "app-key", "rtmart-app-key");
        properties.put(PREFIX + "private-key", privateKey);
        return new SpringApplicationBuilder(LazySpringApplicationConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run();
    }

    private static List<String> enabledProperties() {
        return List.of(
                PREFIX + "enabled=true",
                PREFIX + "base-url=https://test.ltsw.cn",
                PREFIX + "app-key=rtmart-app-key",
                PREFIX + "private-key=" + VALID_PRIVATE_KEY,
                PREFIX + "connect-timeout=123ms",
                PREFIX + "write-timeout=456ms",
                PREFIX + "read-timeout=789ms");
    }

    private static String generatePrivateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate test RSA key pair", exception);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomPortConfiguration {

        @Bean
        RtmartEntryUrlPort customRtmartEntryUrlPort() {
            return new RtmartEntryUrlPort() {
                @Override
                public EntryUrlResult getHomeUrl(HomeUrlCommand command) {
                    return new EntryUrlResult("https://custom.example/home");
                }

                @Override
                public EntryUrlResult getDetailUrl(DetailUrlCommand command) {
                    return new EntryUrlResult("https://custom.example/detail");
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SharedOkHttpClientConfiguration {

        @Bean
        OkHttpClient sharedOkHttpClient() {
            return new OkHttpClient();
        }

        @Bean
        UnqualifiedOkHttpClientConsumer unqualifiedOkHttpClientConsumer(OkHttpClient client) {
            return new UnqualifiedOkHttpClientConsumer(client);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({RtmartEntryAdapterConfiguration.class, InstoreEntryPortConfiguration.class, JacksonAutoConfiguration.class})
    static class LazySpringApplicationConfiguration {
    }

    private record UnqualifiedOkHttpClientConsumer(OkHttpClient client) {
    }
}
