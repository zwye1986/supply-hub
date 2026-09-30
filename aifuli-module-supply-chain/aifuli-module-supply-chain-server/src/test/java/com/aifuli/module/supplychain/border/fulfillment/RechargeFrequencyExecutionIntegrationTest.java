package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentCommandFacade;
import com.aifuli.module.supplychain.border.fulfillment.application.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.infrastructure.security.CredentialProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 真实MySQL FIFO执行门禁，见设计 #42-认领。 */
class RechargeFrequencyExecutionIntegrationTest {
    @Test void nonHeadCannotAcquireExecution() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(ExecutionConfiguration.class)) {
            fixture.seed(context);
            var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("FIFO-HEAD", 1L));
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("FIFO-TAIL", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var operations = jdbc.queryForList("SELECT active_operation_no FROM sc_recharge_frequency_entry ORDER BY enqueue_sequence", String.class);
            assertThat(context.getBean(SupplierOrderLifecycle.class).claimForExecution(operations.get(1))).isFalse();
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM sc_fulfillment_operation WHERE operation_no=?", Integer.class, operations.get(1))).isZero();
        } finally { fixture.stopContainer(); }
    }
    @Test void concurrentClaimOnlyGrantsHeadAndFreezesCommittedVersions() throws Exception {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(ExecutionConfiguration.class)) {
            fixture.seed(context); var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("CLAIM-ONE", 1L));
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("CLAIM-TWO", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var ops = operations(jdbc); var lifecycle = context.getBean(RechargeFrequencyLifecycle.class);
            var barrier = new CyclicBarrier(3);
            try (var pool = Executors.newFixedThreadPool(3)) {
                var one = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return lifecycle.claim(ops.getFirst()); });
                var duplicate = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return lifecycle.claim(ops.getFirst()); });
                var tail = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return lifecycle.claim(ops.getLast()); });
                var permits = Arrays.asList(one.get(20, TimeUnit.SECONDS), duplicate.get(20, TimeUnit.SECONDS), tail.get(20, TimeUnit.SECONDS));
                assertThat(permits.stream().filter(Objects::nonNull).count()).isEqualTo(1L);
                assertThat(tail.get()).isNull();
                var permit = permits.stream().filter(Objects::nonNull).findFirst().orElseThrow();
                assertThat(permit.entryVersion()).isEqualTo(jdbc.queryForObject("SELECT version FROM sc_recharge_frequency_entry WHERE id=?", Long.class, permit.entryId()));
                assertThat(permit.operationVersion()).isEqualTo(jdbc.queryForObject("SELECT version FROM sc_fulfillment_operation WHERE operation_no=?", Long.class, permit.operationNo()));
                assertThat(lifecycle.markSendPossible(permit)).isTrue();
                assertThat(lifecycle.markSendPossible(permit)).isFalse();
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_recharge_frequency_entry WHERE status=10", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM sc_fulfillment_operation WHERE operation_no=?", Integer.class, ops.getLast())).isZero();
        } finally { fixture.stopContainer(); }
    }

    @Test void revokedPreparedCannotSendAndOriginalOrderReclaimsWithNewGeneration() throws Exception {
        competeBoundary(true);
    }
    @Test void committedMarkerRejectsRevokeAndLostExecutorStillHolds() throws Exception {
        competeBoundary(false);
    }
    private void competeBoundary(boolean revokeFirst) throws Exception {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(ExecutionConfiguration.class)) {
            fixture.seed(context); context.getBean(BuyerFulfillmentCommandFacade.class)
                    .acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("BOUNDARY", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var lifecycle = context.getBean(RechargeFrequencyLifecycle.class); var op = operations(jdbc).getFirst();
            var permit = lifecycle.claim(op); var committed = new CountDownLatch(1); var barrier = new CyclicBarrier(2);
            var sends = new AtomicInteger();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS);
                    boolean result = revokeFirst ? lifecycle.revokePrepared(permit) : lifecycle.markSendPossible(permit);
                    committed.countDown(); return result; });
                var second = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS);
                    assertThat(committed.await(15, TimeUnit.SECONDS)).isTrue();
                    boolean result = revokeFirst ? lifecycle.markSendPossible(permit) : lifecycle.revokePrepared(permit);
                    if (revokeFirst && result) { sends.incrementAndGet(); } return result; });
                assertThat(first.get(20, TimeUnit.SECONDS)).isTrue();
                assertThat(second.get(20, TimeUnit.SECONDS)).isFalse();
            }
            assertThat(sends.get()).isZero();
            if (revokeFirst) {
                assertThat(jdbc.queryForObject("SELECT status FROM sc_recharge_frequency_entry", Short.class)).isZero();
                assertThat(jdbc.queryForObject("SELECT enqueue_sequence FROM sc_recharge_frequency_entry", Long.class)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isNull();
                var reclaimed = lifecycle.claim(op);
                assertThat(reclaimed.generation()).isGreaterThan(permit.generation());
                assertThat(lifecycle.markSendPossible(permit)).isFalse();
                assertThat(lifecycle.markSendPossible(reclaimed)).isTrue();
            } else {
                jdbc.update("UPDATE sc_recharge_frequency_queue SET acquired_at=DATE_SUB(acquired_at, INTERVAL 31 MINUTE)");
                assertThat(lifecycle.revokePrepared(permit)).isFalse();
                assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isNotNull();
                assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isPositive();
            }
        } finally { fixture.stopContainer(); }
    }

    @Test void handlerCannotSendMissingMobileMigrationEntryButAllowsTrustedExternalAccount() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(ExecutionConfiguration.class)) {
            fixture.seed(context); var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("MISSING-ENTRY", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var op = operations(jdbc).getFirst(); jdbc.update("DELETE FROM sc_recharge_frequency_entry");
            var handler = context.getBean(FulfillmentOperationExecuteHandler.class); var counter = context.getBean(CountingExecutor.class);
            assertThat(handler.handle(timer(), context.getBean(ObjectMapper.class).createObjectNode().put("operationNo", op)))
                    .isEqualTo("RECHARGE_FREQUENCY_MIGRATION_MISSING");
            assertThat(counter.calls.get()).isZero();
            assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isPositive();
            var request = FulfillmentAcceptanceIntegrationTest.request("EXTERNAL-ACCOUNT", 1L);
            request.getTarget().setValues(Map.of("accountType", "EXTERNAL_ACCOUNT", "accountValue", "trusted-external")); facade.acceptFulfillment(request);
            var externalOp = jdbc.queryForObject("SELECT operation_no FROM sc_fulfillment_operation ORDER BY id DESC LIMIT 1", String.class);
            assertThat(handler.handle(timer(), context.getBean(ObjectMapper.class).createObjectNode().put("operationNo", externalOp)))
                    .isEqualTo("ACCEPTED");
            assertThat(counter.calls.get()).isEqualTo(1);
        } finally { fixture.stopContainer(); }
    }
    @Test void credentialFailureReturnsOriginalWaitingWithoutCountingExternalAttempt() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(ExecutionConfiguration.class)) {
            fixture.seed(context); context.getBean(BuyerFulfillmentCommandFacade.class)
                    .acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("CREDENTIAL-FAIL", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var op = operations(jdbc).getFirst(); var handler = context.getBean(FulfillmentOperationExecuteHandler.class);
            var provider = context.getBean(MutableCredentialProvider.class); provider.fail = true;
            var payload = context.getBean(ObjectMapper.class).createObjectNode().put("operationNo", op);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(timer(), payload))
                    .isInstanceOf(IllegalStateException.class).hasMessage("CREDENTIAL_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT status FROM sc_recharge_frequency_entry", Short.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM sc_fulfillment_operation", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isPositive();
            provider.fail = false;
            assertThat(handler.handle(timer(), payload)).isEqualTo("ACCEPTED");
            assertThat(context.getBean(CountingExecutor.class).calls.get()).isEqualTo(1);
        } finally { fixture.stopContainer(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"FULFILL", "QUERY_ORDER"})
    void credentialFailureWithPrebuiltFutureOperationOrQueryReturnsWaitingAndRetriesOnce(String kind) {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(ExecutionConfiguration.class)) {
            fixture.seed(context); context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("PREBUILT-"+kind, 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var op = operations(jdbc).getFirst(); long opId = jdbc.queryForObject("SELECT id FROM sc_fulfillment_operation WHERE operation_no=?", Long.class, op);
            if ("QUERY_ORDER".equals(kind)) { jdbc.update("UPDATE sc_fulfillment_operation SET sequence_no=2 WHERE id=?", opId); }
            RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc, "sc_fulfillment_operation", opId,
                    Map.of("operation_no", "OP_PREBUILT", "sequence_no", "QUERY_ORDER".equals(kind) ? 1 : 2, "operation_kind", kind, "idempotency_key", "PREBUILT"));
            long frozen = jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class);
            long wake = jdbc.queryForObject("SELECT wake_generation FROM sc_recharge_frequency_queue", Long.class);
            var provider = context.getBean(MutableCredentialProvider.class); provider.fail = true;
            var handler = context.getBean(FulfillmentOperationExecuteHandler.class); var payload = context.getBean(ObjectMapper.class).createObjectNode().put("operationNo", op);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(timer(), payload)).hasMessage("CREDENTIAL_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT status FROM sc_recharge_frequency_entry", Short.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT status FROM sc_fulfillment_operation WHERE id=?", Short.class, opId)).isZero();
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM sc_fulfillment_operation WHERE id=?", Integer.class, opId)).isZero();
            assertThat(jdbc.queryForObject("SELECT enqueue_sequence FROM sc_recharge_frequency_entry", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(frozen);
            assertThat(jdbc.queryForObject("SELECT wake_generation FROM sc_recharge_frequency_queue", Long.class)).isGreaterThan(wake);
            assertThat(jdbc.queryForObject("SELECT next_check_at FROM sc_recharge_frequency_queue", java.time.LocalDateTime.class)).isNotNull();
            provider.fail = false; assertThat(handler.handle(timer(), payload)).isEqualTo("ACCEPTED");
            assertThat(context.getBean(CountingExecutor.class).calls.get()).isEqualTo(1);
        } finally { fixture.stopContainer(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 3})
    void credentialFailureWithUnconfirmedChargingRiskKeepsHolderEvenWhenRiskIsLater(int riskSequence) {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(ExecutionConfiguration.class)) {
            fixture.seed(context); context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("RISK-"+riskSequence, 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class)); var op = operations(jdbc).getFirst();
            long opId = jdbc.queryForObject("SELECT id FROM sc_fulfillment_operation WHERE operation_no=?", Long.class, op);
            jdbc.update("UPDATE sc_fulfillment_operation SET sequence_no=2 WHERE id=?", opId);
            RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc, "sc_fulfillment_operation", opId,
                    Map.of("operation_no", "OP_UNKNOWN_RISK", "sequence_no", riskSequence, "status", 30, "result_class", "RESULT_UNKNOWN", "attempt_count", 1, "idempotency_key", "UNKNOWN_RISK"));
            long frozen = jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class);
            var provider = context.getBean(MutableCredentialProvider.class); provider.fail = true;
            var handler = context.getBean(FulfillmentOperationExecuteHandler.class); var payload = context.getBean(ObjectMapper.class).createObjectNode().put("operationNo", op);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(timer(), payload)).hasMessage("CREDENTIAL_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT status FROM sc_recharge_frequency_entry", Short.class)).isEqualTo((short)10);
            assertThat(jdbc.queryForObject("SELECT dispatch_phase FROM sc_recharge_frequency_entry", Short.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isEqualTo(frozen);
            provider.fail = false; assertThat(handler.handle(timer(), payload)).isEqualTo("ALREADY_EXECUTING");
            assertThat(context.getBean(CountingExecutor.class).calls.get()).isZero();
        } finally { fixture.stopContainer(); }
    }

    @Test void preparationAndRevocationStorageFailuresKeepSafeDualCausesAndRollbackPreparedState() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(ExecutionConfiguration.class, RevokeStorageFailureConfiguration.class)) {
            fixture.seed(context); context.getBean(BuyerFulfillmentCommandFacade.class).acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("DOUBLE-FAULT", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class)); var op = operations(jdbc).getFirst();
            var provider = context.getBean(MutableCredentialProvider.class); provider.fail = true;
            provider.failure = new IllegalArgumentException("phone=13800138000 credential=evil-credential");
            context.getBean(RevokeStorageFailureHook.class).armed = true;
            var handler = context.getBean(FulfillmentOperationExecuteHandler.class); var payload = context.getBean(ObjectMapper.class).createObjectNode().put("operationNo", op);
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> handler.handle(timer(), payload));
            assertThat(failure).hasMessage("CREDENTIAL_UNAVAILABLE"); assertThat(failure.getCause().getMessage()).contains("IllegalArgumentException");
            assertThat(failure.getSuppressed()).hasSize(1); assertThat(failure.getSuppressed()[0].getMessage()).contains("IllegalStateException", "SQLException");
            var text = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(text));
            assertThat(text.toString()).doesNotContain("13800138000", "evil-credential", "evil-storage");
            assertThat(jdbc.queryForObject("SELECT status FROM sc_fulfillment_operation", Short.class)).isEqualTo((short)20);
            assertThat(jdbc.queryForObject("SELECT status FROM sc_recharge_frequency_entry", Short.class)).isEqualTo((short)10);
            assertThat(jdbc.queryForObject("SELECT dispatch_phase FROM sc_recharge_frequency_entry", Short.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isNotNull();
            assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isPositive();
            assertThat(context.getBean(CountingExecutor.class).calls.get()).isZero();
        } finally { fixture.stopContainer(); }
    }
    @Configuration(proxyBeanMethods=false)
    static class RevokeStorageFailureConfiguration { @Bean static RevokeStorageFailureHook revokeStorageFailure() { return new RevokeStorageFailureHook(); } }
    /** 在真实撤销事务已更新Operation/Entry之后抛储存异常，验证两次写入完整回滚。 */
    static class RevokeStorageFailureHook implements org.springframework.beans.factory.config.BeanPostProcessor {
        volatile boolean armed;
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyEntryMapper)) { return bean; }
            var factory = new org.springframework.aop.framework.ProxyFactory(bean);
            factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                Object result = invocation.proceed();
                if (armed && invocation.getMethod().getName().equals("updateById") && invocation.getArguments()[0] instanceof com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyEntryDO entry && entry.getStatus() == RechargeFrequencyEntryStatus.WAITING.getCode()) {
                    throw new IllegalStateException("phone=13800138000 secret=evil-storage", new java.sql.SQLException("evil-storage credential"));
                }
                return result;
            });
            return factory.getProxy();
        }
    }

    @Test void sameMobileDifferentSpuClaimsConcurrently() throws Exception {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(ExecutionConfiguration.class)) {
            var seed = fixture.seed(context); var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            long listing = jdbc.queryForObject("SELECT id FROM sc_buyer_listing WHERE listing_no=?", Long.class, seed.listingNo());
            long sku = jdbc.queryForObject("SELECT platform_sku_id FROM sc_buyer_listing WHERE id=?", Long.class, listing);
            long spu = jdbc.queryForObject("SELECT spu_id FROM sc_platform_sku WHERE id=?", Long.class, sku);
            long newSpu = RechargeFrequencyAcceptanceIntegrationTest.cloneRow(jdbc, "sc_platform_spu", spu, Map.of("spu_code", "SPU_CONCURRENT", "code_scope_no", 2));
            long newSku = RechargeFrequencyAcceptanceIntegrationTest.cloneSkuAndSupply(jdbc, sku, "SKU_CONCURRENT", newSpu);
            RechargeFrequencyAcceptanceIntegrationTest.cloneListing(jdbc, listing, "LST_CONCURRENT", "WS-CONCURRENT", seed.buyerId(), newSku);
            var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("SPU-ONE", 1L));
            var other = FulfillmentAcceptanceIntegrationTest.request("SPU-TWO", 1L); other.setBuyerSkuCode("WS-CONCURRENT"); facade.acceptFulfillment(other);
            var ops = operations(jdbc); var lifecycle = context.getBean(RechargeFrequencyLifecycle.class); var barrier = new CyclicBarrier(2);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var one = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return lifecycle.claim(ops.getFirst()); });
                var two = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return lifecycle.claim(ops.getLast()); });
                assertThat(one.get(20, TimeUnit.SECONDS)).isNotNull(); assertThat(two.get(20, TimeUnit.SECONDS)).isNotNull();
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_recharge_frequency_queue WHERE holder_entry_id IS NOT NULL", Long.class)).isEqualTo(2L);
        } finally { fixture.stopContainer(); }
    }

    @Test void handlerRevokeCommittedBeforeMarkerMakesZeroAdapterCalls() throws Exception { handlerBoundary(false); }
    @Test void handlerNodeLossAfterMarkerStillRejectsRevokeAndHoldsFunds() throws Exception { handlerBoundary(true); }
    private void handlerBoundary(boolean markerFirst) throws Exception {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(ExecutionConfiguration.class, BoundaryConfiguration.class)) {
            fixture.seed(context); context.getBean(BuyerFulfillmentCommandFacade.class)
                    .acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("HANDLER-BOUNDARY", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var op = operations(jdbc).getFirst(); var hook = context.getBean(SendBoundaryHook.class);
            hook.markerFirst = markerFirst; hook.armed = true;
            var payload = context.getBean(ObjectMapper.class).createObjectNode().put("operationNo", op);
            var handler = context.getBean(FulfillmentOperationExecuteHandler.class);
            var lifecycle = context.getBean(RechargeFrequencyLifecycle.class);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var sender = pool.submit(() -> handler.handle(timer(), payload));
                var revoker = pool.submit(() -> {
                    assertThat(hook.reached.await(15, TimeUnit.SECONDS)).isTrue();
                    try { return lifecycle.revokePrepared(hook.permit); }
                    finally { hook.resume.countDown(); }
                });
                assertThat(revoker.get(20, TimeUnit.SECONDS)).isEqualTo(!markerFirst);
                if (markerFirst) {
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> sender.get(20, TimeUnit.SECONDS))
                            .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(SimulatedNodeLoss.class);
                } else { assertThat(sender.get(20, TimeUnit.SECONDS)).isEqualTo("SEND_PERMIT_SKIPPED"); }
            }
            assertThat(context.getBean(CountingExecutor.class).calls.get()).isZero();
            assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isPositive();
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class))
                    .isEqualTo(markerFirst ? hook.permit.entryId() : null);
        } finally { fixture.stopContainer(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class BoundaryConfiguration {
        @Bean static SendBoundaryHook boundaryHook() { return new SendBoundaryHook(); }
    }
    /** 仅在真实事务代理的marker前/提交后插屏障；不替换认领、行锁、版本或持久化行为。 */
    static class SendBoundaryHook implements org.springframework.beans.factory.config.BeanPostProcessor {
        volatile boolean armed; volatile boolean markerFirst; volatile RechargeFrequencyExecutionPermit permit;
        final CountDownLatch reached = new CountDownLatch(1); final CountDownLatch resume = new CountDownLatch(1);
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof RechargeFrequencyLifecycle lifecycle)) { return bean; }
            var factory = new org.springframework.aop.framework.ProxyFactory(lifecycle);
            factory.setProxyTargetClass(true);
            factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                if (!armed || !invocation.getMethod().getName().equals("markSendPossible")) { return invocation.proceed(); }
                permit = (RechargeFrequencyExecutionPermit) invocation.getArguments()[0];
                if (!markerFirst) {
                    reached.countDown(); assertThat(resume.await(15, TimeUnit.SECONDS)).isTrue();
                    return invocation.proceed();
                }
                boolean marked = (Boolean) invocation.proceed(); assertThat(marked).isTrue();
                reached.countDown(); assertThat(resume.await(15, TimeUnit.SECONDS)).isTrue();
                throw new SimulatedNodeLoss();
            });
            return factory.getProxy();
        }
    }
    static class SimulatedNodeLoss extends Error { }

    @Test void ticketAssemblyFailureRevokesOriginalPreparedAndPropagatesSafeRetryableError() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(ExecutionConfiguration.class, CallbackFailureConfiguration.class)) {
            fixture.seed(context); context.getBean(BuyerFulfillmentCommandFacade.class)
                    .acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("TICKET-FAIL", 1L));
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var op = operations(jdbc).getFirst(); var hook = context.getBean(CallbackAssemblyFailureHook.class); hook.armed = true;
            var handler = context.getBean(FulfillmentOperationExecuteHandler.class);
            var payload = context.getBean(ObjectMapper.class).createObjectNode().put("operationNo", op);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handle(timer(), payload))
                    .isInstanceOf(IllegalStateException.class).hasMessage("TICKET_PREPARATION_FAILED");
            assertThat(jdbc.queryForObject("SELECT status FROM sc_recharge_frequency_entry", Short.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT holder_entry_id FROM sc_recharge_frequency_queue", Long.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM sc_fulfillment_operation", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account", Long.class)).isPositive();
            assertThat(jdbc.queryForObject("SELECT enqueue_sequence FROM sc_recharge_frequency_entry", Long.class)).isEqualTo(1L);
            hook.armed = false;
            assertThat(handler.handle(timer(), payload)).isEqualTo("ACCEPTED");
            assertThat(context.getBean(CountingExecutor.class).calls.get()).isEqualTo(1);
            assertThat(operations(jdbc)).containsExactly(op);
        } finally { fixture.stopContainer(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class CallbackFailureConfiguration {
        @Bean static CallbackAssemblyFailureHook callbackFailure() { return new CallbackAssemblyFailureHook(); }
    }
    /** 只在本地ticket读取回调配置时注入故障，不替换队列、事务、adapter执行与资金服务。 */
    static class CallbackAssemblyFailureHook implements org.springframework.beans.factory.config.BeanPostProcessor {
        volatile boolean armed;
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof FulfillmentCallbackProperties properties)) { return bean; }
            var wrapped = org.mockito.Mockito.spy(properties);
            org.mockito.Mockito.doAnswer(invocation -> {
                if (armed) { throw new IllegalStateException("test callback assembly unavailable"); }
                return invocation.callRealMethod();
            }).when(wrapped).getPublicCallbackBaseUrl();
            return wrapped;
        }
    }

    static List<String> operations(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT active_operation_no FROM sc_recharge_frequency_entry ORDER BY enqueue_sequence", String.class);
    }
    static TimerTaskContext timer() { return new TimerTaskContext("FREQ-TEST", 99L, 1, 10L, "test-worker"); }
    @Configuration(proxyBeanMethods=false)
    @Import({SupplierOrderLifecycle.class, FulfillmentOperationExecuteHandler.class})
    static class ExecutionConfiguration {
        @Bean FulfillmentCallbackProperties callbacks() { return new FulfillmentCallbackProperties(); }
        @Bean PublishedConnectorConfigurationResolver connectorResolver(DataSource supplyChainDataSource) {
            var resolver = mock(PublishedConnectorConfigurationResolver.class);
            org.mockito.Mockito.when(resolver.resolve(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation ->
                    new PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration("CON_QNM", invocation.getArgument(0), 1,
                            "QNM_GAME_ORDER_V1", "https://supplier.invalid", "fixture",
                            new JdbcTemplate(supplyChainDataSource).queryForObject("SELECT credential_slot_id FROM sc_supplier_connector_version WHERE id=?", Long.class, invocation.getArgument(0, Long.class)), null,
                            Set.of(com.aifuli.module.supplychain.supplieradapter.metadata.Capability.CREATE_RECHARGE)));
            return resolver;
        }
        @Bean MutableCredentialProvider credentialProvider() { return new MutableCredentialProvider(); }
        @Bean CountingExecutor executor() { return new CountingExecutor(); }
    }
    static class MutableCredentialProvider implements CredentialProvider {
        volatile boolean fail;
        volatile RuntimeException failure = new IllegalStateException("test credential unavailable");
        public boolean supports(String providerType) { return true; }
        public boolean isResolvable(com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO slot) { return !fail; }
        public char[] resolve(com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO slot) {
            if (fail) { throw failure; } return "test-secret".toCharArray();
        }
    }
    static class CountingExecutor implements OperationExecutorPort {
        final AtomicInteger calls = new AtomicInteger();
        public boolean supports(String adapterType) { return true; }
        public OperationOutcome execute(OperationTicket ticket) { calls.incrementAndGet(); return OperationOutcome.builder().outcomeClass(OperationOutcomeClass.ACCEPTED).build(); }
    }
}
