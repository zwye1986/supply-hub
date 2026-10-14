package com.aifuli.module.supplychain.api.fulfillment;

import static org.assertj.core.api.Assertions.*;

import com.aifuli.module.supplychain.api.fulfillment.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;

class ProjectStandardOrderQueryContractTest {
    private final ObjectMapper json =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void exposesClosedProjectQueryContractsWithNullAndOffsetTime() throws Exception {
        var dto = new ProjectStandardOrderQueryRespDTO();
        dto.setRequestId("AR_1");
        dto.setUpdatedAt(OffsetDateTime.parse("2026-10-02T12:30:00+08:00"));
        var node = json.readTree(json.writeValueAsBytes(dto));
        var names = new HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names)
                .containsExactlyInAnyOrder(
                        "buyerOrderNo",
                        "requestId",
                        "receiptStatus",
                        "receiptVersion",
                        "rejectionCode",
                        "failureReasonCode",
                        "failureReasonNote",
                        "orderNo",
                        "orderStatus",
                        "fulfillmentStatus",
                        "requestedQuantity",
                        "deliveredQuantity",
                        "assetAvailable",
                        "acceptedAt",
                        "completedAt",
                        "updatedAt");
        assertThat(node.get("orderNo").isNull()).isTrue();
        assertThat(node.get("updatedAt").textValue()).isEqualTo("2026-10-02T12:30:00+08:00");
    }

    @Test
    void assetsExposeOnlyClosedFieldsAndSafeDiagnosticString() throws Exception {
        var asset = new ProjectStandardOrderAssetRespDTO();
        asset.setCardCode("secret-card");
        asset.setCardSecret("secret-password");
        asset.setVoucherCode("secret-voucher");
        asset.setVoucherUrl("secret-url");
        var dto = new ProjectStandardOrderAssetsRespDTO();
        dto.setAssets(List.of(asset));
        var node = json.valueToTree(dto);
        var names = new HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("buyerOrderNo", "orderNo", "assets", "hint");
        names.clear();
        node.get("assets").get(0).fieldNames().forEachRemaining(names::add);
        assertThat(names)
                .containsExactlyInAnyOrder(
                        "assetNo",
                        "sequenceNo",
                        "assetType",
                        "cardCode",
                        "cardSecret",
                        "voucherCode",
                        "voucherUrl",
                        "maskedValue",
                        "validFrom",
                        "validUntil",
                        "lifecycleStatus");
        assertThat(asset.toString() + dto.toString())
                .doesNotContain("secret-card", "secret-password", "secret-voucher", "secret-url");
    }

    @Test
    void createResponseExposesOnlyClosedFieldsAndIsIsolatedFromInternalFields() throws Exception {
        var dto = new ProjectStandardOrderCreateRespDTO();
        dto.setRequestId("AR_20261002001");
        dto.setBuyerOrderNo("PO_20261002001");
        dto.setReceiptStatus(FulfillmentReceiptStatus.ACCEPTED);
        dto.setReceiptVersion(1L);
        dto.setOrderNo("SO_20261002001");
        dto.setAcceptedAt(OffsetDateTime.parse("2026-10-02T12:00:00+08:00"));
        dto.setIdempotentReplay(false);
        var node = json.readTree(json.writeValueAsBytes(dto));
        var names = new HashSet<String>();
        node.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder(
                "requestId",
                "buyerOrderNo",
                "receiptStatus",
                "receiptVersion",
                "orderNo",
                "orderStatus",
                "rejectionCode",
                "idempotentReplay",
                "acceptedAt");
        // 绝不暴露供应商或内部履约请求号或内部备注
        assertThat(names).doesNotContain("supplierCode", "supplierName", "fulfillmentRequestNo", "remark");
        assertThat(node.get("orderStatus").isNull()).isTrue();
        assertThat(node.get("acceptedAt").textValue()).isEqualTo("2026-10-02T12:00:00+08:00");
    }
}
