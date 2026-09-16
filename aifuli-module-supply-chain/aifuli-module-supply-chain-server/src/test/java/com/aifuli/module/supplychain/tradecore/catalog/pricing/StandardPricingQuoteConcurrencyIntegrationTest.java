package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.StandardPricingQuoteDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.StandardPricingQuoteMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService.FrozenQuote;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 真实 MySQL 上行锁串行化凭证消费、回滚恢复与同订单幂等。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#8-自动化测试与联调门禁}。
 */
class StandardPricingQuoteConcurrencyIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");
    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");

    @BeforeAll
    static void startContainer() {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @Test
    void concurrentConsumptionAllowsOnlyOneOrderAndRollbackRestoresIssued() throws Exception {
        try (ConfigurableApplicationContext context = applicationContext()) {
            StandardPricingQuoteMapper mapper = context.getBean(StandardPricingQuoteMapper.class);
            StandardPricingQuoteService service = context.getBean(StandardPricingQuoteService.class);
            TransactionTemplate tx = context.getBean(TransactionTemplate.class);
            SupplyChainDatabaseClock clock = context.getBean(SupplyChainDatabaseClock.class);
            LocalDateTime now = clock.now();

            StandardPricingQuoteDO quote = insertIssued(mapper, "token-concurrent", "ORD-CONCURRENT", now);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = executor.submit(consume(service, tx, quote, now, 101L, ready, start, successes, conflicts));
                Future<?> second = executor.submit(consume(service, tx, quote, now, 102L, ready, start, successes, conflicts));
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
            assertThat(successes.get()).isEqualTo(1);
            assertThat(conflicts.get()).isEqualTo(1);
            StandardPricingQuoteDO afterRace = mapper.selectById(quote.getId());
            assertThat(afterRace.getStatus()).isEqualTo(PricingQuoteStatus.CONSUMED.name());
            assertThat(afterRace.getConsumedOrderId()).isIn(101L, 102L);

            StandardPricingQuoteDO rollbackQuote = insertIssued(mapper, "token-rollback", "ORD-ROLLBACK", now);
            tx.executeWithoutResult(status -> {
                FrozenQuote frozen = service.requireForFulfillment(contextOf(rollbackQuote, now.plusMinutes(1)));
                service.markConsumed(frozen.quoteId(), 301L, now.plusMinutes(1));
                status.setRollbackOnly();
            });
            StandardPricingQuoteDO afterRollback = mapper.selectById(rollbackQuote.getId());
            assertThat(afterRollback.getStatus()).isEqualTo(PricingQuoteStatus.ISSUED.name());
            assertThat(afterRollback.getConsumedOrderId()).isNull();

            StandardPricingQuoteDO idempotent = insertIssued(mapper, "token-idempotent", "ORD-IDEM", now);
            tx.executeWithoutResult(status -> {
                FrozenQuote frozen = service.requireForFulfillment(contextOf(idempotent, now.plusMinutes(1)));
                service.markConsumed(frozen.quoteId(), 201L, now.plusMinutes(1));
            });
            tx.executeWithoutResult(status -> {
                FrozenQuote frozen = service.requireForFulfillment(contextOf(idempotent, now.plusMinutes(1)));
                service.markConsumed(frozen.quoteId(), 201L, now.plusMinutes(1));
            });
            assertThat(mapper.selectById(idempotent.getId()).getConsumedOrderId()).isEqualTo(201L);
        }
    }

    private static Runnable consume(StandardPricingQuoteService service, TransactionTemplate tx,
                                    StandardPricingQuoteDO quote, LocalDateTime now, long orderId,
                                    CountDownLatch ready, CountDownLatch start,
                                    AtomicInteger successes, AtomicInteger conflicts) {
        return () -> {
            ready.countDown();
            try {
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待并发消费起跑信号超时");
                }
                tx.executeWithoutResult(status -> {
                    FrozenQuote frozen = service.requireForFulfillment(contextOf(quote, now.plusMinutes(1)));
                    service.markConsumed(frozen.quoteId(), orderId, now.plusMinutes(1));
                });
                successes.incrementAndGet();
            } catch (RuntimeException ex) {
                conflicts.incrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        };
    }

    private static QuoteConsumptionContext contextOf(StandardPricingQuoteDO quote, LocalDateTime paidAt) {
        return new QuoteConsumptionContext(quote.getQuoteToken(), quote.getBuyerId(), quote.getProjectId(),
                quote.getPlatformSkuId(), quote.getBuyerOrderNo(), quote.getBuyerUserId(),
                quote.getQuantity(), paidAt);
    }

    private static StandardPricingQuoteDO insertIssued(StandardPricingQuoteMapper mapper, String token,
                                                       String orderNo, LocalDateTime now) {
        StandardPricingQuoteDO quote = StandardPricingQuoteDO.builder()
                .quoteToken(token).sourceSystem("AIFULI_MALL").externalTenantId("tenant-a")
                .buyerId(1L).projectId(2L).listingId(3L).platformSkuId(4L).platformSkuCode("MOVIE_100")
                .projectPricingVersionId(9L).buyerUserId("13800138000").buyerOrderNo(orderNo)
                .quantity(1L).distributionUnitPriceCents(9_800L).salesUnitPriceCents(10_000L)
                .productSubtotalCents(10_000L).serviceFeeCents(0L).userPayableAmountCents(10_000L)
                .taxMetadataJson("{\"taxIncluded\":false,\"taxRateCode\":\"NON_TAXABLE\"}")
                .status(PricingQuoteStatus.ISSUED.name()).expiresAt(now.plusMinutes(15)).createdAt(now)
                .build();
        mapper.insert(quote);
        return quote;
    }

    private ConfigurableApplicationContext applicationContext() {
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
        StandardPricingQuoteService standardPricingQuoteService(StandardPricingQuoteMapper mapper,
                                                                SupplyChainDatabaseClock clock,
                                                                ObjectMapper objectMapper) {
            return new StandardPricingQuoteService(mock(BuyerResolver.class), mock(BuyerProjectMapper.class),
                    mock(GenericBatchPricingService.class), mapper, clock, objectMapper);
        }

        @Bean
        TransactionTemplate supplyChainTransactionTemplate(
                @Qualifier("supplyChainTransactionManager") PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }
    }
}
