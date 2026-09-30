package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentCommandFacade;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 真实MySQL持久化唤醒，见限频设计 #6-timer-与补偿。 */
class RechargeFrequencyTimerIntegrationTest {
    @Test void acceptancePersistsVersionedWakeInSameTransaction() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext()) {
            fixture.seed(context);
            context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("TIMER-HEAD", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_task WHERE task_type='RECHARGE_FREQUENCY_WAKE'", Long.class)).isEqualTo(1L);
            var queue = jdbc.queryForMap("SELECT id,wake_generation,next_check_at FROM sc_recharge_frequency_queue");
            var task = jdbc.queryForMap("SELECT idempotency_key,payload_version,payload_json,next_fire_at FROM sc_timer_task WHERE task_type='RECHARGE_FREQUENCY_WAKE'");
            assertThat(task.get("idempotency_key")).isEqualTo(queue.get("id")+":"+queue.get("wake_generation"));
            assertThat(task.get("payload_version")).isEqualTo(1);
            assertThat(task.get("next_fire_at")).isEqualTo(queue.get("next_check_at"));
            assertThat(task.get("payload_json").toString()).contains("queueId", "wakeGeneration").doesNotContain("mobile", "phone", "138");
        } finally { fixture.stopContainer(); }
    }

    @Test void sameGenerationIsUniqueAndCompletedOldGenerationCannotConsumeNewIntentAfterRestart() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try {
            Long queueId; long firstGeneration;
            try (var context = fixture.applicationContext()) {
                fixture.seed(context); var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
                facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("RESTART-HEAD", 1L));
                var jdbc = jdbc(context); queueId = queueId(jdbc); firstGeneration = generation(jdbc);
                var scheduler = context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyWakeScheduler.class);
                var mapper = context.getBean(com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyQueueMapper.class);
                var tx = new org.springframework.transaction.support.TransactionTemplate(context.getBean("supplyChainTransactionManager", org.springframework.transaction.PlatformTransactionManager.class));
                tx.executeWithoutResult(status -> { scheduler.schedule(mapper.selectById(queueId)); scheduler.schedule(mapper.selectById(queueId)); });
                assertThat(wakeCount(jdbc)).isEqualTo(1L);
                jdbc.update("UPDATE sc_timer_task SET status=40 WHERE task_type='RECHARGE_FREQUENCY_WAKE'");
                facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("RESTART-TAIL", 1L));
                assertThat(generation(jdbc)).isGreaterThan(firstGeneration); assertThat(wakeCount(jdbc)).isEqualTo(2L);
            }
            // 新Spring容器只读数据库恢复，不依赖前一个节点内存队列。
            try (var restarted = fixture.applicationContext()) {
                var jdbc = jdbc(restarted); var registry = restarted.getBean(com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandlerRegistry.class);
                var handler = registry.require("RECHARGE_FREQUENCY_WAKE");
                var json = restarted.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
                handler.handle(RechargeFrequencyExecutionIntegrationTest.timer(), json.createObjectNode().put("queueId",queueId).put("wakeGeneration",firstGeneration));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_task WHERE task_type='FULFILLMENT_OPERATION_EXECUTE'",Long.class)).isZero();
                restarted.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyRecoveryService.class).compensate();
                assertThat(jdbc.queryForObject("SELECT next_check_at >= DATE_ADD(NOW(3),INTERVAL 29 SECOND) FROM sc_recharge_frequency_queue",Boolean.class)).isTrue();
                handler.handle(RechargeFrequencyExecutionIntegrationTest.timer(), json.createObjectNode().put("queueId",queueId).put("wakeGeneration",generation(jdbc)));
                var operation = RechargeFrequencyExecutionIntegrationTest.operations(jdbc).getFirst();
                var task = jdbc.queryForMap("SELECT payload_json,idempotency_key FROM sc_timer_task WHERE task_type='FULFILLMENT_OPERATION_EXECUTE'");
                assertThat(task.get("idempotency_key").toString()).startsWith(operation+":");
                assertThat(jdbc.queryForObject("SELECT status FROM sc_fulfillment_operation WHERE operation_no=?",Short.class,operation)).isZero();
                restarted.getBean(com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationExecuteHandler.class)
                        .handle(RechargeFrequencyExecutionIntegrationTest.timer(),json.readTree(task.get("payload_json").toString()));
                assertThat(restarted.getBean(RechargeFrequencyExecutionIntegrationTest.CountingExecutor.class).calls.get()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue",Long.class)).isNotNull();
            }
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
        finally { fixture.stopContainer(); }
    }

    @Test void compensationPreservesFutureRetryAndRunningLeaseButRepairsDeadMissingAndExpiredTasks() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext()) {
            fixture.seed(context); context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("REPAIR",1L));
            var jdbc=jdbc(context); var recovery=context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyRecoveryService.class);
            long initial=generation(jdbc);
            jdbc.update("UPDATE sc_timer_task SET status=30,next_fire_at=DATE_ADD(NOW(),INTERVAL 1 HOUR)");
            assertThat(recovery.compensate()).isZero(); assertThat(generation(jdbc)).isEqualTo(initial);
            // 扫描进度应该前移，防前100个有效future-task永久占满到期索引窗口。
            assertThat(jdbc.queryForObject("SELECT next_check_at >= DATE_ADD(NOW(3),INTERVAL 29 SECOND) FROM sc_recharge_frequency_queue",Boolean.class)).isTrue();
            jdbc.update("UPDATE sc_recharge_frequency_queue SET next_check_at=DATE_SUB(NOW(),INTERVAL 1 SECOND)");
            jdbc.update("UPDATE sc_timer_task SET status=20,lease_until=DATE_ADD(NOW(),INTERVAL 1 HOUR)");
            assertThat(recovery.compensate()).isZero(); assertThat(generation(jdbc)).isEqualTo(initial);
            jdbc.update("UPDATE sc_recharge_frequency_queue SET next_check_at=DATE_SUB(NOW(),INTERVAL 1 SECOND)");
            jdbc.update("UPDATE sc_timer_task SET status=60,lease_until=NULL");
            assertThat(recovery.compensate()).isEqualTo(1); assertThat(generation(jdbc)).isEqualTo(initial+1);
            jdbc.update("DELETE FROM sc_timer_task WHERE task_type='RECHARGE_FREQUENCY_WAKE' AND idempotency_key=?",queueId(jdbc)+":"+generation(jdbc));
            assertThat(recovery.compensate()).isEqualTo(1); assertThat(generation(jdbc)).isEqualTo(initial+2);
            jdbc.update("UPDATE sc_timer_task SET status=20,lease_until=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE idempotency_key=?",queueId(jdbc)+":"+generation(jdbc));
            assertThat(recovery.compensate()).isEqualTo(1); assertThat(generation(jdbc)).isEqualTo(initial+3);
        } finally { fixture.stopContainer(); }
    }

    @Test void successWakeUsesRemainingOriginalWindowAndQuiescesEmptyQueue() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context=fixture.applicationContext()) {
            fixture.seed(context); context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("WINDOW",1L));
            var jdbc=jdbc(context); var frequency=context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle.class);
            var lifecycle=context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.SupplierOrderLifecycle.class);
            var recovery=context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyRecoveryService.class);
            var operation=RechargeFrequencyExecutionIntegrationTest.operations(jdbc).getFirst(); var permit=frequency.claim(operation); frequency.markSendPossible(permit);
            var clock=context.getBean(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class);
            var acquired=clock.now().plusHours(1); var minimum=acquired.plusSeconds(5);
            jdbc.update("UPDATE sc_recharge_frequency_queue SET acquired_at=?,minimum_hold_until=?",acquired,minimum);
            lifecycle.confirmTrustedOutcome(operation,RechargeFrequencyLifecycleIntegrationTest.observedSuccess(),"WINDOW",permit);
            assertThat(recovery.prepareWake(queueId(jdbc),generation(jdbc))).isNull();
            assertThat(jdbc.queryForObject("SELECT minimum_hold_until FROM sc_recharge_frequency_queue",java.time.LocalDateTime.class)).isEqualTo(minimum);
            assertThat(jdbc.queryForObject("SELECT next_check_at FROM sc_recharge_frequency_queue",java.time.LocalDateTime.class)).isEqualTo(minimum);
            var task=jdbc.queryForObject("SELECT next_fire_at FROM sc_timer_task WHERE task_type='RECHARGE_FREQUENCY_WAKE' AND idempotency_key=?",java.time.LocalDateTime.class,queueId(jdbc)+":"+generation(jdbc));
            assertThat(clock.toInstant(task)).isEqualTo(minimum.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant());
            jdbc.update("UPDATE sc_recharge_frequency_queue SET acquired_at=DATE_SUB(NOW(),INTERVAL 10 SECOND),minimum_hold_until=DATE_SUB(NOW(),INTERVAL 5 SECOND),next_check_at=DATE_SUB(NOW(),INTERVAL 1 SECOND)");
            dueWork(context);
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue",Long.class)).isNull();
            recovery.prepareWake(queueId(jdbc),generation(jdbc));
            assertThat(jdbc.queryForObject("SELECT next_check_at FROM sc_recharge_frequency_queue",java.time.LocalDateTime.class)).isNull();
            assertThat(RechargeFrequencyLifecycleIntegrationTest.ledger(jdbc,"COMMIT")).isEqualTo(1L);
        } finally { fixture.stopContainer(); }
    }

    @Test void preparedNodeLossNeverQueriesButSentManualQueriesWithoutTargetAndTrustedSuccessKeepsLateNewHolder() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context=fixture.applicationContext()) {
            fixture.seed(context); var facade=context.getBean(BuyerFulfillmentCommandFacade.class);
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("QUERY-HEAD",1L)); facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("QUERY-TAIL",1L));
            var jdbc=jdbc(context); var ops=RechargeFrequencyExecutionIntegrationTest.operations(jdbc);
            var frequency=context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle.class);
            var recovery=context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyRecoveryService.class);
            var query=context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyQueryService.class);
            var lifecycle=context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.SupplierOrderLifecycle.class);
            var permit=frequency.claim(ops.getFirst());
            assertThat(recovery.prepareWake(queueId(jdbc),generation(jdbc))).isNull();
            assertThat(frequency.markSendPossible(permit)).isTrue();
            lifecycle.confirmTrustedOutcome(ops.getFirst(),RechargeFrequencyLifecycleIntegrationTest.fact(com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass.MANUAL_REQUIRED),"MANUAL",permit);
            jdbc.update("UPDATE sc_fulfillment_request SET target_ciphertext=X'00'");
            var executor=context.getBean(RechargeFrequencyExecutionIntegrationTest.CountingExecutor.class);
            var unsupported=dueWork(context); query.query(unsupported,"UNSUPPORTED"); assertThat(executor.calls.get()).isZero();
            enableQuery(context);
            executor.outcome=com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome.builder().outcomeClass(com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass.NOT_FOUND)
                    .callDigest(com.aifuli.module.supplychain.border.fulfillment.domain.CallDigestFact.builder().httpStatus(404).build()).build();
            query.query(dueWork(context),"404");
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue",Long.class)).isEqualTo(permit.entryId());
            assertThat(RechargeFrequencyLifecycleIntegrationTest.ledger(jdbc,"RELEASE")).isZero();
            var ticket=executor.lastTicket;
            assertThat(ticket.operationKind()).isEqualTo(com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind.QUERY_ORDER);
            assertThat(ticket.operationNo()).isEqualTo(ops.getFirst()); assertThat(ticket.rechargeAccount()).isNull();
            assertThat(ticket.secretKey()).isEqualTo("test-secret");
            assertThat(ticket.billno()).isEqualTo(jdbc.queryForObject("SELECT billno FROM sc_b_order WHERE id=(SELECT b_order_id FROM sc_fulfillment_operation WHERE operation_no=?)",String.class,ops.getFirst()));
            jdbc.update("UPDATE sc_recharge_frequency_queue SET acquired_at=DATE_SUB(NOW(),INTERVAL 10 SECOND),minimum_hold_until=DATE_SUB(NOW(),INTERVAL 5 SECOND)");
            var lateWork=dueWork(context);
            executor.outcome=RechargeFrequencyLifecycleIntegrationTest.observedSuccess(); query.query(lateWork,"SUCCESS");
            var next=frequency.claim(ops.getLast()); assertThat(next).isNotNull();
            query.query(lateWork,"LATE");
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue",Long.class)).isEqualTo(next.entryId());
            assertThat(RechargeFrequencyLifecycleIntegrationTest.ledger(jdbc,"COMMIT")).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fund_reservation",Long.class)).isEqualTo(2L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_operation",Long.class)).isEqualTo(2L);
        } finally { fixture.stopContainer(); }
    }

    @Test void realQnmHttp200AuthenticationErrorsKeepHolderAndFrozenFunds() throws Exception {
        var fixture=new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try(var server=new okhttp3.mockwebserver.MockWebServer(); var context=fixture.applicationContext()) {
            server.start(); fixture.seed(context);
            context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("QNM-AUTH",1L));
            var jdbc=jdbc(context); var operation=RechargeFrequencyExecutionIntegrationTest.operations(jdbc).getFirst();
            var frequency=context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle.class);
            var permit=frequency.claim(operation); assertThat(frequency.markSendPossible(permit)).isTrue();
            jdbc.update("UPDATE sc_fulfillment_request SET target_ciphertext=X'00'");
            var resolver=context.getBean(com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.class);
            long version=jdbc.queryForObject("SELECT id FROM sc_supplier_connector_version LIMIT 1",Long.class);
            var original=resolver.resolve(version);
            org.mockito.Mockito.when(resolver.resolve(version)).thenReturn(new com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration(
                    original.connectorNo(),original.connectorVersionId(),original.versionNo(),com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata.TYPE,
                    server.url("/").toString(),original.merchantAccount(),original.credentialSlotId(),original.callbackPath(),java.util.Set.of(com.aifuli.module.supplychain.supplieradapter.metadata.Capability.QUERY_ORDER)));
            var adapter=new com.aifuli.module.supplychain.supplieradapter.qnm.QnmSupplierAdapter(new com.aifuli.module.supplychain.supplieradapter.qnm.QnmHttpExecutor(),context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class));
            var query=new com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyQueryService(resolver,
                    context.getBean(com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper.class),
                    context.getBean(com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper.class),
                    context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationTicketFactory.class),adapter,
                    context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.SupplierOrderLifecycle.class));
            long frozen=jdbc.queryForObject("SELECT frozen FROM sc_buyer_account",Long.class);
            for(String code:java.util.List.of("-100","-101","SIGN_ERROR")) {
                for(int status:java.util.List.of(1,2)) {
                    server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("{\"ResCode\":\""+code+"\",\"orderStatus\":"+status+"}"));
                    query.query(dueWork(context),"QNM-AUTH");
                    assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue",Long.class)).isEqualTo(permit.entryId());
                    assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account",Long.class)).isEqualTo(frozen);
                    assertThat(RechargeFrequencyLifecycleIntegrationTest.ledger(jdbc,"RELEASE")).isZero();
                    assertThat(RechargeFrequencyLifecycleIntegrationTest.ledger(jdbc,"COMMIT")).isZero();
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fulfillment_operation",Long.class)).isEqualTo(1L);
                    var request=server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(request).isNotNull(); assertThat(request.getPath()).isEqualTo("/GameOrder/Query");
                    assertThat(request.getBody().readUtf8()).doesNotContain("account=","13800138000");
                }
            }
            assertThat(frozen).isEqualTo(9800L);
        } finally { fixture.stopContainer(); }
    }

    @Test void alarmsAreStrictPersistentRateLimitedAndRecoverAcrossContainerRestart() {
        var fixture=new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try {
            java.time.LocalDateTime alertAt;
            try (var context=fixture.applicationContext(AlarmRollbackConfiguration.class)) {
                fixture.seed(context); var facade=context.getBean(BuyerFulfillmentCommandFacade.class);
                for(int index=0;index<5;index++) { facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("ALARM-"+index,1L)); }
                var jdbc=jdbc(context); var recovery=context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyRecoveryService.class);
                recovery.repair(queueId(jdbc)); assertThat(jdbc.queryForObject("SELECT backlog_alert_active FROM sc_recharge_frequency_queue",Boolean.class)).isFalse();
                facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("ALARM-5",1L));
                var hook=context.getBean(AlarmRollbackHook.class); hook.fail=true;
                var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyRecoveryService.class);
                var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); events.start(); logger.addAppender(events);
                try {
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->recovery.repair(queueId(jdbc))).isInstanceOf(IllegalStateException.class);
                    assertThat(jdbc.queryForObject("SELECT backlog_alert_active FROM sc_recharge_frequency_queue",Boolean.class)).isFalse();
                    assertThat(jdbc.queryForObject("SELECT last_backlog_alert_at FROM sc_recharge_frequency_queue",java.time.LocalDateTime.class)).isNull();
                    assertThat(events.list).isEmpty();
                    hook.fail=false; recovery.repair(queueId(jdbc));
                    assertThat(events.list).hasSize(1);
                    assertThat(events.list.getFirst().getFormattedMessage()).contains("BACKLOG_ACTIVE").doesNotContain("13800138000","test-secret");
                } finally { hook.fail=false; logger.detachAppender(events); events.stop(); }
                assertThat(jdbc.queryForObject("SELECT backlog_alert_active FROM sc_recharge_frequency_queue",Boolean.class)).isTrue();
                alertAt=jdbc.queryForObject("SELECT last_backlog_alert_at FROM sc_recharge_frequency_queue",java.time.LocalDateTime.class);
                jdbc.update("UPDATE sc_recharge_frequency_queue SET next_check_at=DATE_SUB(NOW(),INTERVAL 1 SECOND)"); recovery.repair(queueId(jdbc));
                assertThat(jdbc.queryForObject("SELECT last_backlog_alert_at FROM sc_recharge_frequency_queue",java.time.LocalDateTime.class)).isEqualTo(alertAt);
            }
            try(var context=fixture.applicationContext()) {
                var jdbc=jdbc(context); var recovery=context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyRecoveryService.class);
                jdbc.update("UPDATE sc_recharge_frequency_queue SET next_check_at=DATE_SUB(NOW(),INTERVAL 1 SECOND)"); recovery.repair(queueId(jdbc));
                assertThat(jdbc.queryForObject("SELECT last_backlog_alert_at FROM sc_recharge_frequency_queue",java.time.LocalDateTime.class)).isEqualTo(alertAt);
                jdbc.update("UPDATE sc_recharge_frequency_queue SET last_backlog_alert_at=DATE_SUB(NOW(),INTERVAL 1801 SECOND),next_check_at=DATE_SUB(NOW(),INTERVAL 1 SECOND)"); recovery.repair(queueId(jdbc));
                assertThat(jdbc.queryForObject("SELECT last_backlog_alert_at FROM sc_recharge_frequency_queue",java.time.LocalDateTime.class)).isAfter(alertAt);
                var frequency=context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle.class);
                var op=RechargeFrequencyExecutionIntegrationTest.operations(jdbc).getFirst(); var permit=frequency.claim(op); frequency.markSendPossible(permit);
                jdbc.update("UPDATE sc_recharge_frequency_queue SET acquired_at=DATE_SUB(NOW(),INTERVAL 1801 SECOND),next_check_at=DATE_SUB(NOW(),INTERVAL 1 SECOND)"); recovery.repair(queueId(jdbc));
                assertThat(jdbc.queryForObject("SELECT occupied_alert_active FROM sc_recharge_frequency_queue",Boolean.class)).isTrue();
                // 可信整单失败才能解除占用，告警恢复不参与释放授权。
                context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.SupplierOrderLifecycle.class).confirmTrustedOutcome(op,RechargeFrequencyLifecycleIntegrationTest.fact(com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass.REJECTED),"ALARM-END",permit);
                recovery.repair(queueId(jdbc));
                assertThat(jdbc.queryForObject("SELECT occupied_alert_active FROM sc_recharge_frequency_queue",Boolean.class)).isFalse();
                assertThat(jdbc.queryForObject("SELECT backlog_alert_active FROM sc_recharge_frequency_queue",Boolean.class)).isFalse();
            }
        } finally { fixture.stopContainer(); }
    }
    @Test void repeatedLocalCredentialFailuresCreateDelayedWakeAndRecoverOriginalOperation() {
        var fixture=new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try(var context=fixture.applicationContext()) {
            fixture.seed(context); context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("LOCAL-FAULT",1L));
            var jdbc=jdbc(context); var operation=RechargeFrequencyExecutionIntegrationTest.operations(jdbc).getFirst();
            var handler=context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationExecuteHandler.class);
            var credentials=context.getBean(RechargeFrequencyExecutionIntegrationTest.MutableCredentialProvider.class); credentials.fail=true;
            var payload=context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class).createObjectNode().put("operationNo",operation);
            for(int attempt=0;attempt<2;attempt++) {
                org.assertj.core.api.Assertions.assertThatThrownBy(()->handler.handle(RechargeFrequencyExecutionIntegrationTest.timer(),payload)).hasMessage("CREDENTIAL_UNAVAILABLE");
                assertThat(jdbc.queryForObject("SELECT next_check_at >= DATE_ADD(NOW(3),INTERVAL 29 SECOND) FROM sc_recharge_frequency_queue",Boolean.class)).isTrue();
                assertThat(jdbc.queryForObject("SELECT next_fire_at >= DATE_ADD(NOW(3),INTERVAL 29 SECOND) FROM sc_timer_task WHERE task_type='RECHARGE_FREQUENCY_WAKE' AND idempotency_key=?",Boolean.class,queueId(jdbc)+":"+generation(jdbc))).isTrue();
                assertThat(jdbc.queryForObject("SELECT attempt_count FROM sc_fulfillment_operation",Integer.class)).isZero();
                assertThat(context.getBean(RechargeFrequencyExecutionIntegrationTest.CountingExecutor.class).calls.get()).isZero();
            }
            // 早到/重复任务不能忽略持久化fireAt，把新任务提前推进。
            context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyRecoveryService.class).prepareWake(queueId(jdbc),generation(jdbc));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_task WHERE task_type='FULFILLMENT_OPERATION_EXECUTE'",Long.class)).isZero();
            credentials.fail=false;
            dueWork(context);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_task WHERE task_type='FULFILLMENT_OPERATION_EXECUTE'",Long.class)).isEqualTo(1L);
            handler.handle(RechargeFrequencyExecutionIntegrationTest.timer(),payload);
            assertThat(context.getBean(RechargeFrequencyExecutionIntegrationTest.CountingExecutor.class).calls.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT enqueue_sequence FROM sc_recharge_frequency_entry",Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_fund_reservation",Long.class)).isEqualTo(1L);
        } finally { fixture.stopContainer(); }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    static class AlarmRollbackConfiguration {
        @org.springframework.context.annotation.Bean static AlarmRollbackHook alarmRollbackHook() { return new AlarmRollbackHook(); }
    }
    /** 只注入控制行保存故障，QUEUE行锁与实际事务仍由MySQL执行；验证回滚不发首次告警。 */
    static class AlarmRollbackHook implements org.springframework.beans.factory.config.BeanPostProcessor {
        volatile boolean fail;
        @Override public Object postProcessAfterInitialization(Object bean,String name) {
            if (!(bean instanceof com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyQueueMapper mapper)) { return bean; }
            var wrapped=org.mockito.Mockito.spy(mapper);
            org.mockito.Mockito.doAnswer(invocation -> {
                if (fail) { throw new IllegalStateException("fixture queue write unavailable"); }
                return invocation.callRealMethod();
            }).when(wrapped).updateById(org.mockito.ArgumentMatchers.any(com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyQueueDO.class));
            return wrapped;
        }
    }

    static com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyRecoveryService.QueryWork dueWork(org.springframework.context.ConfigurableApplicationContext context) {
        var jdbc=jdbc(context); jdbc.update("UPDATE sc_recharge_frequency_queue SET next_check_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND)");
        jdbc.update("UPDATE sc_timer_task SET next_fire_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE task_type='RECHARGE_FREQUENCY_WAKE' AND idempotency_key=?",queueId(jdbc)+":"+generation(jdbc));
        return context.getBean(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyRecoveryService.class).prepareWake(queueId(jdbc),generation(jdbc));
    }
    static void enableQuery(org.springframework.context.ConfigurableApplicationContext context) {
        var resolver=context.getBean(com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.class);
        long version=jdbc(context).queryForObject("SELECT id FROM sc_supplier_connector_version LIMIT 1",Long.class);
        var original=resolver.resolve(version);
        org.mockito.Mockito.when(resolver.resolve(version)).thenReturn(new com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration(
                original.connectorNo(),original.connectorVersionId(),original.versionNo(),original.adapterType(),original.baseUrl(),original.merchantAccount(),original.credentialSlotId(),original.callbackPath(),
                java.util.Set.of(com.aifuli.module.supplychain.supplieradapter.metadata.Capability.CREATE_RECHARGE,com.aifuli.module.supplychain.supplieradapter.metadata.Capability.QUERY_ORDER)));
    }
    static JdbcTemplate jdbc(org.springframework.context.ConfigurableApplicationContext context) { return new JdbcTemplate(context.getBean("supplyChainDataSource",DataSource.class)); }
    static Long queueId(JdbcTemplate jdbc) { return jdbc.queryForObject("SELECT id FROM sc_recharge_frequency_queue",Long.class); }
    static long generation(JdbcTemplate jdbc) { return jdbc.queryForObject("SELECT wake_generation FROM sc_recharge_frequency_queue",Long.class); }
    static long wakeCount(JdbcTemplate jdbc) { return jdbc.queryForObject("SELECT COUNT(*) FROM sc_timer_task WHERE task_type='RECHARGE_FREQUENCY_WAKE'",Long.class); }
}
