package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.application.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.infrastructure.security.FrozenCredentialSetResolver;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 设计 §6、§9.3：独立容器，真实 Spring 事务/Liquibase/Mapper，出站边界挡板绝不连真实供应商。 */
class FulfillmentCaseIntegrationTest {
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("case_flow");
    static ConfigurableApplicationContext context;
    FulfillmentCaseAdminService admin;
    FulfillmentCaseVerifyExecutionService verify;
    FulfillmentCaseActionMapper actions;
    FulfillmentOperationMapper operations;
    JdbcTemplate jdbc;
    FixtureData fixture;

    @BeforeAll static void start() {
        MYSQL.start();
        var key = Base64.getEncoder().encodeToString(new byte[32]);
        context = new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE).run(
                "--aifuli.supply-chain.enabled=true", "--aifuli.supply-chain.datasource.url=" + MYSQL.getJdbcUrl(),
                "--aifuli.supply-chain.datasource.username=" + MYSQL.getUsername(),
                "--aifuli.supply-chain.datasource.password=" + MYSQL.getPassword(),
                "--aifuli.supply-chain.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "--aifuli.supply-chain.security.key-version=local-v1",
                "--aifuli.supply-chain.security.envelope-key-base64=" + key,
                "--aifuli.supply-chain.security.fingerprint-key-base64=" + key);
    }
    @AfterAll static void stop() { if (context != null) context.close(); MYSQL.stop(); }
    @BeforeEach void setup() {
        admin = context.getBean(FulfillmentCaseAdminService.class);
        verify = context.getBean(FulfillmentCaseVerifyExecutionService.class);
        actions = context.getBean(FulfillmentCaseActionMapper.class);
        operations = context.getBean(FulfillmentOperationMapper.class);
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
        reset(context.getBean(HuashengOperationExecutor.class));
        context.getBean(TestClock.class).offsetSeconds = 0;
        fixture = seed();
    }

    @Test void orchestrationCommitsStartedBeforeSingleOutsideTransactionQueryAndPersistsEncryptedEvidence() {
        String action = submit();
        when(context.getBean(HuashengOperationExecutor.class).execute(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(actions.selectByActionNo(action).getExecutionStatus()).isEqualTo("STARTED");
            assertThat(java.time.Duration.between(actions.selectByActionNo(action).getUpdatedAt(),
                    actions.selectByActionNo(action).getLeaseUntil()).getSeconds()).isEqualTo(150L);
            return OperationOutcome.builder().outcomeClass(OperationOutcomeClass.ACCEPTED)
                    .protocolFact(fact(HuashengStepFact.IsTake.N)).build();
        });
        assertThat(verify.executeVerify(action)).isEqualTo("VERIFY_COMPLETED");
        assertThat(verify.executeVerify(action)).isEqualTo("ALREADY_STARTED_OR_FINISHED");
        verify(context.getBean(HuashengOperationExecutor.class), times(1)).execute(any());
        var result = actions.selectByActionNo(action);
        assertThat(result.getExecutionStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.getVerifyConclusion()).isEqualTo(FulfillmentCaseAdminService.CONCLUSION_SUPPLIER_CANCELLED);
        assertThat(result.getEvidenceKeyVersion()).isEqualTo("local-v1");
        assertThat(result.getEvidenceCiphertext()).isNotEmpty();
        assertThat(result.getActiveIntentFlag()).isNull();
    }

    @Test void missingExplicitUntakenProofDoesNotQualifyCancellation() {
        String action = submit();
        verify.markVerifyStarted(action);
        verify.completeVerify(action, fact(HuashengStepFact.IsTake.ABSENT));
        assertThat(actions.selectByActionNo(action).getVerifyConclusion()).isEqualTo(FulfillmentCaseAdminService.CONCLUSION_REFUND_PENDING);
        assertThatThrownBy(() -> confirm(action)).hasMessageContaining("does not qualify");
    }

    @Test void expiryRejectsLateCompletionAndInvalidatesOwnGeneration() {
        String action = submit(); verify.markVerifyStarted(action);
        long generation = actions.selectByActionNo(action).getActualGeneration();
        jdbc.update("UPDATE sc_fulfillment_case_action SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE action_no=?", action);
        verify.completeVerify(action, fact(HuashengStepFact.IsTake.N));
        assertThat(actions.selectByActionNo(action).getExecutionStatus()).isEqualTo("UNKNOWN");
        assertThat(operations.selectById(fixture.operation.getId()).getExecutionGeneration()).isEqualTo(generation + 1);
        verify.completeVerify(action, fact(HuashengStepFact.IsTake.N));
        assertThat(actions.selectByActionNo(action).getEvidenceCiphertext()).isNotEmpty();
        assertThat(actions.selectByActionNo(action).getFreshUntil()).isNull();
    }

    @Test void watchdogClearsActiveIntentAtLeaseAndIsIdempotent() {
        String action = submit(); verify.markVerifyStarted(action);
        assertThat(verify.finalizeExpiredVerify(action)).isEqualTo("LEASE_NOT_EXPIRED");
        jdbc.update("UPDATE sc_fulfillment_case_action SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE action_no=?", action);
        assertThat(verify.finalizeExpiredVerify(action)).isEqualTo("FINALIZED_UNKNOWN");
        assertThat(verify.finalizeExpiredVerify(action)).isEqualTo("SKIPPED");
        assertThat(actions.selectByActionNo(action).getActiveIntentFlag()).isNull();
    }

    @Test void replayIsIdempotentButChangedActorOrReasonAndNonManualCaseAreRejected() {
        String action = submit();
        var command = new FulfillmentCaseAdminService.VerifySubmitCommand(fixture.caseDo.getCaseNo(), action, 0L, 1L, "test", 7L);
        assertThat(admin.submitVerify(command).actionNo()).isEqualTo(action);
        assertThatThrownBy(() -> admin.submitVerify(new FulfillmentCaseAdminService.VerifySubmitCommand(
                command.caseNo(), action, 0L, 1L, "test", 8L))).hasMessageContaining("fingerprint");
        verify.markVerifyStarted(action); verify.completeVerifyUnknown(action);
        jdbc.update("UPDATE sc_b_order SET fulfillment_status=20 WHERE id=?", fixture.bOrder.getId());
        assertThatThrownBy(this::submit).hasMessageContaining("人工");
    }

    @Test void evidenceTamperAndExpiredFreshnessCannotConfirm() {
        String action = submit(); verify.markVerifyStarted(action); verify.completeVerify(action, fact(HuashengStepFact.IsTake.N));
        jdbc.update("UPDATE sc_fulfillment_case_action SET fresh_until=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE action_no=?", action);
        assertThatThrownBy(() -> confirm(action)).hasMessageContaining("fresh");
        jdbc.update("UPDATE sc_fulfillment_case_action SET fresh_until=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE), evidence_ciphertext=X'00' WHERE action_no=?", action);
        assertThatThrownBy(() -> confirm(action)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_case_action WHERE command_type='CONFIRM_FAILURE' AND case_id=?", Long.class, fixture.caseDo.getId())).isZero();
    }

    @Test void cancellationConfirmationClosesRealAggregateCaseAndReleasesExactlyOnce() throws Exception {
        String action = submit(); verify.markVerifyStarted(action); verify.completeVerify(action, fact(HuashengStepFact.IsTake.N));
        var command = new FulfillmentCaseAdminService.ConfirmFailureCommand(fixture.caseDo.getCaseNo(), id(), action,
                0L, actions.selectByActionNo(action).getActualGeneration(), "真实资金验证", 7L);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return admin.confirmFailure(command); });
            var second = pool.submit(() -> { start.await(); return admin.confirmFailure(command); });
            start.countDown();
            assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS).executionStatus()).isEqualTo("SUCCEEDED");
            assertThat(second.get(20, java.util.concurrent.TimeUnit.SECONDS).executionStatus()).isEqualTo("SUCCEEDED");
        }
        assertThat(admin.confirmFailure(command).executionStatus()).isEqualTo("SUCCEEDED");
        var b = context.getBean(BOrderMapper.class).selectById(fixture.bOrder.getId());
        assertThat(b.getFulfillmentStatus()).isEqualTo((short) 60);
        assertThat(b.getReportedSupplierSettlementAmount()).isEqualTo(20000L);
        assertThat(b.getExternalOrderNo()).isEqualTo(fixture.bOrder.getBillno());
        var c = context.getBean(FulfillmentExceptionCaseMapper.class).selectById(fixture.caseDo.getId());
        assertThat(c.getOperatorId()).isEqualTo(7L);
        assertThat(c.getActiveCaseFlag()).isNull();
        assertThat(c.getResolutionAction()).isEqualTo("CONFIRM_FAILURE");
        assertThat(jdbc.queryForObject("SELECT released_amount FROM sc_fund_reservation WHERE reference_no=?", Long.class, fixture.request.getRequestNo())).isEqualTo(9600L);
        assertThat(jdbc.queryForObject("SELECT remaining_amount FROM sc_fund_reservation WHERE reference_no=?", Long.class, fixture.request.getRequestNo())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE reference_no=? AND entry_type='RELEASE'",
                Long.class, fixture.request.getRequestNo())).isEqualTo(1L);
    }

    @Test void lockedReservationCheckHoldsUntilReleaseAndPreventsConcurrentCommit() throws Exception {
        var funds = context.getBean(com.aifuli.module.supplychain.tradecore.account.application.FundReservationService.class);
        String orderNo = context.getBean(SupplyChainOrderMapper.class).selectById(fixture.bOrder.getOrderId()).getOrderNo();
        var tx = new org.springframework.transaction.support.TransactionTemplate(context.getBean(
                "supplyChainTransactionManager", org.springframework.transaction.PlatformTransactionManager.class));
        assertThatThrownBy(() -> funds.lockSnapshotByReference(fixture.request.getBuyerId(), "CARD_ORDER", fixture.request.getRequestNo()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var started = new java.util.concurrent.CountDownLatch(1);
            var commit = tx.execute(status -> {
                var snapshot = funds.lockSnapshotByReference(fixture.request.getBuyerId(), "CARD_ORDER", fixture.request.getRequestNo());
                assertThat(snapshot.remainingAmount()).isEqualTo(9600L);
                var future = executor.submit(() -> {
                    started.countDown();
                    return funds.commitByReference(fixture.request.getBuyerId(), "CARD_ORDER", fixture.request.getRequestNo(),
                            orderNo, "fixture-commit", "fixture-commit");
                });
                try {
                    assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> future.get(300, java.util.concurrent.TimeUnit.MILLISECONDS))
                            .isInstanceOf(java.util.concurrent.TimeoutException.class);
                    funds.releaseByReference(fixture.request.getBuyerId(), "CARD_ORDER", fixture.request.getRequestNo(),
                            orderNo, "fixture-release", "fixture-release");
                    return future;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            assertThatThrownBy(() -> commit.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
        var released = funds.findSnapshotByReference(fixture.request.getBuyerId(), "CARD_ORDER", fixture.request.getRequestNo());
        assertThat(released.committedAmount()).isZero();
        assertThat(released.releasedAmount()).isEqualTo(9600L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE reference_no=? AND entry_type IN ('COMMIT','RELEASE')",
                Long.class, fixture.request.getRequestNo())).isEqualTo(1L);
    }

    @Test void historicalSuccessWithoutDeliveredAssetStillBlocksManualFailureRelease() {
        var op = operations.selectById(fixture.operation.getId());
        var codec = context.getBean(HuashengCheckpointCodec.class);
        var binding = new HuashengCheckpoint.Binding(fixture.bOrder.getId(), fixture.bOrder.getBOrderNo(), op.getOperationNo(), 1,
                AdapterStepCode.PLACE_ORDER, 200L, 100L, op.getConfigDigest());
        var old = codec.decrypt(new HuashengCheckpointCodec.Stored(op.getCheckpointSchemaVersion(), op.getCheckpointKeyVersion(),
                op.getCheckpointCiphertext()), binding);
        var observation = new HuashengCheckpoint.Observation(
                com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint.QUERY,
                fixture.bOrder.getBillno(), fixture.bOrder.getBillno(), 1, "200.00", 20000L,
                com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengOrderStatus.SUCCESS,
                com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengField.absent(),
                com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengField.absent());
        var stored = codec.encrypt(new HuashengCheckpoint(binding, old.placeParameters(), old.orderSn(), observation, old.progress(), null));
        op.setCheckpointSchemaVersion(stored.schemaVersion()); op.setCheckpointKeyVersion(stored.keyVersion());
        op.setCheckpointCiphertext(stored.ciphertext()); operations.updateById(op);
        String action = submit(); verify.markVerifyStarted(action); verify.completeVerify(action, fact(HuashengStepFact.IsTake.N));
        assertThatThrownBy(() -> confirm(action)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT remaining_amount FROM sc_fund_reservation WHERE reference_no=?", Long.class, fixture.request.getRequestNo())).isEqualTo(9600L);
    }

    @Test void candidateQueryPersistsBackoffAndEarlyNewCommandCannotQuery() {
        candidate(0);
        String action = submit(); verify.markVerifyStarted(action); verify.completeVerify(action, notFound());
        assertThat(actions.selectByActionNo(action).getValidNotCreatedCount()).isEqualTo(1);
        String next = submit();
        assertThat(verify.markVerifyStarted(next)).isNull();
        assertThat(actions.selectByActionNo(next).getExecutionStatus()).isEqualTo("RECEIVED");
        assertThat(actions.selectByActionNo(next).getQueryStartedFlag()).isNull();
    }

    @Test void transientCandidateQueryAdvancesBackoffWithoutIncreasingValidCount() {
        var flow = context.getBean(HuashengFulfillmentLifecycle.class);
        prepareInitialRejection(flow);
        jdbc.update("UPDATE sc_b_order SET fulfillment_status=90 WHERE id=?", fixture.bOrder.getId());
        jdbc.update("UPDATE sc_fulfillment_operation SET status=90 WHERE id=?", fixture.operation.getId());
        var clock = context.getBean(TestClock.class);
        clock.offsetSeconds = 5;
        String first = submit();
        assertThat(verify.markVerifyStarted(first)).isNotNull();
        verify.completeVerify(first, new HuashengStepFact(HuashengStepFact.Endpoint.QUERY, HuashengStepFact.StepResult.RESULT_UNKNOWN,
                fixture.bOrder.getBillno(), null, 0, null, null, null, null, null, null, List.of(), 50, null, null));
        assertThat(actions.selectByActionNo(first).getValidNotCreatedCount()).isZero();
        String second = submit();
        clock.offsetSeconds += 5;
        assertThat(verify.markVerifyStarted(second)).isNull();
        clock.offsetSeconds += 10;
        assertThat(verify.markVerifyStarted(second)).isNotNull();
        verify.completeVerifyUnknown(second);
        verify.completeVerifyUnknown(second);
        verify.completeVerify(second, notFound());
        assertThat(actions.selectByActionNo(second).getExecutionStatus()).isEqualTo("UNKNOWN");
        String third = submit();
        clock.offsetSeconds += 15;
        assertThat(verify.markVerifyStarted(third)).isNull();
        clock.offsetSeconds += 15;
        assertThat(verify.markVerifyStarted(third)).isNotNull();
    }

    @Test void successCallbackOnConflictCaseInvalidatesOldVerificationAndBlocksFutureRelease() {
        String old = submit(); verify.markVerifyStarted(old); verify.completeVerify(old, fact(HuashengStepFact.IsTake.N));
        long generation = actions.selectByActionNo(old).getActualGeneration();
        jdbc.update("UPDATE sc_fulfillment_operation SET business_send_possible_at=CURRENT_TIMESTAMP(3) WHERE id=?", fixture.operation.getId());
        var callback = new HuashengStepFact(HuashengStepFact.Endpoint.CALLBACK, HuashengStepFact.StepResult.ACCEPTED,
                fixture.bOrder.getBillno(), fixture.bOrder.getBillno(), 1, "200.00", 20000L, HuashengStepFact.Status.SUCCESS,
                HuashengStepFact.RefundStatus.ABSENT, HuashengStepFact.IsTake.Y, null, List.of());
        context.getBean(HuashengFulfillmentLifecycle.class).applyCallback(fixture.bOrder.getId(), callback, "callback");
        assertThat(context.getBean(BOrderMapper.class).selectById(fixture.bOrder.getId()).getFulfillmentStatus())
                .isEqualTo(com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus.SUCCEEDED.getCode());
        assertThat(operations.selectById(fixture.operation.getId()).getExecutionGeneration()).isGreaterThan(generation);
        assertThat(admin.getCaseDetail(fixture.caseDo.getCaseNo()).canConfirmFailure()).isFalse();
        assertThatThrownBy(() -> confirm(old)).isInstanceOf(IllegalStateException.class);
        // 供应商已成功，不再具备人工失败核实资格；不能用新命令重新取得释放入口。
        assertThatThrownBy(this::submit).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT remaining_amount FROM sc_fund_reservation WHERE reference_no=?", Long.class, fixture.request.getRequestNo())).isEqualTo(9600L);
    }

    @Test void concurrentSameActionReturnsOnePersistedCommand() throws Exception {
        var command = new FulfillmentCaseAdminService.VerifySubmitCommand(fixture.caseDo.getCaseNo(), id(), 0L, 1L, "test", 7L);
        var ready = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { ready.await(); return admin.submitVerify(command); });
            var second = pool.submit(() -> { ready.await(); return admin.submitVerify(command); });
            ready.countDown();
            assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS).actionNo()).isEqualTo(command.actionNo());
            assertThat(second.get(20, java.util.concurrent.TimeUnit.SECONDS).actionNo()).isEqualTo(command.actionNo());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_case_action WHERE case_id=?", Long.class, fixture.caseDo.getId())).isEqualTo(1);
    }

    @Test void caseAuditWriteFailureRollsBackClosureAndRealFundRelease() {
        String action = submit(); verify.markVerifyStarted(action); verify.completeVerify(action, fact(HuashengStepFact.IsTake.N));
        jdbc.execute("ALTER TABLE sc_fulfillment_exception_case ADD CONSTRAINT ck_case_fixture_rollback CHECK (case_no <> '"
                + fixture.caseDo.getCaseNo() + "' OR resolution_action IS NULL)");
        try {
            assertThatThrownBy(() -> confirm(action)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(context.getBean(BOrderMapper.class).selectById(fixture.bOrder.getId()).getFulfillmentStatus()).isEqualTo((short) 90);
            assertThat(jdbc.queryForObject("SELECT remaining_amount FROM sc_fund_reservation WHERE reference_no=?", Long.class, fixture.request.getRequestNo())).isEqualTo(9600L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_case_action WHERE case_id=? AND command_type='CONFIRM_FAILURE'", Long.class, fixture.caseDo.getId())).isZero();
        } finally { jdbc.execute("ALTER TABLE sc_fulfillment_exception_case DROP CHECK ck_case_fixture_rollback"); }
    }

    @Test void fourthNotCreatedQueryIsPersistedButNeverAutomaticallyReleases() {
        candidate(3);
        String action = submit(); verify.markVerifyStarted(action); verify.completeVerify(action, notFound());
        assertThat(actions.selectByActionNo(action).getValidNotCreatedCount()).isEqualTo(4);
        assertThat(actions.selectByActionNo(action).getVerifyConclusion()).isEqualTo(FulfillmentCaseAdminService.CONCLUSION_NOT_CREATED);
        assertThat(jdbc.queryForObject("SELECT remaining_amount FROM sc_fund_reservation WHERE reference_no=?", Long.class, fixture.request.getRequestNo())).isEqualTo(9600L);
        assertThatThrownBy(() -> confirm(action)).isInstanceOf(IllegalStateException.class);
    }

    @Test void fourPersistedQueriesAfterInitialRejectionCloseAutomaticallyAndReleaseOnce() {
        var flow = context.getBean(HuashengFulfillmentLifecycle.class);
        prepareInitialRejection(flow);
        int[] delays = {5, 15, 30, 60};
        for (int i = 0; i < delays.length; i++) {
            assertThat(flow.claimForRecovery(fixture.operation.getOperationNo())).isNull();
            context.getBean(TestClock.class).offsetSeconds += delays[i];
            var permit = flow.claimForRecovery(fixture.operation.getOperationNo());
            assertThat(permit).isNotNull();
            var outcome = OperationOutcome.builder().outcomeClass(OperationOutcomeClass.RESULT_UNKNOWN).protocolFact(notFound()).build();
            var result = flow.apply(permit, outcome, "fixture-query");
            assertThat(result.decision()).isEqualTo(i == 3 ? HuashengFlowDecision.FINAL_NOT_CREATED : HuashengFlowDecision.WAIT_QUERY);
            assertThat(flow.apply(permit, outcome, "replay").applied()).isFalse();
        }
        var b = context.getBean(BOrderMapper.class).selectById(fixture.bOrder.getId());
        assertThat(b.getFulfillmentStatus()).isEqualTo((short) 50);
        assertThat(b.getTerminalReasonCode()).isEqualTo("HUASHENG_NOT_CREATED");
        assertThat(b.getActiveFlag()).isNull();
        assertThat(operations.selectById(fixture.operation.getId()).getStatus()).isEqualTo(SupplierOrderOperationStatus.FAILED.getCode());
        assertThat(context.getBean(FulfillmentRequestMapper.class).selectById(fixture.request.getId()).getStatus()).isEqualTo(FulfillmentRequestStatus.FAILED.getCode());
        assertThat(jdbc.queryForObject("SELECT remaining_amount FROM sc_fund_reservation WHERE reference_no=?", Long.class, fixture.request.getRequestNo())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE reference_no=? AND entry_type='RELEASE'", Long.class, fixture.request.getRequestNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_call_log WHERE b_order_id=? AND evidence_ciphertext IS NOT NULL", Long.class, fixture.bOrder.getId())).isEqualTo(5);
    }

    @Test void transientQueryDoesNotCountAndFourManualQueriesOnlyReleaseAfterConfirmation() {
        var flow = context.getBean(HuashengFulfillmentLifecycle.class);
        prepareInitialRejection(flow);
        jdbc.update("UPDATE sc_fulfillment_operation SET status=90 WHERE id=?", fixture.operation.getId());
        jdbc.update("UPDATE sc_b_order SET fulfillment_status=90 WHERE id=?", fixture.bOrder.getId());
        jdbc.update("UPDATE sc_fulfillment_request SET status=90 WHERE id=?", fixture.request.getId());
        int[] delays = {5, 15, 30, 60, 60};
        String last = null;
        for (int i = 0; i < delays.length; i++) {
            context.getBean(TestClock.class).offsetSeconds += delays[i];
            last = submit();
            assertThat(verify.markVerifyStarted(last)).isNotNull();
            var fact = i == 0 ? new HuashengStepFact(HuashengStepFact.Endpoint.QUERY, HuashengStepFact.StepResult.RESULT_UNKNOWN,
                    fixture.bOrder.getBillno(), null, 0, null, null, null, null, null, null, List.of(), 50, null, null) : notFound();
            verify.completeVerify(last, fact);
            assertThat(actions.selectByActionNo(last).getValidNotCreatedCount()).isEqualTo(i);
            assertThat(jdbc.queryForObject("SELECT remaining_amount FROM sc_fund_reservation WHERE reference_no=?", Long.class, fixture.request.getRequestNo())).isEqualTo(9600L);
        }
        confirm(last);
        assertThat(context.getBean(BOrderMapper.class).selectById(fixture.bOrder.getId()).getTerminalReasonCode()).isEqualTo("HUASHENG_NOT_CREATED");
        assertThat(jdbc.queryForObject("SELECT released_amount FROM sc_fund_reservation WHERE reference_no=?", Long.class, fixture.request.getRequestNo())).isEqualTo(9600L);
    }

    void prepareInitialRejection(HuashengFulfillmentLifecycle flow) {
        candidate(0);
        var op = operations.selectById(fixture.operation.getId());
        var binding = new HuashengCheckpoint.Binding(fixture.bOrder.getId(), fixture.bOrder.getBOrderNo(), op.getOperationNo(), 1,
                AdapterStepCode.PLACE_ORDER, 200L, 100L, op.getConfigDigest());
        var codec = context.getBean(HuashengCheckpointCodec.class);
        var cp = codec.decrypt(new HuashengCheckpointCodec.Stored(op.getCheckpointSchemaVersion(), op.getCheckpointKeyVersion(), op.getCheckpointCiphertext()), binding);
        var stored = codec.encrypt(new HuashengCheckpoint(binding, cp.placeParameters(), null, null, cp.progress(), null));
        op.setCheckpointSchemaVersion(stored.schemaVersion()); op.setCheckpointKeyVersion(stored.keyVersion()); op.setCheckpointCiphertext(stored.ciphertext());
        op.setStatus((short) 0); operations.updateById(op);
        jdbc.update("UPDATE sc_b_order SET fulfillment_status=10 WHERE id=?", fixture.bOrder.getId());
        var permit = flow.claimForExecution(op.getOperationNo());
        var fact = new HuashengStepFact(HuashengStepFact.Endpoint.PLACE, HuashengStepFact.StepResult.RESULT_UNKNOWN,
                fixture.bOrder.getBillno(), null, 1, null, null, null, null, null, null,
                List.of(new HuashengStepFact.AttemptObservation("PLACE", 200, 7010, 1, 1, 1, 1, true)), 7010, null, null);
        assertThat(flow.apply(permit, OperationOutcome.builder().outcomeClass(OperationOutcomeClass.RESULT_UNKNOWN).protocolFact(fact).build(), "fixture-reject").decision())
                .isEqualTo(HuashengFlowDecision.WAIT_QUERY);
    }

    void candidate(int count) {
        var op = operations.selectById(fixture.operation.getId());
        var binding = new HuashengCheckpoint.Binding(fixture.bOrder.getId(), fixture.bOrder.getBOrderNo(), op.getOperationNo(), 1,
                AdapterStepCode.PLACE_ORDER, 200L, 100L, new byte[32]);
        var now = context.getBean(SupplyChainDatabaseClock.class).now().withNano(0);
        var cp = new HuashengCheckpoint(binding, new HuashengCheckpoint.PlaceParameters(fixture.bOrder.getBillno(), 1L, 1, 600,
                null, "https://fixture.invalid/callback"), null, null,
                new HuashengCheckpoint.Progress(0, 0, 0, null, null, "fixture"), null,
                new HuashengCheckpoint.NotCreatedCandidate(7010, now.minusMinutes(2), count,
                        count == 0 ? null : now.minusMinutes(1), now.minusSeconds(1), false));
        var stored = context.getBean(HuashengCheckpointCodec.class).encrypt(cp);
        op.setCheckpointSchemaVersion(stored.schemaVersion()); op.setCheckpointKeyVersion(stored.keyVersion());
        op.setCheckpointCiphertext(stored.ciphertext()); operations.updateById(op);
    }

    HuashengStepFact notFound() {
        return new HuashengStepFact(HuashengStepFact.Endpoint.QUERY, HuashengStepFact.StepResult.RESULT_UNKNOWN,
                fixture.bOrder.getBillno(), null, 0, null, null, null, null, null, null, List.of(), 7014, null, null);
    }

    String submit() {
        var op = operations.selectById(fixture.operation.getId());
        String action = id();
        admin.submitVerify(new FulfillmentCaseAdminService.VerifySubmitCommand(fixture.caseDo.getCaseNo(), action,
                0L, op.getExecutionGeneration(), "test", 7L));
        return action;
    }
    void confirm(String action) {
        var result = actions.selectByActionNo(action);
        admin.confirmFailure(new FulfillmentCaseAdminService.ConfirmFailureCommand(fixture.caseDo.getCaseNo(), id(), action,
                0L, result.getActualGeneration(), "test", 7L));
    }
    HuashengStepFact fact(HuashengStepFact.IsTake take) {
        return new HuashengStepFact(HuashengStepFact.Endpoint.QUERY, HuashengStepFact.StepResult.ACCEPTED,
                fixture.bOrder.getBillno(), fixture.bOrder.getBillno(), 1, "200.00", 20000L, HuashengStepFact.Status.CANCEL,
                HuashengStepFact.RefundStatus.SUCCESS, take, null, List.of());
    }
    static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    FixtureData seed() {
        LocalDateTime now = context.getBean(SupplyChainDatabaseClock.class).now();
        var buyerCode = context.getBean(com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService.class)
                .createBuyer(new com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService.CreateBuyerCommand(
                        null, "Case测试", "Case测试", null, null, "AIFULI_MALL", id(), now, 7L));
        var buyer = context.getBean(com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper.class).selectByBuyerCodeForUpdate(buyerCode);
        context.getBean(com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService.class).open(
                new com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService.OpeningBalanceCommand(
                        buyerCode, com.aifuli.module.supplychain.tradecore.account.domain.Money.cny(100000L), 7L,
                        "fixture://opening", new byte[32], "fixture", "fixture", "fixture"));
        var order = SupplyChainOrderDO.builder().orderNo(id()).orderType("CARD").buyerId(buyer.getId())
                .buyerCodeSnapshot("buyer").buyerNameSnapshot("buyer").buyerOrderNo(id()).acceptanceReceiptId(System.nanoTime())
                .platformSkuId(1L).detailType("FULFILLMENT").currency("CNY").projectId(1L).orderStatus((short) 20)
                .aftersaleStatus((short) 0).businessStage("FULFILLING").detailVersion(1L).quantity(1L).buyerSettlementAmount(9600L)
                .version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(SupplyChainOrderMapper.class).insert(order);
        var request = FulfillmentRequestDO.builder().requestNo(id()).orderId(order.getId()).buyerId(buyer.getId()).listingId(1L)
                .listingVersionId(1L).platformSkuId(1L).targetSchemaCode("MOBILE_RECHARGE").targetSchemaVersion(1)
                .targetCiphertext(new byte[]{1}).targetDigest(new byte[32]).targetKeyVersion("v1").status((short) 90)
                .requestedQuantity(1L).fulfilledQuantity(0L).aggregateVersion(0L).createdAt(now).updatedAt(now).build();
        context.getBean(FulfillmentRequestMapper.class).insert(request);
        var b = BOrderDO.builder().bOrderNo(id()).fulfillmentRequestId(request.getId()).orderId(order.getId()).attemptNo(1)
                .supplierId(1L).offerId(1L).offerVersionId(100L).connectorVersionId(200L).inventorySourceId(1L).billno(id())
                .idempotencyKey(id()).requestedQuantity(1L).fulfilledQuantity(0L).supplierSettlementUnitPrice(9500L)
                .expectedSupplierSettlementAmount(9500L).currency("CNY").fulfillmentStatus((short) 90).settlementStatus((short) 0)
                .activeFlag(true).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(BOrderMapper.class).insert(b);
        var op = FulfillmentOperationDO.builder().operationNo(id()).bOrderId(b.getId()).operationKind("FULFILL")
                .adapterStepCode("PLACE_ORDER").sequenceNo(1).idempotencyKey(id()).configDigest(new byte[32])
                .status((short) 90).attemptCount(0).executionGeneration(1L).version(0L).createdAt(now).updatedAt(now).build();
        operations.insert(op);
        var binding = new HuashengCheckpoint.Binding(b.getId(), b.getBOrderNo(), op.getOperationNo(), 1,
                AdapterStepCode.PLACE_ORDER, 200L, 100L, new byte[32]);
        var cp = new HuashengCheckpoint(binding, new HuashengCheckpoint.PlaceParameters(b.getBillno(), 1L, 1, 600,
                null, "https://fixture.invalid/callback"), b.getBillno(), null,
                new HuashengCheckpoint.Progress(0, 0, 0, null, null, "fixture"), null);
        var stored = context.getBean(HuashengCheckpointCodec.class).encrypt(cp);
        op.setCheckpointSchemaVersion(stored.schemaVersion()); op.setCheckpointKeyVersion(stored.keyVersion());
        op.setCheckpointCiphertext(stored.ciphertext()); operations.updateById(op);
        var c = FulfillmentExceptionCaseDO.builder().caseNo(id()).orderId(order.getId()).fulfillmentRequestId(request.getId())
                .bOrderId(b.getId()).operationId(op.getId()).reasonCode("SETTLEMENT_MISMATCH").status((short) 0)
                .evidenceRef("fixture").evidenceDigest(new byte[32])
                .activeCaseFlag(1).version(0L).createdAt(now).updatedAt(now).build();
        context.getBean(FulfillmentExceptionCaseMapper.class).insert(c);
        context.getBean(com.aifuli.module.supplychain.tradecore.account.application.FundReservationService.class).freeze(
                new com.aifuli.module.supplychain.tradecore.account.application.FundReservationService.FreezeCommand(
                        buyer.getId(), "CARD_ORDER", request.getRequestNo(), com.aifuli.module.supplychain.tradecore.account.domain.Money.cny(9600L),
                        order.getOrderNo(), "fixture", "fixture"));
        return new FixtureData(b, op, c, request);
    }
    record FixtureData(BOrderDO bOrder, FulfillmentOperationDO operation, FulfillmentExceptionCaseDO caseDo, FulfillmentRequestDO request) { }

    @Configuration(proxyBeanMethods = false)
    @Import({HuashengSettlementIntegrationTest.TestConfig.class, FulfillmentCaseAdminService.class, FulfillmentCaseVerifyExecutionService.class})
    static class Config {
        @Bean @Primary TestClock testClock(@org.springframework.beans.factory.annotation.Qualifier("supplyChainDataSource") DataSource source) {
            return new TestClock(source);
        }
        @Bean PublishedConnectorConfigurationResolver connectorResolver() {
            var resolver = mock(PublishedConnectorConfigurationResolver.class);
            when(resolver.resolve(any())).thenReturn(new PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration(
                    "fixture", 200L, 1, HuashengOrderV1Metadata.TYPE, "https://fixture.invalid", "fixture", 1L, "/callback", Set.of()));
            return resolver;
        }
        @Bean FrozenCredentialSetResolver credentials() {
            var resolver = mock(FrozenCredentialSetResolver.class);
            when(resolver.resolve(any(), any(), any())).thenAnswer(call -> new HashMap<>(Map.of(
                    HuashengOrderV1Metadata.APP_SECRET, "fixture".toCharArray(), HuashengOrderV1Metadata.PRIVATE_KEY, "fixture".toCharArray())));
            return resolver;
        }
        @Bean FulfillmentOperationTicketFactory tickets() {
            var factory = mock(FulfillmentOperationTicketFactory.class);
            when(factory.createForHuasheng(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(mock(OperationTicket.class));
            return factory;
        }
        @Bean HuashengOperationExecutor executor() { return mock(HuashengOperationExecutor.class); }
        @Bean(name = "huashengExecutionLock") java.util.concurrent.locks.ReentrantLock lock() { return new java.util.concurrent.locks.ReentrantLock(); }
    }
    /** 仅推进隔离夹具时间，不修改操作/证据/Timer中的权威时间；保留真实数据库时区与毫秒精度。 */
    static class TestClock extends SupplyChainDatabaseClock {
        long offsetSeconds;
        TestClock(DataSource source) { super(source); }
        @Override public LocalDateTime now() { return super.now().plusSeconds(offsetSeconds); }
    }
}
