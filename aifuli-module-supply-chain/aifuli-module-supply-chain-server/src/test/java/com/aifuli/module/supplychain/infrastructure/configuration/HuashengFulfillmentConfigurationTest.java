package com.aifuli.module.supplychain.infrastructure.configuration;

import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationTicketFactory;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCommunicationFacade;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.infrastructure.security.FrozenCredentialSetResolver;
import com.aifuli.module.supplychain.infrastructure.security.SupplierEndpointPolicy;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 华昇生产履约显式配置装配单元测试。
 *
 * <p>验证：
 * 1. 默认及 enabled=false 条件下不装配任何华昇组件；
 * 2. enabled=true 条件下装配全部真实组件（真实 executor、facade、runner 等，禁 mock executor）。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#31-执行边界}、
 * {@code #9-验收启用与停止条件}。</p>
 */
class HuashengFulfillmentConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(HuashengFulfillmentConfiguration.class, MockDependenciesConfig.class);

    @Test
    @DisplayName("默认情况下（未配置 enabled 属性）不装配华昇组件")
    void whenDisabledByDefault_noHuashengBeansLoaded() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(HuashengFulfillmentRunner.class);
            assertThat(context).doesNotHaveBean(HuashengOperationExecutor.class);
            assertThat(context).doesNotHaveBean(HuashengCommunicationFacade.class);
            assertThat(context).doesNotHaveBean(HuashengOrderV1Metadata.class);
            assertThat(context).doesNotHaveBean("huashengExecutionLock");
            assertThat(context).doesNotHaveBean(HuashengExecuteTimerHandler.class);
            assertThat(context).doesNotHaveBean(HuashengRecoveryTimerHandler.class);
            assertThat(context).doesNotHaveBean(HuashengLeaseWatchdogHandler.class);
            assertThat(context).doesNotHaveBean(HuashengPreparationWatchdogHandler.class);
        });
    }

    @Test
    @DisplayName("enabled=false 时显式不装配华昇组件")
    void whenExplicitlyDisabled_noHuashengBeansLoaded() {
        contextRunner.withPropertyValues("aifuli.supply-chain.huasheng-fulfillment.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(HuashengFulfillmentRunner.class);
                    assertThat(context).doesNotHaveBean(HuashengOperationExecutor.class);
                    assertThat(context).doesNotHaveBean(HuashengCommunicationFacade.class);
                    assertThat(context).doesNotHaveBean(HuashengOrderV1Metadata.class);
                    assertThat(context).doesNotHaveBean("huashengExecutionLock");
                });
    }

    @Test
    @DisplayName("enabled=true 时装配真实组件（包含真实的 HuashengOperationExecutor 与 HuashengFulfillmentRunner）")
    void whenEnabled_loadsAllRealBeans() {
        contextRunner.withPropertyValues("aifuli.supply-chain.huasheng-fulfillment.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(HuashengFulfillmentRunner.class);
                    assertThat(context).hasSingleBean(HuashengOperationExecutor.class);
                    assertThat(context).hasSingleBean(HuashengCommunicationFacade.class);
                    assertThat(context).hasSingleBean(HuashengOrderV1Metadata.class);
                    assertThat(context).hasBean("huashengExecutionLock");
                    assertThat(context.getBean("huashengExecutionLock")).isInstanceOf(ReentrantLock.class);

                    assertThat(context).hasSingleBean(HuashengExecuteTimerHandler.class);
                    assertThat(context).hasSingleBean(HuashengRecoveryTimerHandler.class);
                    assertThat(context).hasSingleBean(HuashengLeaseWatchdogHandler.class);
                    assertThat(context).hasSingleBean(HuashengPreparationWatchdogHandler.class);

                    // 验证 executor 为真实实例而非 mock
                    HuashengOperationExecutor executor = context.getBean(HuashengOperationExecutor.class);
                    assertThat(executor.getClass()).isEqualTo(HuashengOperationExecutor.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class MockDependenciesConfig {
        @Bean
        SupplierEndpointPolicy supplierEndpointPolicy() {
            return mock(SupplierEndpointPolicy.class);
        }

        @Bean
        HuashengFulfillmentLifecycle huashengFulfillmentLifecycle() {
            return mock(HuashengFulfillmentLifecycle.class);
        }

        @Bean
        FulfillmentOperationTicketFactory ticketFactory() {
            return mock(FulfillmentOperationTicketFactory.class);
        }

        @Bean
        FrozenCredentialSetResolver frozenCredentialSetResolver() {
            return mock(FrozenCredentialSetResolver.class);
        }

        @Bean
        PublishedConnectorConfigurationResolver connectorResolver() {
            return mock(PublishedConnectorConfigurationResolver.class);
        }

        @Bean
        FulfillmentOperationMapper operationMapper() {
            return mock(FulfillmentOperationMapper.class);
        }

        @Bean
        BOrderMapper bOrderMapper() {
            return mock(BOrderMapper.class);
        }

        @Bean
        FulfillmentRequestMapper requestMapper() {
            return mock(FulfillmentRequestMapper.class);
        }

        @Bean
        SupplierMapper supplierMapper() {
            return mock(SupplierMapper.class);
        }

        @Bean
        SupplierOfferVersionMapper offerVersionMapper() {
            return mock(SupplierOfferVersionMapper.class);
        }

        @Bean
        SupplyChainDatabaseClock databaseClock() {
            return mock(SupplyChainDatabaseClock.class);
        }
    }
}
