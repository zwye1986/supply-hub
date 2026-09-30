package com.aifuli.module.supplychain.infrastructure.security;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.auth.ProjectCallContextAccessorImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 项目调用可信上下文默认实现的 fail-closed 合同。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#42-可信上下文}。</p>
 */
class ProjectCallContextConfigurationTest {

    private static final String AUTO_CONFIGURATION_CLASS_NAME =
            ProjectCallContextConfiguration.class.getName();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProjectCallContextConfiguration.class));

    @Test
    void defaultAccessorFailsClosedWhenNoTrustedAccessorIsProvided() {
        contextRunner.run(context -> {
            ProjectCallContextAccessor accessor = context.getBean(ProjectCallContextAccessor.class);

            assertBuyerAuthInvalid(accessor::requireBuyerContext);
            assertBuyerAuthInvalid(accessor::requireChannelContext);
            assertBuyerAuthInvalid(accessor::requireProjectContext);
        });
    }

    @Test
    void fallbackIsDiscoveredAsBootAutoConfiguration() {
        assertThat(ImportCandidates.load(
                AutoConfiguration.class, ProjectCallContextConfiguration.class.getClassLoader()).getCandidates())
                .contains(AUTO_CONFIGURATION_CLASS_NAME);
        assertThat(ProjectCallContextConfiguration.class).hasAnnotation(AutoConfiguration.class);
    }

    @Test
    void realComponentReliablyPreventsFallbackAndLeavesSingleAccessor() {
        assertThat(ProjectCallContextAccessorImpl.class).hasAnnotation(Component.class);

        contextRunner.withUserConfiguration(RealAccessorConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProjectCallContextAccessor.class);
            assertThat(context.getBean(ProjectCallContextAccessor.class))
                    .isInstanceOf(ProjectCallContextAccessorImpl.class);
        });
    }

    @Test
    void customAccessorPreventsDefaultAccessorRegistration() {
        contextRunner.withUserConfiguration(CustomAccessorConfiguration.class).run(context -> {
            assertThat(context.getBeansOfType(ProjectCallContextAccessor.class)).containsOnlyKeys("customProjectCallContextAccessor");
            assertThat(context.getBean(ProjectCallContextAccessor.class))
                    .isSameAs(context.getBean("customProjectCallContextAccessor"));
        });
    }

    @Test
    void buyerContextToStringOmitsBuyerUserId() {
        ProjectCallContextAccessor.BuyerContext context =
                new ProjectCallContextAccessor.BuyerContext(1L, 2L, "BUYER-USER-SENTINEL");

        assertThat(context.toString())
                .contains("projectId=1", "buyerId=2")
                .doesNotContain("BUYER-USER-SENTINEL");
    }

    @Test
    void projectContextRejectsMissingProjectId() {
        assertThatThrownBy(() -> new ProjectCallContextAccessor.ProjectContext(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");
    }

    private static void assertBuyerAuthInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(BUYER_AUTH_INVALID.getCode());
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAccessorConfiguration {

        @Bean
        ProjectCallContextAccessor customProjectCallContextAccessor() {
            return new ProjectCallContextAccessor() {
                @Override
                public BuyerContext requireBuyerContext() {
                    return new BuyerContext(1L, 2L, "buyer-user-001");
                }

                @Override
                public ChannelContext requireChannelContext() {
                    return new ChannelContext(1L, "INSTORE");
                }

                @Override
                public ProjectContext requireProjectContext() {
                    return new ProjectContext(1L);
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ProjectCallContextAccessorImpl.class)
    static class RealAccessorConfiguration {
    }
}
