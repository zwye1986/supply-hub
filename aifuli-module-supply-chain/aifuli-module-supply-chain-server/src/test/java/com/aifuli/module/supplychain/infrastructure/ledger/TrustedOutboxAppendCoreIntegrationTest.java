package com.aifuli.module.supplychain.infrastructure.ledger;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.dataobject.OutboxAppendIdentityDO;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.OutboxAppendIdentityMapper;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventDO;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventMapper;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R3-02O（rev3）：可信追加底座的真实 MySQL 故障矩阵（O-S01/O-S03/O-S04/O-S05/O-S06/O-S09 深取证）。
 * 全部经同模块 privateLookupIn 反射桥驱动 main 私有嵌套底座；故障注入仅在隔离真实 SQL 参数
 * 与真实 Mapper 边界进行，不替换生产事务或源读回。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrustedOutboxAppendCoreIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("b0_trusted_core_faults");
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static DataSource dataSource;
    private static OutboxEventMapper sourceMapper;
    private static OutboxAppendIdentityMapper ledgerMapper;
    private static BusinessNoGenerator businessNoGenerator;
    private static OutboxEventRepository repository;
    private static Object core;
    private static TransactionTemplate tx;

    @BeforeAll
    static void start() {
        MYSQL.start();
        context = new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.NONE)
                .run(baseProperties());
        dataSource = context.getBean("supplyChainDataSource", DataSource.class);
        jdbc = new JdbcTemplate(dataSource);
        sourceMapper = context.getBean(OutboxEventMapper.class);
        ledgerMapper = context.getBean(OutboxAppendIdentityMapper.class);
        businessNoGenerator = context.getBean(BusinessNoGenerator.class);
        repository = context.getBean(OutboxEventRepository.class);
        core = TrustedOutboxAppendCoreBridge.newCore(sourceMapper, businessNoGenerator, ledgerMapper, dataSource);
        tx = new TransactionTemplate(context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
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
    @Order(1)
    void bcsBusinessEntryFailsClosedBeforeAnyDal() {
        assertThatThrownBy(() -> repository.appendLocalIdempotent(
                "BUYER_SELECTION_CATALOG_REFRESH", "id", 1L, "BUYER_SELECTION_CATALOG_REFRESH_REQUESTED",
                "buyer-selection-catalog-refresh", 1, "BCS_JSON_V1", "{}", "trace", LocalDateTime.now()))
                .isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("BCS业务Schema与投影注册尚未冻结");
        // LEGACY 也不能经 B 入口进入
        assertThatThrownBy(() -> repository.appendLocalIdempotent(
                "R3_02O_TECH_FIXTURE", "id", 1L, "EVENT", "t", 1, "LEGACY_JSON_V1", "{}", "trace", LocalDateTime.now()))
                .isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("BCS业务Schema与投影注册尚未冻结");
        // 账本与源零交互
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_append_identity", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event", Integer.class)).isZero();
    }

    @Test
    @Order(2)
    void beforeInsertTriggerFaultsCannotBindAndRollBackCompletely() throws Exception {
        String[][] faults = {
                {"fault_target_name", "SET NEW.target_name = 'WRONG'", "targetName与本次命令不一致"},
                {"fault_target_type", "SET NEW.target_type = 'HTTP'", "targetType非LOCAL"},
                {"fault_payload_version", "SET NEW.payload_version = 2", "payloadVersion与本次命令不一致"},
                {"fault_event_id", "SET NEW.event_id = 'EVT_TAMPERED'", "源事件编号与本次分配不一致"},
                {"fault_aggregate_id", "SET NEW.aggregate_id = 'OTHER'", "四列聚合身份与本次命令不一致"},
                {"fault_status", "SET NEW.status = 10", "初始状态非PENDING"},
                {"fault_attempt", "SET NEW.attempt_count = 1", "初始attemptCount非0"},
                {"fault_token", "SET NEW.fencing_token = 1", "初始fencingToken非0"},
                {"fault_available_at", "SET NEW.available_at = '2000-01-01 00:00:00.000'", "availableAt非法或与计划不一致"},
        };
        try (Connection admin = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            for (String[] fault : faults) {
                String key = "FAULT_" + fault[0];
                admin.createStatement().execute("DROP TRIGGER IF EXISTS " + fault[0]);
                admin.createStatement().execute(
                        "CREATE TRIGGER " + fault[0] + " BEFORE INSERT ON sc_outbox_event FOR EACH ROW " + fault[1]);
                try {
                    assertThatThrownBy(() -> tx.execute(status -> TrustedOutboxAppendCoreBridge.append(core,
                            command("ORDER", key, 1L, "ORDER_PAID", "handler", "{\"orderId\":\"fault\"}"))))
                            .isInstanceOf(SystemIntegrityException.class)
                            .hasMessageContaining(fault[2]);
                    // 独立连接读回：源与账本全无残留
                    assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM sc_outbox_append_identity WHERE aggregate_id = ?", Integer.class, key))
                            .as(fault[0] + " 后账本必须无残留").isZero();
                    assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_id = ?", Integer.class, key))
                            .as(fault[0] + " 后源必须无残留").isZero();
                } finally {
                    admin.createStatement().execute("DROP TRIGGER IF EXISTS " + fault[0]);
                }
            }
        }
    }

    @Test
    @Order(3)
    void missingSourceRowFaultCannotBindAndRollsBack() {
        // MySQL 1442 禁止触发器修改自身表删除行；缺行故障在真实源读回 Mapper 边界注入（返回 null），其余全部委派真实 SQL。
        OutboxEventMapper missingReadMapper = (OutboxEventMapper) java.lang.reflect.Proxy.newProxyInstance(
                OutboxEventMapper.class.getClassLoader(),
                new Class<?>[]{OutboxEventMapper.class},
                (proxy, method, args) ->
                        "selectByIdForUpdate".equals(method.getName())
                                ? null
                                : method.invoke(sourceMapper, args == null ? null : args));
        Object faultCore = TrustedOutboxAppendCoreBridge.newCore(missingReadMapper, businessNoGenerator, ledgerMapper, dataSource);
        assertThatThrownBy(() -> tx.execute(status -> TrustedOutboxAppendCoreBridge.append(faultCore,
                command("ORDER", "MISSING", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"missing\"}"))))
                .isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("源Outbox事件行缺失");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_append_identity WHERE aggregate_id = 'MISSING'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_id = 'MISSING'", Integer.class)).isZero();
    }

    @Test
    @Order(4)
    void eventIdUniqueConflict1062DoesNotRetryAndRollsBack() {
        // 固定 eventId 生成器：预置同 event_id 行制造真实 uk_sc_outbox_event_id 冲突
        BusinessNoGenerator fixedGenerator = new BusinessNoGenerator(new UlidGenerator()) {
            @Override
            public String next(String prefix) {
                return "EVT_FIXED000000000000000000001";
            }
        };
        Object fixedCore = TrustedOutboxAppendCoreBridge.newCore(sourceMapper, fixedGenerator, ledgerMapper, dataSource);
        jdbc.update("INSERT INTO sc_outbox_event (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, " +
                "target_type, target_name, payload_version, payload_json, available_at, status, max_attempts, trace_id, created_at, updated_at) " +
                "VALUES ('EVT_FIXED000000000000000000001', 'ORDER', 'FIXED_OTHER', 1, 'ORDER_OTHER', 'LOCAL', 'other', 1, '{}', CURRENT_TIMESTAMP(3), 0, 3, 'trace-x', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))");

        assertThatThrownBy(() -> tx.execute(status -> TrustedOutboxAppendCoreBridge.append(fixedCore,
                command("ORDER", "FIXED_KEY", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"fixed\"}"))))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_outbox_append_identity WHERE aggregate_id = 'FIXED_KEY'", Integer.class)).isZero();
    }

    @Test
    @Order(5)
    void duplicateBoundRowKeepsEveryFieldUnchangedAndClientFoundRowsBothSettings() {
        // 首次
        LocalDateTime now = LocalDateTime.now();
        String first = tx.execute(status -> TrustedOutboxAppendCoreBridge.append(core,
                command("ORDER", "BOUND_KEY", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"bound\"}")));
        assertThat(first).startsWith("EVT_");
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT * FROM sc_outbox_append_identity WHERE aggregate_id = 'BOUND_KEY'");
        // 重复：BOUND 每个字段不变（含 nonce/bound_at/created_at/协议/载荷）
        String duplicate = tx.execute(status -> TrustedOutboxAppendCoreBridge.append(core,
                command("ORDER", "BOUND_KEY", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"bound\"}")));
        assertThat(duplicate).isEqualTo(first);
        Map<String, Object> after = jdbc.queryForMap(
                "SELECT * FROM sc_outbox_append_identity WHERE aggregate_id = 'BOUND_KEY'");
        byte[] beforeNonce = (byte[]) before.remove("reservation_nonce");
        byte[] afterNonce = (byte[]) after.remove("reservation_nonce");
        assertThat(after).as("重复路径不得改变BOUND行任何字段").isEqualTo(before);
        assertThat(afterNonce).as("重复路径不得改变nonce").isEqualTo(beforeNonce);

        // CLIENT_FOUND_ROWS 两设置：同一流程在 useAffectedRows=true 的独立上下文行为一致
        try (ConfigurableApplicationContext foundRowsContext = new SpringApplicationBuilder(Fixture.class)
                .web(WebApplicationType.NONE).run(withFoundRows())) {
            DataSource foundRowsDataSource = foundRowsContext.getBean("supplyChainDataSource", DataSource.class);
            JdbcTemplate foundRowsJdbc = new JdbcTemplate(foundRowsDataSource);
            Object foundRowsCore = TrustedOutboxAppendCoreBridge.newCore(
                    foundRowsContext.getBean(OutboxEventMapper.class),
                    foundRowsContext.getBean(BusinessNoGenerator.class),
                    foundRowsContext.getBean(OutboxAppendIdentityMapper.class),
                    foundRowsDataSource);
            TransactionTemplate foundRowsTx = new TransactionTemplate(
                    foundRowsContext.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
            String foundFirst = foundRowsTx.execute(status -> TrustedOutboxAppendCoreBridge.append(foundRowsCore,
                    command("ORDER", "FOUND_ROWS_KEY", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"fr\"}")));
            assertThat(foundFirst).startsWith("EVT_");
            String foundDuplicate = foundRowsTx.execute(status -> TrustedOutboxAppendCoreBridge.append(foundRowsCore,
                    command("ORDER", "FOUND_ROWS_KEY", 1L, "ORDER_PAID", "handler", "{\"orderId\":\"fr\"}")));
            assertThat(foundDuplicate).as("useAffectedRows=true 下重复仍按当前锁读判定，不靠影响行数").isEqualTo(foundFirst);
            assertThat(foundRowsJdbc.queryForObject(
                    "SELECT COUNT(*) FROM sc_outbox_event WHERE aggregate_id = 'FOUND_ROWS_KEY'", Integer.class))
                    .as("两设置下源都恰1行").isEqualTo(1);
        }
    }

    private static String[] withFoundRows() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl() + "?useAffectedRows=true");
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
    @Order(6)
    void oldBoundPayloadBudgetAndMappingIntegrityFailures() {
        LocalDateTime now = LocalDateTime.now();
        // 预置 BOUND 行：超预算旧载荷（65 层嵌套触发 DEPTH 预算）+ targetName 不匹配
        // 注：MySQL JSON 列无法存储会溢出 DOUBLE 的巨数（如 1e4096），故超预算正例改用可入库的深嵌套载荷。
        String overBudgetPayload = "[".repeat(65) + "0" + "]".repeat(65);
        jdbc.update("INSERT INTO sc_outbox_append_identity (aggregate_type, aggregate_id, aggregate_version, event_type, " +
                        "binding_status, reservation_nonce, target_type, target_name, payload_version, comparison_protocol, " +
                        "payload_json, event_id, created_at, bound_at) VALUES ('ORDER','BUDGET_KEY',1,'ORDER_PAID',20," +
                        "UNHEX(REPEAT('0A',16)),'LOCAL','other',1,'LEGACY_JSON_V1',?,'EVT_OLD',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",
                overBudgetPayload);
        // 旧载荷预算先于 target/version 差异：cause 必须是预算原因
        assertThatThrownBy(() -> tx.execute(status -> TrustedOutboxAppendCoreBridge.append(core,
                command("ORDER", "BUDGET_KEY", 1L, "ORDER_PAID", "t", "0"))))
                .isInstanceOf(SystemIntegrityException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("JSON预算超限: DEPTH");

        // 非法 eventId（空串，非空但非法）
        jdbc.update("UPDATE sc_outbox_append_identity SET payload_json = '{}', event_id = '' WHERE aggregate_id = 'BUDGET_KEY'");
        assertThatThrownBy(() -> tx.execute(status -> TrustedOutboxAppendCoreBridge.append(core,
                command("ORDER", "BUDGET_KEY", 1L, "ORDER_PAID", "other", "{}"))))
                .isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("BOUND映射非法eventId");

        // 异协议（真实SQL篡改）
        jdbc.update("UPDATE sc_outbox_append_identity SET event_id = 'EVT_OLD', comparison_protocol = 'OTHER' WHERE aggregate_id = 'BUDGET_KEY'");
        assertThatThrownBy(() -> tx.execute(status -> TrustedOutboxAppendCoreBridge.append(core,
                command("ORDER", "BUDGET_KEY", 1L, "ORDER_PAID", "other", "{}"))))
                .isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("载荷协议或内容冲突");

        // 异 payloadVersion（真实SQL篡改）
        jdbc.update("UPDATE sc_outbox_append_identity SET comparison_protocol = 'LEGACY_JSON_V1', payload_version = 2 WHERE aggregate_id = 'BUDGET_KEY'");
        assertThatThrownBy(() -> tx.execute(status -> TrustedOutboxAppendCoreBridge.append(core,
                command("ORDER", "BUDGET_KEY", 1L, "ORDER_PAID", "other", "{}"))))
                .isInstanceOf(SystemIntegrityException.class)
                .hasMessageContaining("载荷协议或内容冲突");

        // 命令构造先于DAL：坏JSON直接拒绝
        assertThatThrownBy(() -> TrustedOutboxAppendCoreBridge.newCommand(
                "ORDER", "BAD_JSON_KEY", 1L, "ORDER_PAID", "LOCAL", "t", 1, "LEGACY_JSON_V1", "{",
                "trace", null, null, now))
                .isInstanceOf(SystemIntegrityException.class)
                .hasCauseInstanceOf(Throwable.class);
    }

    private static Object command(String aggregateType, String aggregateId, long aggregateVersion,
                                  String eventType, String targetName, String payloadJson) {
        return TrustedOutboxAppendCoreBridge.newCommand(aggregateType, aggregateId, aggregateVersion,
                eventType, "LOCAL", targetName, 1, "LEGACY_JSON_V1", payloadJson,
                "trace-" + aggregateId, null, null, LocalDateTime.now());
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, BusinessNoGenerator.class, UlidGenerator.class,
            OutboxEventRepository.class})
    static class Fixture {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
