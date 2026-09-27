package com.aifuli.module.supplychain.infrastructure.identity;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.framework.common.exception.ServiceException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;

import java.util.Map;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编号占用与业务事务共享同一个真实 MySQL 连接，覆盖多实例共用的数据库锁语义。
 * 设计见 docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#5-存储迁移与并发。
 */
class ScopedBusinessCodeIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    @BeforeAll
    static void start() {
        MYSQL.start();
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    @Test
    void manualHighNumberDoesNotPreventAutoFillingLowestFreeNumber() {
        try (ConfigurableApplicationContext context = context()) {
            ScopedBusinessCodeService service = context.getBean(ScopedBusinessCodeService.class);
            assertThat(service.allocateRoot(ScopedBusinessCodeService.Kind.BRD, "BRD_100").code())
                    .isEqualTo("BRD_100");
            assertThat(service.allocateRoot(ScopedBusinessCodeService.Kind.BRD, null).code())
                    .isEqualTo("BRD_001");
            assertThat(service.allocateRoot(ScopedBusinessCodeService.Kind.BRD, null).code())
                    .isEqualTo("BRD_002");
        }
    }

    @Test
    void childCodesUseParentScopeAndRemainGloballyDistinct() {
        try (ConfigurableApplicationContext context = context()) {
            ScopedBusinessCodeService service = context.getBean(ScopedBusinessCodeService.class);
            assertThat(service.allocateChild(ScopedBusinessCodeService.Kind.SKU, 101L, 1, null).code())
                    .isEqualTo("SKU_001_001");
            assertThat(service.allocateChild(ScopedBusinessCodeService.Kind.SKU, 102L, 2, null).code())
                    .isEqualTo("SKU_002_001");
        }
    }

    @Test
    void manualCodeWithSurroundingSpacesIsRejectedInsteadOfSilentlyNormalized() {
        try (ConfigurableApplicationContext context = context()) {
            ScopedBusinessCodeService service = context.getBean(ScopedBusinessCodeService.class);
            assertThatThrownBy(() -> service.allocateRoot(ScopedBusinessCodeService.Kind.SPL, " SPL_001 "))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("SPL_001");
        }
    }

    @Test
    void legacyVisibleCodeIsSkippedByAutomaticAllocation() {
        try (ConfigurableApplicationContext context = context()) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.update("INSERT INTO sc_brand (brand_code, brand_name, status, created_at, updated_at) "
                    + "VALUES ('brd_001', '历史品牌', 0, NOW(3), NOW(3))");
            ScopedBusinessCodeService service = context.getBean(ScopedBusinessCodeService.class);
            assertThat(service.allocateRoot(ScopedBusinessCodeService.Kind.BRD, null).code())
                    .isEqualTo("BRD_002");
        }
    }

    @Test
    void historicalLookupReturnsOnlyRequestedCandidateCodes() {
        try (ConfigurableApplicationContext context = context()) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.update("INSERT INTO sc_brand (brand_code, brand_name, status, created_at, updated_at) "
                    + "VALUES ('BRD_001', '候选品牌', 0, NOW(3), NOW(3))");
            jdbc.update("INSERT INTO sc_brand (brand_code, brand_name, status, created_at, updated_at) "
                    + "VALUES ('BRD_LEGACY', '无关历史品牌', 0, NOW(3), NOW(3))");
            var mapper = context.getBean(
                    com.aifuli.module.supplychain.infrastructure.identity.dal.mysql.BusinessCodeExistingMapper.class);
            assertThat(mapper.selectExistingCodes(ScopedBusinessCodeService.Kind.BRD.name(),
                    List.of("BRD_001", "BRD_002"))).containsExactly("BRD_001");
        }
    }

    @Test
    void allocationLogsEntranceAndResultWithOperator() {
        try (ConfigurableApplicationContext context = context()) {
            var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                    .getLogger(ScopedBusinessCodeService.class);
            var priorLevel = logger.getLevel();
            var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
            try {
                context.getBean(ScopedBusinessCodeService.class)
                        .allocateRoot(ScopedBusinessCodeService.Kind.BRD, null, 42L);
                assertThat(appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage))
                        .anyMatch(message -> message.contains("分配入口") && message.contains("operatorId=42"))
                        .anyMatch(message -> message.contains("分配完成") && message.contains("BRD_001")
                                && message.contains("operatorId=42"));
            } finally {
                logger.detachAppender(appender);
                logger.setLevel(priorLevel);
                appender.stop();
            }
        }
    }

    @Test
    void permanentCodeCollisionAndScopeClaimHaveDistinctErrorCodes() {
        try (ConfigurableApplicationContext context = context()) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.update("INSERT INTO sc_brand (brand_code, brand_name, status, created_at, updated_at) "
                    + "VALUES ('BRD_100', '历史品牌', 0, NOW(3), NOW(3))");
            ScopedBusinessCodeService service = context.getBean(ScopedBusinessCodeService.class);
            ServiceException permanent = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> service.allocateRoot(ScopedBusinessCodeService.Kind.BRD, "BRD_100"),
                    ServiceException.class);
            service.allocateRoot(ScopedBusinessCodeService.Kind.BRD, "BRD_101");
            ServiceException occupied = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> service.allocateRoot(ScopedBusinessCodeService.Kind.BRD, "BRD_101"),
                    ServiceException.class);
            assertThat(permanent.getCode()).isNotEqualTo(occupied.getCode());
        }
    }

    @Test
    void legacyParentCollisionReportsParentTypeWithDedicatedError() {
        try (ConfigurableApplicationContext context = context()) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.update("INSERT INTO sc_buyer (buyer_code, buyer_name, short_name, buyer_type, status, "
                    + "created_at, updated_at) VALUES ('BYR_OLD', '旧分销商', '旧', 'SAAS_MALL', 10, NOW(3), NOW(3))");
            context.getBean(BusinessCodeLegacyBackfill.class).backfillNow();
            ScopedBusinessCodeService service = context.getBean(ScopedBusinessCodeService.class);
            ServiceException conflict = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> service.allocateRoot(ScopedBusinessCodeService.Kind.BYR, "BYR_001"),
                    ServiceException.class);
            assertThat(conflict.getCode())
                    .isNotEqualTo(com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants
                            .BUSINESS_CODE_OCCUPIED.getCode());
            assertThat(conflict.getMessage()).contains("旧分销商");
        }
    }

    @Test
    void backfillReportsBothObjectsBeforeConflictingParentNumbersAreWritten() {
        try (ConfigurableApplicationContext context = context()) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.update("INSERT INTO sc_buyer (buyer_code, code_scope_no, buyer_name, short_name, "
                    + "buyer_type, status, created_at, updated_at) "
                    + "VALUES ('BYR_OLD', 100, '旧分销商甲', '甲', 'SAAS_MALL', 10, NOW(3), NOW(3))");
            jdbc.update("INSERT INTO sc_buyer (buyer_code, buyer_name, short_name, buyer_type, status, "
                    + "created_at, updated_at) VALUES ('BYR_100', '旧分销商乙', '乙', 'SAAS_MALL', 10, NOW(3), NOW(3))");
            long firstId = jdbc.queryForObject("SELECT id FROM sc_buyer WHERE buyer_code='BYR_OLD'", Long.class);
            long secondId = jdbc.queryForObject("SELECT id FROM sc_buyer WHERE buyer_code='BYR_100'", Long.class);
            try {
                assertThatThrownBy(() -> context.getBean(BusinessCodeLegacyBackfill.class).backfillNow())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("BYR_OLD")
                        .hasMessageContaining("BYR_100")
                        .hasMessageContaining("id=" + firstId)
                        .hasMessageContaining("id=" + secondId);
                assertThat(jdbc.queryForObject("SELECT code_scope_no FROM sc_buyer WHERE id=?", Integer.class,
                        secondId)).isNull();
            } finally {
                // 下一测试启动时会先执行 ApplicationRunner；冲突样本必须在关闭上下文前清掉。
                jdbc.update("DELETE FROM sc_buyer WHERE buyer_code IN ('BYR_OLD', 'BYR_100')");
            }
        }
    }

    @Test
    void oldBuyerGetsStableHiddenNumberWithoutChangingPermanentCode() {
        try (ConfigurableApplicationContext context = context()) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.update("INSERT INTO sc_buyer (buyer_code, buyer_name, short_name, buyer_type, status, "
                    + "created_at, updated_at) VALUES ('BYR_100', '旧分销商甲', '甲', 'SAAS_MALL', 10, NOW(3), NOW(3))");
            jdbc.update("INSERT INTO sc_buyer (buyer_code, buyer_name, short_name, buyer_type, status, "
                    + "created_at, updated_at) VALUES ('BYR_OLD', '旧分销商乙', '乙', 'SAAS_MALL', 10, NOW(3), NOW(3))");
            BusinessCodeLegacyBackfill backfill = context.getBean(BusinessCodeLegacyBackfill.class);
            backfill.backfillNow();
            backfill.backfillNow();
            assertThat(jdbc.queryForObject("SELECT code_scope_no FROM sc_buyer WHERE buyer_code='BYR_100'", Integer.class))
                    .isEqualTo(100);
            assertThat(jdbc.queryForObject("SELECT code_scope_no FROM sc_buyer WHERE buyer_code='BYR_OLD'", Integer.class))
                    .isEqualTo(1);
            long oldBuyerId = jdbc.queryForObject("SELECT id FROM sc_buyer WHERE buyer_code='BYR_OLD'", Long.class);
            assertThat(context.getBean(ScopedBusinessCodeService.class)
                    .allocateChild(ScopedBusinessCodeService.Kind.PRJ, oldBuyerId, 1, null).code())
                    .isEqualTo("PRJ_001_001");
        }
    }

    @Test
    void oldSpuAndCategoryGetHiddenParentNumbersWithoutChangingPermanentCodes() {
        try (ConfigurableApplicationContext context = context()) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.update("INSERT INTO sc_brand (brand_code, brand_name, status, created_at, updated_at) "
                    + "VALUES ('BRD_LEGACY', '历史品牌', 10, NOW(3), NOW(3))");
            jdbc.update("INSERT INTO sc_product_category (category_code, category_name, category_level, status, "
                    + "created_at, updated_at) VALUES ('CAT_LEGACY', '历史分类', 1, 10, NOW(3), NOW(3))");
            long brandId = jdbc.queryForObject("SELECT id FROM sc_brand WHERE brand_code='BRD_LEGACY'", Long.class);
            long categoryId = jdbc.queryForObject("SELECT id FROM sc_product_category "
                    + "WHERE category_code='CAT_LEGACY'", Long.class);
            jdbc.update("INSERT INTO sc_platform_spu (spu_code, brand_id, primary_category_id, spu_name, status, "
                    + "created_at, updated_at) VALUES ('SPU_LEGACY', ?, ?, '历史商品', 0, NOW(3), NOW(3))",
                    brandId, categoryId);
            BusinessCodeLegacyBackfill backfill = context.getBean(BusinessCodeLegacyBackfill.class);
            backfill.backfillNow();
            backfill.backfillNow();
            assertThat(jdbc.queryForObject("SELECT code_scope_no FROM sc_platform_spu "
                    + "WHERE spu_code='SPU_LEGACY'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT category_node_no FROM sc_product_category "
                    + "WHERE category_code='CAT_LEGACY'", Integer.class)).isEqualTo(1);
            long spuId = jdbc.queryForObject("SELECT id FROM sc_platform_spu WHERE spu_code='SPU_LEGACY'", Long.class);
            ScopedBusinessCodeService service = context.getBean(ScopedBusinessCodeService.class);
            assertThat(service.allocateChild(ScopedBusinessCodeService.Kind.SKU, spuId, 1, null).code())
                    .isEqualTo("SKU_001_001");
            assertThat(service.allocateChild(ScopedBusinessCodeService.Kind.CAT_CHILD, categoryId, 1, null).code())
                    .isEqualTo("CAT_001_001");
        }
    }

    @Test
    void rolledBackClaimCanBeReused() {
        try (ConfigurableApplicationContext context = context()) {
            ScopedBusinessCodeService service = context.getBean(ScopedBusinessCodeService.class);
            TransactionTemplate tx = new TransactionTemplate(
                    context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
            String rolledBack = tx.execute(status -> {
                String code = service.allocateRoot(ScopedBusinessCodeService.Kind.SPL, null).code();
                status.setRollbackOnly();
                return code;
            });
            assertThat(service.allocateRoot(ScopedBusinessCodeService.Kind.SPL, null).code())
                    .isEqualTo(rolledBack);
        }
    }

    @Test
    void twoInstancesAllocateDistinctCodesWithinSharedMysqlScope() throws Exception {
        try (ConfigurableApplicationContext context = context();
             ConfigurableApplicationContext another = contextWithoutCleanup();
             var pool = Executors.newFixedThreadPool(2)) {
            ScopedBusinessCodeService service = context.getBean(ScopedBusinessCodeService.class);
            ScopedBusinessCodeService otherService = another.getBean(ScopedBusinessCodeService.class);
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> {
                start.await();
                return otherService.allocateRoot(ScopedBusinessCodeService.Kind.SPL, null).code();
            });
            var second = pool.submit(() -> {
                start.await();
                return service.allocateRoot(ScopedBusinessCodeService.Kind.SPL, null).code();
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("SPL_001", "SPL_002");
        }
    }

    @Test
    void twoInstancesCannotClaimTheSameManualCode() throws Exception {
        try (ConfigurableApplicationContext context = context();
             ConfigurableApplicationContext another = contextWithoutCleanup();
             var pool = Executors.newFixedThreadPool(2)) {
            ScopedBusinessCodeService firstService = context.getBean(ScopedBusinessCodeService.class);
            ScopedBusinessCodeService secondService = another.getBean(ScopedBusinessCodeService.class);
            CountDownLatch start = new CountDownLatch(1);
            var first = pool.submit(() -> {
                start.await();
                return firstService.allocateRoot(ScopedBusinessCodeService.Kind.BRD, "BRD_100").code();
            });
            var second = pool.submit(() -> {
                start.await();
                return secondService.allocateRoot(ScopedBusinessCodeService.Kind.BRD, "BRD_100").code();
            });
            start.countDown();
            int success = 0;
            int occupied = 0;
            for (var future : List.of(first, second)) {
                try {
                    assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo("BRD_100");
                    success++;
                } catch (java.util.concurrent.ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(ServiceException.class)
                            .hasMessageContaining("BRD_100");
                    occupied++;
                }
            }
            assertThat(success).isEqualTo(1);
            assertThat(occupied).isEqualTo(1);
        }
    }

    @Test
    void claimedNumberIsNeverReusedAfterBusinessRecordIsArchivedOrRemoved() {
        try (ConfigurableApplicationContext context = context()) {
            ScopedBusinessCodeService service = context.getBean(ScopedBusinessCodeService.class);
            assertThat(service.allocateRoot(ScopedBusinessCodeService.Kind.BRD, "BRD_100").code())
                    .isEqualTo("BRD_100");
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            jdbc.update("INSERT INTO sc_brand (brand_code, brand_name, status, created_at, updated_at) "
                    + "VALUES ('BRD_100', '待归档品牌', 0, NOW(3), NOW(3))");
            jdbc.update("UPDATE sc_brand SET status=90 WHERE brand_code='BRD_100'");
            assertThatThrownBy(() -> service.allocateRoot(ScopedBusinessCodeService.Kind.BRD, "BRD_100"))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("BRD_100");
            jdbc.update("DELETE FROM sc_brand WHERE brand_code='BRD_100'");
            assertThatThrownBy(() -> service.allocateRoot(ScopedBusinessCodeService.Kind.BRD, "BRD_100"))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("BRD_100");
            assertThat(service.allocateRoot(ScopedBusinessCodeService.Kind.BRD, null).code())
                    .isEqualTo("BRD_001");
        }
    }

    @Test
    void exhaustedScopeReturnsCapacityError() {
        try (ConfigurableApplicationContext context = context()) {
            ScopedBusinessCodeService service = context.getBean(ScopedBusinessCodeService.class);
            service.allocateRoot(ScopedBusinessCodeService.Kind.SPL, null);
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            Long scopeId = jdbc.queryForObject("SELECT id FROM sc_business_code_scope "
                    + "WHERE code_kind='SPL' AND owner_id=0", Long.class);
            jdbc.batchUpdate("INSERT INTO sc_business_code_claim (scope_id, serial_no, created_at) "
                    + "VALUES (?, ?, NOW(3))", IntStream.rangeClosed(2, 999).boxed().toList(), 200,
                    (statement, number) -> {
                        statement.setLong(1, scopeId);
                        statement.setInt(2, number);
                    });
            assertThatThrownBy(() -> service.allocateRoot(ScopedBusinessCodeService.Kind.SPL, null))
                    .isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class)
                    .hasMessageContaining("容量已满");
        }
    }

    @Test
    void legacyCategoryOverflowStopsBackfillWithAffectedTableAndCount() {
        try (ConfigurableApplicationContext context = context()) {
            JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            try {
                jdbc.batchUpdate("INSERT INTO sc_product_category (category_code, category_name, "
                        + "category_level, status, created_at, updated_at) VALUES (?, ?, 1, 0, NOW(3), NOW(3))",
                        IntStream.rangeClosed(1, 1000).boxed().toList(), 200,
                        (statement, number) -> {
                            statement.setString(1, "LEGACY_CAT_" + number);
                            statement.setString(2, "历史分类" + number);
                        });
                assertThatThrownBy(() -> context.getBean(BusinessCodeLegacyBackfill.class).backfillNow())
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("sc_product_category")
                        .hasMessageContaining("总数=1000");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_product_category "
                        + "WHERE category_node_no IS NOT NULL", Long.class)).isZero();
            } finally {
                jdbc.update("DELETE FROM sc_product_category WHERE category_code LIKE 'LEGACY_CAT_%'");
            }
        }
    }

    private static ConfigurableApplicationContext context() {
        ConfigurableApplicationContext context = contextWithoutCleanup();
        JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        jdbc.update("DELETE FROM sc_platform_spu");
        jdbc.update("DELETE FROM sc_product_category");
        jdbc.update("DELETE FROM sc_brand");
        jdbc.update("DELETE FROM sc_buyer");
        jdbc.update("DELETE FROM sc_business_code_claim");
        jdbc.update("DELETE FROM sc_business_code_scope");
        return context;
    }

    private static ConfigurableApplicationContext contextWithoutCleanup() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TestConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of(
                        "aifuli.supply-chain.enabled", true,
                        "aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl(),
                        "aifuli.supply-chain.datasource.username", MYSQL.getUsername(),
                        "aifuli.supply-chain.datasource.password", MYSQL.getPassword(),
                        "aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver"))
                .run();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, BusinessCodeLegacyBackfill.class})
    static class TestConfiguration {
    }
}
