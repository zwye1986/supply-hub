package com.aifuli.module.supplychain.border.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentCommandFacade;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyExecutionPermit;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMigrationService;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMonitorQueryService;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyRecoveryService;
import com.aifuli.module.supplychain.border.fulfillment.application.SupplierOrderLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyMonitorMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import javax.sql.DataSource;

/**
 * 真实MySQL存量迁移门禁；见
 * docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#101-迁移执行与核实边界。
 */
class RechargeFrequencyMigrationIntegrationTest {
    @Test
    void migrationReconstructsMultiplePossibleSendsAsBlockedWithoutArbitraryHolder()
            throws Exception {
        var fixture = new FulfillmentAcceptanceIntegrationTest();
        fixture.startContainer();
        try (var context = fixture.applicationContext(MigrationConfiguration.class)) {
            fixture.seed(context);
            var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            facade.acceptFulfillment(
                    FulfillmentAcceptanceIntegrationTest.request("MIGRATION-ONE", 1L));
            facade.acceptFulfillment(
                    FulfillmentAcceptanceIntegrationTest.request("MIGRATION-TWO", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            jdbc.update("DELETE FROM sc_recharge_frequency_entry");
            jdbc.update("DELETE FROM sc_recharge_frequency_queue");
            jdbc.update(
                    "UPDATE sc_fulfillment_operation SET"
                            + " status=30,result_class='RESULT_UNKNOWN',attempt_count=1");
            long frozen = jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class);
            var migration = context.getBean(RechargeFrequencyMigrationService.class);
            migration.migrate();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sc_recharge_frequency_entry WHERE"
                                            + " status=10 AND dispatch_phase=10",
                                    Long.class))
                    .isEqualTo(2L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT holder_entry_id FROM sc_recharge_frequency_queue",
                                    Long.class))
                    .isNull();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT blocked_reason FROM sc_recharge_frequency_queue",
                                    String.class))
                    .isEqualTo("MIGRATION_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class))
                    .isEqualTo(frozen);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sc_fulfillment_call_log", Long.class))
                    .isZero();
        } finally {
            fixture.stopContainer();
        }
    }

    @Test
    void allCandidatePreflightRejectsMissingCipherAndNeverFallsBackToBuyerUserOrDisplayMobile() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "MISSING-CIPHER-ONE");
                    accept(context, "MISSING-CIPHER-TWO");
                    clearFrequency(jdbc);
                    jdbc.update(
                            "UPDATE sc_fulfillment_request SET target_ciphertext=X'' WHERE"
                                    + " id=(SELECT MAX(x.id) FROM (SELECT id FROM"
                                    + " sc_fulfillment_request) x)");
                    var before = moneyAndEffects(jdbc);
                    assertThatThrownBy(
                                    () ->
                                            context.getBean(RechargeFrequencyMigrationService.class)
                                                    .migrate())
                            .isInstanceOf(
                                    RechargeFrequencyMigrationService.MigrationException.class)
                            .hasMessageContaining("TARGET_UNRECOVERABLE");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM sc_recharge_frequency_entry",
                                            Long.class))
                            .isZero();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM sc_recharge_frequency_queue",
                                            Long.class))
                            .isZero();
                    assertThat(moneyAndEffects(jdbc)).isEqualTo(before);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT state FROM"
                                                    + " sc_recharge_frequency_migration_gate",
                                            String.class))
                            .isEqualTo("CLOSED");
                    assertThatThrownBy(() -> accept(context, "AFTER-FAILED-MIGRATION"))
                            .hasMessageContaining("门禁未就绪");
                });
    }

    @Test
    void oneUnknownUsesMigrationClockWithoutInferringOperationCreatedAtAndReplaysFrozenIdentity() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "UNKNOWN-MIGRATION");
                    clearFrequency(jdbc);
                    jdbc.update(
                            "UPDATE sc_fulfillment_operation SET"
                                + " status=30,result_class='RESULT_UNKNOWN',attempt_count=1,created_at=DATE_SUB(created_at,INTERVAL"
                                + " 1 HOUR)");
                    var migration = context.getBean(RechargeFrequencyMigrationService.class);
                    var before = moneyAndEffects(jdbc);
                    migration.migrate();
                    var frozen = jdbc.queryForMap("SELECT * FROM sc_recharge_frequency_entry");
                    assertThat(frozen.get("migration_time_basis")).isEqualTo("MIGRATION_CLOCK");
                    assertThat(frozen.get("migration_acquired_at"))
                            .isEqualTo(
                                    jdbc.queryForMap(
                                                    "SELECT acquired_at FROM"
                                                            + " sc_recharge_frequency_queue")
                                            .get("acquired_at"));
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT acquired_at > (SELECT created_at FROM"
                                                    + " sc_fulfillment_operation) FROM"
                                                    + " sc_recharge_frequency_queue",
                                            Boolean.class))
                            .isTrue();
                    assertThat(
                                    context.getBean(RechargeFrequencyLifecycle.class)
                                            .claim((String) frozen.get("active_operation_no")))
                            .isNull();
                    jdbc.update("UPDATE sc_platform_sku SET spu_id=999999");
                    jdbc.update("UPDATE sc_fulfillment_request SET target_ciphertext=X''");
                    assertThat(migration.migrate().migratedCount()).isZero();
                    assertThat(jdbc.queryForMap("SELECT * FROM sc_recharge_frequency_entry"))
                            .isEqualTo(frozen);
                    assertThat(moneyAndEffects(jdbc)).isEqualTo(before);
                });
    }

    @Test
    void multipleUnverifiedRechargeOperationsOnSameOrderAreRejectedBeforeAnyWrites() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "DOUBLE-RISK");
                    clearFrequency(jdbc);
                    jdbc.update(
                            "UPDATE sc_fulfillment_operation SET"
                                    + " status=30,result_class='RESULT_UNKNOWN',attempt_count=1");
                    long id =
                            jdbc.queryForObject(
                                    "SELECT id FROM sc_fulfillment_operation", Long.class);
                    RechargeFrequencyAcceptanceIntegrationTest.cloneRow(
                            jdbc,
                            "sc_fulfillment_operation",
                            id,
                            java.util.Map.of(
                                    "operation_no",
                                    "OP_MIGRATION_SECOND",
                                    "sequence_no",
                                    2,
                                    "idempotency_key",
                                    "MIGRATION_SECOND"));
                    assertThatThrownBy(
                                    () ->
                                            context.getBean(RechargeFrequencyMigrationService.class)
                                                    .migrate())
                            .hasMessageContaining("MULTIPLE_UNVERIFIED_OPERATIONS");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM sc_recharge_frequency_entry",
                                            Long.class))
                            .isZero();
                });
    }

    @Test
    void trustedLegacyEndsOnlyOwnRiskAndOfflineCasWaitsForSuccessWindow() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "LEGACY-ONE");
                    accept(context, "LEGACY-TWO");
                    accept(context, "LEGACY-WAITING");
                    var ops = RechargeFrequencyExecutionIntegrationTest.operations(jdbc);
                    clearFrequency(jdbc);
                    jdbc.update(
                            "UPDATE sc_fulfillment_operation SET"
                                + " status=30,result_class='RESULT_UNKNOWN',attempt_count=1 WHERE"
                                + " operation_no IN (?,?)",
                            ops.get(0),
                            ops.get(1));
                    var migration = context.getBean(RechargeFrequencyMigrationService.class);
                    migration.migrate();
                    long queue =
                            jdbc.queryForObject(
                                    "SELECT id FROM sc_recharge_frequency_queue", Long.class);
                    long generation =
                            jdbc.queryForObject(
                                    "SELECT occupancy_generation FROM sc_recharge_frequency_queue",
                                    Long.class);
                    assertThatThrownBy(
                                    () ->
                                            migration.confirmTrustedLegacyOutcome(
                                                    ops.getFirst(),
                                                    fact(OperationOutcomeClass.RESULT_UNKNOWN),
                                                    "UNKNOWN"))
                            .hasMessageContaining("UNRESOLVED_RISK");
                    var normal = context.getBean(SupplierOrderLifecycle.class);
                    normal.transition(
                            ops.getFirst(),
                            fact(OperationOutcomeClass.SUCCEEDED),
                            "UNTRUSTED-LATE");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM sc_recharge_frequency_entry WHERE"
                                                    + " status=20",
                                            Long.class))
                            .isZero();
                    migration.confirmTrustedLegacyOutcome(
                            ops.getFirst(), fact(OperationOutcomeClass.SUCCEEDED), "VERIFIED-ONE");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM sc_recharge_frequency_entry WHERE"
                                                    + " status=10",
                                            Long.class))
                            .isEqualTo(1L);
                    assertThat(RechargeFrequencyLifecycleIntegrationTest.ledger(jdbc, "COMMIT"))
                            .isEqualTo(1L);
                    normal.confirmTrustedOutcome(
                            ops.getFirst(), fact(OperationOutcomeClass.SUCCEEDED), "REPLAY", null);
                    assertThat(RechargeFrequencyLifecycleIntegrationTest.ledger(jdbc, "COMMIT"))
                            .isEqualTo(1L);
                    assertThatThrownBy(
                                    () ->
                                            migration.recoverBlocked(
                                                    queue, generation, queueVersion(jdbc)))
                            .hasMessageContaining("UNRESOLVED_RISK");
                    migration.confirmTrustedLegacyOutcome(
                            ops.get(1), fact(OperationOutcomeClass.REJECTED), "VERIFIED-TWO");
                    assertThat(RechargeFrequencyLifecycleIntegrationTest.ledger(jdbc, "RELEASE"))
                            .isEqualTo(1L);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT blocked_reason FROM"
                                                    + " sc_recharge_frequency_queue",
                                            String.class))
                            .isEqualTo("MIGRATION_CONFLICT");
                    assertThatThrownBy(
                                    () ->
                                            migration.recoverBlocked(
                                                    queue, generation, queueVersion(jdbc) - 1))
                            .hasMessageContaining("STALE_QUEUE");
                    // 冻结时间以真实DB字段推进到未来，验证离线入口窗口前拒绝，之后允许；不使用睡眠测试边界。
                    jdbc.update(
                            "UPDATE sc_recharge_frequency_entry SET"
                                + " migration_acquired_at=DATE_ADD(NOW(3),INTERVAL 1 SECOND) WHERE"
                                + " active_operation_no=?",
                            ops.getFirst());
                    assertThatThrownBy(
                                    () ->
                                            migration.recoverBlocked(
                                                    queue, generation, queueVersion(jdbc)))
                            .hasMessageContaining("SUCCESS_WINDOW_PENDING");
                    jdbc.update(
                            "UPDATE sc_recharge_frequency_entry SET"
                                + " migration_acquired_at=DATE_SUB(NOW(3),INTERVAL 6 SECOND) WHERE"
                                + " active_operation_no=?",
                            ops.getFirst());
                    migration.recoverBlocked(queue, generation, queueVersion(jdbc));
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT blocked_reason FROM"
                                                    + " sc_recharge_frequency_queue",
                                            String.class))
                            .isNull();
                    var frequency = context.getBean(RechargeFrequencyLifecycle.class);
                    var next = frequency.claim(ops.get(2));
                    assertThat(next).isNotNull();
                    normal.confirmTrustedOutcome(
                            ops.getFirst(),
                            fact(OperationOutcomeClass.SUCCEEDED),
                            "OLD-AFTER-NEW",
                            null);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT holder_entry_id FROM"
                                                    + " sc_recharge_frequency_queue",
                                            Long.class))
                            .isEqualTo(next.entryId());
                    var oldPermit =
                            new RechargeFrequencyExecutionPermit(
                                    queue, 1, ops.getFirst(), generation, 0, 0);
                    assertThat(frequency.markSendPossible(oldPermit)).isFalse();
                });
    }

    @Test
    void allRejectedRisksRemoveConservativeWindowButNeverAutoUnblock() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "ALL-FAIL-ONE");
                    accept(context, "ALL-FAIL-TWO");
                    var ops = RechargeFrequencyExecutionIntegrationTest.operations(jdbc);
                    clearFrequency(jdbc);
                    jdbc.update(
                            "UPDATE sc_fulfillment_operation SET"
                                    + " status=90,result_class='MANUAL_REQUIRED',attempt_count=1");
                    var migration = context.getBean(RechargeFrequencyMigrationService.class);
                    migration.migrate();
                    migration.confirmTrustedLegacyOutcome(
                            ops.getFirst(), fact(OperationOutcomeClass.REJECTED), "FAIL-ONE");
                    migration.confirmTrustedLegacyOutcome(
                            ops.getLast(), fact(OperationOutcomeClass.REJECTED), "FAIL-TWO");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT minimum_hold_until FROM"
                                                    + " sc_recharge_frequency_queue",
                                            LocalDateTime.class))
                            .isNull();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT blocked_reason FROM"
                                                    + " sc_recharge_frequency_queue",
                                            String.class))
                            .isEqualTo("MIGRATION_CONFLICT");
                    migration.recoverBlocked(
                            jdbc.queryForObject(
                                    "SELECT id FROM sc_recharge_frequency_queue", Long.class),
                            jdbc.queryForObject(
                                    "SELECT occupancy_generation FROM sc_recharge_frequency_queue",
                                    Long.class),
                            queueVersion(jdbc));
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT blocked_reason FROM"
                                                    + " sc_recharge_frequency_queue",
                                            String.class))
                            .isNull();
                    assertThat(RechargeFrequencyLifecycleIntegrationTest.ledger(jdbc, "RELEASE"))
                            .isEqualTo(2L);
                });
    }

    @Test
    void earlierOmittedOrderCannotChangeAlreadyFrozenFifo() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "EARLY-OMITTED");
                    accept(context, "LATER-FROZEN");
                    jdbc.update("DELETE FROM sc_recharge_frequency_entry WHERE enqueue_sequence=1");
                    // 同毫秒顺序也必须由orderId保持，不能悄悄分配到冻结队尾。
                    jdbc.update(
                            "UPDATE sc_recharge_frequency_entry SET"
                                    + " enqueued_at=DATE_ADD(enqueued_at,INTERVAL 1 SECOND)");
                    assertThatThrownBy(
                                    () ->
                                            context.getBean(RechargeFrequencyMigrationService.class)
                                                    .migrate())
                            .hasMessageContaining("FIFO_GAP");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT enqueue_sequence FROM"
                                                    + " sc_recharge_frequency_entry",
                                            Long.class))
                            .isEqualTo(2L);
                });
    }

    @Test
    void closedMigrationCoverageGateRejectsNewMobileAcceptance() {
        withFixture(
                (context, jdbc) -> {
                    jdbc.update("UPDATE sc_recharge_frequency_migration_gate SET state='CLOSED'");
                    assertThatThrownBy(() -> accept(context, "CLOSED-GATE-NEW-MOBILE"))
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM sc_supply_chain_order",
                                            Long.class))
                            .isZero();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM"
                                                    + " sc_fulfillment_acceptance_receipt",
                                            Long.class))
                            .isZero();
                    context.getBean(RechargeFrequencyMigrationService.class).migrate();
                    var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
                    var request =
                            FulfillmentAcceptanceIntegrationTest.request(
                                    "CLOSED-GATE-NEW-MOBILE", 1L);
                    var accepted = facade.acceptFulfillment(request);
                    assertThat(accepted.getOrderNo()).isNotBlank();
                    var before = moneyAndEffects(jdbc);
                    jdbc.update("UPDATE sc_recharge_frequency_migration_gate SET state='CLOSED'");
                    assertThat(facade.acceptFulfillment(request).getOrderNo())
                            .isEqualTo(accepted.getOrderNo());
                    assertThat(moneyAndEffects(jdbc)).isEqualTo(before);
                });
    }

    @Test
    void readyWithWrongValidKeyFingerprintRejectsAcceptanceAndOriginalSendPermit() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "KEY-GATE-ORIGINAL");
                    var frequency = context.getBean(RechargeFrequencyLifecycle.class);
                    var op = RechargeFrequencyExecutionIntegrationTest.operations(jdbc).getFirst();
                    var permit = frequency.claim(op);
                    byte[] wrong = new byte[32];
                    wrong[0] = 1;
                    jdbc.update(
                            "UPDATE sc_recharge_frequency_migration_gate SET"
                                    + " identity_key_fingerprint=?",
                            wrong);
                    assertThatThrownBy(() -> accept(context, "KEY-GATE-WRONG"))
                            .hasMessageContaining("门禁未就绪");
                    assertThatThrownBy(() -> frequency.markSendPossible(permit))
                            .hasMessageContaining("门禁未就绪");
                    assertThat(frequency.revokePrepared(permit)).isTrue();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM"
                                                    + " sc_fulfillment_acceptance_receipt",
                                            Long.class))
                            .isEqualTo(1L);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM sc_supply_chain_order",
                                            Long.class))
                            .isEqualTo(1L);
                });
    }

    @Test
    void blockedNoHolderStillCountsAndAlertsAfterThirtyMinutesWithoutReleaseOrSend() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "OVERDUE-ONE");
                    accept(context, "OVERDUE-TWO");
                    clearFrequency(jdbc);
                    jdbc.update(
                            "UPDATE sc_fulfillment_operation SET"
                                    + " status=30,result_class='RESULT_UNKNOWN',attempt_count=1");
                    context.getBean(RechargeFrequencyMigrationService.class).migrate();
                    jdbc.update(
                            "UPDATE sc_recharge_frequency_queue SET"
                                    + " acquired_at=DATE_SUB(NOW(3),INTERVAL 31"
                                    + " MINUTE),next_check_at=NOW(3)");
                    var filter =
                            new RechargeFrequencyMonitorQueryService.Filter(
                                    1,
                                    20,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    context.getBean(
                                                    com.aifuli.module.supplychain.infrastructure
                                                            .time.SupplyChainDatabaseClock.class)
                                            .now());
                    assertThat(
                                    context.getBean(RechargeFrequencyMonitorMapper.class)
                                            .statistics(filter)
                                            .getOverdueQueueCount())
                            .isEqualTo(1L);
                    long queue =
                            jdbc.queryForObject(
                                    "SELECT id FROM sc_recharge_frequency_queue", Long.class);
                    context.getBean(RechargeFrequencyRecoveryService.class).repair(queue);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT occupied_alert_active FROM"
                                                    + " sc_recharge_frequency_queue",
                                            Boolean.class))
                            .isTrue();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT blocked_reason FROM"
                                                    + " sc_recharge_frequency_queue",
                                            String.class))
                            .isEqualTo("MIGRATION_CONFLICT");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT holder_entry_id FROM"
                                                    + " sc_recharge_frequency_queue",
                                            Long.class))
                            .isNull();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM sc_fulfillment_call_log",
                                            Long.class))
                            .isZero();
                    long wake =
                            jdbc.queryForObject(
                                    "SELECT wake_generation FROM sc_recharge_frequency_queue",
                                    Long.class);
                    jdbc.update(
                            "UPDATE sc_timer_task SET next_fire_at=DATE_SUB(NOW(3),INTERVAL 1"
                                    + " SECOND)");
                    context.getBean(RechargeFrequencyRecoveryService.class)
                            .prepareWake(queue, wake);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT next_check_at FROM sc_recharge_frequency_queue",
                                            LocalDateTime.class))
                            .isNotNull();
                    assertThat(RechargeFrequencyLifecycleIntegrationTest.ledger(jdbc, "RELEASE"))
                            .isZero();
                });
    }

    @Test
    void laterUnverifiedSideEffectCannotBeHiddenByTrustedLegacyRecovery() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "NEW-DOUBLE-RISK");
                    clearFrequency(jdbc);
                    jdbc.update(
                            "UPDATE sc_fulfillment_operation SET"
                                    + " status=30,result_class='RESULT_UNKNOWN',attempt_count=1");
                    var migration = context.getBean(RechargeFrequencyMigrationService.class);
                    migration.migrate();
                    var op =
                            jdbc.queryForObject(
                                    "SELECT operation_no FROM sc_fulfillment_operation",
                                    String.class);
                    long id =
                            jdbc.queryForObject(
                                    "SELECT id FROM sc_fulfillment_operation", Long.class);
                    RechargeFrequencyAcceptanceIntegrationTest.cloneRow(
                            jdbc,
                            "sc_fulfillment_operation",
                            id,
                            java.util.Map.of(
                                    "operation_no",
                                    "OP_UNVERIFIED_AFTER",
                                    "sequence_no",
                                    2,
                                    "idempotency_key",
                                    "UNVERIFIED_AFTER"));
                    assertThatThrownBy(
                                    () ->
                                            migration.confirmTrustedLegacyOutcome(
                                                    op,
                                                    fact(OperationOutcomeClass.REJECTED),
                                                    "NEW-RISK"))
                            .hasMessageContaining("UNRESOLVED_RISK");
                    context.getBean(SupplierOrderLifecycle.class)
                            .confirmTrustedOutcome(
                                    op,
                                    fact(OperationOutcomeClass.REJECTED),
                                    "DIRECT-NEW-RISK",
                                    null);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT status FROM sc_recharge_frequency_entry",
                                            Short.class))
                            .isEqualTo((short) 10);
                    assertThat(RechargeFrequencyLifecycleIntegrationTest.ledger(jdbc, "RELEASE"))
                            .isZero();
                });
    }

    @Test
    void migrationKeepsGlobalSpuFifoAcrossProjectsAndSuppliersWhileOtherSpuIsIndependent() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "GLOBAL-FIRST");
                    accept(context, "GLOBAL-SECOND");
                    accept(context, "OTHER-SPU");
                    var orderIds =
                            jdbc.queryForList(
                                    "SELECT id FROM sc_supply_chain_order ORDER BY id", Long.class);
                    long otherProject =
                            jdbc.queryForObject("SELECT MAX(id) FROM sc_buyer_project", Long.class);
                    jdbc.update(
                            "UPDATE sc_supply_chain_order SET project_id=? WHERE id=?",
                            otherProject,
                            orderIds.get(1));
                    jdbc.update(
                            "UPDATE sc_fulfillment_acceptance_receipt SET project_id=? WHERE"
                                    + " result_order_id=?",
                            otherProject,
                            orderIds.get(1));
                    jdbc.update(
                            "UPDATE sc_b_order SET supplier_id=999999 WHERE order_id=?",
                            orderIds.get(1));
                    long spu = jdbc.queryForObject("SELECT id FROM sc_platform_spu", Long.class);
                    long otherSpu =
                            RechargeFrequencyAcceptanceIntegrationTest.cloneRow(
                                    jdbc,
                                    "sc_platform_spu",
                                    spu,
                                    java.util.Map.of(
                                            "spu_code", "SPU_MIGRATION_OTHER", "code_scope_no", 2));
                    long sku = jdbc.queryForObject("SELECT id FROM sc_platform_sku", Long.class);
                    long otherSku =
                            RechargeFrequencyAcceptanceIntegrationTest.cloneRow(
                                    jdbc,
                                    "sc_platform_sku",
                                    sku,
                                    java.util.Map.of(
                                            "sku_code", "SKU_MIGRATION_OTHER", "spu_id", otherSpu));
                    jdbc.update(
                            "UPDATE sc_supply_chain_order SET platform_sku_id=? WHERE id=?",
                            otherSku,
                            orderIds.get(2));
                    jdbc.update(
                            "UPDATE sc_fulfillment_request SET platform_sku_id=? WHERE order_id=?",
                            otherSku,
                            orderIds.get(2));
                    clearFrequency(jdbc);
                    var before = moneyAndEffects(jdbc);
                    context.getBean(RechargeFrequencyMigrationService.class).migrate();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM sc_recharge_frequency_queue",
                                            Long.class))
                            .isEqualTo(2L);
                    assertThat(
                                    jdbc.queryForList(
                                            "SELECT enqueue_sequence FROM"
                                                + " sc_recharge_frequency_entry WHERE"
                                                + " queue_id=(SELECT id FROM"
                                                + " sc_recharge_frequency_queue WHERE"
                                                + " platform_spu_id=?) ORDER BY enqueue_sequence",
                                            Long.class,
                                            spu))
                            .containsExactly(1L, 2L);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(DISTINCT project_id) FROM"
                                                    + " sc_recharge_frequency_entry WHERE"
                                                    + " queue_id=(SELECT id FROM"
                                                    + " sc_recharge_frequency_queue WHERE"
                                                    + " platform_spu_id=?)",
                                            Long.class,
                                            spu))
                            .isEqualTo(2L);
                    var ops =
                            jdbc.queryForList(
                                    "SELECT active_operation_no FROM sc_recharge_frequency_entry"
                                            + " ORDER BY order_id",
                                    String.class);
                    var frequency = context.getBean(RechargeFrequencyLifecycle.class);
                    assertThat(frequency.claim(ops.getFirst())).isNotNull();
                    assertThat(frequency.claim(ops.get(1))).isNull();
                    assertThat(frequency.claim(ops.get(2))).isNotNull();
                    assertThat(moneyAndEffects(jdbc)).isEqualTo(before);
                });
    }

    @Test
    void orphanOrderPreventsReadyCoverageEvenWhenRequestScanIsEmpty() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "ORPHAN-REQUEST");
                    clearFrequency(jdbc);
                    jdbc.update("DELETE FROM sc_fulfillment_request");
                    assertThatThrownBy(
                                    () ->
                                            context.getBean(RechargeFrequencyMigrationService.class)
                                                    .migrate())
                            .hasMessageContaining("ASSOCIATION_INVALID");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT state FROM"
                                                    + " sc_recharge_frequency_migration_gate",
                                            String.class))
                            .isEqualTo("CLOSED");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM sc_recharge_frequency_queue",
                                            Long.class))
                            .isZero();
                });
    }

    @Test
    void firstIdentityBindingVerifiesExistingFrozenDigestAndNeverReassignsSpuOrSequence() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "FIRST-BINDING");
                    var before = jdbc.queryForMap("SELECT * FROM sc_recharge_frequency_entry");
                    jdbc.update(
                            "UPDATE sc_recharge_frequency_migration_gate SET"
                                    + " state='CLOSED',identity_key_fingerprint=NULL");
                    context.getBean(RechargeFrequencyMigrationService.class).migrate();
                    assertThat(jdbc.queryForMap("SELECT * FROM sc_recharge_frequency_entry"))
                            .isEqualTo(before);
                    jdbc.update(
                            "UPDATE sc_recharge_frequency_migration_gate SET"
                                    + " state='CLOSED',identity_key_fingerprint=NULL");
                    byte[] incorrect = new byte[32];
                    incorrect[0] = 9;
                    jdbc.update(
                            "UPDATE sc_recharge_frequency_queue SET mobile_digest=?", incorrect);
                    assertThatThrownBy(
                                    () ->
                                            context.getBean(RechargeFrequencyMigrationService.class)
                                                    .migrate())
                            .hasMessageContaining("IDENTITY_KEY_INVALID");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT state FROM"
                                                    + " sc_recharge_frequency_migration_gate",
                                            String.class))
                            .isEqualTo("CLOSED");
                    assertThat(jdbc.queryForMap("SELECT * FROM sc_recharge_frequency_entry"))
                            .isEqualTo(before);
                });
    }

    @Test
    void changedValidKeyOnReadyMigrationRerunCommitsClosedBeforeRejecting() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "CHANGED-KEY");
                    byte[] incorrect = new byte[32];
                    incorrect[0] = 9;
                    jdbc.update(
                            "UPDATE sc_recharge_frequency_migration_gate SET"
                                    + " identity_key_fingerprint=?",
                            incorrect);
                    assertThatThrownBy(
                                    () ->
                                            context.getBean(RechargeFrequencyMigrationService.class)
                                                    .migrate())
                            .hasMessageContaining("IDENTITY_KEY_INVALID");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT state FROM"
                                                    + " sc_recharge_frequency_migration_gate",
                                            String.class))
                            .isEqualTo("CLOSED");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT identity_key_fingerprint FROM"
                                                    + " sc_recharge_frequency_migration_gate",
                                            byte[].class))
                            .isEqualTo(incorrect);
                });
    }

    @Test
    void frozenWaitingWithNowUnknownOperationRejectsReadyWithoutRecalculatingIdentity() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "FROZEN-WAITING-UNKNOWN");
                    var before = jdbc.queryForMap("SELECT * FROM sc_recharge_frequency_entry");
                    jdbc.update(
                            "UPDATE sc_fulfillment_operation SET"
                                + " status=30,result_class='RESULT_UNKNOWN',attempt_count=1");
                    assertThatThrownBy(
                                    () ->
                                            context.getBean(RechargeFrequencyMigrationService.class)
                                                    .migrate())
                            .hasMessageContaining("STATE_CONTRADICTION");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT state FROM"
                                                + " sc_recharge_frequency_migration_gate",
                                            String.class))
                            .isEqualTo("CLOSED");
                    assertThat(jdbc.queryForMap("SELECT * FROM sc_recharge_frequency_entry"))
                            .isEqualTo(before);
                });
    }

    @Test
    void frozenHolderWithAdditionalUnverifiedOperationRejectsReady() {
        withFixture(
                (context, jdbc) -> {
                    accept(context, "FROZEN-HOLDER-MULTIPLE");
                    var frequency = context.getBean(RechargeFrequencyLifecycle.class);
                    var op = RechargeFrequencyExecutionIntegrationTest.operations(jdbc).getFirst();
                    var permit = frequency.claim(op);
                    frequency.markSendPossible(permit);
                    jdbc.update(
                            "UPDATE sc_fulfillment_operation SET"
                                + " status=30,result_class='RESULT_UNKNOWN',attempt_count=1");
                    long id =
                            jdbc.queryForObject(
                                    "SELECT id FROM sc_fulfillment_operation", Long.class);
                    RechargeFrequencyAcceptanceIntegrationTest.cloneRow(
                            jdbc,
                            "sc_fulfillment_operation",
                            id,
                            java.util.Map.of(
                                    "operation_no",
                                    "OP_FROZEN_EXTRA_RISK",
                                    "sequence_no",
                                    2,
                                    "idempotency_key",
                                    "FROZEN_EXTRA_RISK"));
                    assertThatThrownBy(
                                    () ->
                                            context.getBean(RechargeFrequencyMigrationService.class)
                                                    .migrate())
                            .hasMessageContaining("MULTIPLE_UNVERIFIED_OPERATIONS");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT state FROM"
                                                + " sc_recharge_frequency_migration_gate",
                                            String.class))
                            .isEqualTo("CLOSED");
                });
    }

    @Test
    void missingDeploymentEvidenceRefusesEvenEmptyMigration() {
        var fixture = new FulfillmentAcceptanceIntegrationTest();
        fixture.startContainer();
        try (var context = fixture.applicationContext(MissingEvidenceConfiguration.class)) {
            assertThatThrownBy(
                            () ->
                                    context.getBean(RechargeFrequencyMigrationService.class)
                                            .migrate())
                    .hasMessageContaining("DEPLOYMENT_EVIDENCE_MISSING");
        } finally {
            fixture.stopContainer();
        }
    }

    private static void withFixture(
            java.util.function.BiConsumer<ConfigurableApplicationContext, JdbcTemplate> work) {
        var fixture = new FulfillmentAcceptanceIntegrationTest();
        fixture.startContainer();
        try (var context = fixture.applicationContext(MigrationConfiguration.class)) {
            fixture.seed(context);
            work.accept(
                    context,
                    new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class)));
        } finally {
            fixture.stopContainer();
        }
    }

    private static void accept(ConfigurableApplicationContext context, String buyerOrderNo) {
        context.getBean(BuyerFulfillmentCommandFacade.class)
                .acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request(buyerOrderNo, 1L));
    }

    private static void clearFrequency(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM sc_recharge_frequency_entry");
        jdbc.update("DELETE FROM sc_recharge_frequency_queue");
    }

    private static java.util.Map<String, Object> moneyAndEffects(JdbcTemplate jdbc) {
        return jdbc.queryForMap(
                "SELECT (SELECT frozen FROM sc_buyer_account) frozen,(SELECT COUNT(*) FROM"
                    + " sc_account_ledger) ledgers,(SELECT COUNT(*) FROM sc_fulfillment_operation)"
                    + " operations,(SELECT COUNT(*) FROM sc_fulfillment_call_log) calls");
    }

    private static long queueVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT version FROM sc_recharge_frequency_queue", Long.class);
    }

    private static OperationOutcome fact(OperationOutcomeClass outcome) {
        return OperationOutcome.builder().outcomeClass(outcome).build();
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RechargeFrequencyMigrationService.class)
    static class MissingEvidenceConfiguration {}

    @Configuration(proxyBeanMethods = false)
    @Import(RechargeFrequencyMigrationService.class)
    static class MigrationConfiguration {
        @org.springframework.context.annotation.Bean
        static org.springframework.beans.factory.config.BeanFactoryPostProcessor
                migrationVersion() {
            return factory -> {
                ((org.springframework.core.env.ConfigurableEnvironment)
                                factory.getBean(org.springframework.core.env.Environment.class))
                        .getPropertySources()
                        .addFirst(
                                new org.springframework.core.env.MapPropertySource(
                                        "migrationVersion",
                                        java.util.Map.of(
                                                RechargeFrequencyMigrationService
                                                        .TARGET_VERSION_PROPERTY,
                                                "a".repeat(40))));
            };
        }

        @org.springframework.context.annotation.Bean
        RechargeFrequencyMigrationService.DeploymentQuiescenceEvidencePort evidence() {
            return (version, fingerprint) -> {
                assertThat(version).hasSize(40);
                assertThat(fingerprint).hasSize(32);
            };
        }
    }
}
