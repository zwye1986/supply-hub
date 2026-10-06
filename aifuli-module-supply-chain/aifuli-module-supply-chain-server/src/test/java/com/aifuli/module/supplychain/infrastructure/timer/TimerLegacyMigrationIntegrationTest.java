package com.aifuli.module.supplychain.infrastructure.timer;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.UlidGenerator;
import com.aifuli.module.supplychain.infrastructure.ledger.DefaultAppendScheduleIdentityLedgerService;
import com.aifuli.module.supplychain.infrastructure.ledger.SystemIntegrityException;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R3-04 强制旧 Timer 迁移与启动切换检查集成测试。
 * 依据设计规范：
 * docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#82-强制旧-timer-迁移与启动切换检查实现r3-04
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TimerLegacyMigrationIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("b0_timer_migration")
            .withCommand("--log-bin-trust-function-creators=1");
    private static ConfigurableApplicationContext context;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static PlatformTransactionManager txManager;
    private static TimerLegacyMigrationService migrationService;

    @BeforeAll
    static void start() {
        MYSQL.start();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", MYSQL.getJdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", MYSQL.getUsername());
        properties.put("aifuli.supply-chain.datasource.password", MYSQL.getPassword());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("aifuli.supply-chain.datasource.connect-timeout-millis", 1500);
        properties.put("aifuli.supply-chain.timer.enabled", false);
        properties.put("aifuli.supply-chain.outbox.enabled", false);

        context = new SpringApplicationBuilder(Fixture.class).web(WebApplicationType.NONE)
                .run(properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                        .toArray(String[]::new));
        dataSource = context.getBean("supplyChainDataSource", DataSource.class);
        jdbc = new JdbcTemplate(dataSource);
        txManager = context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class);
        migrationService = context.getBean(TimerLegacyMigrationService.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        MYSQL.stop();
    }

    @Test @Order(1)
    void backfillLegacyTasksAcrossAllStatusesCreatesBoundSlotsWithoutModifyingSourceRows() {
        // 插入 7 个不同状态的历史旧任务：
        // 0: PENDING, 10: CLAIMED, 20: EXECUTING, 30: RETRY, 40: COMPLETED, 50: CANCELLED, 60: DEAD
        int[] statuses = new int[]{0, 10, 20, 30, 40, 50, 60};
        for (int i = 0; i < statuses.length; i++) {
            int st = statuses[i];
            jdbc.update("INSERT INTO sc_timer_task "
                    + "(task_no, task_type, idempotency_key, payload_version, payload_json, schedule_type, "
                    + "next_fire_at, priority, shard, status, attempt_count, max_attempts, fencing_token, version, created_at, updated_at) "
                    + "VALUES (?, 'MIGRATION_TASK', ?, 1, ?, 'ONCE', NOW(3), 0, 0, ?, 0, 3, 0, 0, NOW(3), NOW(3))",
                    "TMR_LEGACY_" + st, "key-legacy-" + st, "{\"status\":" + st + "}", st);
        }

        // 保存源表各行全字段快照
        List<Map<String, Object>> beforeSnapshots = jdbc.queryForList(
                "SELECT id, task_no, task_type, idempotency_key, payload_version, CAST(payload_json AS CHAR) as payload_str, "
                        + "schedule_type, priority, shard, status, attempt_count, max_attempts, fencing_token, version "
                        + "FROM sc_timer_task WHERE task_type='MIGRATION_TASK' ORDER BY id ASC");
        assertThat(beforeSnapshots).hasSize(7);

        // 执行回填
        int count = migrationService.backfillLegacyTasks();
        assertThat(count).isEqualTo(7);

        // 验证账本记录全部生成且为 BOUND (20)
        List<Map<String, Object>> ledgerRows = jdbc.queryForList(
                "SELECT id, task_type, idempotency_key, binding_status, reservation_nonce, payload_version, "
                        + "comparison_protocol, task_no, task_id, bound_at "
                        + "FROM sc_timer_schedule_identity WHERE task_type='MIGRATION_TASK' ORDER BY task_id ASC");
        assertThat(ledgerRows).hasSize(7);
        for (Map<String, Object> row : ledgerRows) {
            assertThat(row.get("binding_status")).isEqualTo(20);
            assertThat(row.get("comparison_protocol")).isEqualTo("LEGACY_JSON_V1");
            assertThat(row.get("task_no")).isNotNull();
            assertThat(row.get("task_id")).isNotNull();
            assertThat(row.get("bound_at")).isNotNull();
            byte[] nonce = (byte[]) row.get("reservation_nonce");
            assertThat(nonce).hasSize(16);
        }

        // 验证源表全部字段逐字逐值不变
        List<Map<String, Object>> afterSnapshots = jdbc.queryForList(
                "SELECT id, task_no, task_type, idempotency_key, payload_version, CAST(payload_json AS CHAR) as payload_str, "
                        + "schedule_type, priority, shard, status, attempt_count, max_attempts, fencing_token, version "
                        + "FROM sc_timer_task WHERE task_type='MIGRATION_TASK' ORDER BY id ASC");
        assertThat(afterSnapshots).isEqualTo(beforeSnapshots);
    }

    @Test @Order(2)
    void idempotentRerunDoesNotDuplicateOrCorruptSlots() {
        int count = migrationService.backfillLegacyTasks();
        assertThat(count).isZero();

        Integer totalLedger = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sc_timer_schedule_identity WHERE task_type='MIGRATION_TASK'", Integer.class);
        assertThat(totalLedger).isEqualTo(7);
    }

    @Test @Order(3)
    void payloadExceedingResourceBudgetThrowsSystemIntegrityExceptionAndBlocks() {
        // 构建深度超过 64 层的合法 JSON 载荷
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 66; i++) {
            sb.append("{\"a\":");
        }
        sb.append("1");
        for (int i = 0; i < 66; i++) {
            sb.append("}");
        }
        String deepJson = sb.toString();

        jdbc.update("INSERT INTO sc_timer_task "
                + "(task_no, task_type, idempotency_key, payload_version, payload_json, schedule_type, "
                + "next_fire_at, priority, shard, status, attempt_count, max_attempts, fencing_token, version, created_at, updated_at) "
                + "VALUES ('TMR_OVER_BUDGET', 'BUDGET_TASK', 'key-over-budget', 1, ?, 'ONCE', NOW(3), 0, 0, 0, 0, 3, 0, 0, NOW(3), NOW(3))",
                deepJson);

        try {
            assertThatThrownBy(() -> migrationService.backfillLegacyTasks())
                    .isInstanceOf(SystemIntegrityException.class)
                    .hasMessageContaining("预算");
        } finally {
            jdbc.update("DELETE FROM sc_timer_task WHERE task_no='TMR_OVER_BUDGET'");
        }
    }

    @Test @Order(4)
    void switchGuardPassesWhenAllTasksAreBoundAndConsistent() {
        // 当前 7 个任务均已回填，核验应正常通过
        migrationService.verifyMigrationAndSwitchIntegrity();
    }

    @Test @Order(5)
    void switchGuardFailsClosedWhenSourceHasRowWithoutBoundLedger() {
        jdbc.update("INSERT INTO sc_timer_task "
                + "(task_no, task_type, idempotency_key, payload_version, payload_json, schedule_type, "
                + "next_fire_at, priority, shard, status, attempt_count, max_attempts, fencing_token, version, created_at, updated_at) "
                + "VALUES ('TMR_UNBOUND', 'MIGRATION_TASK', 'key-unbound', 1, '{\"v\":1}', 'ONCE', NOW(3), 0, 0, 0, 0, 3, 0, 0, NOW(3), NOW(3))");

        try {
            assertThatThrownBy(() -> migrationService.verifyMigrationAndSwitchIntegrity())
                    .isInstanceOf(SystemIntegrityException.class)
                    .hasMessageContaining("未映射");
        } finally {
            jdbc.update("DELETE FROM sc_timer_task WHERE task_no='TMR_UNBOUND'");
        }
    }

    @Test @Order(6)
    void switchGuardFailsClosedWhenMappingIsTampered() {
        // 故意篡改一个账本槽的 task_no
        jdbc.update("UPDATE sc_timer_schedule_identity SET task_no='TMR_TAMPERED' WHERE task_no='TMR_LEGACY_0'");

        try {
            assertThatThrownBy(() -> migrationService.verifyMigrationAndSwitchIntegrity())
                    .isInstanceOf(SystemIntegrityException.class)
                    .hasMessageContaining("不一致");
        } finally {
            jdbc.update("UPDATE sc_timer_schedule_identity SET task_no='TMR_LEGACY_0' WHERE task_no='TMR_TAMPERED'");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            SupplyChainDataSourceConfiguration.class,
            BusinessNoGenerator.class,
            UlidGenerator.class,
            DefaultAppendScheduleIdentityLedgerService.class,
            OutboxEventRepository.class,
            DatabasePersistentTimer.class,
            PersistentTimerHandlerRegistry.class,
            TimerLegacyMigrationService.class
    })
    static class Fixture {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }
}
