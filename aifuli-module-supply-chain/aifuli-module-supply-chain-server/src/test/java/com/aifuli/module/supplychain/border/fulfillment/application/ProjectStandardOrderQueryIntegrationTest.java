package com.aifuli.module.supplychain.border.fulfillment.application;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLY_CHAIN_ORDER_NOT_FOUND;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;

import org.junit.jupiter.api.*;

import java.util.concurrent.*;

class ProjectStandardOrderQueryIntegrationTest {
    static ProjectStandardQueryIntegrationSupport db;

    @BeforeAll
    static void start() {
        db = new ProjectStandardQueryIntegrationSupport();
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    @BeforeEach
    void reset() {
        db.resetData();
    }

    @Test
    void sameNumberUsesProjectOwnershipDatabaseEqualityAndNeverNullFallback() {
        db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
        db.accepted(2, 4L, 5, "order", "DIRECT_RECHARGE_TARGET");
        db.accepted(3, null, 6, "legacy", "VOUCHER_DELIVERY_TARGET");
        assertThat(db.queries().query(db.queryContext(3, 2), "ORDER").getOrderNo())
                .isEqualTo("SC_1");
        assertThat(db.queries().query(db.queryContext(4, 5), "order").getOrderNo())
                .isEqualTo("SC_2");
        for (var ctx :
                new com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor
                                .StandardProductContext[] {
                    db.queryContext(9, 2), db.queryContext(9, 6)
                }) {
            assertThatThrownBy(
                            () -> db.queries().query(ctx, ctx.buyerId() == 2 ? "order" : "legacy"))
                    .isInstanceOfSatisfying(
                            ServiceException.class,
                            e ->
                                    assertThat(e.getCode())
                                            .isEqualTo(SUPPLY_CHAIN_ORDER_NOT_FOUND.getCode()));
        }
        verifyNoInteractions(db.crypto());
    }

    @Test
    void validatesBuyerAndEveryBidirectionalReceiptOrderRequestReference() {
        String[] corruptions = {
            "UPDATE sc_fulfillment_acceptance_receipt SET buyer_id=8 WHERE id=1",
            "UPDATE sc_supply_chain_order SET buyer_id=8 WHERE id=1",
            "UPDATE sc_supply_chain_order SET acceptance_receipt_id=9 WHERE id=1",
            "UPDATE sc_supply_chain_order SET detail_type='O2O_CHANNEL' WHERE id=1",
            "UPDATE sc_fulfillment_request SET order_id=9 WHERE id=1",
            "UPDATE sc_fulfillment_request SET buyer_id=9 WHERE id=1",
            "UPDATE sc_fulfillment_acceptance_receipt SET result_order_no='SC_other' WHERE id=1",
            "UPDATE sc_fulfillment_acceptance_receipt SET result_fulfillment_request_no='FR_other'"
                    + " WHERE id=1",
            "DELETE FROM sc_fulfillment_request WHERE id=1",
            "DELETE FROM sc_supply_chain_order WHERE id=1",
            "DELETE FROM sc_fulfillment_acceptance_receipt WHERE id=1"
        };
        for (String sql : corruptions) {
            db.resetData();
            db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
            db.jdbc.update(sql);
            assertThatThrownBy(() -> db.queries().query(db.queryContext(3, 2), "order"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void summaryChecksAssetMetadataWithoutDecryptAndExcludesExpiredAndQuarantined() {
        db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
        db.asset(10, 1, 2, "VOUCHER_CODE", (short) 90);
        assertThat(db.queries().query(db.queryContext(3, 2), "order").getAssetAvailable())
                .isFalse();
        db.jdbc.update(
                "UPDATE sc_fulfillment_asset SET"
                    + " lifecycle_status=10,valid_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1"
                    + " DAY) WHERE id=10");
        assertThat(db.queries().query(db.queryContext(3, 2), "order").getAssetAvailable())
                .isFalse();
        db.jdbc.update(
                "UPDATE sc_fulfillment_asset SET"
                    + " valid_until=NULL,valid_from=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 1 DAY)"
                    + " WHERE id=10");
        assertThat(db.queries().query(db.queryContext(3, 2), "order").getAssetAvailable())
                .isFalse();
        db.jdbc.update("UPDATE sc_fulfillment_asset SET valid_from=NULL WHERE id=10");
        assertThat(db.queries().query(db.queryContext(3, 2), "order").getAssetAvailable()).isTrue();
        verifyNoInteractions(db.crypto());
        for (String sql :
                new String[] {
                    "UPDATE sc_fulfillment_asset SET buyer_id=9 WHERE id=10",
                    "UPDATE sc_b_order SET fulfillment_request_id=9 WHERE id=10",
                    "UPDATE sc_b_order SET order_id=9 WHERE id=10",
                    "UPDATE sc_b_order SET supplier_id=8 WHERE id=10",
                    "UPDATE sc_fulfillment_operation SET b_order_id=9 WHERE id=10"
                }) {
            db.resetData();
            db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
            db.asset(10, 1, 2, "VOUCHER_CODE", (short) 10);
            db.jdbc.update(sql);
            assertThatThrownBy(() -> db.queries().query(db.queryContext(3, 2), "order"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void repeatableReadNeverMixesOldReceiptWithConcurrentNewOrder() throws Exception {
        db.receipt(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
        ProjectStandardQueryIntegrationSupport.ReceiptPause.read = new CountDownLatch(1);
        ProjectStandardQueryIntegrationSupport.ReceiptPause.proceed = new CountDownLatch(1);
        ProjectStandardQueryIntegrationSupport.ReceiptPause.enabled.set(true);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reader = executor.submit(() -> db.queries().query(db.queryContext(3, 2), "order"));
            assertThat(
                            ProjectStandardQueryIntegrationSupport.ReceiptPause.read.await(
                                    5, TimeUnit.SECONDS))
                    .isTrue();
            db.transaction()
                    .executeWithoutResult(
                            status -> db.buildOrder(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET"));
            ProjectStandardQueryIntegrationSupport.ReceiptPause.proceed.countDown();
            var before = reader.get(5, TimeUnit.SECONDS);
            assertThat(before.getOrderNo()).isNull();
            var after = db.queries().query(db.queryContext(3, 2), "order");
            assertThat(after.getOrderNo()).isEqualTo("SC_1");
            assertThat(after.getOrderStatus()).isEqualTo(SupplyChainOrderStatus.PROCESSING);
        } finally {
            ProjectStandardQueryIntegrationSupport.ReceiptPause.proceed.countDown();
        }
    }

    @Test
    void unbuiltUnresolvedSchemaStillReturnsSummaryButAssetsFailClosedWithAudit() {
        for (String schema : new String[] {"", "UNRESOLVED_SCHEMA"}) {
            for (int status : new int[] {0, 20, 30}) {
                db.resetData();
                db.receipt(1, 3L, 2, "order", schema);
                if (status != 0)
                    db.jdbc.update(
                            "UPDATE sc_fulfillment_acceptance_receipt SET status=?,"
                                + " rejection_code='UNRESOLVED', rejected_at=CURRENT_TIMESTAMP(3)"
                                + " WHERE id=1",
                            status);
                assertThatCode(() -> db.queries().query(db.queryContext(3, 2), "order"))
                        .doesNotThrowAnyException();
                var summary = db.queries().query(db.queryContext(3, 2), "order");
                assertThat(summary.getRequestId()).isEqualTo("AR_1");
                assertThat(summary.getReceiptStatus().name())
                        .isEqualTo(
                                status == 0
                                        ? "PROCESSING"
                                        : status == 20 ? "REJECTED_RETRYABLE" : "REJECTED_FINAL");
                assertThat(summary.getOrderNo()).isNull();
                assertThat(summary.getAssetAvailable()).isFalse();
                assertThatThrownBy(() -> db.assets().query(db.assetContext(3, 2), "order"))
                        .isInstanceOf(IllegalStateException.class);
                var audit =
                        db.jdbc.queryForMap(
                                "SELECT access_type, reason_code, result_status, resource_id,"
                                        + " order_no FROM sc_sensitive_access_audit");
                assertThat(audit)
                        .containsEntry("access_type", "ASSET_QUERY")
                        .containsEntry("reason_code", "REFERENCE_INCONSISTENT")
                        .containsEntry("resource_id", 0L)
                        .containsEntry("order_no", null);
                assertThat(((Number) audit.get("result_status")).intValue()).isEqualTo(30);
                verifyNoInteractions(db.crypto());
            }
        }
    }

    @Test
    void differentProductFrozenSnapshotsFailClosedAndAuditWithoutDecryption() {
        for (String sql :
                new String[] {
                    "UPDATE sc_fulfillment_request SET platform_sku_id=99 WHERE id=1",
                    "UPDATE sc_fulfillment_acceptance_receipt SET quantity=2 WHERE id=1",
                    "UPDATE sc_fulfillment_request SET target_schema_code='DIRECT_RECHARGE_TARGET'"
                            + " WHERE id=1",
                    "UPDATE sc_fulfillment_request SET target_digest=UNHEX(REPEAT('05',32)) WHERE"
                            + " id=1"
                }) {
            db.resetData();
            db.accepted(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
            db.jdbc.update(sql);
            assertThatThrownBy(() -> db.assets().query(db.assetContext(3, 2), "order"))
                    .isInstanceOf(IllegalStateException.class);
            var row =
                    db.jdbc.queryForMap(
                            "SELECT access_type, reason_code, result_status, resource_id FROM"
                                    + " sc_sensitive_access_audit");
            assertThat(row)
                    .containsEntry("access_type", "ASSET_QUERY")
                    .containsEntry("reason_code", "REFERENCE_INCONSISTENT")
                    .containsEntry("resource_id", 0L);
            assertThat(((Number) row.get("result_status")).intValue()).isEqualTo(30);
            assertThatThrownBy(() -> db.queries().query(db.queryContext(3, 2), "order"))
                    .isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(db.crypto());
        }
    }

    @Test
    void processingWithDanglingResultReferencesFailsClosedInsteadOfWait() {
        db.receipt(1, 3L, 2, "order", "VOUCHER_DELIVERY_TARGET");
        db.jdbc.update(
                "UPDATE sc_fulfillment_acceptance_receipt SET"
                    + " result_order_no='SC_dangling',result_fulfillment_request_no='FR_dangling'"
                    + " WHERE id=1");
        assertThatThrownBy(() -> db.queries().query(db.queryContext(3, 2), "order"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> db.assets().query(db.assetContext(3, 2), "order"))
                .isInstanceOf(IllegalStateException.class);
        var row =
                db.jdbc.queryForMap(
                        "SELECT reason_code,resource_id,order_no FROM sc_sensitive_access_audit");
        assertThat(row)
                .containsEntry("reason_code", "REFERENCE_INCONSISTENT")
                .containsEntry("resource_id", 0L)
                .containsEntry("order_no", null);
        verifyNoInteractions(db.crypto());
    }
}
