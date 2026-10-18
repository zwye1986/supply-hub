package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaService;
import com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaService.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static com.aifuli.module.supplychain.tradecore.supply.domain.MerchantQuotaPlatform.*;
import static org.assertj.core.api.Assertions.*;

/** 真实MySQL公开额度应用入口；不启用生产供给，不用Connector版本限额作默认值。 */
class MerchantQuotaIntegrationTest {
    private final SingleStepFrozenConfigurationIntegrationTest fixture = new SingleStepFrozenConfigurationIntegrationTest();
    private MerchantQuotaService quota;
    private TransactionTemplate tx;
    @BeforeEach void start() {
        fixture.startWith(Config.class); quota = fixture.context.getBean(MerchantQuotaService.class);
        tx = new TransactionTemplate(fixture.context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
    }
    @AfterEach void stop() { fixture.stop(); }
    @Test void configurationAndSharedConcurrencyAreEnforcedWithoutDefault() {
        assertThat(quota.configure(new Configure(RENYUE, "Merchant", 10, 1, null, 1L, "隔离测试"))).isNotNull();
        tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-A", 1, now().plusSeconds(60)));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-B", 1, now().plusSeconds(60))))
                .isInstanceOf(CapacityUnavailable.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> quota.admit(RENYUE, "missing", "OP-C", 1, now().plusSeconds(60))))
                .isInstanceOf(MissingConfiguration.class);
    }
    @Test void releaseDoesNotRefundRollingRateAndConfigCasDoesNotResetUsage() {
        var config = quota.configure(new Configure(RENYUE, "Merchant", 1, 5, null, 1L, "隔离测试"));
        assertThat(config).isNotNull();
        tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-A", 1, now().plusSeconds(60)));
        quota.release("OP-A", 1);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-B", 1, now().plusSeconds(60))))
                .isInstanceOf(CapacityUnavailable.class);
        assertThatThrownBy(() -> quota.configure(new Configure(RENYUE, "Merchant", 5, 5, 99L, 1L, "错误版本"))).isInstanceOf(IllegalStateException.class);
        assertThat(quota.configure(new Configure(RENYUE, "Merchant", 1, 2, config.version(), 1L, "调低并发"))).isNotNull();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-B", 1, now().plusSeconds(60))))
                .isInstanceOf(CapacityUnavailable.class);
    }
    @Test void invalidConfigurationFailsAndAccountsAreExact() {
        assertThatThrownBy(() -> quota.configure(new Configure(RENYUE, "Merchant", 0, 1, null, 1L, "非法"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(quota.configure(new Configure(RENYUE, "Merchant", 10, 1, null, 1L, "隔离测试"))).isNotNull();
        assertThat(quota.configure(new Configure(RENYUE, "merchant", 10, 1, null, 1L, "大小写独立"))).isNotNull();
        assertThat(quota.configure(new Configure(QNM, "Merchant", 10, 1, null, 1L, "平台独立"))).isNotNull();
        tx.executeWithoutResult(s -> { quota.admit(RENYUE, "Merchant", "OP-A", 1, now().plusSeconds(60)); quota.admit(RENYUE, "merchant", "OP-B", 1, now().plusSeconds(60)); quota.admit(QNM, "Merchant", "OP-C", 1, now().plusSeconds(60)); });
    }
    @Test void rolledBackAdmissionLeavesNoOccupation() {
        assertThat(quota.configure(new Configure(RENYUE, "Merchant", 10, 1, null, 1L, "隔离测试"))).isNotNull();
        tx.executeWithoutResult(s -> { quota.admit(RENYUE, "Merchant", "OP-A", 1, now().plusSeconds(60)); s.setRollbackOnly(); });
        tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-B", 1, now().plusSeconds(60)));
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_merchant_call_lease", Long.class)).isEqualTo(1);
    }
    @Test void independentContainersCompeteOnSameDatabaseRootLock() throws Exception {
        quota.configure(new Configure(RENYUE, "Merchant", 100, 1, null, 1L, "双实例隔离测试"));
        try (var second = new FulfillmentAcceptanceIntegrationTest().applicationContext(SingleStepFrozenConfigurationIntegrationTest.Config.class, Config.class)) {
            var secondQuota = second.getBean(MerchantQuotaService.class);
            var secondTx = new TransactionTemplate(second.getBean("supplyChainTransactionManager", PlatformTransactionManager.class));
            var gate = new java.util.concurrent.CountDownLatch(1);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> { gate.await(); try { tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-first", 1, now().plusSeconds(60))); return true; } catch (CapacityUnavailable denied) { return false; } });
                var other = pool.submit(() -> { gate.await(); try { secondTx.executeWithoutResult(s -> secondQuota.admit(RENYUE, "Merchant", "OP-second", 1, now().plusSeconds(60))); return true; } catch (CapacityUnavailable denied) { return false; } });
                gate.countDown();
                assertThat(List.of(first.get(), other.get())).containsExactlyInAnyOrder(true, false);
            }
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_merchant_call_lease", Long.class)).isEqualTo(1);
        }
    }
    @Test void loweringConcurrencyDoesNotCancelOccupantsAndRaisingDoesNotResetThem() {
        var config = quota.configure(new Configure(RENYUE, "Merchant", 100, 5, null, 1L, "隔离测试"));
        for (int i = 0; i < 4; i++) { String op = "OP-" + i; tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", op, 1, now().plusSeconds(60))); }
        var lowered = quota.configure(new Configure(RENYUE, "Merchant", 100, 2, config.version(), 1L, "降额"));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-next", 1, now().plusSeconds(60)))).isInstanceOf(CapacityUnavailable.class);
        quota.release("OP-0", 1); quota.release("OP-1", 1);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-next", 1, now().plusSeconds(60)))).isInstanceOf(CapacityUnavailable.class);
        quota.release("OP-2", 1);
        tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-next", 1, now().plusSeconds(60)));
        quota.configure(new Configure(RENYUE, "Merchant", 100, 3, lowered.version(), 1L, "升额"));
        tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-raised", 1, now().plusSeconds(60)));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-over", 1, now().plusSeconds(60)))).isInstanceOf(CapacityUnavailable.class);
    }
    @Test void expiredLeaseAndRollingBoundaryPermitNewCallButNeverReplayGeneration() {
        quota.configure(new Configure(RENYUE, "Merchant", 1, 1, null, 1L, "隔离测试"));
        tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-A", 1, now().plusSeconds(60)));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-A", 1, now().plusSeconds(60)))).isInstanceOf(IllegalStateException.class);
        fixture.jdbc.update("UPDATE sc_supplier_merchant_call_lease SET admitted_at=DATE_SUB(NOW(3),INTERVAL 2 SECOND),lease_until=DATE_SUB(NOW(3),INTERVAL 1 SECOND)");
        tx.executeWithoutResult(s -> quota.admit(RENYUE, "Merchant", "OP-B", 1, now().plusSeconds(60)));
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_merchant_call_lease", Long.class)).isEqualTo(1);
        assertThat(fixture.jdbc.queryForObject("SELECT operation_no FROM sc_supplier_merchant_call_lease", String.class)).isEqualTo("OP-B");
    }
    private java.time.LocalDateTime now() { return fixture.context.getBean(SupplyChainDatabaseClock.class).now(); }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false) @Import(MerchantQuotaService.class) static class Config { }
}
