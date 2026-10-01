package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentCommandFacade;
import com.aifuli.module.supplychain.border.fulfillment.application.*;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyAdminCommandService.*;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMonitorQueryService.*;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import com.aifuli.framework.common.exception.ServiceException;
import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.time.LocalDateTime;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyEntryStatus;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** 真实MySQL事务、FIFO、CAS和审计，不以Mock替代资金与Timer，见限频设计 #7-管理端契约与权限。 */
class RechargeFrequencyAdminIntegrationTest {
    private FulfillmentAcceptanceIntegrationTest fixture;
    private ConfigurableApplicationContext context;
    private JdbcTemplate jdbc;
    private RechargeFrequencyAdminCommandService commands;
    private RechargeFrequencyLifecycle frequency;
    @BeforeEach void start() {
        fixture=new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        context=fixture.applicationContext(AdminConfiguration.class); fixture.seed(context);
        jdbc=new JdbcTemplate(context.getBean("supplyChainDataSource",DataSource.class));
        commands=context.getBean(RechargeFrequencyAdminCommandService.class); frequency=context.getBean(RechargeFrequencyLifecycle.class);
        var facade=context.getBean(BuyerFulfillmentCommandFacade.class);
        facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("ADMIN-HEAD",1L));
        facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("ADMIN-TAIL",1L));
    }
    @AfterEach void stop() { if(context!=null) context.close(); if(fixture!=null) fixture.stopContainer(); }
    @Configuration(proxyBeanMethods=false)
    @Import({RechargeFrequencyAdminCommandService.class,RechargeFrequencyActionAuditService.class,RechargeFrequencyMonitorQueryService.class})
    static class AdminConfiguration {
        @Bean static AuditFailureHook auditFailureHook(){ return new AuditFailureHook(); }
    }
    static class AuditFailureHook implements org.springframework.beans.factory.config.BeanPostProcessor {
        volatile boolean armed;
        volatile CyclicBarrier actionReadBarrier;
        final java.util.concurrent.atomic.AtomicInteger auditReads=new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger auditInserts=new java.util.concurrent.atomic.AtomicInteger();
        @Override public Object postProcessAfterInitialization(Object bean,String name) {
            if (!(bean instanceof com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyActionAuditMapper mapper)) {return bean;}
            var wrapped=org.mockito.Mockito.spy(mapper);
            org.mockito.Mockito.doAnswer(invocation->{
                auditInserts.incrementAndGet();
                if (armed) {throw new org.springframework.dao.DataAccessResourceFailureException("audit storage unavailable 13800138000");}
                return invocation.callRealMethod();
            }).when(wrapped).insert(org.mockito.ArgumentMatchers.any(com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyActionAuditDO.class));
            org.mockito.Mockito.doAnswer(invocation->{
                var result=invocation.callRealMethod();
                var barrier=actionReadBarrier;
                if (barrier!=null && auditReads.incrementAndGet()<=4) {barrier.await(10,TimeUnit.SECONDS);}
                return result;
            }).when(wrapped).selectByActionNo(org.mockito.ArgumentMatchers.anyString());
            return wrapped;
        }
    }
    @Test void retryPersistsNewWakeGenerationAndReplaysWithoutMutationOrFunds() {
        long entry=firstEntry(); var row=jdbc.queryForMap("SELECT * FROM sc_recharge_frequency_entry WHERE id=?",entry);
        long wake=queueLong("wake_generation"); var command=new RetryCommand("a77edc40-8139-4b37-b1b4-c22ca1fb06c3",((Number)row.get("version")).longValue(),"核查 13800138000");
        var result=commands.retry(7L,entry,command);
        assertThat(result.resultCode()).isEqualTo("RETRY_SCHEDULED");
        assertThat(queueLong("wake_generation")).isEqualTo(wake+1);
        assertThat(commands.retry(7L,entry,command)).isEqualTo(result);
        assertThat(queueLong("wake_generation")).isEqualTo(wake+1);
        assertThat(jdbc.queryForObject("SELECT reason FROM sc_recharge_frequency_action_audit",String.class)).isEqualTo("核查 138****8000");
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(RechargeFrequencyAdminCommandService.class);
        var conflictLogs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();conflictLogs.start();logger.addAppender(conflictLogs);
        try {
            assertThatThrownBy(()->commands.retry(8L,entry,command)).isInstanceOf(ServiceException.class)
                    .extracting("code").isEqualTo(SupplyChainErrorCodeConstants.FREQUENCY_ACTION_CONFLICT.getCode());
            var conflict=conflictLogs.list.stream().filter(event->event.getFormattedMessage().contains("幂等冲突")).findFirst().orElseThrow();
            assertThat(conflict.getFormattedMessage()).contains("actor=8","actionNo=a77edc40-8139-4b37-b1b4-c22ca1fb06c3",
                    "code="+SupplyChainErrorCodeConstants.FREQUENCY_ACTION_CONFLICT.getCode(),"traceId=")
                    .doesNotContain("13800138000","核查");
            assertThat(conflict.getThrowableProxy()).isNull();
            var phoneAction=new RetryCommand("RETRY-13800138000",jdbc.queryForObject("SELECT version FROM sc_recharge_frequency_entry WHERE id=?",Long.class,entry),"secret-value 13800138000");
            commands.retry(7L,entry,phoneAction);
            assertThatThrownBy(()->commands.retry(8L,entry,phoneAction)).isInstanceOf(ServiceException.class)
                    .extracting("code").isEqualTo(SupplyChainErrorCodeConstants.FREQUENCY_ACTION_CONFLICT.getCode());
            var phoneConflict=conflictLogs.list.stream().filter(event->event.getFormattedMessage().contains("幂等冲突")).reduce((first,last)->last).orElseThrow();
            assertThat(phoneConflict.getFormattedMessage()).contains("actor=8","actionNo=RETRY-138****8000",
                    "code="+SupplyChainErrorCodeConstants.FREQUENCY_ACTION_CONFLICT.getCode(),"traceId=")
                    .doesNotContain("13800138000","secret-value");
            assertThat(jdbc.queryForObject("SELECT operator_user_id FROM sc_recharge_frequency_action_audit WHERE action_no=?",Long.class,phoneAction.actionNo())).isEqualTo(7L);
        } finally { logger.detachAppender(conflictLogs);conflictLogs.stop(); }
        assertThat(auditCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_operation",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type IN ('COMMIT','RELEASE')",Long.class)).isZero();
    }
    @Test void preparedReleaseRestoresOriginalFifoAndInvalidatesPermitWithoutFunds() {
        var permit=frequency.claim(operations().getFirst()); var cmd=release("PREPARED",permit);
        var frozen=jdbc.queryForObject("SELECT frozen FROM sc_buyer_account",Long.class);
        assertThat(commands.release(7L,permit.queueId(),cmd).resultCode()).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT status FROM sc_recharge_frequency_entry WHERE id=?",Short.class,permit.entryId())).isZero();
        assertThat(jdbc.queryForObject("SELECT enqueue_sequence FROM sc_recharge_frequency_entry WHERE id=?",Long.class,permit.entryId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account",Long.class)).isEqualTo(frozen);
        assertThat(frequency.markSendPossible(permit)).isFalse();
        assertThat(frequency.claim(operations().getLast())).isNull();
        assertThat(frequency.claim(operations().getFirst())).isNotNull();
        var newerHolder=queueLong("holder_entry_id");
        assertThat(commands.release(7L,permit.queueId(),cmd).resultCode()).isEqualTo("RELEASED");
        assertThat(queueLong("holder_entry_id")).isEqualTo(newerHolder);
    }
    @Test void unsafeUnknownManualAndSuccessWindowAreRejectedAndAuditedAfterRollback() {
        var permit=frequency.claim(operations().getFirst()); frequency.markSendPossible(permit);
        var lifecycle=context.getBean(SupplierOrderLifecycle.class);
        assertThat(occupancy().getDisplayStatus()).isEqualTo("EXECUTING");
        lifecycle.transition(permit.operationNo(),RechargeFrequencyLifecycleIntegrationTest.fact(com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass.RESULT_UNKNOWN),"ADMIN");
        assertThat(jdbc.queryForObject("SELECT status FROM sc_fulfillment_request WHERE id=(SELECT fulfillment_request_id FROM sc_recharge_frequency_entry WHERE id=?)",Short.class,permit.entryId()))
                .isEqualTo(com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentRequestStatus.FULFILLING.getCode());
        assertThat(occupancy().getDisplayStatus()).isEqualTo("RESULT_UNKNOWN");
        assertThat(occupancy().isCanRelease()).isFalse();
        rejectRelease("UNKNOWN",permit,SupplyChainErrorCodeConstants.FREQUENCY_RELEASE_UNSAFE.getCode());
        lifecycle.transition(permit.operationNo(),RechargeFrequencyLifecycleIntegrationTest.fact(com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass.MANUAL_REQUIRED),"ADMIN");
        assertThat(occupancy().getDisplayStatus()).isEqualTo("MANUAL");
        assertThat(occupancy().isCanRelease()).isFalse();
        rejectRelease("MANUAL",permit,SupplyChainErrorCodeConstants.FREQUENCY_RELEASE_UNSAFE.getCode());
        jdbc.update("UPDATE sc_recharge_frequency_queue SET acquired_at='2099-01-01 00:00:00',minimum_hold_until='2099-01-01 00:00:05'");
        lifecycle.confirmTrustedOutcome(permit.operationNo(),RechargeFrequencyLifecycleIntegrationTest.observedSuccess(),"TRUSTED",permit);
        assertThat(occupancy().getDisplayStatus()).isEqualTo("SUCCESS_HOLD");
        assertThat(occupancy().isCanRelease()).isFalse();
        rejectRelease("WINDOW",permit,SupplyChainErrorCodeConstants.FREQUENCY_SUCCESS_WINDOW.getCode());
        assertThat(auditCount()).isEqualTo(3);
        assertThat(queueLong("holder_entry_id")).isEqualTo(permit.entryId());
        jdbc.update("UPDATE sc_recharge_frequency_queue SET acquired_at=NOW()-INTERVAL 10 SECOND,minimum_hold_until=NOW()-INTERVAL 5 SECOND");
        assertThat(occupancy().getDisplayStatus()).isEqualTo("SUCCEEDED");
        assertThat(occupancy().isCanRelease()).isTrue();
        assertThat(commands.release(7L,permit.queueId(),release("FINISHED",permit)).resultCode()).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject("SELECT status FROM sc_recharge_frequency_entry WHERE id=?",Short.class,permit.entryId())).isEqualTo((short)20);
    }
    @Test void auditStorageFailureRollsBackSuccessfulWakeAndKeepsUnknownOutcomeOutOfAudit() {
        long wake=queueLong("wake_generation");
        context.getBean(AuditFailureHook.class).armed=true;
        assertThatThrownBy(()->commands.retry(7L,firstEntry(),new RetryCommand("AUDIT-FAIL",0L,"核查"))).isNotInstanceOf(ServiceException.class);
        assertThat(queueLong("wake_generation")).isEqualTo(wake); assertThat(auditCount()).isZero();
        context.getBean(AuditFailureHook.class).armed=false;
        assertThatThrownBy(()->commands.retry(7L,firstEntry(),new RetryCommand("STALE",99L,"核查"))).isInstanceOf(ServiceException.class);
        assertThat(auditCount()).isEqualTo(1); assertThat(queueLong("wake_generation")).isEqualTo(wake);
        assertThatThrownBy(()->commands.retry(7L,999999L,new RetryCommand("MISSING",0L,"核查"))).isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT queue_id FROM sc_recharge_frequency_action_audit WHERE action_no='MISSING'",Long.class)).isNull();
    }
    @Test void simultaneousIdenticalActionRollsBackLoserThenReplaysOriginalResult() throws Exception {
        var executor=Executors.newFixedThreadPool(2); var ready=new CountDownLatch(2); var go=new CountDownLatch(1);
        long wake=queueLong("wake_generation"); long entry=firstEntry(); var cmd=new RetryCommand("CONCURRENT",0L,"核查");
        var hook=context.getBean(AuditFailureHook.class);hook.actionReadBarrier=new CyclicBarrier(2);
        try {
            Callable<ActionResult> action=()->{ready.countDown(); go.await(); return commands.retry(7L,entry,cmd);};
            var a=executor.submit(action); var b=executor.submit(action); ready.await(10,TimeUnit.SECONDS); go.countDown();
            assertThat(a.get(30,TimeUnit.SECONDS)).isEqualTo(b.get(30,TimeUnit.SECONDS));
            assertThat(queueLong("wake_generation")).isEqualTo(wake+1); assertThat(auditCount()).isEqualTo(1);
            assertThat(hook.auditInserts.get()).isEqualTo(2); assertThat(hook.auditReads.get()).isGreaterThanOrEqualTo(5);
        } finally {executor.shutdownNow();}
    }
    @Test void oldReleaseRacingNewClaimNeverClearsNewHolder() throws Exception {
        var old=frequency.claim(operations().getFirst()); var stale=release("OLD-CAS",old);
        commands.release(7L,old.queueId(),release("FIRST-RELEASE",old));
        var executor=Executors.newFixedThreadPool(2); var ready=new CountDownLatch(2); var go=new CountDownLatch(1);
        String operation=operations().getFirst();
        try {
            var claim=executor.submit(()->{ready.countDown(); go.await(); return frequency.claim(operation);});
            var release=executor.submit(()->{ready.countDown(); go.await();
                try {commands.release(7L,old.queueId(),stale); return 0;}
                catch(ServiceException rejected) {return rejected.getCode();}
            });
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); go.countDown();
            var next=claim.get(30,TimeUnit.SECONDS); assertThat(next).isNotNull();
            assertThat(release.get(30,TimeUnit.SECONDS)).isEqualTo(SupplyChainErrorCodeConstants.FREQUENCY_VERSION_CONFLICT.getCode());
            assertThat(queueLong("holder_entry_id")).isEqualTo(next.entryId());
            assertThat(queueLong("occupancy_generation")).isEqualTo(next.generation());
            assertThat(frequency.markSendPossible(old)).isFalse();
        } finally {executor.shutdownNow();}
    }
    @Test void originalRejectionReplaysAfterWindowChangesAndRawReasonFingerprintRemainsExact() {
        var permit=frequency.claim(operations().getFirst()); frequency.markSendPossible(permit);
        jdbc.update("UPDATE sc_recharge_frequency_queue SET acquired_at='2099-01-01',minimum_hold_until='2099-01-01 00:00:05'");
        context.getBean(SupplierOrderLifecycle.class).confirmTrustedOutcome(permit.operationNo(),RechargeFrequencyLifecycleIntegrationTest.observedSuccess(),"TRUSTED",permit);
        var original=release("WINDOW-REPLAY",permit);
        assertThatThrownBy(()->commands.release(7L,permit.queueId(),original)).isInstanceOf(ServiceException.class).extracting("code")
                .isEqualTo(SupplyChainErrorCodeConstants.FREQUENCY_SUCCESS_WINDOW.getCode());
        jdbc.update("UPDATE sc_recharge_frequency_queue SET acquired_at=NOW()-INTERVAL 10 SECOND,minimum_hold_until=NOW()-INTERVAL 5 SECOND");
        assertThatThrownBy(()->commands.release(7L,permit.queueId(),original)).isInstanceOf(ServiceException.class).extracting("code")
                .isEqualTo(SupplyChainErrorCodeConstants.FREQUENCY_SUCCESS_WINDOW.getCode());
        assertThat(queueLong("holder_entry_id")).isEqualTo(permit.entryId()); assertThat(auditCount()).isEqualTo(1);
        var first=new RetryCommand("REASON-FP",0L,"13800138000"); commands.retry(7L,firstEntry()+1,first);
        assertThatThrownBy(()->commands.retry(7L,firstEntry()+1,new RetryCommand("REASON-FP",0L,"138-0013-8000")))
                .isInstanceOf(ServiceException.class).extracting("code").isEqualTo(SupplyChainErrorCodeConstants.FREQUENCY_ACTION_CONFLICT.getCode());
        assertThat(jdbc.queryForObject("SELECT reason FROM sc_recharge_frequency_action_audit WHERE action_no='REASON-FP'",String.class)).isEqualTo("138****8000");
        assertThat(auditCount()).isEqualTo(2);
    }
    @Test void relatedUnconfirmedOperationBlocksPreparedReleaseButReadOnlyQueryDoesNot() {
        var permit=frequency.claim(operations().getFirst());
        long current=jdbc.queryForObject("SELECT id FROM sc_fulfillment_operation WHERE operation_no=?",Long.class,permit.operationNo());
        long related=RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc,"sc_fulfillment_operation",current,
                Map.of("operation_no","OP_RISK", "idempotency_key","RISK", "sequence_no",2, "status",30, "attempt_count",1));
        var monitor=context.getBean(RechargeFrequencyMonitorQueryService.class);
        var filters=new Filter(1,20,null,null,null,null,null,null,null,null,null);
        var before=(Occupancy)monitor.page(7L,View.OCCUPANCIES,filters,null).page().getList().getFirst();
        assertThat(before.isCanRelease()).isFalse();
        rejectRelease("RISK-BLOCK",permit,SupplyChainErrorCodeConstants.FREQUENCY_RELEASE_UNSAFE.getCode());
        jdbc.update("UPDATE sc_fulfillment_operation SET status=0,attempt_count=0,completed_at=NOW() WHERE id=?",related);
        var completedCandidate=(Occupancy)monitor.page(7L,View.OCCUPANCIES,filters,null).page().getList().getFirst();
        assertThat(completedCandidate.isCanRelease()).isFalse();
        rejectRelease("COMPLETED-CANDIDATE",permit,SupplyChainErrorCodeConstants.FREQUENCY_RELEASE_UNSAFE.getCode());
        jdbc.update("UPDATE sc_fulfillment_operation SET completed_at=NULL WHERE id=?",related);
        var unsentCandidate=(Occupancy)monitor.page(7L,View.OCCUPANCIES,filters,null).page().getList().getFirst();
        assertThat(unsentCandidate.isCanRelease()).isTrue();
        jdbc.update("UPDATE sc_fulfillment_operation SET status=30,attempt_count=1 WHERE id=?",related);
        jdbc.update("UPDATE sc_fulfillment_operation SET operation_kind=? WHERE id=?",com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind.QUERY_ORDER.name(),related);
        var after=(Occupancy)monitor.page(7L,View.OCCUPANCIES,filters,null).page().getList().getFirst();
        assertThat(after.isCanRelease()).isTrue();
        assertThat(commands.release(7L,permit.queueId(),release("QUERY-ALLOWED",permit)).resultCode()).isEqualTo("RELEASED");
    }
    @Test void overdueSqlIsStrictlyGreaterThanThirtyMinutesAndNoTtlReleaseOccurs() {
        var permit=frequency.claim(operations().getFirst());
        var observed=java.time.LocalDateTime.of(2026,10,1,10,0);
        jdbc.update("UPDATE sc_recharge_frequency_queue SET acquired_at=?,minimum_hold_until=?",observed.minusSeconds(1800),observed.minusSeconds(1795));
        var mapper=context.getBean(com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyMonitorMapper.class);
        var atBoundary=new Filter(1,20,null,null,null,null,null,null,null,null,observed);
        assertThat(mapper.statistics(atBoundary).getOverdueQueueCount()).isZero();
        var afterBoundary=new Filter(1,20,null,null,null,null,null,null,null,null,observed.plusNanos(1000000));
        assertThat(mapper.statistics(afterBoundary).getOverdueQueueCount()).isEqualTo(1);
        assertThat(queueLong("holder_entry_id")).isEqualTo(permit.entryId());
    }
    @Test void fullTableStatisticsAndSqlPagesIncludeBeyondTwoHundredAndMigrationBlocks() throws Exception {
        for(int i=0;i<205;i++) {
            long source=firstEntry(); var copy=new HashMap<String,Object>();
            copy.put("order_id",100000L+i); copy.put("order_no","MONITOR-"+i); copy.put("enqueue_sequence",3L+i);
            RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc,"sc_recharge_frequency_entry",source,copy);
        }
        var monitor=context.getBean(RechargeFrequencyMonitorQueryService.class);
        assertThat(monitor.statistics(7L).getWaitingOrderCount()).isEqualTo(207);
        assertThat(monitor.statistics(7L).getOverThresholdQueueCount()).isEqualTo(1);
        var page=monitor.page(7L,View.WAITING,new Filter(3,100,null,null,null,null,null,null,null,null,null),null);
        assertThat(page.page().getList()).hasSize(7); assertThat(page.page().getTotal()).isEqualTo(207);
        var filtered=monitor.page(7L,View.WAITING,new Filter(1,20,null,null,null,"MONITOR-204",null,null,null,null,null),null);
        assertThat(filtered.page().getList()).hasSize(1);
        var exact=monitor.page(7L,View.WAITING,new Filter(1,20,null,null,null,null,null,null,null,null,null),"13900139000");
        assertThat(exact.page().getTotal()).isZero();
        long queue=queueLong("id");
        for(int i=0;i<205;i++) {
            byte[] digest=new byte[32];java.nio.ByteBuffer.wrap(digest).putInt(i+1);
            long otherQueue=RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc,"sc_recharge_frequency_queue",queue,Map.of("mobile_digest",digest,"next_enqueue_sequence",7L));
            for(int sequence=1;sequence<=6;sequence++) {
                RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc,"sc_recharge_frequency_entry",firstEntry(),
                        Map.of("queue_id",otherQueue,"order_id",200000L+i*6+sequence,"order_no","Q"+i+"-"+sequence,"enqueue_sequence",sequence));
            }
        }
        assertThat(monitor.statistics(7L).getWaitingOrderCount()).isEqualTo(1437);
        assertThat(monitor.statistics(7L).getOverThresholdQueueCount()).isEqualTo(206);
        var backlogs=monitor.page(7L,View.BACKLOGS,new Filter(3,100,null,null,null,null,null,null,null,null,null),null);
        assertThat(backlogs.page().getTotal()).isEqualTo(206); assertThat(backlogs.page().getList()).hasSize(6);
        jdbc.update("UPDATE sc_recharge_frequency_entry SET buyer_id=?",99L);
        jdbc.update("UPDATE sc_recharge_frequency_entry SET buyer_id=? WHERE id=?",77L,firstEntry());
        var fullQueue=monitor.page(7L,View.BACKLOGS,new Filter(1,20,77L,null,null,null,null,null,null,null,null),null);
        assertThat(fullQueue.page().getTotal()).isEqualTo(1);
        assertThat(((Backlog)fullQueue.page().getList().getFirst()).getWaitingCount()).isEqualTo(207);
        assertThat(((Backlog)fullQueue.page().getList().getFirst()).isOverThreshold()).isTrue();
        jdbc.update("UPDATE sc_recharge_frequency_entry SET enqueued_at=?",LocalDateTime.of(2026,10,1,9,0));
        var expectedQueues=jdbc.queryForList("SELECT id FROM sc_recharge_frequency_queue ORDER BY id",Long.class);
        long earlierQueue=expectedQueues.removeLast();
        jdbc.update("UPDATE sc_recharge_frequency_entry SET enqueued_at=? WHERE queue_id=?",LocalDateTime.of(2026,10,1,8,0),earlierQueue);
        expectedQueues.add(1,earlierQueue);
        var lastPage=monitor.page(7L,View.BACKLOGS,new Filter(3,100,99L,null,null,null,null,null,null,null,null),null);
        assertThat(lastPage.page().getTotal()).isEqualTo(206);
        assertThat(lastPage.page().getList()).extracting(row->((Backlog)row).getQueueId()).containsExactlyElementsOf(expectedQueues.subList(200,206));
        assertThat(monitor.statistics(7L).getWaitingOrderCount()).isEqualTo(1437);
        assertThat(monitor.statistics(7L).getOverThresholdQueueCount()).isEqualTo(206);
        // 迁移视图只能列未结束HOLDING+SEND_POSSIBLE；等待/已结束/未发送准备项不属于待核实发送风险。
        jdbc.update("UPDATE sc_recharge_frequency_entry SET status=20,completed_at=NOW() WHERE id=?",firstEntry());
        RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc,"sc_recharge_frequency_entry",firstEntry(),
                new HashMap<>() {{put("order_id",400001L);put("order_no","MIGRATION-RISK");put("enqueue_sequence",208L);
                    put("status",10);put("dispatch_phase",10);put("occupancy_generation",1L);put("completed_at",null);}});
        RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc,"sc_recharge_frequency_entry",firstEntry(),
                new HashMap<>() {{put("order_id",400002L);put("order_no","MIGRATION-PREPARED");put("enqueue_sequence",209L);
                    put("status",10);put("dispatch_phase",0);put("occupancy_generation",1L);put("completed_at",null);}});
        jdbc.update("UPDATE sc_recharge_frequency_queue SET blocked_reason='MIGRATION_UNKNOWN' WHERE id=?",queue);
        var blocked=monitor.page(7L,View.OCCUPANCIES,new Filter(1,20,null,null,null,null,null,null,null,null,null),null);
        assertThat(blocked.page().getList()).hasSize(1);
        assertThat(((Occupancy)blocked.page().getList().getFirst()).isCanRelease()).isFalse();
        assertThat(((Occupancy)blocked.page().getList().getFirst()).getDisplayStatus()).isEqualTo("BLOCKED");
        assertThat(((Occupancy)blocked.page().getList().getFirst()).getRiskOrderNos()).containsExactly("MIGRATION-RISK");
        jdbc.update("UPDATE sc_recharge_frequency_entry SET buyer_id=99 WHERE queue_id=? AND status=0",queue);
        var waitingOwner=monitor.page(7L,View.OCCUPANCIES,new Filter(1,20,99L,null,null,null,null,null,null,null,null),null);
        assertThat(waitingOwner.page().getTotal()).isZero();
        var json=context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).writeValueAsString(blocked);
        assertThat(json).contains("observedAt","\"list\"","\"total\":1").doesNotContain("mobileDigest","13800138000");
    }
    /** 项级条件只选队列，完整聚合不能被非匹配项目的更早等待项裁剪。设计见限频设计 §7。 */
    @Test void backlogOwnerFiltersKeepWholeQueueAndNormalizeMobileWithSpu() {
        long head=firstEntry(); long queue=queueLong("id");
        var earliest=LocalDateTime.of(2026,10,1,9,0);
        jdbc.update("UPDATE sc_recharge_frequency_entry SET buyer_id=?,project_id=?,enqueued_at=? WHERE id=?",11L,101L,earliest,head);
        jdbc.update("UPDATE sc_recharge_frequency_entry SET buyer_id=?,project_id=?,enqueued_at=? WHERE id<>?",22L,202L,earliest.plusHours(1),head);
        var monitor=context.getBean(RechargeFrequencyMonitorQueryService.class);
        String spu=jdbc.queryForObject("SELECT spu_code FROM sc_platform_spu WHERE id=(SELECT platform_spu_id FROM sc_recharge_frequency_queue WHERE id=?)",String.class,queue);
        var filter=new Filter(1,20,22L,202L,spu,null,null,null,null,null,null);
        var result=monitor.page(7L,View.BACKLOGS,filter," +8613800138000 ");
        assertThat(result.page().getTotal()).isEqualTo(1);
        var row=(Backlog)result.page().getList().getFirst();
        assertThat(row.getQueueId()).isEqualTo(queue);assertThat(row.getWaitingCount()).isEqualTo(2);
        assertThat(row.getEarliestEnqueuedAt()).isEqualTo(earliest);assertThat(row.isOverThreshold()).isFalse();
        assertThat(monitor.page(7L,View.BACKLOGS,filter,"13900139000").page().getTotal()).isZero();
        assertThat(monitor.page(7L,View.BACKLOGS,new Filter(1,20,22L,202L,"OTHER-SPU",null,null,null,null,null,null),null).page().getTotal()).isZero();
        var stats=monitor.statistics(7L);assertThat(stats.getWaitingOrderCount()).isEqualTo(2);assertThat(stats.getOverThresholdQueueCount()).isZero();
        assertThatThrownBy(()->monitor.page(7L,View.BACKLOGS,filter,"invalid-mobile")).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(SupplyChainErrorCodeConstants.FREQUENCY_PARAMETER_INVALID.getCode());
        assertThatThrownBy(()->monitor.page(7L,View.BACKLOGS,new Filter(0,20,null,null,null,null,null,null,null,null,null),null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->monitor.page(7L,View.BACKLOGS,new Filter(1,101,null,null,null,null,null,null,null,null,null),null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(()->monitor.page(7L,View.BACKLOGS,new Filter(1,20,null,null,null,null,null,earliest.plusHours(1),earliest,null,null),null)).isInstanceOf(ServiceException.class);
    }
    /** 真实SQL验证多项条件必须在同一WAITING行命中，订单与时间边界不能跨行拼接。 */
    @Test void backlogEntryPredicatesRequireSameWaitingRowAndInclusiveTimeBoundaries() {
        long head=firstEntry();
        var boundary=LocalDateTime.of(2026,10,1,10,0);
        jdbc.update("UPDATE sc_recharge_frequency_entry SET buyer_id=?,project_id=?,enqueued_at=? WHERE id=?",11L,101L,boundary.minusHours(1),head);
        jdbc.update("UPDATE sc_recharge_frequency_entry SET buyer_id=?,project_id=?,enqueued_at=? WHERE id<>?",22L,202L,boundary,head);
        var tail=jdbc.queryForMap("SELECT e.order_no,o.buyer_order_no FROM sc_recharge_frequency_entry e JOIN sc_supply_chain_order o ON o.id=e.order_id WHERE e.id<>?",head);
        String order=(String)tail.get("order_no");String buyerOrder=(String)tail.get("buyer_order_no");
        var mapper=context.getBean(com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyMonitorMapper.class);
        var matching=new Filter(1,20,22L,202L,null,order,buyerOrder,boundary,boundary,null,boundary);
        assertThat(mapper.backlogCount(new Filter(1,20,11L,202L,null,order,buyerOrder,boundary,boundary,null,boundary))).isZero();
        assertThat(mapper.backlogCount(new Filter(1,20,22L,202L,null,order,"NONMATCHING",boundary,boundary,null,boundary))).isZero();
        assertThat(mapper.backlogCount(new Filter(1,20,22L,202L,null,order,buyerOrder,boundary.plusSeconds(1),null,null,boundary))).isZero();
        assertThat(mapper.backlogCount(new Filter(1,20,22L,202L,null,order,buyerOrder,null,boundary.minusSeconds(1),null,boundary))).isZero();
        assertThat(mapper.backlogCount(matching)).isEqualTo(1);assertThat(mapper.backlogs(matching).getFirst().getWaitingCount()).isEqualTo(2);
        var monitor=context.getBean(RechargeFrequencyMonitorQueryService.class);
        assertThat(monitor.page(7L,View.BACKLOGS,matching,null).page().getTotal()).isEqualTo(1);
    }
    /** 占用者和已结束项的匹配不能替代WAITING项；队列仍有不匹配等待项时也须排除。 */
    @Test void backlogFiltersExcludeHolderAndFinishedOnlyMatches() {
        var permit=frequency.claim(operations().getFirst());
        jdbc.update("UPDATE sc_recharge_frequency_entry SET buyer_id=?,project_id=? WHERE id=?",11L,101L,permit.entryId());
        jdbc.update("UPDATE sc_recharge_frequency_entry SET buyer_id=?,project_id=? WHERE id<>?",22L,202L,permit.entryId());
        var mapper=context.getBean(com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyMonitorMapper.class);
        var filter=new Filter(1,20,11L,101L,null,null,null,null,null,null,LocalDateTime.of(2026,10,1,10,0));
        assertThat(mapper.backlogCount(filter)).isZero();assertThat(mapper.backlogs(filter)).isEmpty();
        jdbc.update("UPDATE sc_recharge_frequency_entry SET status=?,completed_at=NOW() WHERE id=?",RechargeFrequencyEntryStatus.FINISHED.getCode(),permit.entryId());
        assertThat(mapper.backlogCount(filter)).isZero();assertThat(mapper.backlogs(filter)).isEmpty();
        assertThat(mapper.backlogCount(new Filter(1,20,22L,202L,null,null,null,null,null,null,filter.observedAt()))).isEqualTo(1);
    }
    private Occupancy occupancy() {
        return (Occupancy)context.getBean(RechargeFrequencyMonitorQueryService.class).page(7L,View.OCCUPANCIES,
                new Filter(1,20,null,null,null,null,null,null,null,null,null),null).page().getList().getFirst();
    }
    private void rejectRelease(String action,RechargeFrequencyExecutionPermit permit,int code) {
        assertThatThrownBy(()->commands.release(7L,permit.queueId(),release(action,permit))).isInstanceOf(ServiceException.class).extracting("code").isEqualTo(code);
    }
    private ReleaseCommand release(String no,RechargeFrequencyExecutionPermit p) {
        var order=jdbc.queryForObject("SELECT order_no FROM sc_recharge_frequency_entry WHERE id=?",String.class,p.entryId());
        return new ReleaseCommand(no,order,queueLong("occupancy_generation"),queueLong("version"),"核查");
    }
    private List<String> operations(){return RechargeFrequencyExecutionIntegrationTest.operations(jdbc);}
    private long firstEntry(){return jdbc.queryForObject("SELECT MIN(id) FROM sc_recharge_frequency_entry",Long.class);}
    private long queueLong(String col){return jdbc.queryForObject("SELECT "+col+" FROM sc_recharge_frequency_queue",Long.class);}
    private long auditCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM sc_recharge_frequency_action_audit",Long.class);}
}
