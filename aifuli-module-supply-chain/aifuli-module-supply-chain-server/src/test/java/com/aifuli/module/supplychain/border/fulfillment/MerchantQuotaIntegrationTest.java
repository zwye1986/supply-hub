package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaAdminService;
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
        assertThat(quota.release("OP-A", 1)).isTrue();
        assertThat(quota.release("OP-A", 1)).as("重复释放可确认已释放的真实租约").isTrue();
        assertThat(quota.release("OP-empty", 1)).as("空租约不作为数据库回收恢复证据").isFalse();
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

    /** 配置入口对审计原子性负责，不能只在管理端Controller外层另开事务补记。 */
    @Test void configurationHistoryIsAppendOnlyAndAuditFailureRollsBackBothCreateAndUpdate() {
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='sc_supplier_merchant_quota_audit'", Long.class))
                .as("追加审计表须由全量Liquibase迁移创建").isEqualTo(1);
        var created = quota.configure(new Configure(RENYUE, "AuditMerchant", 10, 3, null, 71L, "创建"));
        quota.configure(new Configure(RENYUE, "AuditMerchant", 7, 2, created.version(), 72L, "降额"));
        var history = fixture.jdbc.queryForList("SELECT * FROM sc_supplier_merchant_quota_audit WHERE quota_id=? ORDER BY quota_version", created.id());
        assertThat(history).hasSize(2);
        assertThat(history.getFirst()).containsEntry("before_rate_limit_per_second", null)
                .containsEntry("before_concurrency_limit", null).containsEntry("after_rate_limit_per_second", 10)
                .containsEntry("after_concurrency_limit", 3).containsEntry("quota_version", 0L).containsEntry("operator_id", 71L);
        assertThat(history.getLast()).containsEntry("before_rate_limit_per_second", 10)
                .containsEntry("before_concurrency_limit", 3).containsEntry("after_rate_limit_per_second", 7)
                .containsEntry("after_concurrency_limit", 2).containsEntry("quota_version", 1L)
                .containsEntry("operator_id", 72L).containsEntry("reason", "降额");
        fixture.jdbc.execute("ALTER TABLE sc_supplier_merchant_quota_audit ADD CONSTRAINT reject_quota_audit CHECK (operator_id NOT IN (73,74))");
        try {
            assertThatThrownBy(() -> quota.configure(new Configure(RENYUE, "AuditMerchant", 99, 88, 1L, 73L, "失败调整")))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(() -> quota.configure(new Configure(QNM, "FailedCreate", 9, 8, null, 74L, "失败创建")))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(fixture.jdbc.queryForObject("SELECT version FROM sc_supplier_merchant_quota WHERE id=?", Long.class, created.id())).isEqualTo(1);
            assertThat(fixture.jdbc.queryForObject("SELECT rate_limit_per_second FROM sc_supplier_merchant_quota WHERE id=?", Integer.class, created.id())).isEqualTo(7);
            assertThat(fixture.jdbc.queryForObject("SELECT concurrency_limit FROM sc_supplier_merchant_quota WHERE id=?", Integer.class, created.id())).isEqualTo(2);
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_merchant_quota WHERE merchant_account='FailedCreate'", Long.class)).isZero();
            assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_merchant_quota_audit WHERE quota_id=?", Long.class, created.id())).isEqualTo(2);
        } finally { fixture.jdbc.execute("ALTER TABLE sc_supplier_merchant_quota_audit DROP CHECK reject_quota_audit"); }
    }

    @Test void simultaneousEditsWithSameVersionCommitExactlyOneHistoryEntry() throws Exception {
        var created = quota.configure(new Configure(RENYUE, "CasMerchant", 10, 3, null, 71L, "创建"));
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.await(); try { quota.configure(new Configure(RENYUE, "CasMerchant", 7, 2, 0L, 72L, "修改A")); return true; } catch (IllegalStateException conflict) { return false; } });
            var second = pool.submit(() -> { gate.await(); try { quota.configure(new Configure(RENYUE, "CasMerchant", 9, 4, 0L, 73L, "修改B")); return true; } catch (IllegalStateException conflict) { return false; } });
            gate.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(fixture.jdbc.queryForObject("SELECT COUNT(*) FROM sc_supplier_merchant_quota_audit WHERE quota_id=?", Long.class, created.id())).isEqualTo(2);
        var latest = fixture.jdbc.queryForMap("SELECT * FROM sc_supplier_merchant_quota_audit WHERE quota_id=? AND quota_version=1", created.id());
        assertThat(latest).containsEntry("before_rate_limit_per_second", 10).containsEntry("before_concurrency_limit", 3);
        assertThat(latest.get("after_rate_limit_per_second")).isEqualTo(fixture.jdbc.queryForObject("SELECT rate_limit_per_second FROM sc_supplier_merchant_quota WHERE id=?", Integer.class, created.id()));
    }

    @Test void adminPagesMatchExactIdentityAndLegacyHistoryDoesNotInventCreation() {
        var admin = fixture.context.getBean(MerchantQuotaAdminService.class);
        fixture.jdbc.update("INSERT INTO sc_supplier_merchant_quota(platform_code,merchant_account,rate_limit_per_second,concurrency_limit,version,created_by,created_at,updated_by,updated_at,change_reason) VALUES ('RENYUE','LegacyMerchant',11,4,8,1,NOW(3),1,NOW(3),'历史配置')");
        var id = fixture.jdbc.queryForObject("SELECT id FROM sc_supplier_merchant_quota WHERE merchant_account='LegacyMerchant'", Long.class);
        quota.configure(new Configure(QNM, "LegacyMerchant", 17, 5, null, 71L, "其它平台"));
        quota.configure(new Configure(RENYUE, "legacymerchant", 19, 6, null, 72L, "大小写独立"));
        assertThat(admin.page("RENYUE", "LegacyMerchant", 1, 1).getTotal()).isEqualTo(1);
        assertThat(admin.page("RENYUE", "LegacyMerchant", 1, 1).getList().getFirst().version()).isEqualTo(8);
        assertThat(admin.history(id, 1, 10).firstRecordedVersion()).isNull();
        assertThat(admin.history(id, 1, 10).list()).isEmpty();
        admin.update(id, 7, 2, 8L, "第一次实际调额", 73L);
        var history = admin.history(id, 1, 10);
        assertThat(history.firstRecordedVersion()).isEqualTo(9);
        assertThat(history.total()).isEqualTo(1);
        assertThat(history.list().getFirst().beforeRateLimitPerSecond()).isEqualTo(11);
        assertThat(history.list().getFirst().beforeConcurrencyLimit()).isEqualTo(4);
        assertThat(admin.history(id, 2, 1).firstRecordedVersion()).isEqualTo(9);
        assertThat(admin.history(id, 2, 1).list()).isEmpty();
        assertThatThrownBy(() -> admin.update(id, 99, 88, 8L, "陈旧页面", 74L)).isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class);
        assertThat(admin.get(id).version()).isEqualTo(9);
        assertThat(admin.history(id, 1, 10).total()).isEqualTo(1);
        assertThatThrownBy(() -> admin.create("UNKNOWN", "M", 1, 1, "非法平台", 75L))
                .isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class)
                .hasFieldOrPropertyWithValue("code", com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.MERCHANT_QUOTA_PARAMETER_INVALID.getCode());
        assertThatThrownBy(() -> admin.get(Long.MAX_VALUE)).isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class)
                .hasFieldOrPropertyWithValue("code", com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.MERCHANT_QUOTA_NOT_FOUND.getCode());
    }

    @Test void simultaneousInitialCreationReturnsConflictAndNeverOverwritesWinner() throws Exception {
        var admin = fixture.context.getBean(MerchantQuotaAdminService.class);
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var attempts = java.util.stream.IntStream.range(0, 2).mapToObj(index -> pool.submit(() -> {
                gate.await();
                try { admin.create("QNM", "CreateRace", 7 + index, 2 + index, "创建竞争", 71L + index); return 0; }
                catch (com.aifuli.framework.common.exception.ServiceException conflict) { return conflict.getCode(); }
            })).toList();
            gate.countDown();
            assertThat(List.of(attempts.getFirst().get(), attempts.getLast().get())).containsExactlyInAnyOrder(0,
                    com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.MERCHANT_QUOTA_VERSION_CONFLICT.getCode());
        }
        var current = admin.page("QNM", "CreateRace", 1, 10).getList().getFirst();
        assertThat(current.version()).isZero();
        var history = admin.history(current.id(), 1, 10);
        assertThat(history.total()).isEqualTo(1);
        assertThat(history.firstRecordedVersion()).isZero();
        assertThat(history.list().getFirst().afterRateLimitPerSecond()).isEqualTo(current.rateLimitPerSecond());
    }
    private java.time.LocalDateTime now() { return fixture.context.getBean(SupplyChainDatabaseClock.class).now(); }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false) @Import({MerchantQuotaService.class, MerchantQuotaAdminService.class}) static class Config { }
}
