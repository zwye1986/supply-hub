package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentCommandFacade;
import com.aifuli.module.supplychain.border.fulfillment.application.singlestep.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.*;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaService;
import com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaService.Configure;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.aifuli.module.supplychain.tradecore.supply.domain.MerchantQuotaPlatform.RENYUE;
import static org.assertj.core.api.Assertions.*;

/** 经公开受理/Runner验证资源等待不是供应商失败，不能提前跨越CREATE marker。 */
class SingleStepCapacityIntegrationTest {
    private final SingleStepFrozenConfigurationIntegrationTest fixture = new SingleStepFrozenConfigurationIntegrationTest();
    @BeforeEach void start() { fixture.startWith(Config.class); }
    @AfterEach void stop() { fixture.stop(); }
    @Test void saturatedCreateRollsBackFrequencyAndMarkerAndPersistsOneWait() {
        quota().configure(new Configure(RENYUE, "fixture", 100, 1, null, 1L, "隔离测试"));
        transaction().executeWithoutResult(s -> quota().admit(RENYUE, "fixture", "OP-other", 1, clock().now().plusSeconds(60)));
        accept();
        assertThat(runner().runExecute(op().getOperationNo(), "capacity")).isEqualTo("CAPACITY_WAITING");
        assertThat(op().getBusinessSendPossibleAt()).isNull(); assertThat(op().getExecutionGeneration()).isZero();
        assertThat(op().getRecoveryCount()).isZero(); assertThat(op().getRecoveryDeadlineAt()).isNull();
        assertThat(fixture.jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isNull();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_single_step_capacity_wait WHERE active_flag=1", Long.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_task WHERE task_type='SINGLE_STEP_CAPACITY_WAKE'", Long.class)).isEqualTo(1);
        assertThat(runner().runExecute(op().getOperationNo(), "duplicate")).isEqualTo("CAPACITY_WAITING");
        assertThat(fixture.jdbc.queryForObject("SELECT wake_sequence FROM sc_single_step_capacity_wait", Long.class)).isEqualTo(1);
        assertThat(fixture.context.getBean(Counting.class).calls).isZero(); held();
    }
    @Test void missingConfigFailsClosedAsCaseWithoutSending() {
        accept();
        assertThat(runner().runExecute(op().getOperationNo(), "missing")).isEqualTo("MANUAL");
        assertThat(op().getBusinessSendPossibleAt()).isNull();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_exception_case", Long.class)).isEqualTo(1);
        assertThat(fixture.context.getBean(Counting.class).calls).isZero(); held();
    }
    @Test void restartAndRepeatedWakeUseNewSequenceAndEventuallyCreateOnce() throws Exception {
        saturated(); accept(); runner().runExecute(op().getOperationNo(), "wait");
        String old = payload();
        fixture.reopenWith(Config.class);
        dueWait();
        assertThat(consume(old)).isEqualTo("CAPACITY_WAITING");
        assertThat(fixture.jdbc.queryForObject("SELECT wake_sequence FROM sc_single_step_capacity_wait", Long.class)).isEqualTo(2);
        assertThat(consume(old)).isEqualTo("STALE");
        quota().release("OP-other", 1); dueWait();
        assertThat(consume(payload())).isEqualTo("ACCEPTED");
        assertThat(fixture.context.getBean(Counting.class).calls).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT active_flag FROM sc_single_step_capacity_wait", Boolean.class)).isFalse();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_merchant_call_lease WHERE operation_no=? AND released_at IS NOT NULL", Long.class, op().getOperationNo())).isEqualTo(1);
        held();
    }
    @Test void queryWaitKeepsBudgetFactAndDeadlineAndStopsAtOriginalDeadline() throws Exception {
        quota().configure(new Configure(RENYUE, "fixture", 100, 1, null, 1L, "隔离测试")); accept();
        runner().runExecute(op().getOperationNo(), "create");
        fixture.jdbc.update("UPDATE sc_fulfillment_operation SET next_recover_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND)");
        occupy();
        var before = op();
        assertThat(runner().runRecovery(before.getOperationNo(), before.getExecutionGeneration(), "query")).isEqualTo("CAPACITY_WAITING");
        assertThat(op().getRecoveryCount()).isZero(); assertThat(op().getExecutionGeneration()).isEqualTo(before.getExecutionGeneration());
        assertThat(op().getNextRecoverAt()).isEqualTo(before.getNextRecoverAt());
        assertThat(op().getRecoveryDeadlineAt()).isEqualTo(before.getRecoveryDeadlineAt());
        fixture.jdbc.update("UPDATE sc_fulfillment_operation SET recovery_deadline_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND)");
        dueWait();
        // 既有claim在截止时写MANUAL并返回null，Runner对外保持CLAIM_SKIPPED，不改历史返回合同。
        assertThat(consume(payload())).isEqualTo("CLAIM_SKIPPED"); held();
        assertThat(op().getStatus()).isEqualTo(com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus.MANUAL.getCode());
        assertThat(fixture.jdbc.queryForObject("SELECT reason_code FROM sc_fulfillment_exception_case", String.class)).isEqualTo("SINGLE_STEP_DEADLINE_REACHED");
        assertThat(fixture.jdbc.queryForObject("SELECT active_flag FROM sc_single_step_capacity_wait", Boolean.class)).isFalse();
        assertThat(fixture.context.getBean(Counting.class).calls).isEqualTo(1);
    }
    @Test void waitingTimerFailureRollsBackWaitAndClaimAndCanBeRetried() {
        saturated(); accept();
        fixture.jdbc.execute("ALTER TABLE sc_timer_task ADD CONSTRAINT reject_capacity CHECK (task_type<>'SINGLE_STEP_CAPACITY_WAKE')");
        assertThatThrownBy(() -> runner().runExecute(op().getOperationNo(), "fail-wait")).isInstanceOf(RuntimeException.class);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_single_step_capacity_wait", Long.class)).isZero();
        assertThat(op().getBusinessSendPossibleAt()).isNull(); held();
        fixture.jdbc.execute("ALTER TABLE sc_timer_task DROP CHECK reject_capacity");
        assertThat(runner().runExecute(op().getOperationNo(), "retry")).isEqualTo("CAPACITY_WAITING");
    }
    @Test void claimTimerFailureRollsBackQuotaAndFrequency() {
        quota().configure(new Configure(RENYUE, "fixture", 100, 1, null, 1L, "隔离测试")); accept();
        fixture.jdbc.execute("ALTER TABLE sc_timer_task ADD CONSTRAINT reject_claim CHECK (task_type<>'SINGLE_STEP_WATCHDOG')");
        assertThatThrownBy(() -> runner().runExecute(op().getOperationNo(), "fail-claim")).isInstanceOf(RuntimeException.class);
        assertThat(op().getBusinessSendPossibleAt()).isNull();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_merchant_call_lease", Long.class)).isZero();
        assertThat(fixture.jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isNull(); held();
    }
    @Test void preparationWatchdogDoesNotFailRealCapacityWait() {
        saturated(); accept(); runner().runExecute(op().getOperationNo(), "wait");
        fixture.jdbc.update("UPDATE sc_fulfillment_operation SET created_at=DATE_SUB(NOW(3),INTERVAL 6 MINUTE)");
        assertThat(fixture.context.getBean(SingleStepFulfillmentLifecycle.class).preparationFailed(op().getOperationNo(), 0, true)).isEqualTo("CAPACITY_WAITING");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_exception_case", Long.class)).isZero(); held();
    }
    @Test void callbackDuringQueryWaitSettlesOnceAndOldWakeDoesNotSend() throws Exception {
        quota().configure(new Configure(RENYUE, "fixture", 100, 1, null, 1L, "隔离测试")); accept(); runner().runExecute(op().getOperationNo(), "create");
        fixture.jdbc.update("UPDATE sc_fulfillment_operation SET next_recover_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND)"); occupy();
        runner().runRecovery(op().getOperationNo(), op().getExecutionGeneration(), "wait");
        var operation = op(); var bo = fixture.context.getBean(com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper.class).selectById(operation.getBOrderId());
        var binding = new SingleStepCheckpoint.Binding(operation.getOperationNo(), bo.getId(), SingleStepFrozenConfigurationIntegrationTest.TYPE,
                AdapterStepCode.SEND, bo.getSupplierId(), bo.getConnectorVersionId(), bo.getOfferVersionId(), HexFormat.of().formatHex(operation.getConfigDigest()));
        var frozen = fixture.context.getBean(SingleStepCheckpointCodec.class).decrypt(new SingleStepCheckpointCodec.Stored(operation.getCheckpointSchemaVersion(), operation.getCheckpointKeyVersion(), operation.getCheckpointCiphertext()), binding);
        fixture.context.getBean(SingleStepCallbackApplicationService.class).handle(new SingleStepSupplierFact(binding, SingleStepSupplierFact.Source.CALLBACK,
                bo.getBillno(), frozen.business().protectedTargetReference().targetDigest(), 1, SingleStepSupplierFact.Verification.VERIFIED,
                SingleStepSupplierFact.Observation.SUCCESS, "CALLBACK", clock().now(), new byte[32], null, false), "callback");
        dueWait(); assertThat(consume(payload())).isEqualTo("STALE");
        assertThat(fixture.jdbc.queryForObject("SELECT active_flag FROM sc_single_step_capacity_wait", Boolean.class)).isFalse();
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type='COMMIT'", Long.class)).isEqualTo(1);
        assertThat(fixture.context.getBean(Counting.class).calls).isEqualTo(1);
    }
    @Test void distinctConnectorVersionsAndRetiredOrderShareCurrentMerchantQuota() {
        quota().configure(new Configure(RENYUE, "fixture", 100, 1, null, 1L, "隔离跨连接器测试")); accept();
        var original = op();
        runner().runExecute(original.getOperationNo(), "old-create");
        fixture.jdbc.update("UPDATE sc_fulfillment_operation SET next_recover_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND)");
        long connector = fixture.jdbc.queryForObject("SELECT id FROM sc_supplier_connector", Long.class);
        long version = fixture.jdbc.queryForObject("SELECT id FROM sc_supplier_connector_version", Long.class);
        fixture.jdbc.update("UPDATE sc_supplier_connector SET current_version_id=NULL WHERE id=?", connector);
        long nextConnector = RechargeFrequencyAcceptanceIntegrationTest.cloneRow(fixture.jdbc, "sc_supplier_connector", connector,
                Map.of("connector_no", "CON_CAP_NEXT", "connector_code", "NEXT"));
        long nextVersion = RechargeFrequencyAcceptanceIntegrationTest.cloneRow(fixture.jdbc, "sc_supplier_connector_version", version,
                Map.of("connector_id", nextConnector, "rate_limit", 9999, "concurrency_limit", 9999));
        for (long id : fixture.jdbc.queryForList("SELECT id FROM sc_supplier_connector_capability WHERE connector_version_id=?", Long.class, version))
            RechargeFrequencyAcceptanceIntegrationTest.cloneRow(fixture.jdbc, "sc_supplier_connector_capability", id, Map.of("connector_version_id", nextVersion));
        for (long id : fixture.jdbc.queryForList("SELECT id FROM sc_status_mapping_definition WHERE connector_version_id=?", Long.class, version))
            RechargeFrequencyAcceptanceIntegrationTest.cloneRow(fixture.jdbc, "sc_status_mapping_definition", id, Map.of("connector_version_id", nextVersion));
        fixture.jdbc.update("UPDATE sc_supplier_connector SET current_version_id=? WHERE id=?", nextVersion, nextConnector);
        fixture.jdbc.update("UPDATE sc_supplier_connector_version SET status=90 WHERE id=?", version);
        fixture.jdbc.update("UPDATE sc_status_mapping_definition SET status=90 WHERE connector_version_id=?", version);
        long offerVersion = fixture.jdbc.queryForObject("SELECT id FROM sc_supplier_offer_version", Long.class);
        long nextOffer = RechargeFrequencyAcceptanceIntegrationTest.cloneRow(fixture.jdbc, "sc_supplier_offer_version", offerVersion,
                Map.of("version_no", 2, "connector_version_id", nextVersion));
        fixture.jdbc.update("UPDATE sc_supplier_offer SET current_version_id=?", nextOffer);
        var request = FulfillmentAcceptanceIntegrationTest.request("CAPACITY-NEXT");
        request.getTarget().setValues(Map.of("accountType", "MOBILE", "accountValue", "13900139000"));
        fixture.context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(request);
        occupy();
        var next = fixture.context.getBean(FulfillmentOperationMapper.class).selectList(null).stream().filter(o -> !o.getId().equals(original.getId())).findFirst().orElseThrow();
        assertThat(runner().runExecute(next.getOperationNo(), "new-connector")).isEqualTo("CAPACITY_WAITING");
        assertThat(runner().runRecovery(original.getOperationNo(), original.getExecutionGeneration() + 1, "retired-query")).isEqualTo("CAPACITY_WAITING");
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_single_step_capacity_wait WHERE active_flag=1", Long.class)).isEqualTo(2);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_merchant_quota", Long.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type IN ('COMMIT','RELEASE')", Long.class)).isZero();
    }
    @Test void networkResultTransactionSeesQuotaAlreadyReleased() {
        quota().configure(new Configure(RENYUE, "fixture", 100, 1, null, 1L, "隔离测试")); accept();
        fixture.context.getBean(Counting.class).networkFailure = true;
        var interceptor = new com.aifuli.module.supplychain.testsupport.DeterministicSqlInterceptor();
        interceptor.use(new com.aifuli.module.supplychain.testsupport.DeterministicSqlInterceptor.Hook() {
            public void before(String statement, String sql, Object params) {
                if (sql.toUpperCase().startsWith("UPDATE SC_FULFILLMENT_OPERATION")
                        && fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_merchant_call_lease WHERE operation_no=?", Long.class, op().getOperationNo()) > 0
                        && fixture.context.getBean(Counting.class).calls > 0) {
                    assertThat(fixture.jdbc.queryForObject("SELECT released_at FROM sc_supplier_merchant_call_lease WHERE operation_no=?", java.time.LocalDateTime.class, op().getOperationNo())).isNotNull();
                }
            }
        });
        fixture.context.getBean("supplyChainSqlSessionFactory", org.apache.ibatis.session.SqlSessionFactory.class).getConfiguration().addInterceptor(interceptor);
        try { assertThat(runner().runExecute(op().getOperationNo(), "network")).isEqualTo("NETWORK_UNKNOWN"); }
        finally { interceptor.reset(); }
        held();
    }
    @Test void concurrentDueWakeOnlyCreatesOneNewWaitSequence() throws Exception {
        saturated(); accept(); runner().runExecute(op().getOperationNo(), "wait"); dueWait();
        String body = payload();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 3; i++) futures.add(pool.submit(() -> consume(body)));
            for (var future : futures) future.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(fixture.jdbc.queryForObject("SELECT wake_sequence FROM sc_single_step_capacity_wait", Long.class)).isEqualTo(2);
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_task WHERE task_type='SINGLE_STEP_CAPACITY_WAKE'", Long.class)).isEqualTo(2); held();
    }
    private void saturated() { quota().configure(new Configure(RENYUE, "fixture", 100, 1, null, 1L, "隔离测试")); occupy(); }
    private void occupy() { transaction().executeWithoutResult(s -> quota().admit(RENYUE, "fixture", "OP-other", 1, clock().now().plusSeconds(60))); }
    private void dueWait() { fixture.jdbc.update("UPDATE sc_single_step_capacity_wait SET next_wake_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND)"); }
    private String payload() { return fixture.jdbc.queryForObject("SELECT payload_json FROM sc_timer_task WHERE task_type='SINGLE_STEP_CAPACITY_WAKE' ORDER BY id DESC LIMIT 1", String.class); }
    private String consume(String body) throws Exception { return fixture.context.getBean(com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandlerRegistry.class)
            .require(FulfillmentTimerTaskTypes.SINGLE_STEP_CAPACITY_WAKE).handle(new com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext("TMR-test", 1L, 1, 1, "test"), new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)); }
    private void accept() { fixture.context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("CAPACITY")); }
    private MerchantQuotaService quota() { return fixture.context.getBean(MerchantQuotaService.class); }
    private SupplyChainDatabaseClock clock() { return fixture.context.getBean(SupplyChainDatabaseClock.class); }
    private TransactionTemplate transaction() { return new TransactionTemplate(fixture.context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class)); }
    private SingleStepFulfillmentRunner runner() { return fixture.context.getBean(SingleStepFulfillmentRunner.class); }
    private FulfillmentOperationDO op() { return fixture.context.getBean(FulfillmentOperationMapper.class).selectList(null).getFirst(); }
    private void held() { assertThat(fixture.jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(1000); assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type IN ('COMMIT','RELEASE')", Long.class)).isZero(); }
    @Configuration(proxyBeanMethods = false)
    @Import({MerchantQuotaService.class, SingleStepCapacityWaitService.class, SingleStepCallbackApplicationService.class})
    static class Config {
        @Bean @Primary AdapterMetadataRegistry capacityRegistry() { return new AdapterMetadataRegistry(List.of(new Metadata())); }
        @Bean @Primary Counting capacityExecutor() { return new Counting(); }
    }
    static class Metadata extends SingleStepFrozenConfigurationIntegrationTest.TestMetadata {
        @Override public com.aifuli.module.supplychain.tradecore.supply.domain.MerchantQuotaPlatform merchantQuotaPlatform() { return RENYUE; }
    }
    static class Counting implements OperationExecutorPort {
        int calls;
        boolean networkFailure;
        public boolean supports(String type) { return true; }
        public OperationOutcome execute(OperationTicket ticket) { throw new AssertionError("必须有执行截止"); }
        public OperationOutcome execute(OperationTicket ticket, long deadline) {
            calls++;
            if (networkFailure) throw new IllegalStateException("隔离网络故障", new java.io.IOException("test"));
            try {
                var frozen = new com.fasterxml.jackson.databind.ObjectMapper().readValue(ticket.checkpointPayload(), SingleStepCheckpoint.class);
                return OperationOutcome.builder().outcomeClass(OperationOutcomeClass.ACCEPTED).protocolFact(new SingleStepSupplierFact(
                        frozen.binding(), ticket.operationKind() == FulfillmentOperationKind.FULFILL ? SingleStepAction.CREATE : SingleStepAction.QUERY,
                        ticket.billno(), frozen.business().protectedTargetReference().targetDigest(), 1, SingleStepSupplierFact.Verification.VERIFIED,
                        SingleStepSupplierFact.Observation.ACCEPTED, null, java.time.LocalDateTime.now(), new byte[32])).build();
            } catch (java.io.IOException e) { throw new AssertionError(e); }
        }
    }
}
