package com.aifuli.module.supplychain.infrastructure.configuration;

import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationTicketFactory;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.*;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.infrastructure.security.FrozenCredentialSetResolver;
import com.aifuli.module.supplychain.infrastructure.security.SupplierEndpointPolicy;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.token.huasheng.HuashengInMemoryTokenCache;
import com.aifuli.module.supplychain.supplieradapter.huasheng.http.HuashengHttpExecutor;
import com.aifuli.module.supplychain.supplieradapter.huasheng.http.HuashengHttpTransportPort;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengRequestBuilder;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 华昇生产履约显式配置装配。
 *
 * <p>职责：
 * 连接 A4 传输（HuashengHttpExecutor）、A5 缓存与门面（HuashengTokenManager, HuashengCommunicationFacade）、
 * 专用 executor（HuashengOperationExecutor）、metadata（HuashengOrderV1Metadata）、
 * 进程级唯一执行互斥锁（ReentrantLock）、生产 Runner 与相关 Timer/Watchdog Handlers。
 * 启用属性为 aifuli.supply-chain.huasheng-fulfillment.enabled=false（默认关闭）。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#31-执行边界}、
 * {@code #9-验收启用与停止条件}。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "aifuli.supply-chain.huasheng-fulfillment.enabled", havingValue = "true", matchIfMissing = false)
public class HuashengFulfillmentConfiguration {

    @Bean
    public ReentrantLock huashengExecutionLock() {
        return new ReentrantLock();
    }

    @Bean
    public HuashengOrderV1Metadata huashengOrderV1Metadata() {
        return new HuashengOrderV1Metadata();
    }

    @Bean
    public HuashengHttpTransportPort huashengHttpTransportPort(SupplierEndpointPolicy endpointPolicy) {
        return new HuashengHttpExecutor(endpointPolicy);
    }

    @Bean
    public HuashengTokenCachePort huashengTokenCachePort() {
        return new HuashengInMemoryTokenCache();
    }

    @Bean
    public HuashengSafeLog huashengSafeLog() {
        return new HuashengSafeLog();
    }

    @Bean
    public HuashengRequestBuilder huashengRequestBuilder() {
        return new HuashengRequestBuilder();
    }

    @Bean
    public HuashengTokenManager huashengTokenManager(
            HuashengTokenCachePort cachePort,
            HuashengRequestBuilder requestBuilder,
            HuashengHttpTransportPort transportPort) {
        return new HuashengTokenManager(cachePort, requestBuilder, transportPort, Clock.systemUTC(), System::nanoTime);
    }

    @Bean
    public HuashengCommunicationFacade huashengCommunicationFacade(
            HuashengTokenManager tokenManager,
            HuashengRequestBuilder requestBuilder,
            HuashengHttpTransportPort transportPort,
            HuashengSafeLog safeLog) {
        return new HuashengCommunicationFacade(tokenManager, requestBuilder, transportPort, Clock.systemUTC(), System::nanoTime, safeLog);
    }

    @Bean
    public HuashengOperationExecutor huashengOperationExecutor(HuashengCommunicationFacade facade) {
        return new HuashengOperationExecutor(facade);
    }

    @Bean
    public HuashengFulfillmentRunner huashengFulfillmentRunner(
            ReentrantLock huashengExecutionLock,
            HuashengFulfillmentLifecycle lifecycle,
            HuashengOperationExecutor executor,
            FulfillmentOperationTicketFactory ticketFactory,
            FrozenCredentialSetResolver frozenCredentialSetResolver,
            PublishedConnectorConfigurationResolver connectorResolver,
            FulfillmentOperationMapper operationMapper,
            BOrderMapper bOrderMapper,
            FulfillmentRequestMapper requestMapper,
            SupplierMapper supplierMapper,
            SupplierOfferVersionMapper offerVersionMapper,
            SupplyChainDatabaseClock databaseClock) {
        return new HuashengFulfillmentRunner(
                huashengExecutionLock,
                lifecycle,
                executor,
                ticketFactory,
                frozenCredentialSetResolver,
                connectorResolver,
                operationMapper,
                bOrderMapper,
                requestMapper,
                supplierMapper,
                offerVersionMapper,
                databaseClock
        );
    }

    @Bean
    public HuashengExecuteTimerHandler huashengExecuteTimerHandler(ObjectProvider<HuashengFulfillmentRunner> runner) {
        return new HuashengExecuteTimerHandler(runner);
    }

    @Bean
    public HuashengRecoveryTimerHandler huashengRecoveryTimerHandler(ObjectProvider<HuashengFulfillmentRunner> runner) {
        return new HuashengRecoveryTimerHandler(runner);
    }

    @Bean
    public HuashengLeaseWatchdogHandler huashengLeaseWatchdogHandler(ObjectProvider<HuashengFulfillmentRunner> runner) {
        return new HuashengLeaseWatchdogHandler(runner);
    }

    @Bean
    public HuashengPreparationWatchdogHandler huashengPreparationWatchdogHandler(ObjectProvider<HuashengFulfillmentRunner> runner) {
        return new HuashengPreparationWatchdogHandler(runner);
    }

    /** 人工 Case 原单核实与租约收尾 Timer 处理器（2026-10-13 华昇错误分类设计 §6.2）。 */
    @Bean
    public HuashengCaseVerifyTimerHandler huashengCaseVerifyTimerHandler(
            ObjectProvider<com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentCaseVerifyExecutionService> serviceProvider) {
        return new HuashengCaseVerifyTimerHandler(serviceProvider);
    }

    @Bean
    public HuashengCaseVerifyFinalizeTimerHandler huashengCaseVerifyFinalizeTimerHandler(
            ObjectProvider<com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentCaseVerifyExecutionService> serviceProvider) {
        return new HuashengCaseVerifyFinalizeTimerHandler(serviceProvider);
    }
}
