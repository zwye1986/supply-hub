package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteReqDTO;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.StandardPricingQuoteDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.StandardPricingQuoteMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.CurrentProjectPricing;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService.FrozenQuote;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FixedOperator;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FormulaRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.HideRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PriceMode;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingConfig;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.ServiceFeeRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实 MySQL（已应用 051）上的项目永久键报价语义：项目行锁+DB 唯一键串行首次签发且并发只落一条、
 * 同分销商不同项目同订单号各自独立签发、大小写等价订单号按唯一键重放、消费后仍可重放同 token、
 * 缺报价抛稳定业务错误。迁移与 HALT 预条件由 {@code StandardPricingQuotePermanentKeyMigrationIntegrationTest} 覆盖。
 * 设计见 docs/superpowers/specs/2026-10-02-standard-internal-quote-freeze-design.md#2-首次签发与重放、#3-受理内查取与唯一消费。
 */
class StandardPricingQuotePermanentKeyIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");
    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static StandardPricingQuoteService service;
    private static StandardPricingQuoteMapper quoteMapper;
    private static TransactionTemplate tx;
    private static SupplyChainDatabaseClock clock;
    private static BuyerResolver buyerResolver;
    private static GenericBatchPricingService pricingService;

    @BeforeAll
    static void startContainer() {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
        context = applicationContext();
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        service = context.getBean(StandardPricingQuoteService.class);
        quoteMapper = context.getBean(StandardPricingQuoteMapper.class);
        tx = context.getBean(TransactionTemplate.class);
        clock = context.getBean(SupplyChainDatabaseClock.class);
        buyerResolver = context.getBean(BuyerResolver.class);
        pricingService = context.getBean(GenericBatchPricingService.class);
    }

    @AfterAll
    static void stopContainer() {
        if (context != null) {
            context.close();
        }
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @BeforeEach
    void stubBuyerAndPricing() throws Exception {
        when(buyerResolver.resolve(anyString(), anyString(), any()))
                .thenReturn(new BuyerResolver.BuyerSnapshot(1L, "BYR_A", "分销商A", "A", 1));
        ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
        FormulaRule direct = new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null);
        PricingConfig config = new PricingConfig(direct, direct,
                new ServiceFeeRule(new BigDecimal("1"), FixedOperator.MULTIPLY, BigDecimal.ZERO, true, null),
                new HideRule(null, null),
                new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, "不征税"));
        ProjectSkuPricingVersionDO version = ProjectSkuPricingVersionDO.builder()
                .id(9L).pricingStatus(PricingStatus.READY.name())
                .distributionPriceCents(9_800L).salesPriceCents(10_000L)
                .effectiveConfigJson(objectMapper.writeValueAsString(config)).build();
        when(pricingService.ensureCurrentProjectVersion(anyString(), anyString()))
                .thenReturn(new CurrentProjectPricing(version, 3L, 4L, "MOVIE_100"));
    }

    @Test
    void concurrentFirstIssueByProjectKeyYieldsSingleQuoteAndSameToken() throws Exception {
        insertProject(201L, "PRJ_CONC", "并发签发项目");
        StandardPricingQuoteReqDTO request = requestOf("PRJ_CONC", "ORD-CONC");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<String> tokenA = new AtomicReference<>();
        AtomicReference<String> tokenB = new AtomicReference<>();
        try {
            Future<?> first = executor.submit(() -> quoteAndCapture(request, ready, start, tokenA));
            Future<?> second = executor.submit(() -> quoteAndCapture(request, ready, start, tokenB));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(tokenA.get()).isNotBlank();
        assertThat(tokenB.get()).isEqualTo(tokenA.get());
        assertThat(countQuotes(201L, "ORD-CONC")).isEqualTo(1);
    }

    @Test
    void sameBuyerIssuesIndependentQuotesForTwoProjectsWithSameOrderNumber() {
        insertProject(202L, "PRJ_X1", "跨项目一号");
        insertProject(203L, "PRJ_X2", "跨项目二号");

        var first = service.quote(requestOf("PRJ_X1", "ORD-X"));
        var second = service.quote(requestOf("PRJ_X2", "ORD-X"));

        assertThat(first.getQuoteToken()).isNotBlank();
        assertThat(second.getQuoteToken()).isNotBlank().isNotEqualTo(first.getQuoteToken());
        assertThat(countQuotes(202L, "ORD-X")).isEqualTo(1);
        assertThat(countQuotes(203L, "ORD-X")).isEqualTo(1);
    }

    @Test
    void caseEquivalentOrderNumbersReplayTheSamePermanentQuote() {
        insertProject(204L, "PRJ_CASE", "大小写等价项目");
        service.quote(requestOf("PRJ_CASE", "Ord-Ci-9"));

        var replay = service.quote(requestOf("PRJ_CASE", "ORD-CI-9"));

        StandardPricingQuoteDO only = quoteMapper.selectByProjectOrder(204L, "Ord-Ci-9");
        assertThat(replay.getQuoteToken()).isEqualTo(only.getQuoteToken());
        assertThat(countQuotes(204L, "ORD-CI-9")).isEqualTo(1);
    }

    @Test
    void consumedQuoteReplaysSameTokenAndRequiresByProjectKeyWithoutExpiry() {
        insertProject(205L, "PRJ_LIFE", "全生命周期项目");
        var issued = service.quote(requestOf("PRJ_LIFE", "ORD-LIFE"));
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM sc_buyer_project WHERE project_code = 'PRJ_LIFE'", Long.class);

        tx.executeWithoutResult(status -> {
            FrozenQuote frozen = service.requireForProject(
                    new ProjectQuoteKeyContext(1L, projectId, 4L, "ORD-LIFE", 1L));
            service.markConsumed(frozen.quoteId(), 777L, clock.now());
        });

        var replay = service.quote(requestOf("PRJ_LIFE", "ORD-LIFE"));
        assertThat(replay.getQuoteToken()).isEqualTo(issued.getQuoteToken());
        assertThat(replay.getUserPayableAmountCents()).isEqualTo(issued.getUserPayableAmountCents());

        tx.executeWithoutResult(status -> {
            FrozenQuote consumed = service.requireForProject(
                    new ProjectQuoteKeyContext(1L, projectId, 4L, "ORD-LIFE", 1L));
            assertThat(consumed.consumed()).isTrue();
            assertThat(consumed.consumedOrderId()).isEqualTo(777L);
        });
    }

    @Test
    void missingQuoteIsRejectedWithStableBusinessErrorAndNoPricingCall() {
        insertProject(206L, "PRJ_MISS", "缺报价项目");
        Long projectId = jdbc.queryForObject(
                "SELECT id FROM sc_buyer_project WHERE project_code = 'PRJ_MISS'", Long.class);

        // 断言放在事务之外：内层 MANDATORY 代理抛业务异常会把参与的外层事务标记回滚，
        // 缺报价属只读拒绝路径，回滚即预期结果。
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> service.requireForProject(
                        new ProjectQuoteKeyContext(1L, projectId, 4L, "ORD-NEVER", 1L))))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode())
                        .isEqualTo(SupplyChainErrorCodeConstants.STANDARD_PRICING_QUOTE_MISSING.getCode()));
    }

    private static void quoteAndCapture(StandardPricingQuoteReqDTO request, CountDownLatch ready,
                                        CountDownLatch start, AtomicReference<String> token) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发签发起跑信号超时");
            }
            token.set(service.quote(request).getQuoteToken());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static int countQuotes(long projectId, String orderNo) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM sc_standard_pricing_quote WHERE project_id = ? AND buyer_order_no = ?
                """, Integer.class, projectId, orderNo);
    }

    private static void insertProject(long id, String code, String name) {
        jdbc.update("""
                INSERT INTO sc_buyer_project(id, project_code, project_name, buyer_id, catalog_mode, status, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1, 'MANAGED', ?, 0, NOW(3), NOW(3))
                """, id, code, name, BuyerProjectStatus.ACTIVE.getCode());
    }

    private static StandardPricingQuoteReqDTO requestOf(String projectCode, String orderNo) {
        StandardPricingQuoteReqDTO request = new StandardPricingQuoteReqDTO();
        request.setSourceSystem("AIFULI_MALL");
        request.setExternalTenantId("tenant-a");
        request.setProjectCode(projectCode);
        request.setPlatformSkuCode("MOVIE_100");
        request.setBuyerOrderNo(orderNo);
        request.setQuantity(1L);
        return request;
    }

    private static ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        return new SpringApplicationBuilder(TestConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run();
    }

    private static String jdbcUrl() {
        return EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
    }

    private static String username() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-username", "root") : MYSQL.getUsername();
    }

    private static String password() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-password", "") : MYSQL.getPassword();
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SupplyChainDataSourceConfiguration.class)
    static class TestConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        BuyerResolver buyerResolver() {
            return mock(BuyerResolver.class);
        }

        @Bean
        GenericBatchPricingService genericBatchPricingService() {
            return mock(GenericBatchPricingService.class);
        }

        @Bean
        StandardPricingQuoteService standardPricingQuoteService(BuyerProjectMapper projectMapper,
                                                                StandardPricingQuoteMapper mapper,
                                                                SupplyChainDatabaseClock databaseClock,
                                                                ObjectMapper objectMapper,
                                                                BuyerResolver resolver,
                                                                GenericBatchPricingService pricing) {
            return new StandardPricingQuoteService(resolver, projectMapper, pricing, mapper,
                    databaseClock, objectMapper);
        }

        @Bean
        TransactionTemplate supplyChainTransactionTemplate(
                @Qualifier("supplyChainTransactionManager") PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }
    }
}
