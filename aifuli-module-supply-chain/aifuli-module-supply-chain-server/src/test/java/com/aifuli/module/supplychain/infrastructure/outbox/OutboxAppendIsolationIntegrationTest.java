package com.aifuli.module.supplychain.infrastructure.outbox;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.ledger.SystemIntegrityException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.aop.support.AopUtils;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R3-02O（rev3）O-S08/O-S08a/O-S08b/O-S08c：旧 appendLocal 的 B 保留命名空间隔离门禁。
 * 真实 MySQL：装配 AopUtils.isAopProxy、粗过滤零DB、候选参数化无表 SELECT、零锁副作用
 * （data_locks probe前后增量对比）、语句计数、快照时序负例、collation 等价变体逐项固定。
 */
class OutboxAppendIsolationIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("b0_append_isolation");
    private static ConfigurableApplicationContext advised;
    private static ConfigurableApplicationContext plain;
    private static OutboxEventRepository advisedRepository;
    private static OutboxEventRepository plainRepository;
    private static JdbcTemplate jdbc;
    private static DataSource dataSource;
    private static TransactionTemplate tx;

    @BeforeAll
    static void start() {
        MYSQL.start();
        try {
            MYSQL.execInContainer("mysql", "-uroot", "-p" + MYSQL.getPassword(), "-e",
                    "GRANT PROCESS ON *.* TO '" + MYSQL.getUsername() + "'@'%'; "
                            + "GRANT SELECT ON performance_schema.* TO '" + MYSQL.getUsername() + "'@'%'; FLUSH PRIVILEGES;");
            var historySetup = MYSQL.execInContainer("mysql", "-uroot", "-p" + MYSQL.getPassword(), "-e",
                    "UPDATE performance_schema.setup_consumers SET ENABLED='YES' "
                            + "WHERE NAME IN ('events_statements_current','events_statements_history_long')");
            assertThat(historySetup.getExitCode()).as("本地MySQL语句历史必须启用").isZero();
        } catch (Exception failure) {
            throw new IllegalStateException("本地MySQL诊断权限与语句历史装配失败", failure);
        }
        advised = new SpringApplicationBuilder(AdvisedFixture.class).web(WebApplicationType.NONE).run(baseProperties());
        plain = new SpringApplicationBuilder(PlainFixture.class).web(WebApplicationType.NONE).run(baseProperties());
        advisedRepository = advised.getBean(OutboxEventRepository.class);
        plainRepository = plain.getBean(OutboxEventRepository.class);
        dataSource = advised.getBean("supplyChainDataSource", DataSource.class);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(advised.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
    }

    @AfterAll
    static void stop() {
        if (advised != null) {
            advised.close();
        }
        if (plain != null) {
            plain.close();
        }
        MYSQL.stop();
    }

    private static String[] baseProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", MYSQL.getUsername());
        properties.put("aifuli.supply-chain.datasource.password", MYSQL.getPassword());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.datasource.connect-timeout-millis", 1500);
        properties.put("aifuli.supply-chain.timer.enabled", false);
        properties.put("aifuli.supply-chain.outbox.enabled", false);
        return properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
    }

    @Test
    void coarseFilterZeroDbReleaseSetIsExactlyTheProvenSafeSet() {
        // (a) null
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved(null)).isTrue();
        // (b) 码点<31
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("BUYER_LISTING")).isTrue();
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("ROUTING_POLICY")).isTrue();
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("A".repeat(30))).isTrue();
        // (c) 存在 ASCII 码点 ∉ [A-Za-z_]
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("BUYER_SELECTION_CATALOG_REFRESH!")).isTrue();
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("BUYER_SELECTION_CATALOG_REFRESH9")).isTrue();
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("BUYER-SELECTION-CATALOG-REFRESH")).isTrue();
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("BUYER SELECTION CATALOG REFRESH")).isTrue();
        // 未命中快路径：一律候选 SELECT（不得零DB放行）
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("BUYER_SELECTION_CATALOG_REFRESH")).isFalse();
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("buyer_selection_catalog_refresh")).isFalse();
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("Buyer_Selection_Catalog_Refresh")).isFalse();
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("BUY\u00c9R_SELECTION_CATALOG_REFRESH")).isFalse();
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("BUYER\u0301_SELECTION_CATALOG_REFRESH")).isFalse();
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("BUYER_SELECTION_CATALOG_REFRESH\u034F")).isFalse();
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("BUYER_SELECTION_CATALOG_REFRESH_X")).isFalse();
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("A".repeat(31))).isFalse();
        assertThat(OutboxAppendIsolationConfiguration.provablyNotReserved("A".repeat(64))).isFalse();
    }

    @Test
    void productionAssemblyProxiesOutboxEventRepository() {
        assertThat(AopUtils.isAopProxy(advisedRepository))
                .as("main装配必须使 outboxEventRepository 成为AOP代理").isTrue();
        assertThat(AopUtils.isAopProxy(plainRepository))
                .as("未装配advisor的对照上下文必须不是代理").isFalse();
    }

    @Test
    void missingAutoProxyCreatorFailsStartup() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(NegativeFixture.class)
                .web(WebApplicationType.NONE).run(baseProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未装配 appendLocal 保留命名空间隔离 advisor");
    }

    @Test
    void advisorDoesNotMatchSameNamedMethodOnOtherBean() {
        StatementWindow window = advisedTxWindow(() -> org.assertj.core.api.Assertions.assertThatCode(() ->
                assertThat(advised.getBean(OtherAppender.class).appendLocal("BUYER_SELECTION_CATALOG_REFRESH"))
                        .isEqualTo("未拦截"))
                .as("单bean门禁不得拒绝同名非目标bean").doesNotThrowAnyException());
        assertThat(window.digests()).as("非目标bean的同名方法不得被隔离advisor代理").isEmpty();
    }

    @Test
    void coarseFilterAddsZeroStatementsForNonReservedNames() {
        // 按受测物理连接的真实SQL窗口对照；驱动INSERT前read-only检查不冒充advisor probe。
        LocalDateTime now = LocalDateTime.now();
        StatementWindow plainWindow = plainTxWindow(() -> plainRepository.appendLocal(
                "BUYER_LISTING", "AGG_ISO_PLAIN", 1L, "LISTING_EVENT", "t", Map.of("k", 1), "trace", null, null, now));
        StatementWindow advisedWindow = advisedTxWindow(() -> advisedRepository.appendLocal(
                "BUYER_LISTING", "AGG_ISO_ADVISED", 1L, "LISTING_EVENT", "t", Map.of("k", 1), "trace", null, null, now));
        assertThat(advisedWindow.digests()).as("粗过滤与无advisor基线的真实SQL形状必须完全一致")
                .isEqualTo(plainWindow.digests());
        advisedWindow.requireProbeCount(0);
        assertThat(advisedWindow.sourceInserts()).isEqualTo(1);

        // null 聚合名：粗过滤放行后由源NOT NULL约束拒绝（旧语义不变，非SystemIntegrityException）
        assertThatThrownBy(() -> advisedRepository.appendLocal(
                null, "AGG_ISO_NULL", 1L, "LISTING_EVENT", "t", Map.of("k", 1), "trace", null, null, now))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .isNotInstanceOf(SystemIntegrityException.class);
    }

    @Test
    void reservedNamesAndCollationVariantsAreRejectedBeforeSourceWithZeroInsert() {
        // B保留名及collation等价变体（真实MySQL裁决）：probe恰好1条只读无表SELECT、源零INSERT、SystemIntegrityException
        List<String> reservedVariants = List.of(
                "BUYER_SELECTION_CATALOG_REFRESH",
                "buyer_selection_catalog_refresh",
                "Buyer_Selection_Catalog_Refresh",
                "BUY\u00c9R_SELECTION_CATALOG_REFRESH",
                "BUYER\u0301_SELECTION_CATALOG_REFRESH",
                "BUYER_SELECTION_CATALOG_REFRESH\u034F",
                "BUYER\u0301_SELECTION_CATALOG_REFRESH\u034F");
        for (String variant : reservedVariants) {
            StatementWindow window = advisedTxWindow(() -> assertThatThrownBy(() -> advisedRepository.appendLocal(
                    variant, "AGG_ISO", 1L, "LISTING_EVENT", "t", Map.of("k", 1), "trace", null, null, LocalDateTime.now()))
                    .isInstanceOf(SystemIntegrityException.class)
                    .hasMessageContaining("禁止经旧appendLocal写入"));
            window.requireProbeCount(1);
            assertThat(window.sourceInserts()).as(variant + " 源零INSERT").isZero();
            assertThat(window.driverReadOnlySelects()).as("拒绝路径不进入驱动INSERT检查").isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_type = ?", Integer.class, variant))
                    .as(variant + " 源表零行").isZero();
        }

        // 非等价候选：probe返回0后完全透明放行（INSERT成功、源恰1行）
        List<String> nonEquivalent = List.of(
                "BUYER_SELECTION_CATALOG_REFRESH_X",
                "BUYER_SELECTION_CATALOG_REFRESH\u034FX",
                "BUYER_SELECTION_CATALOG_REFRESHXX");
        for (String candidate : nonEquivalent) {
            StatementWindow window = advisedTxWindow(() -> {
                String eventId = advisedRepository.appendLocal(
                        candidate, "AGG_ISO", 1L, "LISTING_EVENT", "t", Map.of("k", 1), "trace", null, null, LocalDateTime.now());
                assertThat(eventId).startsWith("EVT_");
            });
            window.requireProbeCount(1);
            assertThat(window.sourceInserts()).as(candidate + " 透明放行后严格INSERT1").isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_type = ?", Integer.class, candidate))
                    .as(candidate + " 源表恰1行").isEqualTo(1);
        }

        // 粗过滤放行（含ASCII非字母码点）：零probe、严格INSERT1
        for (String candidate : List.of("BUYER_SELECTION_CATALOG_REFRESH!", "BUYER_SELECTION_CATALOG_REFRESH9")) {
            StatementWindow window = advisedTxWindow(() -> {
                String eventId = advisedRepository.appendLocal(
                        candidate, "AGG_ISO", 1L, "LISTING_EVENT", "t", Map.of("k", 1), "trace", null, null, LocalDateTime.now());
                assertThat(eventId).startsWith("EVT_");
            });
            window.requireProbeCount(0);
            assertThat(window.sourceInserts()).as(candidate + " 严格INSERT1").isEqualTo(1);
        }
    }

    @Test
    void probeAddsNoLocksByDataLocksIncrementComparison() {
        // 预置一行账本行用于取得真实锁
        jdbc.update("INSERT INTO sc_outbox_append_identity (aggregate_type, aggregate_id, aggregate_version, event_type, " +
                "binding_status, reservation_nonce, target_type, target_name, payload_version, comparison_protocol, " +
                "payload_json, created_at) VALUES ('LOCKREF','LOCKREF',1,'EVT',10,UNHEX(REPEAT('0A',16)),'LOCAL','t',1," +
                "'LEGACY_JSON_V1','{}',CURRENT_TIMESTAMP(3))");
        tx.execute(status -> {
            long before = lockCount();
            jdbc.queryForMap("SELECT id FROM sc_outbox_append_identity WHERE aggregate_id='LOCKREF' FOR UPDATE");
            long afterRealLock = lockCount();
            assertThat(afterRealLock).as("真实行锁必须在 data_locks 可见").isGreaterThan(before);
            // 经 advisor 候选路径执行 probe（保留名 -> probe 后拒绝）
            assertThatThrownBy(() -> advisedRepository.appendLocal(
                    "BUYER_SELECTION_CATALOG_REFRESH", "AGG_ISO", 1L, "LISTING_EVENT", "t",
                    Map.of("k", 1), "trace", null, null, LocalDateTime.now()))
                    .isInstanceOf(SystemIntegrityException.class);
            long afterProbe = lockCount();
            assertThat(afterProbe).as("probe本身新增锁必须为0（事务既有锁不计入增量）").isEqualTo(afterRealLock);
            status.setRollbackOnly();
            return null;
        });
    }

    @Test
    void probeSelectDoesNotAnchorReadViewButTableReadDoes() throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS snapshot_probe (id BIGINT PRIMARY KEY)");

        // 对照1：新RR事务先执行probe，另一连接提交新行，随后普通SELECT必须可见（读视图不锚定于probe）
        CountDownLatch probeDone = new CountDownLatch(1);
        CountDownLatch rowCommitted = new CountDownLatch(1);
        AtomicInteger seenAfterProbe = new AtomicInteger(-1);
        Thread probeThread = new Thread(() -> {
            tx.execute(status -> {
                OutboxAppendIsolationConfiguration.queryReservedMatch(dataSource, "BUYER_SELECTION_CATALOG_REFRESH");
                probeDone.countDown();
                try {
                    rowCommitted.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                seenAfterProbe.set(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM snapshot_probe WHERE id = 999", Integer.class));
                status.setRollbackOnly();
                return null;
            });
        });
        probeThread.start();
        probeDone.await();
        jdbc.update("INSERT INTO snapshot_probe VALUES (999)");
        rowCommitted.countDown();
        probeThread.join();
        assertThat(seenAfterProbe.get()).as("无表SELECT不锚定读视图：后提交行必须可见").isEqualTo(1);

        // 对照2：probe换成真实表普通SELECT后同一编排不可见（读视图被锚定）
        CountDownLatch tableReadDone = new CountDownLatch(1);
        CountDownLatch secondRowCommitted = new CountDownLatch(1);
        AtomicInteger seenAfterTableRead = new AtomicInteger(-1);
        Thread tableThread = new Thread(() -> {
            tx.execute(status -> {
                jdbc.queryForObject("SELECT COUNT(*) FROM snapshot_probe WHERE id = 998", Integer.class);
                tableReadDone.countDown();
                try {
                    secondRowCommitted.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                seenAfterTableRead.set(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM snapshot_probe WHERE id = 998", Integer.class));
                status.setRollbackOnly();
                return null;
            });
        });
        tableThread.start();
        tableReadDone.await();
        jdbc.update("INSERT INTO snapshot_probe VALUES (998)");
        secondRowCommitted.countDown();
        tableThread.join();
        assertThat(seenAfterTableRead.get()).as("真实表普通读锚定读视图：后提交行不可见").isZero();
    }

    /**
     * O-S08b计量夹具：观察连接与事务连接分离，按THREAD_ID与事件边界采集真实服务器语句。
     * 设计：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#721。
     * 不相减空事务基线、不加容差；唯一非probe SELECT白名单是诊断已实证的驱动read-only检查。
     * SQL只保留DIGEST_TEXT（常量参数化），不输出载荷/凭据。
     */
    private static StatementWindow plainTxWindow(Runnable action) {
        TransactionTemplate plainTx = new TransactionTemplate(
                plain.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
        JdbcTemplate plainJdbc = new JdbcTemplate(plain.getBean("supplyChainDataSource", DataSource.class));
        return plainTx.execute(status -> observeStatements(plainJdbc, action));
    }

    private static StatementWindow advisedTxWindow(Runnable action) {
        return tx.execute(status -> observeStatements(jdbc, action));
    }

    private static StatementWindow observeStatements(JdbcTemplate targetJdbc, Runnable action) {
        long targetThread = targetJdbc.queryForObject("SELECT PS_CURRENT_THREAD_ID()", Long.class);
        try (var observer = MYSQL.createConnection("")) {
            try (var statement = observer.createStatement();
                 var result = statement.executeQuery("SELECT PS_CURRENT_THREAD_ID()")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).as("观察连接不得是受测事务连接").isNotEqualTo(targetThread);
            }
            long boundary;
            try (var statement = observer.prepareStatement(
                    "SELECT MAX(EVENT_ID) FROM performance_schema.events_statements_history_long WHERE THREAD_ID = ?")) {
                statement.setLong(1, targetThread);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    boundary = result.getLong(1);
                    assertThat(boundary).as("窗口起点语句必须实际记录，历史缺失不可假绿").isPositive();
                }
            }
            action.run();
            var digests = new java.util.ArrayList<String>();
            try (var statement = observer.prepareStatement(
                    "SELECT DIGEST_TEXT FROM performance_schema.events_statements_history_long "
                            + "WHERE THREAD_ID = ? AND EVENT_ID > ? AND EVENT_NAME LIKE 'statement/sql/%' ORDER BY EVENT_ID")) {
                statement.setLong(1, targetThread);
                statement.setLong(2, boundary);
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        String digest = result.getString(1);
                        assertThat(digest).as("每条受测语句须可分类，不允许忽略NULL digest").isNotNull();
                        digests.add(digest);
                    }
                }
            }
            return new StatementWindow(List.copyOf(digests));
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("读取本地MySQL语句窗口失败", failure);
        }
    }

    private record StatementWindow(List<String> digests) {
        private static final String PROBE_DIGEST =
                "SELECT CAST ( ? AS CHARACTER CHARACTER SET `utf8mb4` ) COLLATE `utf8mb4_0900_ai_ci` "
                        + "= CAST ( ? AS CHARACTER CHARACTER SET `utf8mb4` ) COLLATE `utf8mb4_0900_ai_ci` AS `reserved_match`";
        private static final String DRIVER_READ_ONLY_DIGEST = "SELECT @@SESSION . `transaction_read_only`";

        long sourceInserts() {
            return digests.stream().filter(sql -> sql.startsWith("INSERT INTO `sc_outbox_event` (")).count();
        }

        long driverReadOnlySelects() {
            return digests.stream().filter(DRIVER_READ_ONLY_DIGEST::equals).count();
        }

        void requireProbeCount(long expected) {
            assertThat(digests.stream().filter(PROBE_DIGEST::equals).count())
                    .as("同事务连接实际probe数；窗口=" + digests).isEqualTo(expected);
            assertThat(digests.stream().filter(sql -> sql.startsWith("SELECT "))
                    .filter(sql -> !PROBE_DIGEST.equals(sql) && !DRIVER_READ_ONLY_DIGEST.equals(sql)).toList())
                    .as("不能用驱动白名单掩盖其它SELECT").isEmpty();
        }
    }

    @Test
    void sqlWindowRejectsDuplicateProbeAndUnrelatedSelect() {
        StatementWindow duplicate = advisedTxWindow(() -> {
            OutboxAppendIsolationConfiguration.queryReservedMatch(dataSource, "BUYER_SELECTION_CATALOG_REFRESH");
            OutboxAppendIsolationConfiguration.queryReservedMatch(dataSource, "BUYER_SELECTION_CATALOG_REFRESH");
        });
        assertThatThrownBy(() -> duplicate.requireProbeCount(1)).isInstanceOf(AssertionError.class);
        duplicate.requireProbeCount(2);
        assertThat(duplicate.sourceInserts()).isZero();
        StatementWindow unrelated = advisedTxWindow(() -> jdbc.queryForObject("SELECT 42", Integer.class));
        assertThatThrownBy(() -> unrelated.requireProbeCount(0)).isInstanceOf(AssertionError.class);
    }

    private static long lockCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM performance_schema.data_locks l " +
                        "JOIN performance_schema.threads t ON t.THREAD_ID = l.THREAD_ID " +
                        "WHERE t.PROCESSLIST_ID = CONNECTION_ID()", Long.class);
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan(
            basePackageClasses = OutboxEventRepository.class, useDefaultFilters = false,
            includeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                    type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = OutboxEventRepository.class))
    @Import({SupplyChainDataSourceConfiguration.class,
            BusinessNoGenerator.class, UlidGenerator.class, OutboxAppendIsolationConfiguration.class})
    static class AdvisedFixture {

        @Bean
        OtherAppender otherAppender() {
            return new OtherAppender();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    /** 反例bean：方法同名但不是获审目标，禁止被advisor误匹配。 */
    static class OtherAppender {
        public String appendLocal(String aggregateType) {
            return "未拦截";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan(
            basePackageClasses = OutboxEventRepository.class, useDefaultFilters = false,
            includeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                    type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = OutboxEventRepository.class))
    @Import({SupplyChainDataSourceConfiguration.class,
            BusinessNoGenerator.class, UlidGenerator.class})
    static class PlainFixture {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan(
            basePackageClasses = OutboxEventRepository.class, useDefaultFilters = false,
            includeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                    type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = OutboxEventRepository.class))
    @Import({SupplyChainDataSourceConfiguration.class,
            BusinessNoGenerator.class, UlidGenerator.class})
    static class NegativeFixture {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        OutboxAppendIsolationAssemblyCheck assemblyCheck(OutboxEventRepository outboxEventRepository) {
            return new OutboxAppendIsolationAssemblyCheck(outboxEventRepository);
        }
    }
}
