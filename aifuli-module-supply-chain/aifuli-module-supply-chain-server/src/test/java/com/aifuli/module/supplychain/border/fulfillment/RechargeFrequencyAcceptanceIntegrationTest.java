package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentCommandFacade;
import com.aifuli.module.supplychain.api.fulfillment.dto.*;
import com.aifuli.module.supplychain.border.fulfillment.application.AcceptanceFailureInjector;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyQueueMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import javax.sql.DataSource;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * 真实MySQL受理事务门禁：实际目标/SPU分组、资金原子性与首次唯一键竞争。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#41-入队。
 */
class RechargeFrequencyAcceptanceIntegrationTest {
    @Test
    void identityCrossesBuyersAndProjectsWhileDifferentSpuAndMobileAreIndependent() {
        var fixture = new FulfillmentAcceptanceIntegrationTest();
        fixture.startContainer();
        try (var context = fixture.applicationContext()) {
            var seed = fixture.seed(context);
            var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var request = FulfillmentAcceptanceIntegrationTest.request("FREQ-ONE", 1L);
            assertAccepted(facade, request);
            assertThat(facade.acceptFulfillment(request).getIdempotentReplay()).isTrue();
            assertThat(count(jdbc, "sc_recharge_frequency_entry")).isEqualTo(1L);
            assertThat(count(jdbc, "sc_fund_reservation")).isEqualTo(1L);
            assertThat(freezes(jdbc)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT next_check_at IS NOT NULL FROM sc_recharge_frequency_queue", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT wake_generation FROM sc_recharge_frequency_queue", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT dispatch_phase FROM sc_recharge_frequency_entry", Short.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT mobile_masked FROM sc_recharge_frequency_queue", String.class)).isEqualTo("138****8000");

            var projectB = FulfillmentAcceptanceIntegrationTest.request("FREQ-PROJECT-B", 1L);
            projectB.setProjectCode("PRJ_LIMIT_B");
            // buyerUserId保留旧内核语义，但频控不能用它作为实际充值手机。
            projectB.setBuyerUserId("13900139000");
            projectB.getTarget().setValues(Map.of("accountType", "MOBILE", "accountValue", "+8613800138000"));
            assertAccepted(facade, projectB);
            assertThat(count(jdbc, "sc_recharge_frequency_queue")).isEqualTo(1L);

            long buyer2 = cloneRow(jdbc, "sc_buyer", seed.buyerId(), Map.of("buyer_code", "BYR_FREQ_2", "buyer_name", "第二分销商", "code_scope_no", 2));
            long binding = jdbc.queryForObject("SELECT id FROM sc_buyer_tenant_binding WHERE buyer_id=?", Long.class, seed.buyerId());
            cloneRow(jdbc, "sc_buyer_tenant_binding", binding, Map.of("buyer_id", buyer2, "external_tenant_id", "tenant-frequency-2"));
            long project = jdbc.queryForObject("SELECT id FROM sc_buyer_project WHERE project_code='PRJ_LIMIT_A'", Long.class);
            cloneRow(jdbc, "sc_buyer_project", project, Map.of("buyer_id", buyer2, "project_code", "PRJ_FREQ_2"));
            cloneRow(jdbc, "sc_buyer_account", jdbc.queryForObject("SELECT id FROM sc_buyer_account WHERE buyer_id=?", Long.class, seed.buyerId()), Map.of("buyer_id", buyer2, "account_no", "ACT_FREQ_2", "frozen", 0L, "version", 0L));
            long listingId = jdbc.queryForObject("SELECT id FROM sc_buyer_listing WHERE listing_no=?", Long.class, seed.listingNo());
            cloneListing(jdbc, listingId, "LST_FREQ_BUYER2", "WS-FREQ-2", buyer2, null);
            jdbc.update("UPDATE sc_std_routing_rule SET distributor_mode='ALL' WHERE route_mode='FIXED'");
            var otherBuyer = FulfillmentAcceptanceIntegrationTest.request("FREQ-BUYER2", 1L);
            otherBuyer.setExternalTenantId("tenant-frequency-2"); otherBuyer.setProjectCode("PRJ_FREQ_2"); otherBuyer.setBuyerSkuCode("WS-FREQ-2");
            assertAccepted(facade, otherBuyer);
            assertThat(count(jdbc, "sc_recharge_frequency_queue")).isEqualTo(1L);
            assertThat(jdbc.queryForList("SELECT enqueue_sequence FROM sc_recharge_frequency_entry ORDER BY enqueue_sequence", Long.class)).containsExactly(1L, 2L, 3L);

            long sourceSku = jdbc.queryForObject("SELECT platform_sku_id FROM sc_buyer_listing WHERE id=?", Long.class, listingId);
            long sourceSpu = jdbc.queryForObject("SELECT spu_id FROM sc_platform_sku WHERE id=?", Long.class, sourceSku);
            long spu2 = cloneRow(jdbc, "sc_platform_spu", sourceSpu, Map.of("spu_code", "SPU_FREQ_2", "code_scope_no", 2));
            long sku2 = cloneSkuAndSupply(jdbc, sourceSku, "SKU_FREQ_2", spu2);
            cloneListing(jdbc, listingId, "LST_FREQ_SPU2", "WS-FREQ-SPU2", seed.buyerId(), sku2);
            var differentSpu = FulfillmentAcceptanceIntegrationTest.request("FREQ-SPU2", 1L); differentSpu.setBuyerSkuCode("WS-FREQ-SPU2");
            assertAccepted(facade, differentSpu);
            assertThat(count(jdbc, "sc_recharge_frequency_queue")).isEqualTo(2L);
            var differentMobile = FulfillmentAcceptanceIntegrationTest.request("FREQ-MOBILE2", 1L);
            differentMobile.getTarget().setValues(Map.of("accountType", "MOBILE", "accountValue", "13700137000"));
            assertAccepted(facade, differentMobile);
            assertThat(count(jdbc, "sc_recharge_frequency_queue")).isEqualTo(3L);

            var external = FulfillmentAcceptanceIntegrationTest.request("FREQ-EXTERNAL", 1L);
            external.getTarget().setValues(Map.of("accountType", "EXTERNAL_ACCOUNT", "accountValue", "external-account"));
            assertAccepted(facade, external);
            assertThat(count(jdbc, "sc_recharge_frequency_entry")).isEqualTo(5L);
            var invalid = FulfillmentAcceptanceIntegrationTest.request("FREQ-INVALID", 1L);
            invalid.getTarget().setValues(Map.of("accountType", "MOBILE", "accountValue", "private-invalid"));
            assertThat(facade.acceptFulfillment(invalid).getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.REJECTED_FINAL);
            assertThat(count(jdbc, "sc_recharge_frequency_entry")).isEqualTo(5L);
        } finally { fixture.stopContainer(); }
    }

    @Test
    void enqueueStorageFailureAndPostFreezeFailureRollbackOrdersQueuesAndFunds() {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext()) {
            fixture.seed(context);
            var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            var balance = jdbc.queryForMap("SELECT balance, frozen FROM sc_buyer_account");
            var storageFailureRequest = FulfillmentAcceptanceIntegrationTest.request("FREQ-STORAGE-FAIL", 1L);
            jdbc.execute("ALTER TABLE sc_recharge_frequency_entry ADD CONSTRAINT ck_frequency_test_reject CHECK (order_id < 0)");
            try {
                assertThatThrownBy(() -> facade.acceptFulfillment(storageFailureRequest))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertEmptyBusiness(jdbc);
                assertThat(jdbc.queryForMap("SELECT balance, frozen FROM sc_buyer_account")).isEqualTo(balance);
            } finally {
                jdbc.execute("ALTER TABLE sc_recharge_frequency_entry DROP CHECK ck_frequency_test_reject");
            }
            var failures = context.getBean(FulfillmentAcceptanceIntegrationTest.MutableFailureInjector.class);
            failures.failAt(AcceptanceFailureInjector.Stage.FUNDS_FROZEN);
            assertThatThrownBy(() -> facade.acceptFulfillment(FulfillmentAcceptanceIntegrationTest.request("FREQ-FUNDS-FAIL", 1L)))
                    .isInstanceOf(FulfillmentAcceptanceIntegrationTest.InjectedFailure.class);
            assertEmptyBusiness(jdbc);
            assertThat(jdbc.queryForMap("SELECT balance, frozen FROM sc_buyer_account")).isEqualTo(balance);
            failures.clear();
            assertAccepted(facade, storageFailureRequest);
            assertThat(freezes(jdbc)).isEqualTo(1L);
        } finally { fixture.stopContainer(); }
    }

    @Test
    void concurrentDifferentSkusOfSameSpuRetryIdentityCompetitionInFreshTransaction() throws Exception {
        var fixture = new FulfillmentAcceptanceIntegrationTest(); fixture.startContainer();
        try (var context = fixture.applicationContext(RaceConfiguration.class)) {
            var seed = fixture.seed(context);
            var facade = context.getBean(BuyerFulfillmentCommandFacade.class);
            var jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
            long listing = jdbc.queryForObject("SELECT id FROM sc_buyer_listing WHERE listing_no=?", Long.class, seed.listingNo());
            long sku = jdbc.queryForObject("SELECT platform_sku_id FROM sc_buyer_listing WHERE id=?", Long.class, listing);
            long clonedSku = cloneSkuAndSupply(jdbc, sku, "SKU_FREQ_RACE", null);
            cloneListing(jdbc, listing, "LST_FREQ_RACE", "WS-FREQ-RACE", seed.buyerId(), clonedSku);
            var hook = context.getBean(IdentityRaceHook.class); hook.armed.set(true);
            var first = FulfillmentAcceptanceIntegrationTest.request("FREQ-RACE-1", 1L);
            var second = FulfillmentAcceptanceIntegrationTest.request("FREQ-RACE-2", 1L); second.setBuyerSkuCode("WS-FREQ-RACE");
            try (var pool = Executors.newFixedThreadPool(2)) {
                var one = pool.submit(() -> facade.acceptFulfillment(first));
                var two = pool.submit(() -> facade.acceptFulfillment(second));
                assertThat(one.get(30, TimeUnit.SECONDS).getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
                assertThat(two.get(30, TimeUnit.SECONDS).getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
            }
            assertThat(hook.firstMisses.get()).isEqualTo(2);
            assertThat(hook.identityReads.get()).isEqualTo(3);
            assertThat(count(jdbc, "sc_recharge_frequency_queue")).isEqualTo(1L);
            assertThat(count(jdbc, "sc_recharge_frequency_entry")).isEqualTo(2L);
            assertThat(count(jdbc, "sc_supply_chain_order")).isEqualTo(2L);
            assertThat(count(jdbc, "sc_fulfillment_request")).isEqualTo(2L);
            assertThat(count(jdbc, "sc_b_order")).isEqualTo(2L);
            assertThat(count(jdbc, "sc_fulfillment_operation")).isEqualTo(2L);
            assertThat(count(jdbc, "sc_fund_reservation")).isEqualTo(2L);
            assertThat(freezes(jdbc)).isEqualTo(2L);
            assertThat(jdbc.queryForList("SELECT enqueue_sequence FROM sc_recharge_frequency_entry ORDER BY enqueue_sequence", Long.class)).containsExactly(1L, 2L);
        } finally { fixture.stopContainer(); }
    }

    private static void assertAccepted(BuyerFulfillmentCommandFacade facade, AcceptFulfillmentReqDTO request) {
        assertThat(facade.acceptFulfillment(request).getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
    }
    private static long count(JdbcTemplate jdbc, String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private static long freezes(JdbcTemplate jdbc) { return jdbc.queryForObject("SELECT COUNT(*) FROM sc_account_ledger WHERE entry_type='FREEZE'", Long.class); }
    private static void assertEmptyBusiness(JdbcTemplate jdbc) {
        for (String table : List.of("sc_supply_chain_order", "sc_fulfillment_request", "sc_b_order", "sc_fulfillment_operation", "sc_recharge_frequency_entry", "sc_recharge_frequency_queue", "sc_fund_reservation")) {
            assertThat(count(jdbc, table)).as(table).isZero();
        }
        assertThat(freezes(jdbc)).isZero();
    }
    /** 测试复制有效种子关系，所有引用仍在真实供应链库中。 */
    static long cloneRow(JdbcTemplate jdbc, String table, long id, Map<String, Object> overrides) {
        var values = new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM " + table + " WHERE id=?", id));
        values.remove("id"); values.putAll(overrides);
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var sql = "INSERT INTO " + table + " (" + String.join(",", values.keySet()) + ") VALUES (" + String.join(",", Collections.nCopies(values.size(), "?")) + ")";
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int index = 1; for (Object value : values.values()) { statement.setObject(index++, value); }
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    static void cloneListing(JdbcTemplate jdbc, long original, String listingNo, String buyerSku, long buyer, Long sku) {
        var override = new HashMap<String, Object>(); override.put("listing_no", listingNo); override.put("buyer_sku_code", buyerSku); override.put("buyer_id", buyer);
        if (sku != null) { override.put("platform_sku_id", sku); }
        long listing = cloneRow(jdbc, "sc_buyer_listing", original, override);
        long version = jdbc.queryForObject("SELECT current_version_id FROM sc_buyer_listing WHERE id=?", Long.class, original);
        long newVersion = cloneRow(jdbc, "sc_buyer_listing_version", version, Map.of("listing_id", listing));
        jdbc.update("UPDATE sc_buyer_listing SET current_version_id=? WHERE id=?", newVersion, listing);
        long availability = jdbc.queryForObject("SELECT id FROM sc_buyer_listing_availability WHERE listing_id=?", Long.class, original);
        cloneRow(jdbc, "sc_buyer_listing_availability", availability, Map.of("listing_id", listing));
    }
    static long cloneSkuAndSupply(JdbcTemplate jdbc, long original, String code, Long spu) {
        var overrides = new HashMap<String, Object>(); overrides.put("sku_code", code);
        if (spu != null) { overrides.put("spu_id", spu); }
        long sku = cloneRow(jdbc, "sc_platform_sku", original, overrides);
        long inventory = jdbc.queryForObject("SELECT id FROM sc_inventory_source WHERE platform_sku_id=?", Long.class, original);
        long newInventory = cloneRow(jdbc, "sc_inventory_source", inventory, Map.of("platform_sku_id", sku, "source_no", "INV_" + code));
        long offer = jdbc.queryForObject("SELECT id FROM sc_supplier_offer WHERE platform_sku_id=?", Long.class, original);
        long newOffer = cloneRow(jdbc, "sc_supplier_offer", offer, Map.of("platform_sku_id", sku, "offer_no", "OFR_" + code, "offer_code", code));
        long version = jdbc.queryForObject("SELECT current_version_id FROM sc_supplier_offer WHERE id=?", Long.class, offer);
        long newVersion = cloneRow(jdbc, "sc_supplier_offer_version", version, Map.of("offer_id", newOffer, "inventory_source_id", newInventory));
        jdbc.update("UPDATE sc_supplier_offer SET current_version_id=? WHERE id=?", newVersion, newOffer);
        return sku;
    }

    @Configuration(proxyBeanMethods = false)
    static class RaceConfiguration { @Bean static IdentityRaceHook identityRaceHook() { return new IdentityRaceHook(); } }
    /** 只将两个真实SQL事务的首次不存在查询同步到同一竞争点，不替换持久化行为。 */
    static class IdentityRaceHook implements BeanPostProcessor {
        final AtomicBoolean armed = new AtomicBoolean(); final AtomicInteger firstMisses = new AtomicInteger();
        final AtomicInteger identityReads = new AtomicInteger(); final CyclicBarrier barrier = new CyclicBarrier(2);
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof RechargeFrequencyQueueMapper mapper)) { return bean; }
            var wrapped = spy(mapper);
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                if (armed.get()) {
                    identityReads.incrementAndGet();
                    if (result == null) { firstMisses.incrementAndGet(); barrier.await(10, TimeUnit.SECONDS); }
                }
                return result;
            }).when(wrapped).selectByIdentity(any(byte[].class), anyLong());
            return wrapped;
        }
    }
}
