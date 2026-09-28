package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAcceptanceReceiptRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAssetQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAssetRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptStatus;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentResultQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentResultRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentAssetStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.SensitiveAccessAuditResultStatus;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAcceptanceReceiptDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAssetDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SensitiveAccessAuditDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAcceptanceReceiptMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAssetMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.SensitiveAccessAuditMapper;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver.BuyerSnapshot;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.FULFILLMENT_ASSET_SCOPE_DENIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购商履约查询服务单测：受理回执查询、结果摘要查询、敏感资产解密与不可变访问审计。
 */
@ExtendWith(MockitoExtension.class)
class BuyerFulfillmentQueryServiceTest {

    @Mock
    private BuyerResolver buyerResolver;
    @Mock
    private FulfillmentAcceptanceReceiptMapper receiptMapper;
    @Mock
    private FulfillmentRequestMapper requestMapper;
    @Mock
    private SupplyChainOrderMapper orderMapper;
    @Mock
    private FulfillmentAssetMapper assetMapper;
    @Mock
    private SensitiveAccessAuditMapper auditMapper;
    @Mock
    private EnvelopeCryptoPort envelopeCrypto;
    @Mock
    private HmacFingerprintPort hmacFingerprint;
    @Mock
    private BusinessNoGenerator businessNoGenerator;
    @Mock
    private SupplyChainDatabaseClock databaseClock;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private BuyerFulfillmentQueryService queryService;

    private final LocalDateTime fixedNow = LocalDateTime.of(2026, 8, 11, 12, 0, 0);

    @BeforeEach
    void setUp() {
        queryService = new BuyerFulfillmentQueryService(
                buyerResolver,
                receiptMapper,
                requestMapper,
                orderMapper,
                assetMapper,
                auditMapper,
                envelopeCrypto,
                hmacFingerprint,
                businessNoGenerator,
                databaseClock,
                objectMapper
        );
    }

    @Test
    void getAcceptanceReceiptReturnsReceiptWhenFound() {
        FulfillmentReceiptQueryReqDTO req = new FulfillmentReceiptQueryReqDTO();
        req.setSourceSystem("AIFULI_MALL");
        req.setExternalTenantId("tenant_01");
        req.setBuyerOrderNo("BO_BUYER_01");

        when(databaseClock.now()).thenReturn(fixedNow);
        when(databaseClock.toInstant(any())).thenReturn(Instant.parse("2026-08-11T12:00:00Z"));
        when(buyerResolver.resolve("AIFULI_MALL", "tenant_01", fixedNow))
                .thenReturn(new BuyerSnapshot(10L, "buyer_code", "BuyerName", "PROJECT_01", 1));

        FulfillmentAcceptanceReceiptDO receipt = FulfillmentAcceptanceReceiptDO.builder()
                .id(1L)
                .receiptNo("RCP_001")
                .buyerId(10L)
                .buyerOrderNo("BO_BUYER_01")
                .status((short) 10) // ACCEPTED
                .version(1L)
                .resultOrderId(100L)
                .resultOrderNo("SC_ORDER_001")
                .resultFulfillmentRequestNo("FR_001")
                .acceptedAt(fixedNow)
                .build();

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(100L)
                .orderNo("SC_ORDER_001")
                .orderStatus((short) 10)
                .displaySupplierCode("QNM")
                .displaySupplierName("奇那梦")
                .build();

        when(receiptMapper.selectByBusinessKey(10L, "BO_BUYER_01")).thenReturn(receipt);
        when(orderMapper.selectById(100L)).thenReturn(order);

        FulfillmentAcceptanceReceiptRespDTO resp = queryService.getAcceptanceReceipt(req);

        assertThat(resp).isNotNull();
        assertThat(resp.getRequestId()).isEqualTo("RCP_001");
        assertThat(resp.getBusinessNo()).isEqualTo("BO_BUYER_01");
        assertThat(resp.getReceiptStatus()).isEqualTo(FulfillmentReceiptStatus.ACCEPTED);
        assertThat(resp.getOrderNo()).isEqualTo("SC_ORDER_001");
        assertThat(resp.getSupplierCode()).isEqualTo("QNM");
    }

    @Test
    void getFulfillmentResultReturnsSummaryWhenFound() {
        FulfillmentResultQueryReqDTO req = new FulfillmentResultQueryReqDTO();
        req.setSourceSystem("AIFULI_MALL");
        req.setExternalTenantId("tenant_01");
        req.setBuyerOrderNo("BO_BUYER_02");

        when(databaseClock.now()).thenReturn(fixedNow);
        when(databaseClock.toInstant(any())).thenReturn(Instant.parse("2026-08-11T12:00:00Z"));
        when(buyerResolver.resolve("AIFULI_MALL", "tenant_01", fixedNow))
                .thenReturn(new BuyerSnapshot(10L, "buyer_code", "BuyerName", "PROJECT_01", 1));

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(101L)
                .orderNo("SC_ORDER_002")
                .buyerId(10L)
                .buyerOrderNo("BO_BUYER_02")
                .detailId(201L)
                .orderStatus((short) 30)
                .completedAt(fixedNow)
                .updatedAt(fixedNow)
                .build();

        FulfillmentRequestDO requestDO = FulfillmentRequestDO.builder()
                .id(201L)
                .requestNo("FR_002")
                .status((short) 40) // SUCCEEDED
                .requestedQuantity(2L)
                .fulfilledQuantity(2L)
                .build();

        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(order);
        when(requestMapper.selectById(201L)).thenReturn(requestDO);

        FulfillmentResultRespDTO resp = queryService.getFulfillmentResult(req);

        assertThat(resp).isNotNull();
        assertThat(resp.getFulfillmentRequestNo()).isEqualTo("FR_002");
        assertThat(resp.getOrderNo()).isEqualTo("SC_ORDER_002");
        assertThat(resp.getBuyerOrderNo()).isEqualTo("BO_BUYER_02");
        assertThat(resp.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.SUCCEEDED);
        assertThat(resp.getRequestedQuantity()).isEqualTo(2L);
        assertThat(resp.getDeliveredQuantity()).isEqualTo(2L);
        assertThat(resp.getAssetAvailable()).isTrue();
    }

    @Test
    void getFulfillmentAssetsRejectsBuyerMismatch() {
        FulfillmentAssetQueryReqDTO req = new FulfillmentAssetQueryReqDTO();
        req.setSourceSystem("AIFULI_MALL");
        req.setExternalTenantId("tenant_01");
        req.setFulfillmentRequestNo("FR_003");
        req.setBuyerUserId("user_003");
        req.setScopeCode("FULFILLMENT_ASSET_READ");

        when(databaseClock.now()).thenReturn(fixedNow);
        when(buyerResolver.resolve("AIFULI_MALL", "tenant_01", fixedNow))
                .thenReturn(new BuyerSnapshot(10L, "buyer_code", "BuyerName", "PROJECT_01", 1));

        FulfillmentRequestDO requestDO = FulfillmentRequestDO.builder()
                .id(301L)
                .requestNo("FR_003")
                .buyerId(999L) // Mismatch: 999 != 10
                .build();

        when(requestMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(requestDO);
        when(businessNoGenerator.next("AUD_")).thenReturn("AUD_001");
        when(hmacFingerprint.fingerprint(eq("SENSITIVE_AUDIT_USER"), any()))
                .thenReturn("digest".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> queryService.getFulfillmentAssets(req))
                .isInstanceOf(ServiceException.class);

        // Verify failure audit recorded
        ArgumentCaptor<SensitiveAccessAuditDO> auditCaptor = ArgumentCaptor.forClass(SensitiveAccessAuditDO.class);
        verify(auditMapper).insert(auditCaptor.capture());
        SensitiveAccessAuditDO audit = auditCaptor.getValue();
        assertThat(audit.getResultStatus()).isEqualTo((short) 20); // 20 失败
        assertThat(audit.getReasonCode()).isEqualTo("BUYER_MISMATCH");
    }

    @Test
    void getFulfillmentAssetsDecryptsAndWritesAudit() {
        FulfillmentAssetQueryReqDTO req = new FulfillmentAssetQueryReqDTO();
        req.setSourceSystem("AIFULI_MALL");
        req.setExternalTenantId("tenant_01");
        req.setFulfillmentRequestNo("FR_004");
        req.setBuyerUserId("13800000000");
        req.setScopeCode("FULFILLMENT_ASSET_READ");

        when(databaseClock.now()).thenReturn(fixedNow);
        when(databaseClock.toInstant(any())).thenReturn(Instant.parse("2026-08-11T12:00:00Z"));
        when(buyerResolver.resolve("AIFULI_MALL", "tenant_01", fixedNow))
                .thenReturn(new BuyerSnapshot(10L, "buyer_code", "BuyerName", "PROJECT_01", 1));

        FulfillmentRequestDO requestDO = FulfillmentRequestDO.builder()
                .id(401L)
                .requestNo("FR_004")
                .buyerId(10L)
                .orderId(501L)
                .build();

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(501L)
                .buyerUserId("13800000000")
                .build();

        FulfillmentAssetDO assetDO = FulfillmentAssetDO.builder()
                .id(601L)
                .assetNo("FA_004")
                .bOrderId(701L)
                .sequenceNo(1)
                .assetType("CARD_SECRET")
                .encryptedPayload(new byte[]{1, 2, 3})
                .keyVersion("1")
                .maskedValue("CARD****")
                .lifecycleStatus((short) 10)
                .validFrom(fixedNow)
                .validUntil(fixedNow.plusYears(1))
                .build();

        when(requestMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(requestDO);
        when(orderMapper.selectById(501L)).thenReturn(order);
        when(assetMapper.selectListByFulfillmentRequestId(401L)).thenReturn(List.of(assetDO));
        when(businessNoGenerator.next("AUD_")).thenReturn("AUD_002");
        when(hmacFingerprint.fingerprint(eq("SENSITIVE_AUDIT_USER"), any()))
                .thenReturn("user-digest".getBytes(StandardCharsets.UTF_8));

        String assetPlaintext = "{\"assetType\":\"CARD_SECRET\",\"cardCode\":\"CARD9999\",\"cardSecret\":\"PASS9999\"}";
        when(envelopeCrypto.decrypt(eq(assetDO.getEncryptedPayload()), eq("1"), any()))
                .thenReturn(assetPlaintext.getBytes(StandardCharsets.UTF_8));

        List<FulfillmentAssetRespDTO> result = queryService.getFulfillmentAssets(req);

        assertThat(result).hasSize(1);
        FulfillmentAssetRespDTO assetResp = result.get(0);
        assertThat(assetResp.getAssetNo()).isEqualTo("FA_004");
        assertThat(assetResp.getCardCode()).isEqualTo("CARD9999");
        assertThat(assetResp.getCardSecret()).isEqualTo("PASS9999");
        assertThat(assetResp.getLifecycleStatus()).isEqualTo("AVAILABLE");

        // Verify successful audit recorded
        ArgumentCaptor<SensitiveAccessAuditDO> auditCaptor = ArgumentCaptor.forClass(SensitiveAccessAuditDO.class);
        verify(auditMapper).insert(auditCaptor.capture());
        SensitiveAccessAuditDO audit = auditCaptor.getValue();
        assertThat(audit.getAuditNo()).isEqualTo("AUD_002");
        assertThat(audit.getResultStatus()).isEqualTo((short) 10); // 10 成功
        assertThat(audit.getScopeCode()).isEqualTo("FULFILLMENT_ASSET_READ");
        assertThat(audit.getReasonCode()).isEqualTo("AUTHORIZED_QUERY");
    }

    @Test
    void getFulfillmentAssetsAllowsNonMobileOpaqueBuyerUserId() {
        String opaqueUserId = "ext_user_uuid_9876543210_abc";
        FulfillmentAssetQueryReqDTO req = new FulfillmentAssetQueryReqDTO();
        req.setSourceSystem("AIFULI_MALL");
        req.setExternalTenantId("tenant_01");
        req.setFulfillmentRequestNo("FR_005");
        req.setBuyerUserId(opaqueUserId);
        req.setScopeCode("FULFILLMENT_ASSET_READ");

        when(databaseClock.now()).thenReturn(fixedNow);
        when(databaseClock.toInstant(any())).thenReturn(Instant.parse("2026-08-11T12:00:00Z"));
        when(buyerResolver.resolve("AIFULI_MALL", "tenant_01", fixedNow))
                .thenReturn(new BuyerSnapshot(10L, "buyer_code", "BuyerName", "PROJECT_01", 1));

        FulfillmentRequestDO requestDO = FulfillmentRequestDO.builder()
                .id(402L)
                .requestNo("FR_005")
                .buyerId(10L)
                .orderId(502L)
                .build();

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(502L)
                .buyerUserId(opaqueUserId)
                .build();

        FulfillmentAssetDO assetDO = FulfillmentAssetDO.builder()
                .id(602L)
                .assetNo("FA_005")
                .bOrderId(702L)
                .sequenceNo(1)
                .assetType("CARD_SECRET")
                .encryptedPayload(new byte[]{4, 5, 6})
                .keyVersion("1")
                .maskedValue("CARD****")
                .lifecycleStatus((short) 10)
                .validFrom(fixedNow)
                .validUntil(fixedNow.plusYears(1))
                .build();

        when(requestMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(requestDO);
        when(orderMapper.selectById(502L)).thenReturn(order);
        when(assetMapper.selectListByFulfillmentRequestId(402L)).thenReturn(List.of(assetDO));
        when(businessNoGenerator.next("AUD_")).thenReturn("AUD_003");
        when(hmacFingerprint.fingerprint(eq("SENSITIVE_AUDIT_USER"), any()))
                .thenReturn("opaque-user-digest".getBytes(StandardCharsets.UTF_8));

        String assetPlaintext = "{\"assetType\":\"CARD_SECRET\",\"cardCode\":\"CARD8888\",\"cardSecret\":\"PASS8888\"}";
        when(envelopeCrypto.decrypt(eq(assetDO.getEncryptedPayload()), eq("1"), any()))
                .thenReturn(assetPlaintext.getBytes(StandardCharsets.UTF_8));

        List<FulfillmentAssetRespDTO> result = queryService.getFulfillmentAssets(req);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCardCode()).isEqualTo("CARD8888");
        assertThat(result.get(0).getCardSecret()).isEqualTo("PASS8888");
    }

    @Test
    void getFulfillmentAssetsFailsClosedWhenAuditInsertFails() {
        FulfillmentAssetQueryReqDTO req = new FulfillmentAssetQueryReqDTO();
        req.setSourceSystem("AIFULI_MALL");
        req.setExternalTenantId("tenant_01");
        req.setFulfillmentRequestNo("FR_006");
        req.setBuyerUserId("user_006");
        req.setScopeCode("FULFILLMENT_ASSET_READ");

        when(databaseClock.now()).thenReturn(fixedNow);
        when(buyerResolver.resolve("AIFULI_MALL", "tenant_01", fixedNow))
                .thenReturn(new BuyerSnapshot(10L, "buyer_code", "BuyerName", "PROJECT_01", 1));

        FulfillmentRequestDO requestDO = FulfillmentRequestDO.builder()
                .id(403L)
                .requestNo("FR_006")
                .buyerId(10L)
                .orderId(503L)
                .build();

        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(503L)
                .buyerUserId("user_006")
                .build();

        when(requestMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(requestDO);
        when(orderMapper.selectById(503L)).thenReturn(order);

        FulfillmentAssetDO assetDO = FulfillmentAssetDO.builder()
                .id(603L)
                .assetNo("FA_006")
                .bOrderId(703L)
                .sequenceNo(1)
                .assetType("CARD_SECRET")
                .encryptedPayload(new byte[]{7, 8, 9})
                .keyVersion("1")
                .maskedValue("CARD****")
                .lifecycleStatus((short) 10)
                .build();
        when(assetMapper.selectListByFulfillmentRequestId(403L)).thenReturn(List.of(assetDO));
        when(envelopeCrypto.decrypt(any(), any(), any())).thenReturn("{}".getBytes(StandardCharsets.UTF_8));
        when(businessNoGenerator.next("AUD_")).thenReturn("AUD_004");
        when(hmacFingerprint.fingerprint(eq("SENSITIVE_AUDIT_USER"), any()))
                .thenReturn("digest".getBytes(StandardCharsets.UTF_8));

        // 模拟审计持久化异常
        when(auditMapper.insert(any(SensitiveAccessAuditDO.class)))
                .thenThrow(new RuntimeException("Database connection timeout"));

        assertThatThrownBy(() -> queryService.getFulfillmentAssets(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("敏感访问审计写入失败，安全拒绝返回");
    }

    @Test
    void getFulfillmentAssetsRejectsMissingAssetReadScope() {
        FulfillmentAssetQueryReqDTO req = new FulfillmentAssetQueryReqDTO();
        req.setSourceSystem("AIFULI_MALL");
        req.setExternalTenantId("tenant_01");
        req.setFulfillmentRequestNo("FR_007");
        req.setBuyerUserId("user_007");
        req.setScopeCode("ORDER_READ");

        when(databaseClock.now()).thenReturn(fixedNow);
        when(buyerResolver.resolve("AIFULI_MALL", "tenant_01", fixedNow))
                .thenReturn(new BuyerSnapshot(10L, "buyer_code", "BuyerName", "PROJECT_01", 1));
        when(businessNoGenerator.next("AUD_")).thenReturn("AUD_SCOPE");
        when(hmacFingerprint.fingerprint(eq("SENSITIVE_AUDIT_USER"), any()))
                .thenReturn("digest".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> queryService.getFulfillmentAssets(req))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(FULFILLMENT_ASSET_SCOPE_DENIED.getCode());

        ArgumentCaptor<SensitiveAccessAuditDO> auditCaptor = ArgumentCaptor.forClass(SensitiveAccessAuditDO.class);
        verify(auditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getResultStatus()).isEqualTo(SensitiveAccessAuditResultStatus.REJECTED.getCode());
        assertThat(auditCaptor.getValue().getReasonCode()).isEqualTo("SCOPE_DENIED");
        assertThat(auditCaptor.getValue().getScopeCode()).isEqualTo("ORDER_READ");
        verify(requestMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    void getFulfillmentAssetsFailsClosedWhenDecryptFails() {
        FulfillmentAssetQueryReqDTO req = new FulfillmentAssetQueryReqDTO();
        req.setSourceSystem("AIFULI_MALL");
        req.setExternalTenantId("tenant_01");
        req.setFulfillmentRequestNo("FR_008");
        req.setBuyerUserId("user_008");
        req.setScopeCode("FULFILLMENT_ASSET_READ");

        when(databaseClock.now()).thenReturn(fixedNow);
        when(buyerResolver.resolve("AIFULI_MALL", "tenant_01", fixedNow))
                .thenReturn(new BuyerSnapshot(10L, "buyer_code", "BuyerName", "PROJECT_01", 1));

        FulfillmentRequestDO requestDO = FulfillmentRequestDO.builder()
                .id(408L)
                .requestNo("FR_008")
                .buyerId(10L)
                .orderId(508L)
                .build();
        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(508L)
                .buyerUserId("user_008")
                .build();
        FulfillmentAssetDO assetDO = FulfillmentAssetDO.builder()
                .id(608L)
                .assetNo("FA_008")
                .bOrderId(708L)
                .sequenceNo(1)
                .assetType("CARD_CODE_SECRET")
                .encryptedPayload(new byte[]{1, 2, 3})
                .keyVersion("1")
                .lifecycleStatus(FulfillmentAssetStatus.AVAILABLE.getCode())
                .build();

        when(requestMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(requestDO);
        when(orderMapper.selectById(508L)).thenReturn(order);
        when(assetMapper.selectListByFulfillmentRequestId(408L)).thenReturn(List.of(assetDO));
        when(envelopeCrypto.decrypt(any(), any(), any())).thenThrow(new IllegalStateException("kms unavailable"));
        when(businessNoGenerator.next("AUD_")).thenReturn("AUD_DECRYPT");
        when(hmacFingerprint.fingerprint(eq("SENSITIVE_AUDIT_USER"), any()))
                .thenReturn("digest".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> queryService.getFulfillmentAssets(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("交付资产解密失败");

        ArgumentCaptor<SensitiveAccessAuditDO> auditCaptor = ArgumentCaptor.forClass(SensitiveAccessAuditDO.class);
        verify(auditMapper).insert(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getResultStatus()).isEqualTo(SensitiveAccessAuditResultStatus.FAILED.getCode());
        assertThat(auditCaptor.getValue().getReasonCode()).isEqualTo("DECRYPT_FAILED");
    }

    @Test
    void getFulfillmentAssetsHidesQuarantinedAssets() {
        FulfillmentAssetQueryReqDTO req = new FulfillmentAssetQueryReqDTO();
        req.setSourceSystem("AIFULI_MALL");
        req.setExternalTenantId("tenant_01");
        req.setFulfillmentRequestNo("FR_009");
        req.setBuyerUserId("user_009");
        req.setScopeCode("FULFILLMENT_ASSET_READ");

        when(databaseClock.now()).thenReturn(fixedNow);
        when(buyerResolver.resolve("AIFULI_MALL", "tenant_01", fixedNow))
                .thenReturn(new BuyerSnapshot(10L, "buyer_code", "BuyerName", "PROJECT_01", 1));

        FulfillmentRequestDO requestDO = FulfillmentRequestDO.builder()
                .id(409L)
                .requestNo("FR_009")
                .buyerId(10L)
                .orderId(509L)
                .build();
        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .id(509L)
                .buyerUserId("user_009")
                .build();
        FulfillmentAssetDO available = FulfillmentAssetDO.builder()
                .id(609L)
                .assetNo("FA_009")
                .bOrderId(709L)
                .sequenceNo(1)
                .assetType("CARD_CODE_SECRET")
                .encryptedPayload(new byte[]{1, 2, 3})
                .keyVersion("1")
                .maskedValue("CARD****")
                .lifecycleStatus(FulfillmentAssetStatus.AVAILABLE.getCode())
                .build();
        FulfillmentAssetDO quarantined = FulfillmentAssetDO.builder()
                .id(610L)
                .assetNo("FA_009Q")
                .bOrderId(709L)
                .sequenceNo(2)
                .assetType("CARD_CODE_SECRET")
                .encryptedPayload(new byte[]{9, 9, 9})
                .keyVersion("1")
                .lifecycleStatus(FulfillmentAssetStatus.QUARANTINED.getCode())
                .build();

        when(requestMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(requestDO);
        when(orderMapper.selectById(509L)).thenReturn(order);
        when(assetMapper.selectListByFulfillmentRequestId(409L)).thenReturn(List.of(available, quarantined));
        when(businessNoGenerator.next("AUD_")).thenReturn("AUD_Q");
        when(hmacFingerprint.fingerprint(eq("SENSITIVE_AUDIT_USER"), any()))
                .thenReturn("digest".getBytes(StandardCharsets.UTF_8));
        when(envelopeCrypto.decrypt(eq(available.getEncryptedPayload()), eq("1"), any()))
                .thenReturn("{\"cardCode\":\"CARD1111\",\"cardSecret\":\"PASS1111\"}".getBytes(StandardCharsets.UTF_8));

        List<FulfillmentAssetRespDTO> result = queryService.getFulfillmentAssets(req);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAssetNo()).isEqualTo("FA_009");
        verify(envelopeCrypto, never()).decrypt(eq(quarantined.getEncryptedPayload()), any(), any());
    }
}
