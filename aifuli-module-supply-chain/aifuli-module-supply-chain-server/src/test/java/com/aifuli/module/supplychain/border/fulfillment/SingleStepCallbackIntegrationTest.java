package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentCommandFacade;
import com.aifuli.module.supplychain.border.fulfillment.application.singlestep.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.SingleStepSupplierFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;

import static org.assertj.core.api.Assertions.*;

/** 内部认证后回调入口的真实MySQL合同；不冒充三家HTTP认证或ACK验收。 */
class SingleStepCallbackIntegrationTest {
    private final SingleStepFrozenConfigurationIntegrationTest fixture = new SingleStepFrozenConfigurationIntegrationTest();

    @BeforeEach void start() { fixture.startWith(SingleStepRecoveryIntegrationTest.ExecutionConfig.class, CallbackConfig.class); }
    @AfterEach void stop() { fixture.stop(); }

    @Test void callbackBeforeCreateResponseCommitsOriginalFundsWithoutQueryPermit() {
        create();
        service().handle(callback(SingleStepSupplierFact.Observation.SUCCESS, 884L, false), "early-callback");
        assertThat(fixture.jdbc.queryForObject("SELECT committed_amount FROM sc_fund_reservation", Long.class)).isEqualTo(1000);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type='COMMIT'", Long.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_inbox_event WHERE status=20", Long.class)).isEqualTo(1);
        assertThat(operation().getRecoveryCount()).isZero();
        assertThat(operation().getExecutionGeneration()).isEqualTo(1);
        assertThat(runtime().successEvidence().fact().source()).isEqualTo(SingleStepSupplierFact.Source.CALLBACK);
    }

    @Test void changedTransmissionTimeAndConcurrentReplaysConsumeOneEventAndOneCommit() throws Exception {
        create();
        var first = callback(SingleStepSupplierFact.Observation.SUCCESS, 900L, false);
        race(() -> service().handle(first, "concurrent-replay"));
        clock().fixed = clock().now().plusHours(12);
        var replay = callback(SingleStepSupplierFact.Observation.SUCCESS, 900L, false);
        byte[] changedTransportDigest = new byte[32];
        java.util.Arrays.fill(changedTransportDigest, (byte) 7);
        service().handle(new SingleStepSupplierFact(replay.binding(), replay.source(), replay.billno(), replay.targetDigest(), replay.quantity(),
                replay.verification(), replay.observation(), replay.externalOrderNo(), replay.receivedAt(), changedTransportDigest,
                replay.reportedSettlementAmount(), replay.zeroDeliveryConfirmed()), "new-timestamp-and-transport-digest");
        assertThat(count("sc_inbox_event")).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_raw_status_observation WHERE source_type='CALLBACK'", Long.class)).isEqualTo(1);
        assertThat(commits()).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT settlement_status FROM sc_b_order", Short.class)).isEqualTo((short) 20);
        assertThat(fixture.jdbc.queryForObject("SELECT confirmed_supplier_settlement_amount FROM sc_b_order", Long.class)).isNull();
        assertThat(count("sc_fulfillment_exception_case")).isZero();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void sameEventDifferentAmountIsDurableConflictEvenBeforeFirstInboxCommit(boolean committed) {
        create();
        var first = callback(SingleStepSupplierFact.Observation.SUCCESS, 884L, false);
        byte[] firstInboxDigest = null;
        if (committed) service().handle(first, "first");
        else {
            fixture.jdbc.execute("ALTER TABLE sc_account_ledger ADD CONSTRAINT fail_callback CHECK (entry_type<>'COMMIT')");
            assertThatThrownBy(() -> service().handle(first, "first-failed")).isInstanceOf(RuntimeException.class);
            fixture.jdbc.execute("ALTER TABLE sc_account_ledger DROP CHECK fail_callback");
            assertThat(count("sc_inbox_event")).isZero();
        }
        if (committed) firstInboxDigest = fixture.jdbc.queryForObject("SELECT payload_digest FROM sc_inbox_event", byte[].class);
        service().handle(callback(SingleStepSupplierFact.Observation.SUCCESS, 1100L, false), "different-amount");
        service().handle(first, "original-replay");
        assertThat(count("sc_inbox_event")).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT status FROM sc_inbox_event", Short.class)).isEqualTo((short) 90);
        assertThat(fixture.jdbc.queryForObject("SELECT last_error_code FROM sc_inbox_event", String.class)).isEqualTo("SINGLE_STEP_SECURITY_CONFLICT");
        if (committed) assertThat(fixture.jdbc.queryForObject("SELECT payload_digest FROM sc_inbox_event", byte[].class)).containsExactly(firstInboxDigest);
        assertThat(fixture.jdbc.queryForObject("SELECT reason_code FROM sc_fulfillment_exception_case", String.class)).isEqualTo("SINGLE_STEP_SECURITY_CONFLICT");
        assertThat(runtime().successEvidence().fact().reportedSettlementAmount()).isEqualTo(884);
        if (committed) assertThat(commits()).isEqualTo(1); else held();
    }

    @ParameterizedTest @ValueSource(strings = {"COMMIT", "OUTBOX", "INBOX"})
    void resultFailureRollsBackInboxOrderFundsButKeepsSuccessThenRestartReplaySettles(String fault) throws Exception {
        create();
        var fact = callback(SingleStepSupplierFact.Observation.SUCCESS, null, false);
        String table = switch (fault) { case "COMMIT" -> "sc_account_ledger"; case "OUTBOX" -> "sc_outbox_event"; default -> "sc_inbox_event"; };
        String condition = switch (fault) {
            case "COMMIT" -> "entry_type<>'COMMIT'";
            case "OUTBOX" -> "NOT (event_type='SUPPLY_CHAIN_ORDER_CHANGED' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.orderStatus'))='SUCCEEDED')";
            default -> "status<>20";
        };
        fixture.jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT fail_callback CHECK (" + condition + ")");
        assertThatThrownBy(() -> service().handle(fact, "result-failure")).isInstanceOf(RuntimeException.class);
        held();
        assertThat(count("sc_inbox_event")).isZero();
        assertThat(runtime().successEvidence()).isNotNull();
        assertThat(runtime().terminalEvidence()).isNull();
        assertThat(fixture.jdbc.queryForObject("SELECT order_status FROM sc_supply_chain_order", Short.class)).isEqualTo((short) 10);
        assertThat(terminalEvents()).isZero();
        fixture.jdbc.execute("ALTER TABLE " + table + " DROP CHECK fail_callback");
        fixture.reopenWith(SingleStepRecoveryIntegrationTest.ExecutionConfig.class, CallbackConfig.class);
        service().handle(fact, "replay-after-restart");
        service().handle(fact, "ack-lost-replay");
        assertThat(commits()).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT committed_amount FROM sc_fund_reservation", Long.class)).isEqualTo(1000);
        assertThat(fixture.jdbc.queryForObject("SELECT status FROM sc_inbox_event", Short.class)).isEqualTo((short) 20);
        assertThat(fixture.jdbc.queryForObject("SELECT settlement_status FROM sc_b_order", Short.class)).isZero();
        assertThat(terminalEvents()).isEqualTo(1);
        var executor = fixture.context.getBean(SingleStepRecoveryIntegrationTest.TestExecutor.class);
        assertThat(executor.creates.get() + executor.queries.get()).isZero();
    }

    @Test void manualAfterDeadlineAllowsVerifiedZeroDeliveryCallbackWithoutQueryPermit() {
        create();
        clock().fixed = operation().getRecoveryDeadlineAt();
        lifecycle().watchdog(operation().getOperationNo(), 1, "deadline");
        held();
        service().handle(callback(SingleStepSupplierFact.Observation.FAILURE, null, true), "late-failure");
        service().handle(callback(SingleStepSupplierFact.Observation.FAILURE, null, true), "repeat-late-failure");
        assertThat(fixture.jdbc.queryForObject("SELECT released_amount FROM sc_fund_reservation", Long.class)).isEqualTo(1000);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type='RELEASE'", Long.class)).isEqualTo(1);
        assertThat(operation().getRecoveryCount()).isZero();
    }

    @Test void nonterminalCallbacksPreserveCurrentQueryLeaseBudgetAndNotFoundCounter() {
        var created = create();
        lifecycle().networkUnknown(created, "unknown-create");
        clock().fixed = operation().getNextRecoverAt();
        var query = lifecycle().claim(operation().getOperationNo(), SingleStepAction.QUERY, 1, runtime().frozen().binding(), "query");
        var lease = operation().getExecutionLeaseUntil();
        service().handle(callback(SingleStepSupplierFact.Observation.PROCESSING, null, false), "processing-callback");
        service().handle(callback(SingleStepSupplierFact.Observation.RESPONSE_UNKNOWN, null, false), "unknown-callback");
        assertThat(operation().getExecutionGeneration()).isEqualTo(query.generation());
        assertThat(operation().getExecutionLeaseUntil()).isEqualTo(lease);
        assertThat(operation().getRecoveryCount()).isEqualTo(1);
        assertThat(operation().getNextRecoverAt()).isNull();
        assertThat(runtime().progress().trustedExists()).isTrue();
        held();
    }

    @ParameterizedTest @ValueSource(strings = {"AUTH", "TARGET", "AMOUNT", "NOT_FOUND", "NO_MARKER"})
    void invalidCallbackCannotConsumeInboxOrMoveFunds(String fault) {
        create();
        var original = callback(SingleStepSupplierFact.Observation.SUCCESS, 884L, false);
        if ("NO_MARKER".equals(fault)) fixture.jdbc.update("UPDATE sc_fulfillment_operation SET business_send_possible_at=NULL");
        var invalid = new SingleStepSupplierFact(original.binding(), original.source(), original.billno(),
                "TARGET".equals(fault) ? "f".repeat(64) : original.targetDigest(), 1,
                "AUTH".equals(fault) ? SingleStepSupplierFact.Verification.AUTHENTICATION_FAILED : original.verification(),
                "NOT_FOUND".equals(fault) ? SingleStepSupplierFact.Observation.NOT_FOUND : original.observation(),
                original.externalOrderNo(), original.receivedAt(), original.payloadDigest(), "AMOUNT".equals(fault) ? -1L : 884L, false);
        assertThatThrownBy(() -> service().handle(invalid, "invalid")).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("sc_inbox_event")).isZero();
        assertThat(runtime().successEvidence()).isNull();
        held();
    }

    @Test void callbackAndQuerySuccessRaceCommitOnceAndOppositeLateCallbackCannotReverse() throws Exception {
        var created = create();
        lifecycle().networkUnknown(created, "unknown-create");
        clock().fixed = operation().getNextRecoverAt();
        var query = lifecycle().claim(operation().getOperationNo(), SingleStepAction.QUERY, 1, runtime().frozen().binding(), "query");
        var cb = callback(SingleStepSupplierFact.Observation.SUCCESS, 884L, false);
        var queryFact = new SingleStepSupplierFact(cb.binding(), SingleStepAction.QUERY, cb.billno(), cb.targetDigest(), 1,
                cb.verification(), cb.observation(), cb.externalOrderNo(), cb.receivedAt(), new byte[32], 884L, false);
        var outcome = OperationOutcome.builder().outcomeClass(OperationOutcomeClass.SUCCEEDED).reportedSettlementAmount(884L).protocolFact(queryFact).build();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); service().handle(cb, "callback"); return null; });
            var b = pool.submit(() -> { start.await(); fixture.context.getBean(SingleStepFulfillmentRunner.class).applyResult(query, outcome, "query"); return null; });
            start.countDown(); a.get(30, java.util.concurrent.TimeUnit.SECONDS); b.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        service().handle(callback(SingleStepSupplierFact.Observation.FAILURE, null, true), "opposite-after-success");
        assertThat(commits()).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT released_amount FROM sc_fund_reservation", Long.class)).isZero();
        assertThat(count("sc_fulfillment_exception_case")).isEqualTo(1);
        assertThat(terminalEvents()).isEqualTo(1);
    }

    @Test void failureAlreadyReleasedThenSuccessCallbackOnlyCreatesConflictCase() {
        create();
        service().handle(callback(SingleStepSupplierFact.Observation.FAILURE, null, true), "failure");
        service().handle(callback(SingleStepSupplierFact.Observation.SUCCESS, 884L, false), "late-success");
        assertThat(fixture.jdbc.queryForObject("SELECT released_amount FROM sc_fund_reservation", Long.class)).isEqualTo(1000);
        assertThat(commits()).isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT order_status FROM sc_supply_chain_order", Short.class)).isEqualTo((short) 40);
        assertThat(fixture.jdbc.queryForObject("SELECT reason_code FROM sc_fulfillment_exception_case", String.class)).isEqualTo("SINGLE_STEP_TERMINAL_CONFLICT");
        assertThat(runtime().successEvidence()).isNotNull();
    }

    private void race(Runnable action) throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var jobs = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> { start.await(); action.run(); return null; })).toList();
            start.countDown();
            for (var job : jobs) job.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }
    private long count(String table) { return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private long commits() { return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type='COMMIT'", Long.class); }
    private long terminalEvents() { return fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_outbox_event WHERE event_type='SUPPLY_CHAIN_ORDER_CHANGED' AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.orderStatus')) IN ('SUCCEEDED','FAILED')", Long.class); }
    private void held() {
        assertThat(fixture.jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(1000);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type IN ('COMMIT','RELEASE')", Long.class)).isZero();
    }

    private SingleStepExecutionPermit create() {
        fixture.context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("CALLBACK-ORDER"));
        var frozen = runtime().frozen();
        return lifecycle().claim(operation().getOperationNo(), SingleStepAction.CREATE, 0, frozen.binding(), "create");
    }
    private FulfillmentOperationDO operation() { return fixture.context.getBean(FulfillmentOperationMapper.class).selectList(null).getFirst(); }
    private SingleStepFulfillmentLifecycle lifecycle() { return fixture.context.getBean(SingleStepFulfillmentLifecycle.class); }
    private SingleStepCallbackApplicationService service() { return fixture.context.getBean(SingleStepCallbackApplicationService.class); }
    private SingleStepRecoveryIntegrationTest.TestClock clock() { return fixture.context.getBean(SingleStepRecoveryIntegrationTest.TestClock.class); }
    private SingleStepRuntimeCheckpoint runtime() {
        var op = operation();
        var bo = fixture.context.getBean(com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper.class).selectById(op.getBOrderId());
        var binding = new SingleStepCheckpoint.Binding(op.getOperationNo(), bo.getId(), SingleStepFrozenConfigurationIntegrationTest.TYPE,
                com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode.SEND, bo.getSupplierId(), bo.getConnectorVersionId(), bo.getOfferVersionId(), java.util.HexFormat.of().formatHex(op.getConfigDigest()));
        return fixture.context.getBean(SingleStepCheckpointCodec.class).decryptRuntime(new SingleStepCheckpointCodec.Stored(
                op.getCheckpointSchemaVersion(), op.getCheckpointKeyVersion(), op.getCheckpointCiphertext()), binding);
    }
    private SingleStepSupplierFact callback(SingleStepSupplierFact.Observation observation, Long amount, boolean zeroDelivery) {
        var frozen = runtime().frozen();
        return new SingleStepSupplierFact(frozen.binding(), SingleStepSupplierFact.Source.CALLBACK, frozen.business().billno(),
                frozen.business().protectedTargetReference().targetDigest(), 1, SingleStepSupplierFact.Verification.VERIFIED,
                observation, "supplier-callback-order", clock().now(), new byte[32], amount, zeroDelivery);
    }
    @Configuration(proxyBeanMethods = false)
    @Import(SingleStepCallbackApplicationService.class)
    static class CallbackConfig { }
}
