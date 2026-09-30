package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentCommandFacade;
import com.aifuli.module.supplychain.border.fulfillment.application.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实MySQL资金与可信终态，见限频设计 #5-结果窗口和资金；不以超时释放。 */
class RechargeFrequencyLifecycleIntegrationTest {
    @Test void acceptedUnknownManualAndNotFoundKeepFundsThenTrustedSuccessCommitsOnceAndWaitsFixedWindow() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(RechargeFrequencyExecutionIntegrationTest.ExecutionConfiguration.class)) {
            fixture.seed(context); var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("FACT-HEAD", 1L));
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("FACT-TAIL", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var ops = RechargeFrequencyExecutionIntegrationTest.operations(jdbc);
            var frequency = context.getBean(RechargeFrequencyLifecycle.class); var lifecycle = context.getBean(SupplierOrderLifecycle.class);
            var permit = frequency.claim(ops.getFirst()); assertThat(frequency.markSendPossible(permit)).isTrue();
            for (var result : new OperationOutcomeClass[]{OperationOutcomeClass.ACCEPTED, OperationOutcomeClass.RESULT_UNKNOWN, OperationOutcomeClass.MANUAL_REQUIRED, OperationOutcomeClass.NOT_FOUND}) {
                lifecycle.transition(ops.getFirst(), fact(result), "FACT");
                assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isEqualTo(permit.entryId());
                assertThat(frequency.claim(ops.getLast())).isNull();
                assertThat(ledger(jdbc, "COMMIT")).isZero(); assertThat(ledger(jdbc, "RELEASE")).isZero();
            }
            lifecycle.transition(ops.getFirst(), fact(OperationOutcomeClass.SUCCEEDED), "UNTRUSTED-MANUAL");
            assertThat(ledger(jdbc, "COMMIT")).isZero();
            // 未来窗口固定在数据库，消除测试运行时间影响，不用sleep猜5秒。
            var acquired = LocalDateTime.of(2099, 1, 1, 0, 0);
            jdbc.update("UPDATE sc_recharge_frequency_queue SET acquired_at=?, minimum_hold_until=?", acquired, acquired.plusSeconds(5));
            var success = observedSuccess();
            lifecycle.confirmTrustedOutcome(ops.getFirst(), success, "TRUSTED", permit);
            lifecycle.confirmTrustedOutcome(ops.getFirst(), success, "DUPLICATE", permit);
            assertThat(ledger(jdbc, "COMMIT")).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_raw_status_observation", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT acquired_at FROM sc_recharge_frequency_queue", LocalDateTime.class)).isEqualTo(acquired);
            assertThat(frequency.claim(ops.getLast())).isNull();
            lifecycle.confirmTrustedOutcome(ops.getFirst(), fact(OperationOutcomeClass.REJECTED), "CONTRADICTION", permit);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_exception_case WHERE reason_code='SUPPLIER_TERMINAL_CONTRADICTION'", Long.class)).isEqualTo(1L);
            assertThat(ledger(jdbc, "RELEASE")).isZero();
            jdbc.update("UPDATE sc_recharge_frequency_queue SET acquired_at=DATE_SUB(NOW(), INTERVAL 10 SECOND), minimum_hold_until=DATE_SUB(NOW(), INTERVAL 5 SECOND)");
            lifecycle.confirmTrustedOutcome(ops.getFirst(), success, "WINDOW", permit);
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isNull();
            var next = frequency.claim(ops.getLast()); assertThat(next).isNotNull();
            lifecycle.confirmTrustedOutcome(ops.getFirst(), success, "LATE", permit);
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isEqualTo(next.entryId());
            assertThat(ledger(jdbc, "COMMIT")).isEqualTo(1L);
        } finally { fixture.stopContainer(); }
    }
    @Test void trustedFailureReleasesFundsAndOccupancyOnce() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(RechargeFrequencyExecutionIntegrationTest.ExecutionConfiguration.class)) {
            fixture.seed(context); context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("FAILURE", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var op = RechargeFrequencyExecutionIntegrationTest.operations(jdbc).getFirst();
            var frequency = context.getBean(RechargeFrequencyLifecycle.class); var permit = frequency.claim(op); frequency.markSendPossible(permit);
            var lifecycle = context.getBean(SupplierOrderLifecycle.class);
            lifecycle.transition(op, fact(OperationOutcomeClass.REJECTED), "FAIL");
            lifecycle.confirmTrustedOutcome(op, fact(OperationOutcomeClass.REJECTED), "FAIL-REPLAY", permit);
            assertThat(ledger(jdbc, "RELEASE")).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT active_flag FROM sc_b_order", Integer.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM sc_recharge_frequency_entry", Short.class)).isEqualTo((short)20);
        } finally { fixture.stopContainer(); }
    }
    @Test void continuingOperationKeepsOriginalHolderGenerationAndFrozenSpu() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(RechargeFrequencyExecutionIntegrationTest.ExecutionConfiguration.class)) {
            fixture.seed(context); var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("CONTINUE-HEAD", 1L));
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("CONTINUE-TAIL", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var ops = RechargeFrequencyExecutionIntegrationTest.operations(jdbc); var frequency = context.getBean(RechargeFrequencyLifecycle.class);
            var first = frequency.claim(ops.getFirst()); frequency.markSendPossible(first);
            long frozenBefore = jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class);
            var queueBefore = jdbc.queryForMap("SELECT platform_spu_id, holder_entry_id, occupancy_generation, acquired_at, minimum_hold_until FROM sc_recharge_frequency_queue");
            long operationId = jdbc.queryForObject("SELECT id FROM sc_fulfillment_operation WHERE operation_no=?", Long.class, ops.getFirst());
            long nextId = RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc, "sc_fulfillment_operation", operationId,
                    java.util.Map.of("operation_no", "OP_CONTINUING", "sequence_no", 2, "idempotency_key", "CONTINUING", "status", 0,
                            "attempt_count", 0, "version", 0));
            jdbc.update("UPDATE sc_fulfillment_operation SET result_class=NULL WHERE id=?", nextId);
            // 经真实可信结果入口收敛旧操作，不允许用SQL伪造FAILED绕过资金流程。
            context.getBean(SupplierOrderLifecycle.class).confirmTrustedRejectedAndContinue(ops.getFirst(), fact(OperationOutcomeClass.REJECTED), "CONTINUE-FACT", first, "OP_CONTINUING");
            assertThat(jdbc.queryForObject("SELECT status FROM sc_fulfillment_request WHERE id=(SELECT fulfillment_request_id FROM sc_b_order WHERE id=(SELECT b_order_id FROM sc_fulfillment_operation WHERE operation_no=?))", Short.class, ops.getFirst())).isEqualTo(FulfillmentRequestStatus.FULFILLING.getCode());
            assertThat(ledger(jdbc, "RELEASE")).isZero();
            assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(frozenBefore);
            assertThat(jdbc.queryForObject("SELECT enqueue_sequence FROM sc_recharge_frequency_entry WHERE id=?", Long.class, first.entryId())).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT active_operation_no FROM sc_recharge_frequency_entry WHERE id=?", String.class, first.entryId())).isEqualTo("OP_CONTINUING");
            assertThat(jdbc.queryForObject("SELECT wake_generation FROM sc_recharge_frequency_queue", Long.class)).isGreaterThan(0L);
            assertThat(frequency.claim(ops.getLast())).isNull();
            var next = frequency.claim("OP_CONTINUING"); assertThat(next).isNotNull();
            assertThat(jdbc.queryForMap("SELECT platform_spu_id, holder_entry_id, occupancy_generation, acquired_at, minimum_hold_until FROM sc_recharge_frequency_queue")).isEqualTo(queueBefore);
            // 后来可变SKU归属不能让已冻结项移队或绕过频控。
            jdbc.update("UPDATE sc_platform_sku SET fulfillment_type='ACCOUNT_BOUND_BENEFIT'");
            assertThat(frequency.requiresFrequency("OP_CONTINUING", "EXTERNAL_ACCOUNT")).isTrue();
            context.getBean(SupplierOrderLifecycle.class).confirmTrustedOutcome(ops.getFirst(), fact(OperationOutcomeClass.REJECTED), "OLD-OP", first);
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isEqualTo(next.entryId());
            assertThat(ledger(jdbc, "RELEASE")).isZero();
            assertThat(jdbc.queryForObject("SELECT active_operation_no FROM sc_recharge_frequency_entry WHERE id=?", String.class, first.entryId())).isEqualTo("OP_CONTINUING");
            assertThat(frequency.markSendPossible(first)).isFalse();
            assertThat(frequency.markSendPossible(next)).isTrue();
            var lifecycle = context.getBean(SupplierOrderLifecycle.class);
            lifecycle.confirmTrustedOutcome("OP_CONTINUING", fact(OperationOutcomeClass.REJECTED), "FINAL-FAIL", next);
            lifecycle.confirmTrustedOutcome("OP_CONTINUING", fact(OperationOutcomeClass.REJECTED), "FINAL-REPLAY", next);
            assertThat(ledger(jdbc, "RELEASE")).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(frozenBefore / 2);
            var tail = frequency.claim(ops.getLast()); assertThat(tail).isNotNull();
            lifecycle.confirmTrustedOutcome(ops.getFirst(), fact(OperationOutcomeClass.REJECTED), "OLD-AFTER-TAIL", first);
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isEqualTo(tail.entryId());
        } finally { fixture.stopContainer(); }
    }

    @Test void trustedManualRejectionCanSwitchToExistingNextSupplierAttemptAtomically() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(RechargeFrequencyExecutionIntegrationTest.ExecutionConfiguration.class)) {
            fixture.seed(context); var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("NEXT-ATTEMPT", 1L));
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("ATTEMPT-TAIL", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var ops = RechargeFrequencyExecutionIntegrationTest.operations(jdbc); var frequency = context.getBean(RechargeFrequencyLifecycle.class);
            var lifecycle = context.getBean(SupplierOrderLifecycle.class); var permit = frequency.claim(ops.getFirst()); frequency.markSendPossible(permit);
            long oldOpId = jdbc.queryForObject("SELECT id FROM sc_fulfillment_operation WHERE operation_no=?", Long.class, ops.getFirst());
            long oldBOrderId = jdbc.queryForObject("SELECT b_order_id FROM sc_fulfillment_operation WHERE id=?", Long.class, oldOpId);
            var changes = new java.util.HashMap<String, Object>();
            changes.put("b_order_no", "BO_NEXT_ATTEMPT"); changes.put("attempt_no", 2); changes.put("active_flag", null);
            changes.put("billno", "BILL_NEXT_ATTEMPT"); changes.put("idempotency_key", "ATTEMPT_KEY"); changes.put("version", 0);
            long nextBOrderId = RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc, "sc_b_order", oldBOrderId, changes);
            long nextOpId = RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc, "sc_fulfillment_operation", oldOpId,
                    java.util.Map.of("operation_no", "OP_NEXT_ATTEMPT", "b_order_id", nextBOrderId, "idempotency_key", "NEXT_ATTEMPT", "status", 0, "version", 0));
            var before = jdbc.queryForMap("SELECT platform_spu_id,holder_entry_id,occupancy_generation,acquired_at,minimum_hold_until FROM sc_recharge_frequency_queue");
            lifecycle.transition(ops.getFirst(), fact(OperationOutcomeClass.MANUAL_REQUIRED), "MANUAL");
            assertThat(jdbc.queryForObject("SELECT active_flag FROM sc_b_order WHERE id=?", Integer.class, oldBOrderId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT completed_at FROM sc_b_order WHERE id=?", LocalDateTime.class, oldBOrderId)).isNull();
            // 兼容历史MANUAL曾写completedAt的记录；旧操作状态仍由真实结果入口推进。
            jdbc.update("UPDATE sc_b_order SET completed_at=NOW() WHERE id=?", oldBOrderId);
            assertThat(jdbc.queryForObject("SELECT order_status FROM sc_supply_chain_order WHERE id=(SELECT order_id FROM sc_b_order WHERE id=?)", Short.class, oldBOrderId)).isEqualTo((short)90);
            jdbc.update("UPDATE sc_b_order SET attempt_no=3 WHERE id=?", nextBOrderId);
            assertThatThrownBy(() -> lifecycle.confirmTrustedRejectedAndContinue(ops.getFirst(), fact(OperationOutcomeClass.REJECTED), "WRONG-ATTEMPT", permit, "OP_NEXT_ATTEMPT")).isInstanceOf(IllegalStateException.class);
            jdbc.update("UPDATE sc_b_order SET attempt_no=2 WHERE id=?", nextBOrderId);
            jdbc.update("UPDATE sc_fulfillment_operation SET sequence_no=2 WHERE id=?", nextOpId);
            assertThatThrownBy(() -> lifecycle.confirmTrustedRejectedAndContinue(ops.getFirst(), fact(OperationOutcomeClass.REJECTED), "WRONG-FIRST-SEQ", permit, "OP_NEXT_ATTEMPT")).isInstanceOf(IllegalStateException.class);
            jdbc.update("UPDATE sc_fulfillment_operation SET sequence_no=1 WHERE id=?", nextOpId);
            lifecycle.confirmTrustedRejectedAndContinue(ops.getFirst(), fact(OperationOutcomeClass.REJECTED), "VERIFIED-REJECTION", permit, "OP_NEXT_ATTEMPT");
            assertThat(jdbc.queryForObject("SELECT active_flag FROM sc_b_order WHERE id=?", Integer.class, oldBOrderId)).isNull();
            assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM sc_b_order WHERE id=?", Short.class, oldBOrderId)).isEqualTo((short)50);
            assertThat(jdbc.queryForObject("SELECT active_flag FROM sc_b_order WHERE id=?", Integer.class, nextBOrderId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT active_b_order_id FROM sc_fulfillment_request WHERE id=(SELECT fulfillment_request_id FROM sc_b_order WHERE id=?)", Long.class, nextBOrderId)).isEqualTo(nextBOrderId);
            assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM sc_b_order WHERE id=?", Short.class, nextBOrderId)).isEqualTo((short)20);
            assertThat(jdbc.queryForObject("SELECT order_status FROM sc_supply_chain_order WHERE id=(SELECT order_id FROM sc_b_order WHERE id=?)", Short.class, oldBOrderId)).isEqualTo((short)10);
            assertThat(jdbc.queryForMap("SELECT platform_spu_id,holder_entry_id,occupancy_generation,acquired_at,minimum_hold_until FROM sc_recharge_frequency_queue")).isEqualTo(before);
            assertThat(ledger(jdbc, "RELEASE")).isZero(); assertThat(frequency.claim(ops.getLast())).isNull();
            var next = frequency.claim("OP_NEXT_ATTEMPT"); assertThat(next).isNotNull(); frequency.markSendPossible(next);
            lifecycle.confirmTrustedOutcome(ops.getFirst(), fact(OperationOutcomeClass.REJECTED), "LATE-OLD", permit);
            assertThat(jdbc.queryForObject("SELECT active_operation_no FROM sc_recharge_frequency_entry WHERE id=?", String.class, permit.entryId())).isEqualTo("OP_NEXT_ATTEMPT");
            lifecycle.confirmTrustedOutcome("OP_NEXT_ATTEMPT", fact(OperationOutcomeClass.REJECTED), "EXHAUSTED", next);
            lifecycle.confirmTrustedOutcome("OP_NEXT_ATTEMPT", fact(OperationOutcomeClass.REJECTED), "EXHAUSTED-REPLAY", next);
            assertThat(ledger(jdbc, "RELEASE")).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT status FROM sc_fulfillment_operation WHERE id=?", Short.class, nextOpId)).isEqualTo((short)50);
        } finally { fixture.stopContainer(); }
    }

    @Test void trustedManualSameBOrderRecoveryClearsHistoricalCompletionWithVersionCas() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(RechargeFrequencyExecutionIntegrationTest.ExecutionConfiguration.class)) {
            fixture.seed(context); context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("MANUAL-SAME-BO", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var op = RechargeFrequencyExecutionIntegrationTest.operations(jdbc).getFirst();
            var frequency = context.getBean(RechargeFrequencyLifecycle.class); var lifecycle = context.getBean(SupplierOrderLifecycle.class);
            var permit = frequency.claim(op); frequency.markSendPossible(permit);
            long oldId = jdbc.queryForObject("SELECT id FROM sc_fulfillment_operation WHERE operation_no=?", Long.class, op);
            long boId = jdbc.queryForObject("SELECT b_order_id FROM sc_fulfillment_operation WHERE id=?", Long.class, oldId);
            RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc, "sc_fulfillment_operation", oldId,
                    java.util.Map.of("operation_no", "OP_MANUAL_CONTINUE", "sequence_no", 2, "idempotency_key", "MANUAL_CONTINUE", "status", 0, "version", 0));
            long frozenBefore = jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class);
            lifecycle.transition(op, fact(OperationOutcomeClass.MANUAL_REQUIRED), "MANUAL-SAME");
            assertThat(jdbc.queryForObject("SELECT active_flag FROM sc_b_order WHERE id=?", Integer.class, boId)).isEqualTo(1);
            jdbc.update("UPDATE sc_b_order SET completed_at=NOW() WHERE id=?", boId);
            long versionBefore = jdbc.queryForObject("SELECT version FROM sc_b_order WHERE id=?", Long.class, boId);
            lifecycle.confirmTrustedRejectedAndContinue(op, fact(OperationOutcomeClass.REJECTED), "CONFIRMED", permit, "OP_MANUAL_CONTINUE");
            assertThat(jdbc.queryForObject("SELECT completed_at FROM sc_b_order WHERE id=?", LocalDateTime.class, boId)).isNull();
            assertThat(jdbc.queryForObject("SELECT fulfillment_status FROM sc_b_order WHERE id=?", Short.class, boId)).isEqualTo((short)20);
            assertThat(jdbc.queryForObject("SELECT version FROM sc_b_order WHERE id=?", Long.class, boId)).isEqualTo(versionBefore+1);
            assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(frozenBefore);
            assertThat(ledger(jdbc, "RELEASE")).isZero();
            var next = frequency.claim("OP_MANUAL_CONTINUE"); assertThat(next).isNotNull(); assertThat(frequency.markSendPossible(next)).isTrue();
            lifecycle.confirmTrustedOutcome("OP_MANUAL_CONTINUE", fact(OperationOutcomeClass.REJECTED), "FINAL", next);
            assertThat(jdbc.queryForObject("SELECT active_flag FROM sc_b_order WHERE id=?", Integer.class, boId)).isNull();
            assertThat(ledger(jdbc, "RELEASE")).isEqualTo(1L);
        } finally { fixture.stopContainer(); }
    }

    @Test void illegalContinuationRollsBackRealResultFactsStatusMoneyAndHolder() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(RechargeFrequencyExecutionIntegrationTest.ExecutionConfiguration.class)) {
            fixture.seed(context); var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("INVALID-NEXT", 1L));
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("OTHER-REQUEST", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var ops = RechargeFrequencyExecutionIntegrationTest.operations(jdbc); var frequency = context.getBean(RechargeFrequencyLifecycle.class);
            var lifecycle = context.getBean(SupplierOrderLifecycle.class); var permit = frequency.claim(ops.getFirst()); frequency.markSendPossible(permit);
            long oldId = jdbc.queryForObject("SELECT id FROM sc_fulfillment_operation WHERE operation_no=?", Long.class, ops.getFirst());
            long nextId = RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc, "sc_fulfillment_operation", oldId,
                    java.util.Map.of("operation_no", "OP_INVALID_NEXT", "sequence_no", 2, "idempotency_key", "INVALID_NEXT", "status", 0, "version", 0));
            var before = jdbc.queryForMap("SELECT id,platform_spu_id,holder_entry_id,occupancy_generation,acquired_at,minimum_hold_until,wake_generation,version,next_check_at FROM sc_recharge_frequency_queue");
            var observedRejected = OperationOutcome.builder().outcomeClass(OperationOutcomeClass.REJECTED)
                    .rawObservation(RawObservationFact.builder().sourceType("CALLBACK").externalOrderNo("INVALID-REJECTION").externalStatus("R").payloadDigest(new byte[32]).observedAt(LocalDateTime.now()).build()).build();
            java.util.function.Consumer<String> rejected = no -> assertThatThrownBy(() -> lifecycle.confirmTrustedRejectedAndContinue(ops.getFirst(), observedRejected, "INVALID", permit, no)).isInstanceOf(IllegalStateException.class);
            rejected.accept(ops.getFirst()); rejected.accept(ops.getLast());
            jdbc.update("UPDATE sc_fulfillment_operation SET operation_kind='QUERY_ORDER' WHERE id=?", nextId); rejected.accept("OP_INVALID_NEXT");
            jdbc.update("UPDATE sc_fulfillment_operation SET operation_kind='FULFILL',sequence_no=3 WHERE id=?", nextId); rejected.accept("OP_INVALID_NEXT");
            jdbc.update("UPDATE sc_fulfillment_operation SET sequence_no=2,status=30,result_class='RESULT_UNKNOWN',attempt_count=1 WHERE id=?", nextId); rejected.accept("OP_INVALID_NEXT");
            jdbc.update("UPDATE sc_fulfillment_operation SET status=0,result_class=NULL,attempt_count=1 WHERE id=?", nextId); rejected.accept("OP_INVALID_NEXT");
            jdbc.update("UPDATE sc_fulfillment_operation SET attempt_count=0,external_operation_no='UNVERIFIED' WHERE id=?", nextId); rejected.accept("OP_INVALID_NEXT");
            jdbc.update("UPDATE sc_fulfillment_operation SET external_operation_no=NULL WHERE id=?", nextId);
            assertThatThrownBy(() -> lifecycle.confirmTrustedRejectedAndContinue(ops.getFirst(), fact(OperationOutcomeClass.RESULT_UNKNOWN), "NOT-REJECTION", permit, "OP_INVALID_NEXT")).isInstanceOf(IllegalStateException.class);
            long requestId = jdbc.queryForObject("SELECT fulfillment_request_id FROM sc_b_order WHERE id=(SELECT b_order_id FROM sc_fulfillment_operation WHERE id=?)", Long.class, oldId);
            long orderId = jdbc.queryForObject("SELECT order_id FROM sc_b_order WHERE id=(SELECT b_order_id FROM sc_fulfillment_operation WHERE id=?)", Long.class, oldId);
            jdbc.update("UPDATE sc_fulfillment_request SET active_b_order_id=999999 WHERE id=?", requestId); rejected.accept("OP_INVALID_NEXT");
            jdbc.update("UPDATE sc_fulfillment_request SET active_b_order_id=NULL WHERE id=?", requestId);
            jdbc.update("UPDATE sc_fulfillment_request SET status=50 WHERE id=?", requestId); rejected.accept("OP_INVALID_NEXT");
            jdbc.update("UPDATE sc_fulfillment_request SET status=20 WHERE id=?", requestId);
            jdbc.update("UPDATE sc_supply_chain_order SET order_status=50 WHERE id=?", orderId); rejected.accept("OP_INVALID_NEXT");
            jdbc.update("UPDATE sc_supply_chain_order SET order_status=10 WHERE id=?", orderId);
            var stale = new RechargeFrequencyExecutionPermit(permit.queueId(), permit.entryId(), permit.operationNo(), permit.generation()+1, permit.entryVersion(), permit.operationVersion());
            assertThatThrownBy(() -> lifecycle.confirmTrustedRejectedAndContinue(ops.getFirst(), observedRejected, "STALE", stale, "OP_INVALID_NEXT")).isInstanceOf(IllegalStateException.class);
            long priorRisk = RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc, "sc_fulfillment_operation", nextId,
                    java.util.Map.of("operation_no", "OP_PRIOR_RISK", "sequence_no", 3, "idempotency_key", "PRIOR_RISK", "status", 30, "attempt_count", 1, "result_class", "RESULT_UNKNOWN"));
            rejected.accept("OP_INVALID_NEXT");
            jdbc.update("DELETE FROM sc_fulfillment_operation WHERE id=?", priorRisk);
            assertThat(jdbc.queryForMap("SELECT id,platform_spu_id,holder_entry_id,occupancy_generation,acquired_at,minimum_hold_until,wake_generation,version,next_check_at FROM sc_recharge_frequency_queue")).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT status FROM sc_fulfillment_operation WHERE id=?", Short.class, oldId)).isEqualTo((short)20);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_raw_status_observation", Long.class)).isZero();
            assertThat(ledger(jdbc, "RELEASE")).isZero();
            // 同样的合法待执行种子可由真实可信结果推进，证明拒绝来自前置风险校验。
            lifecycle.confirmTrustedRejectedAndContinue(ops.getFirst(), observedRejected, "VALID", permit, "OP_INVALID_NEXT");
            assertThatThrownBy(() -> lifecycle.confirmTrustedRejectedAndContinue(ops.getFirst(), observedRejected, "REPLAY-CONTINUE", permit, "OP_INVALID_NEXT")).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_raw_status_observation", Long.class)).isEqualTo(1L);
        } finally { fixture.stopContainer(); }
    }

    @Test void concurrentRepeatedTrustedObservationNeverRollsBackOnUniqueKey() throws Exception {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(RechargeFrequencyExecutionIntegrationTest.ExecutionConfiguration.class, ObservationRaceConfiguration.class)) {
            fixture.seed(context); context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("OBSERVATION-RACE", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var op = RechargeFrequencyExecutionIntegrationTest.operations(jdbc).getFirst();
            var frequency = context.getBean(RechargeFrequencyLifecycle.class); var permit = frequency.claim(op); frequency.markSendPossible(permit);
            var hook = context.getBean(ObservationPreReadBarrier.class); hook.armed = true;
            var lifecycle = context.getBean(SupplierOrderLifecycle.class); var fact = observedSuccess();
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var one = pool.submit(() -> lifecycle.confirmTrustedOutcome(op, fact, "RACE-ONE", permit));
                var two = pool.submit(() -> lifecycle.confirmTrustedOutcome(op, fact, "RACE-TWO", permit));
                assertThat(one.get(20, java.util.concurrent.TimeUnit.SECONDS).isTerminal()).isTrue();
                assertThat(two.get(20, java.util.concurrent.TimeUnit.SECONDS).isTerminal()).isTrue();
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_raw_status_observation", Long.class)).isEqualTo(1L);
            assertThat(ledger(jdbc, "COMMIT")).isEqualTo(1L);
        } finally { fixture.stopContainer(); }
    }
    @Test void differentOperationsCanInsertFirstObservationsInParallelWithoutGapDeadlock() throws Exception {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(RechargeFrequencyExecutionIntegrationTest.ExecutionConfiguration.class, ObservationRaceConfiguration.class)) {
            fixture.seed(context); var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("FIRST-OBS-ONE", 1L));
            var other = FulfillmentAcceptanceIntegrationTest.request("FIRST-OBS-TWO", 1L);
            other.getTarget().setValues(java.util.Map.of("accountType", "MOBILE", "accountValue", "13700137000")); facade.acceptFulfillment(other);
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var ops = RechargeFrequencyExecutionIntegrationTest.operations(jdbc);
            var frequency = context.getBean(RechargeFrequencyLifecycle.class);
            var onePermit = frequency.claim(ops.getFirst()); frequency.markSendPossible(onePermit);
            var twoPermit = frequency.claim(ops.getLast()); frequency.markSendPossible(twoPermit);
            context.getBean(ObservationPreReadBarrier.class).armed = true;
            var lifecycle = context.getBean(SupplierOrderLifecycle.class);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var one = pool.submit(() -> lifecycle.confirmTrustedOutcome(ops.getFirst(), observedSuccess(), "FIRST-ONE", onePermit));
                var two = pool.submit(() -> lifecycle.confirmTrustedOutcome(ops.getLast(), observedSuccess(), "FIRST-TWO", twoPermit));
                assertThat(one.get(20, java.util.concurrent.TimeUnit.SECONDS).isTerminal()).isTrue();
                assertThat(two.get(20, java.util.concurrent.TimeUnit.SECONDS).isTerminal()).isTrue();
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_raw_status_observation", Long.class)).isEqualTo(2L);
            assertThat(ledger(jdbc, "COMMIT")).isEqualTo(2L);
        } finally { fixture.stopContainer(); }
    }

    @Test void unrelatedObservationUniqueCollisionRollsBackCompleteResultTransaction() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(RechargeFrequencyExecutionIntegrationTest.ExecutionConfiguration.class, ObservationNumberCollisionConfiguration.class)) {
            fixture.seed(context); var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("OBS-NO-ONE", 1L));
            var other = FulfillmentAcceptanceIntegrationTest.request("OBS-NO-TWO", 1L);
            other.getTarget().setValues(java.util.Map.of("accountType", "MOBILE", "accountValue", "13700137000")); facade.acceptFulfillment(other);
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var ops = RechargeFrequencyExecutionIntegrationTest.operations(jdbc); var frequency = context.getBean(RechargeFrequencyLifecycle.class);
            var one = frequency.claim(ops.getFirst()); frequency.markSendPossible(one);
            var two = frequency.claim(ops.getLast()); frequency.markSendPossible(two);
            context.getBean(ObservationNumberCollisionHook.class).armed = true;
            var lifecycle = context.getBean(SupplierOrderLifecycle.class);
            lifecycle.confirmTrustedOutcome(ops.getFirst(), observedSuccess(), "COLLISION-ONE", one);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> lifecycle.confirmTrustedOutcome(ops.getLast(), observedSuccess(), "COLLISION-TWO", two))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM sc_fulfillment_operation WHERE operation_no=?", Short.class, ops.getLast())).isEqualTo((short)20);
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM sc_fulfillment_operation WHERE operation_no=?", Integer.class, ops.getLast())).isZero();
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue WHERE id=?", Long.class, two.queueId())).isEqualTo(two.entryId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_raw_status_observation", Long.class)).isEqualTo(1L);
            assertThat(ledger(jdbc, "COMMIT")).isEqualTo(1L);
        } finally { fixture.stopContainer(); }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class ObservationNumberCollisionConfiguration {
        @org.springframework.context.annotation.Bean static ObservationNumberCollisionHook collisionHook() { return new ObservationNumberCollisionHook(); }
    }
    /** 只注入业务编号碰撞，其他生成器/SQL/资金服务仍执行真实实现。 */
    static class ObservationNumberCollisionHook implements org.springframework.beans.factory.config.BeanPostProcessor {
        volatile boolean armed;
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator generator)) { return bean; }
            var wrapped = org.mockito.Mockito.spy(generator);
            org.mockito.Mockito.doAnswer(invocation -> armed && com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes.OBS.equals(invocation.getArgument(0))
                    ? "OBS_COLLISION" : invocation.callRealMethod()).when(wrapped).next(org.mockito.ArgumentMatchers.anyString());
            return wrapped;
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class ObservationRaceConfiguration {
        @org.springframework.context.annotation.Bean static ObservationPreReadBarrier observationBarrier() { return new ObservationPreReadBarrier(); }
    }
    /** 让两个真实RR结果事务在定位预读时形成旧snapshot，再竞争同一QUEUE，验证去重必须用current-read。 */
    static class ObservationPreReadBarrier implements org.springframework.beans.factory.config.BeanPostProcessor {
        volatile boolean armed; final java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper mapper)) { return bean; }
            var wrapped = org.mockito.Mockito.spy(mapper);
            org.mockito.Mockito.doAnswer(invocation -> {
                var result = invocation.callRealMethod();
                if (armed && reads.incrementAndGet() <= 2) { barrier.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                return result;
            }).when(wrapped).selectOne(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class));
            return wrapped;
        }
    }

    static OperationOutcome fact(OperationOutcomeClass result) { return OperationOutcome.builder().outcomeClass(result).build(); }
    static OperationOutcome observedSuccess() { return OperationOutcome.builder().outcomeClass(OperationOutcomeClass.SUCCEEDED)
            .rawObservation(RawObservationFact.builder().sourceType("QUERY_RESPONSE").externalStatus("SUCCESS").payloadDigest(new byte[32]).build()).build(); }
    static long ledger(JdbcTemplate jdbc, String type) { return jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type=?", Long.class, type); }
}
