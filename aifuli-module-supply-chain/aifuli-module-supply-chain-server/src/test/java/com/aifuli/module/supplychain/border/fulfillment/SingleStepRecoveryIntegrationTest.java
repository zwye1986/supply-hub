package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentCommandFacade;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationExecuteHandler;
import com.aifuli.module.supplychain.border.fulfillment.application.singlestep.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.*;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.CompositeOperationExecutor;
import com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandlerRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

/** 公开受理与Timer入口的真实MySQL恢复合同；不向真实供应商发请求。 */
class SingleStepRecoveryIntegrationTest {
    private final SingleStepFrozenConfigurationIntegrationTest fixture = new SingleStepFrozenConfigurationIntegrationTest();

    @BeforeEach void start() { fixture.startWith(ExecutionConfig.class); }
    @AfterEach void stop() { fixture.stop(); }

    @Test void operationTimerCommitsOneCreateMarkerSixtySecondLeaseAndDurableRecovery() {
        clock().fixed = null; // 此场景使用真实数据库时间，其余边界场景使用可控时钟推进。
        fixture.context.getBean(BuyerFulfillmentCommandFacade.class)
                .acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("RECOVERY-CREATE"));
        var operations = fixture.context.getBean(FulfillmentOperationMapper.class);
        var operation = operations.selectList(null).getFirst();
        var payload = new ObjectMapper().createObjectNode().put("operationNo", operation.getOperationNo());
        var handler = fixture.context.getBean(FulfillmentOperationExecuteHandler.class);
        assertThatCode(() -> handler.handle(new TimerTaskContext("TMR-test", 1L, 1, 1L, "test"), payload))
                .as("单步骤冻结原单不得落入旧通用结果入口").doesNotThrowAnyException();
        var after = operations.selectById(operation.getId());
        assertThat(after.getBusinessSendPossibleAt()).as("marker必须在外呼前持久化").isNotNull();
        assertThat(after.getExecutionGeneration()).isEqualTo(1);
        assertThat(after.getExecutionLeaseUntil()).isEqualTo(after.getBusinessSendPossibleAt().plusSeconds(60));
        assertThat(after.getRecoveryDeadlineAt()).isEqualTo(after.getBusinessSendPossibleAt().toLocalDate().plusDays(2).atStartOfDay());
        assertThat(after.getNextRecoverAt()).isNotNull();
        assertThat(fixture.jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(1000);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type IN ('COMMIT','RELEASE')", Long.class)).isZero();
    }

    @Test void restartConsumesPersistedWatchdogAndQueryWithoutRecreatingOriginalOrder() throws Exception {
        var op = accept("RESTART");
        claim(SingleStepAction.CREATE); // marker/watchdog已提交，模拟HTTP前进程退出。
        var lease = operation().getExecutionLeaseUntil();
        fixture.reopenWith(ExecutionConfig.class);
        clock().fixed = lease.plusSeconds(17);
        consume(FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG, 1);
        var due = operation().getNextRecoverAt();
        assertThat(due).isEqualTo(lease.plusSeconds(197));
        fixture.reopenWith(ExecutionConfig.class);
        clock().fixed = due;
        consume(FulfillmentTimerTaskTypes.SINGLE_STEP_QUERY, 1);
        assertThat(executor().creates.get()).isZero();
        assertThat(executor().queries.get()).isEqualTo(1);
        assertThat(operation().getRecoveryCount()).isEqualTo(1);
        assertThat(operation().getRecoveryDeadlineAt()).isEqualTo(lease.minusSeconds(60).toLocalDate().plusDays(2).atStartOfDay());
        assertThat(runner().runExecute(op.getOperationNo(), "replay")).isEqualTo("CREATE_ALREADY_MARKED");
        held();
    }

    @Test void credibleAcceptanceUsesFrozen600SecondContractNotCurrentMetadata() {
        var metadata = (SingleStepFrozenConfigurationIntegrationTest.TestMetadata) fixture.context
                .getBean(com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry.class)
                .require(SingleStepFrozenConfigurationIntegrationTest.TYPE);
        metadata.firstQueryDelaySeconds = 600;
        var op = accept("FROZEN-600");
        metadata.firstQueryDelaySeconds = 180;
        var received = clock().now();
        runner().runExecute(op.getOperationNo(), "test");
        assertThat(operation().getNextRecoverAt()).isEqualTo(received.plusSeconds(600));
        assertThat(runtime().frozen().recoveryContract().firstQueryDelaySeconds()).isEqualTo(600);
        held();
    }

    @Test void lateExecutorFailureCannotCloseNewGeneration() {
        var op = accept("STALE-FAILURE");
        var originalBinding = binding();
        executor().failure = new IllegalStateException("测试协议故障");
        executor().beforeFailure = () -> {
            // 在旧Runner空事务同步作用域内用JDBC读取，避免MyBatis一级缓存返回watchdog前的快照。
            clock().fixed = fixture.jdbc.queryForObject("SELECT execution_lease_until FROM sc_fulfillment_operation", LocalDateTime.class);
            assertThat(lifecycle().watchdog(op.getOperationNo(), 1, "watchdog")).isEqualTo("CREATE_LEASE_EXPIRED");
            clock().fixed = fixture.jdbc.queryForObject("SELECT next_recover_at FROM sc_fulfillment_operation", LocalDateTime.class);
            assertThat(lifecycle().claim(op.getOperationNo(), SingleStepAction.QUERY, 1, originalBinding, "new-worker")).isNotNull();
        };
        assertThatThrownBy(() -> runner().runExecute(op.getOperationNo(), "old-worker")).isInstanceOf(IllegalStateException.class);
        assertThat(operation().getExecutionGeneration()).isEqualTo(2);
        assertThat(operation().getStatus()).isEqualTo(com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus.EXECUTING.getCode());
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_exception_case", Long.class)).isZero();
        held();
    }

    @Test void expiredWatchdogConvertsDamagedCheckpointToCaseInsteadOfRetryLoop() throws Exception {
        accept("DAMAGED-RUNTIME");
        var permit = claim(SingleStepAction.CREATE);
        fixture.jdbc.update("UPDATE sc_fulfillment_operation SET checkpoint_ciphertext=?", new byte[]{1, 2, 3});
        clock().fixed = permit.leaseUntil();
        assertThatCode(() -> consume(FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG, 1)).doesNotThrowAnyException();
        assertThat(fixture.jdbc.queryForObject("SELECT reason_code FROM sc_fulfillment_exception_case", String.class)).isEqualTo("SINGLE_STEP_LOCAL_PREPARATION_FAILED");
        assertThat(operation().getNextRecoverAt()).isNull();
        held();
    }

    @Test void inactiveOriginalCannotAdvanceUnknownRecovery() {
        accept("INACTIVE-ORIGINAL");
        var permit = claim(SingleStepAction.CREATE);
        fixture.jdbc.update("UPDATE sc_b_order SET active_flag=NULL");
        assertThat(lifecycle().networkUnknown(permit, "stale-original")).isEqualTo("STALE");
        assertThat(operation().getNextRecoverAt()).isNull();
        assertThat(runtime().progress().lastFact()).isEqualTo(SingleStepRuntimeCheckpoint.Fact.CLAIMED);
        held();
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"IO", "PROTOCOL", "AUTHENTICATION"})
    void runnerDistinguishesNetworkUnknownFromExecutionAndAuthenticationFailure(String failure) {
        var op = accept("FAULT-" + failure);
        if ("IO".equals(failure)) executor().failure = new IllegalStateException(new java.net.SocketTimeoutException("测试超时"));
        else if ("PROTOCOL".equals(failure)) executor().failure = new IllegalArgumentException("测试解析器故障");
        else executor().verification = SingleStepSupplierFact.Verification.AUTHENTICATION_FAILED;
        var persisted = clock().now();
        if ("PROTOCOL".equals(failure)) assertThatThrownBy(() -> runner().runExecute(op.getOperationNo(), "test")).isInstanceOf(IllegalStateException.class);
        else runner().runExecute(op.getOperationNo(), "test");
        assertThat(operation().getBusinessSendPossibleAt()).isNotNull();
        if ("IO".equals(failure)) {
            assertThat(operation().getNextRecoverAt()).isEqualTo(persisted.plusSeconds(180));
            assertThat(runtime().progress().lastFact()).isEqualTo(SingleStepRuntimeCheckpoint.Fact.NETWORK_UNKNOWN);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_exception_case", Long.class)).isZero();
        } else {
            assertThat(operation().getNextRecoverAt()).isNull();
            assertThat(fixture.jdbc.queryForObject("SELECT reason_code FROM sc_fulfillment_exception_case", String.class))
                    .isEqualTo("AUTHENTICATION".equals(failure) ? "SINGLE_STEP_SECURITY_CONFLICT" : "SINGLE_STEP_LOCAL_PREPARATION_FAILED");
            assertThat(runtime().progress().trustedExists()).isFalse();
        }
        held();
    }

    @Test void competingExecuteTimersCommitOneCreateAndOneGeneration() throws Exception {
        var op = accept("RACE-CREATE");
        race(() -> fixture.context.getBean(FulfillmentOperationExecuteHandler.class).handle(timer(),
                new ObjectMapper().createObjectNode().put("operationNo", op.getOperationNo())));
        assertThat(operation().getExecutionGeneration()).isEqualTo(1);
        assertThat(executor().creates.get()).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_task WHERE task_type='SINGLE_STEP_WATCHDOG'", Long.class)).isEqualTo(1);
        held();
    }

    @Test void committedMarkerWithoutHttpRecoversOnlyOriginalQueryAfterDurableUnknownPlus180Seconds() throws Exception {
        accept("CRASH-BEFORE-HTTP");
        var permit = claim(SingleStepAction.CREATE);
        clock().fixed = permit.leaseUntil().minusNanos(1_000_000);
        consume(FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG, 1);
        assertThat(operation().getNextRecoverAt()).isNull();
        clock().fixed = permit.leaseUntil();
        assertThat(lifecycle().canDispatch(permit)).isFalse();
        consume(FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG, 1);
        var first = operation();
        assertThat(first.getNextRecoverAt()).isEqualTo(permit.leaseUntil().plusSeconds(180));
        assertThat(runtime().progress().lastFact()).isEqualTo(SingleStepRuntimeCheckpoint.Fact.CREATE_LEASE_EXPIRED);
        clock().fixed = clock().fixed.plusSeconds(30);
        consume(FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG, 1);
        assertThat(operation().getNextRecoverAt()).isEqualTo(first.getNextRecoverAt());
        assertThat(runner().runExecute(first.getOperationNo(), "test")).isEqualTo("CREATE_ALREADY_MARKED");
        clock().fixed = first.getNextRecoverAt().minusNanos(1_000_000);
        consume(FulfillmentTimerTaskTypes.SINGLE_STEP_QUERY, 1);
        assertThat(executor().queries.get()).isZero();
        clock().fixed = first.getNextRecoverAt();
        consume(FulfillmentTimerTaskTypes.SINGLE_STEP_QUERY, 1);
        assertThat(operation().getRecoveryCount()).isEqualTo(1);
        assertThat(operation().getExecutionGeneration()).isEqualTo(2);
        assertThat(executor().creates.get()).isZero();
        assertThat(executor().queries.get()).isEqualTo(1);
        held();
    }

    @ParameterizedTest @EnumSource(value = SingleStepSupplierFact.Observation.class, names = {"ACCEPTED", "PROCESSING"})
    void credibleCreateUsesResponseReceptionRatherThanApplyTime(SingleStepSupplierFact.Observation observation) {
        accept("RECEIPT-" + observation);
        var permit = claim(SingleStepAction.CREATE);
        var received = clock().now().plusSeconds(5);
        clock().fixed = received.plusSeconds(20);
        lifecycle().apply(permit, fact(permit, observation, received), "test");
        assertThat(operation().getNextRecoverAt()).isEqualTo(received.plusSeconds(180));
        assertThat(runtime().progress().trustedExists()).isTrue();
        var originalDue = operation().getNextRecoverAt();
        clock().fixed = clock().fixed.plusSeconds(10);
        lifecycle().apply(permit, fact(permit, observation, received), "test");
        assertThat(operation().getNextRecoverAt()).isEqualTo(originalDue);
    }

    @Test void frequencyWakeAndOriginalTimerCompeteWithoutBypassingDueTime() throws Exception {
        var op = accept("RACE-QUERY");
        runner().runExecute(op.getOperationNo(), "test");
        var due = operation().getNextRecoverAt();
        clock().fixed = clock().fixed.plusSeconds(30);
        var queueId = fixture.jdbc.queryForObject("SELECT id FROM sc_recharge_frequency_queue", Long.class);
        var generation = fixture.jdbc.queryForObject("SELECT wake_generation FROM sc_recharge_frequency_queue", Long.class);
        var work = fixture.context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyRecoveryService.class)
                .prepareWake(queueId, generation);
        assertThat(work).isNotNull();
        var query = fixture.context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyQueryService.class);
        query.query(work, "frequency-before-due");
        assertThat(executor().queries.get()).isZero();
        clock().fixed = due;
        executor().observation = SingleStepSupplierFact.Observation.NOT_FOUND;
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var a = pool.submit(() -> { gate.await(); query.query(work, "frequency"); return null; });
            var b = pool.submit(() -> { gate.await(); consume(FulfillmentTimerTaskTypes.SINGLE_STEP_QUERY, 1); return null; });
            gate.countDown(); a.get(); b.get();
        }
        assertThat(executor().queries.get()).isEqualTo(1);
        assertThat(operation().getRecoveryCount()).isEqualTo(1);
        assertThat(runtime().progress().consecutiveNotFound()).isEqualTo(1);
        assertThat(runtime().progress().trustedExists()).isTrue();
        assertThat(operation().getNextRecoverAt()).isEqualTo(due.plusSeconds(180));
        held();
    }

    @ParameterizedTest @EnumSource(value = SingleStepRuntimeCheckpoint.Fact.class, names = {"NETWORK_UNKNOWN", "RESPONSE_UNKNOWN", "QUERY_LEASE_EXPIRED"})
    void queryUnknownAtomicallyBreaksTwoNotFoundResultsAndWaits600Seconds(SingleStepRuntimeCheckpoint.Fact unknown) throws Exception {
        var op = accept("UNKNOWN-" + unknown);
        executor().observation = SingleStepSupplierFact.Observation.RESPONSE_UNKNOWN;
        runner().runExecute(op.getOperationNo(), "test");
        executor().observation = SingleStepSupplierFact.Observation.NOT_FOUND;
        for (int i = 0; i < 2; i++) {
            clock().fixed = operation().getNextRecoverAt();
            runner().runRecovery(op.getOperationNo(), operation().getExecutionGeneration(), "test");
        }
        assertThat(runtime().progress().consecutiveNotFound()).isEqualTo(2);
        clock().fixed = operation().getNextRecoverAt();
        var permit = claim(SingleStepAction.QUERY);
        clock().fixed = clock().fixed.plusSeconds(7);
        if (unknown == SingleStepRuntimeCheckpoint.Fact.NETWORK_UNKNOWN) lifecycle().networkUnknown(permit, "test");
        else if (unknown == SingleStepRuntimeCheckpoint.Fact.RESPONSE_UNKNOWN) lifecycle().apply(permit, fact(permit, SingleStepSupplierFact.Observation.RESPONSE_UNKNOWN, clock().now()), "test");
        else { clock().fixed = permit.leaseUntil(); consume(FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG, permit.generation()); }
        var persisted = clock().now();
        assertThat(runtime().progress().lastFact()).isEqualTo(unknown);
        assertThat(runtime().progress().consecutiveNotFound()).isZero();
        assertThat(runtime().progress().trustedExists()).isFalse();
        assertThat(operation().getNextRecoverAt()).isEqualTo(persisted.plusSeconds(600));
        clock().fixed = persisted.plusSeconds(600).minusNanos(1_000_000);
        assertThat(claim(SingleStepAction.QUERY)).isNull();
        clock().fixed = persisted.plusSeconds(600);
        runner().runRecovery(op.getOperationNo(), operation().getExecutionGeneration(), "test");
        assertThat(runtime().progress().consecutiveNotFound()).isEqualTo(1);
        var due = operation().getNextRecoverAt();
        lifecycle().networkUnknown(permit, "duplicate-old-error");
        consume(FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG, permit.generation());
        assertThat(runtime().progress().consecutiveNotFound()).isEqualTo(1);
        assertThat(operation().getNextRecoverAt()).isEqualTo(due);
        assertThat(operation().getRecoveryCount()).isEqualTo(4);
        held();
    }

    @Test void watchdogInsertFailureRollsBackFrequencyMarkerGenerationAndQueryBudget() {
        accept("CLAIM-ROLLBACK");
        failTimer(FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG);
        assertThatThrownBy(() -> claim(SingleStepAction.CREATE)).isInstanceOf(RuntimeException.class);
        assertThat(operation().getBusinessSendPossibleAt()).isNull();
        assertThat(operation().getExecutionGeneration()).isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isNull();
        assertThat(fixture.jdbc.queryForObject("SELECT dispatch_phase FROM sc_recharge_frequency_entry", Short.class)).isNull();
        fixture.jdbc.execute("ALTER TABLE sc_timer_task DROP CHECK fail_single_step_timer");
        var created = claim(SingleStepAction.CREATE);
        lifecycle().networkUnknown(created, "test");
        fixture.jdbc.update("UPDATE sc_fulfillment_operation SET recovery_count=999");
        clock().fixed = operation().getNextRecoverAt();
        failTimer(FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG);
        assertThatThrownBy(() -> claim(SingleStepAction.QUERY)).isInstanceOf(RuntimeException.class);
        assertThat(operation().getRecoveryCount()).isEqualTo(999);
        assertThat(operation().getExecutionGeneration()).isEqualTo(1);
        assertThat(runtime().progress().lastFact()).isEqualTo(SingleStepRuntimeCheckpoint.Fact.NETWORK_UNKNOWN);
        held();
    }

    @Test void resultAndWatchdogRollbackCannotPartiallyApplyFactsOrRefundCommittedQueryClaim() throws Exception {
        accept("RESULT-ROLLBACK");
        var created = claim(SingleStepAction.CREATE);
        lifecycle().networkUnknown(created, "test");
        clock().fixed = operation().getNextRecoverAt();
        var query = claim(SingleStepAction.QUERY);
        failTimer(FulfillmentTimerTaskTypes.SINGLE_STEP_QUERY);
        assertThatThrownBy(() -> lifecycle().apply(query, fact(query, SingleStepSupplierFact.Observation.NOT_FOUND, clock().now()), "test"))
                .isInstanceOf(RuntimeException.class);
        assertThat(operation().getRecoveryCount()).isEqualTo(1);
        assertThat(runtime().progress().consecutiveNotFound()).isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_raw_status_observation", Long.class)).isZero();
        clock().fixed = query.leaseUntil();
        assertThatThrownBy(() -> consume(FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG, query.generation())).isInstanceOf(RuntimeException.class);
        assertThat(runtime().progress().lastFact()).isEqualTo(SingleStepRuntimeCheckpoint.Fact.CLAIMED);
        fixture.jdbc.execute("ALTER TABLE sc_timer_task DROP CHECK fail_single_step_timer");
        clock().fixed = clock().fixed.plusSeconds(13);
        consume(FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG, query.generation());
        assertThat(operation().getNextRecoverAt()).isEqualTo(clock().now().plusSeconds(600));
        assertThat(operation().getRecoveryCount()).isEqualTo(1);
        held();
    }

    @Test void lateCredibleExistenceProtectsOriginalWithoutChangingCurrentLeaseCountOrSchedule() throws Exception {
        accept("LATE-EXISTS");
        var created = claim(SingleStepAction.CREATE);
        clock().fixed = created.leaseUntil();
        consume(FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG, 1);
        clock().fixed = operation().getNextRecoverAt();
        var query = claim(SingleStepAction.QUERY);
        var before = operation();
        lifecycle().apply(created, fact(created, SingleStepSupplierFact.Observation.ACCEPTED, created.leaseUntil().minusSeconds(10)), "late");
        assertThat(runtime().progress().trustedExists()).isTrue();
        assertThat(operation().getExecutionGeneration()).isEqualTo(before.getExecutionGeneration());
        assertThat(operation().getExecutionLeaseUntil()).isEqualTo(before.getExecutionLeaseUntil());
        assertThat(operation().getNextRecoverAt()).isEqualTo(before.getNextRecoverAt());
        lifecycle().apply(query, fact(query, SingleStepSupplierFact.Observation.NOT_FOUND, clock().now()), "test");
        assertThat(runtime().progress().trustedExists()).isTrue();
        assertThat(runtime().progress().consecutiveNotFound()).isEqualTo(1);
        var originalDue = operation().getNextRecoverAt();
        lifecycle().apply(created, fact(created, SingleStepSupplierFact.Observation.SUCCESS, created.leaseUntil().minusSeconds(5)), "late-success");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_raw_status_observation WHERE external_status='SUCCESS'", Long.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_exception_case", Long.class)).isEqualTo(1);
        assertThat(operation().getExecutionGeneration()).isEqualTo(query.generation());
        assertThat(originalDue).isNotNull();
        held();
    }

    @Test void thousandthCommittedQueryCanReturnCredibleResultButNoThousandAndFirstClaimExists() throws Exception {
        accept("BUDGET");
        lifecycle().networkUnknown(claim(SingleStepAction.CREATE), "test");
        fixture.jdbc.update("UPDATE sc_fulfillment_operation SET recovery_count=999");
        clock().fixed = operation().getNextRecoverAt();
        var winners = new java.util.concurrent.ConcurrentLinkedQueue<SingleStepExecutionPermit>();
        race(() -> { var permit = claim(SingleStepAction.QUERY); if (permit != null) winners.add(permit); return "test"; });
        assertThat(winners).hasSize(1);
        var last = winners.element();
        assertThat(operation().getRecoveryCount()).isEqualTo(1000);
        assertThat(claim(SingleStepAction.QUERY)).isNull();
        assertThat(lifecycle().apply(last, fact(last, SingleStepSupplierFact.Observation.SUCCESS, clock().now()), "last")).isEqualTo("TERMINAL_OBSERVED");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_raw_status_observation WHERE external_status='SUCCESS'", Long.class)).isEqualTo(1);
        assertThat(claim(SingleStepAction.QUERY)).isNull();
        assertThat(operation().getRecoveryCount()).isEqualTo(1000);
        held();
    }

    @Test void unknownAtThousandthClaimOpensCaseAndKeepsReservation() {
        var op = accept("BUDGET-UNKNOWN");
        lifecycle().networkUnknown(claim(SingleStepAction.CREATE), "test");
        fixture.jdbc.update("UPDATE sc_fulfillment_operation SET recovery_count=999");
        clock().fixed = operation().getNextRecoverAt();
        runner().runRecovery(op.getOperationNo(), 1L, "test");
        assertThat(operation().getRecoveryCount()).isEqualTo(1000);
        assertThat(operation().getNextRecoverAt()).isNull();
        assertThat(fixture.jdbc.queryForObject("SELECT reason_code FROM sc_fulfillment_exception_case", String.class)).isEqualTo("SINGLE_STEP_BUDGET_EXHAUSTED");
        assertThat(runner().runRecovery(op.getOperationNo(), 2L, "replay")).isEqualTo("ALREADY_COMPLETED");
        assertThat(executor().queries.get()).isEqualTo(1);
        held();
    }

    @Test void unknownAtCalendarLimitStopsClaimsAndKeepsReservation() throws Exception {
        accept("DEADLINE");
        clock().fixed = LocalDateTime.of(2026, 10, 18, 23, 59, 50);
        var created = claim(SingleStepAction.CREATE);
        assertThat(operation().getRecoveryDeadlineAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 0, 0));
        lifecycle().networkUnknown(created, "test");
        var deadline = operation().getRecoveryDeadlineAt();
        clock().fixed = deadline.minusMinutes(2);
        var query = claim(SingleStepAction.QUERY);
        lifecycle().networkUnknown(query, "test");
        assertThat(fixture.jdbc.queryForObject("SELECT next_fire_at FROM sc_timer_task WHERE task_type='SINGLE_STEP_QUERY' ORDER BY id DESC LIMIT 1", LocalDateTime.class)).isEqualTo(deadline);
        clock().fixed = deadline;
        consume(FulfillmentTimerTaskTypes.SINGLE_STEP_QUERY, query.generation());
        assertThat(operation().getRecoveryCount()).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT reason_code FROM sc_fulfillment_exception_case", String.class)).isEqualTo("SINGLE_STEP_DEADLINE_REACHED");
        assertThat(executor().queries.get()).isZero();
        held();
    }

    @Test void preparationWatchdogDoesNotMisclassifyLegitimateFifoWaitingAsFailure() throws Exception {
        accept("WAITING-FIRST");
        fixture.context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("WAITING-SECOND"));
        clock().fixed = clock().fixed.plusMinutes(5);
        var rows = fixture.jdbc.queryForList("SELECT operation_no FROM sc_fulfillment_operation ORDER BY id", String.class);
        var payload = new ObjectMapper().createObjectNode().put("operationNo", rows.get(1));
        fixture.context.getBean(PersistentTimerHandlerRegistry.class).require(FulfillmentTimerTaskTypes.SINGLE_STEP_PREPARATION_WATCHDOG).handle(timer(), payload);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_exception_case", Long.class)).isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT status FROM sc_fulfillment_operation WHERE operation_no=?", Short.class, rows.get(1))).isZero();
    }

    private FulfillmentOperationDO accept(String buyerOrderNo) {
        assertThat(fixture.context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request(buyerOrderNo)).getReceiptStatus())
                .isEqualTo(com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptStatus.ACCEPTED);
        return operation();
    }
    private FulfillmentOperationDO operation() { return fixture.context.getBean(FulfillmentOperationMapper.class).selectList(null).getFirst(); }
    private SingleStepFulfillmentLifecycle lifecycle() { return fixture.context.getBean(SingleStepFulfillmentLifecycle.class); }
    private SingleStepFulfillmentRunner runner() { return fixture.context.getBean(SingleStepFulfillmentRunner.class); }
    private TestClock clock() { return fixture.context.getBean(TestClock.class); }
    private TestExecutor executor() { return fixture.context.getBean(TestExecutor.class); }
    private SingleStepCheckpoint.Binding binding() {
        var op = operation();
        var bo = fixture.context.getBean(com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper.class).selectById(op.getBOrderId());
        return new SingleStepCheckpoint.Binding(op.getOperationNo(), bo.getId(), SingleStepFrozenConfigurationIntegrationTest.TYPE,
                AdapterStepCode.SEND, bo.getSupplierId(), bo.getConnectorVersionId(), bo.getOfferVersionId(), java.util.HexFormat.of().formatHex(op.getConfigDigest()));
    }
    private SingleStepRuntimeCheckpoint runtime() {
        var op = operation();
        return fixture.context.getBean(SingleStepCheckpointCodec.class).decryptRuntime(new SingleStepCheckpointCodec.Stored(
                op.getCheckpointSchemaVersion(), op.getCheckpointKeyVersion(), op.getCheckpointCiphertext()), binding());
    }
    private SingleStepExecutionPermit claim(SingleStepAction action) {
        return lifecycle().claim(operation().getOperationNo(), action, operation().getExecutionGeneration(), binding(), "test-origin");
    }
    private OperationOutcome fact(SingleStepExecutionPermit permit, SingleStepSupplierFact.Observation observation, LocalDateTime received) {
        var frozen = runtime().frozen();
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256").digest((permit.generation() + ":" + observation).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return OperationOutcome.builder().outcomeClass(OperationOutcomeClass.RESULT_UNKNOWN).protocolFact(new SingleStepSupplierFact(
                    frozen.binding(), permit.action(), frozen.business().billno(), frozen.business().protectedTargetReference().targetDigest(), 1,
                    SingleStepSupplierFact.Verification.VERIFIED, observation, null, received, digest)).build();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private void consume(String type, long generation) throws Exception {
        String payload = fixture.jdbc.queryForObject("SELECT payload_json FROM sc_timer_task WHERE task_type=? AND JSON_EXTRACT(payload_json,'$.expectedGeneration')=? ORDER BY id DESC LIMIT 1", String.class, type, generation);
        fixture.context.getBean(PersistentTimerHandlerRegistry.class).require(type).handle(timer(), new ObjectMapper().readTree(payload));
    }
    private static TimerTaskContext timer() { return new TimerTaskContext("TMR-test", 1L, 1, 1L, "test"); }
    private void held() {
        assertThat(fixture.jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(1000);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type IN ('COMMIT','RELEASE')", Long.class)).isZero();
    }
    private void failTimer(String type) {
        // 真实DB插入故障；只约束新代任务，既有行合法，不需要SUPER/TRIGGER或全局数据库设置。
        long generation = operation().getExecutionGeneration() + (FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG.equals(type) ? 1 : 0);
        fixture.jdbc.execute("ALTER TABLE sc_timer_task ADD CONSTRAINT fail_single_step_timer CHECK (NOT (task_type='" + type
                + "' AND JSON_EXTRACT(payload_json,'$.expectedGeneration')=" + generation + "))");
    }
    private static void race(java.util.concurrent.Callable<String> work) throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> { gate.await(); return work.call(); }));
            gate.countDown(); for (var future : futures) future.get();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ExecutionConfig {
        @Bean @Primary TestClock singleStepClock(DataSource supplyChainDataSource) { return new TestClock(supplyChainDataSource); }
        @Bean TestExecutor singleStepTestExecutor(TestClock clock, DataSource supplyChainDataSource) {
            return new TestExecutor(clock, new JdbcTemplate(supplyChainDataSource));
        }
        @Bean @Primary OperationExecutorPort singleStepExecutor(TestExecutor executor) { return new CompositeOperationExecutor(List.of(executor)); }
    }
    static class TestClock extends SupplyChainDatabaseClock {
        volatile LocalDateTime fixed;
        TestClock(DataSource ds) { super(ds); fixed = super.now(); }
        @Override public LocalDateTime now() { return fixed == null ? super.now() : fixed; }
    }
    static class TestExecutor implements OperationExecutorPort {
        final TestClock clock;
        final JdbcTemplate jdbc;
        final AtomicInteger creates = new AtomicInteger();
        final AtomicInteger queries = new AtomicInteger();
        volatile SingleStepSupplierFact.Observation observation = SingleStepSupplierFact.Observation.ACCEPTED;
        SingleStepSupplierFact.Verification verification = SingleStepSupplierFact.Verification.VERIFIED;
        RuntimeException failure;
        Runnable beforeFailure = () -> {};
        TestExecutor(TestClock clock, JdbcTemplate jdbc) { this.clock = clock; this.jdbc = jdbc; }
        public boolean supports(String type) { return SingleStepFrozenConfigurationIntegrationTest.TYPE.equals(type); }
        public OperationOutcome execute(OperationTicket ticket) { throw new AssertionError("单步骤禁止旧execute"); }
        public OperationOutcome execute(OperationTicket ticket, long deadlineNanos) {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(deadlineNanos - System.nanoTime()).isPositive().isLessThanOrEqualTo(60_000_000_000L);
            assertThat(jdbc.queryForObject("SELECT business_send_possible_at FROM sc_fulfillment_operation WHERE operation_no=?", LocalDateTime.class, ticket.operationNo())).isNotNull();
            assertThat(jdbc.queryForObject("SELECT execution_generation FROM sc_fulfillment_operation WHERE operation_no=?", Long.class, ticket.operationNo()))
                    .isEqualTo(ticket.executionGeneration());
            boolean query = ticket.operationKind() == FulfillmentOperationKind.QUERY_ORDER;
            (query ? queries : creates).incrementAndGet();
            if (failure != null) { beforeFailure.run(); throw failure; }
            try {
                var frozen = new ObjectMapper().readValue(ticket.checkpointPayload(), SingleStepCheckpoint.class);
                var fact = new SingleStepSupplierFact(frozen.binding(), query ? SingleStepAction.QUERY : SingleStepAction.CREATE,
                        ticket.billno(), frozen.business().protectedTargetReference().targetDigest(), 1,
                        verification, observation, null, clock.now(),
                        java.security.MessageDigest.getInstance("SHA-256").digest((ticket.operationNo() + ticket.executionGeneration() + observation).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                return OperationOutcome.builder().outcomeClass(OperationOutcomeClass.RESULT_UNKNOWN).protocolFact(fact).build();
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
    }
}
