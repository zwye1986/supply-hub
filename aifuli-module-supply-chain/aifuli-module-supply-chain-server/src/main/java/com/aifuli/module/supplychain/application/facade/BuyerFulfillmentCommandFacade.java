package com.aifuli.module.supplychain.application.facade;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.api.fulfillment.dto.AcceptFulfillmentReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAcceptanceReceiptRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptStatus;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentTargetDTO;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.TargetSchemaSnapshot;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver.ProjectSnapshot;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.IDEMPOTENCY_CONFLICT;

/**
 * RPC/HTTP 共用的标准履约受理门面；加密和 Schema 校验不占用数据库事务。
 *
 * <p>buyerUserId 与履约目标手机号按契约不得写入日志。详见
 * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#7.2-标准履约受理}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerFulfillmentCommandFacade {

    private final SupplyChainDatabaseClock databaseClock;

    private final BuyerResolver buyerResolver;
    private final BuyerProjectAccessResolver projectAccessResolver;
    private final FulfillmentTargetSchemaRegistry targetSchemaRegistry;
    private final EnvelopeCryptoPort envelopeCrypto;
    private final HmacFingerprintPort hmacFingerprint;
    private final FulfillmentAcceptanceLifecycle lifecycle;
    private final BusinessNoGenerator businessNoGenerator;
    private final ObjectMapper objectMapper;

    /**
     * 标准履约受理入口：按平台 SKU 派生并校验目标 Schema 与指纹后进入 Lifecycle 抢占/受理。
     *
     * <p>加密与 Schema 校验在数据库事务外完成。幂等冲突返回已有回执；可重试拒绝由 Lifecycle 收敛。
     * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#7.2-标准履约受理}。</p>
     */
    public FulfillmentAcceptanceReceiptRespDTO acceptFulfillment(AcceptFulfillmentReqDTO request) {
        log.info("[履约][facade] acceptFulfillment 入口 sourceSystem={}, externalTenantId={}, projectCode={}, buyerOrderNo={}, buyerSkuCode={}, quantity={}",
                request.getSourceSystem(), request.getExternalTenantId(), request.getProjectCode(),
                request.getBuyerOrderNo(), request.getBuyerSkuCode(), request.getQuantity());
        String normalizedBuyerUserId = requireBasicRequest(request);
        LocalDateTime now = databaseClock.now();
        BuyerResolver.BuyerSnapshot buyer = buyerResolver.resolve(
                request.getSourceSystem(), request.getExternalTenantId(), now);
        ProjectSnapshot project = projectAccessResolver.requireActiveByCode(request.getProjectCode(), buyer.id());
        TargetSchemaSnapshot targetSchema = lifecycle.resolveTargetSchema(buyer.id(), request.getBuyerSkuCode());
        String requestId = businessNoGenerator.next(BusinessNoPrefixes.REQ);
        FulfillmentAcceptanceLifecycle.PreparedAcceptance prepared = prepare(
                request, normalizedBuyerUserId, buyer, project.projectId(), targetSchema, requestId);
        FulfillmentAcceptanceLifecycle.ClaimResult claim;
        try {
            claim = lifecycle.claim(prepared);
        } catch (DataIntegrityViolationException duplicate) {
            log.info("[履约][facade] 并发首创冲突，回放业务键 buyerId={}, buyerOrderNo={}",
                    buyer.id(), request.getBuyerOrderNo());
            return toResponse(requestId, lifecycle.replayByBusinessKey(buyer.id(), request.getBuyerOrderNo()));
        } catch (FulfillmentAcceptanceLifecycle.IdempotencyConflictException conflict) {
            log.warn("[履约][facade] 幂等冲突 buyerOrderNo={}", request.getBuyerOrderNo());
            throw exception(IDEMPOTENCY_CONFLICT);
        }
        if (!claim.executeAcceptance()) {
            log.info("[履约][facade] 抢占后回放 receiptId={}", claim.receiptId());
            return toResponse(requestId, lifecycle.replay(claim.receiptId()));
        }
        try {
            FulfillmentAcceptanceReceiptRespDTO response = toResponse(requestId, lifecycle.accept(claim.receiptId(), prepared));
            log.info("[履约][facade] acceptFulfillment 完成 requestId={}, businessNo={}, receiptStatus={}, orderNo={}",
                    requestId, response.getBusinessNo(), response.getReceiptStatus(), response.getOrderNo());
            return response;
        } catch (FulfillmentAcceptanceLifecycle.BusinessRejection rejection) {
            log.warn("[履约][facade] 业务拒绝 receiptId={}, code={}",
                    rejection.receiptId(), rejection.rejectionCode());
            return toResponse(requestId, lifecycle.reject(rejection));
        }
    }

    private FulfillmentAcceptanceLifecycle.PreparedAcceptance prepare(
            AcceptFulfillmentReqDTO request, String normalizedBuyerUserId, BuyerResolver.BuyerSnapshot buyer,
            Long projectId, TargetSchemaSnapshot targetSchema, String traceId) {
        FulfillmentTargetDTO target = request.getTarget();
        Map<String, String> normalizedTargetValues = new TreeMap<>(target.getValues());
        boolean mobileTarget = FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE.equals(
                normalizedTargetValues.get(FulfillmentTargetFields.ACCOUNT_TYPE));
        String normalizedTargetMobile = mobileTarget
                ? FulfillmentTargetFields.normalizeMobile(
                normalizedTargetValues.get(FulfillmentTargetFields.ACCOUNT_VALUE)) : null;
        if (normalizedTargetMobile != null) {
            normalizedTargetValues.put(FulfillmentTargetFields.ACCOUNT_VALUE, normalizedTargetMobile);
        }
        byte[] targetCanonical = canonicalTarget(normalizedTargetValues, targetSchema);
        byte[] targetDigest = hmacFingerprint.fingerprint(FulfillmentTargetFields.FINGERPRINT_PURPOSE, targetCanonical);
        boolean targetValid = targetSchema.available() && (!mobileTarget || normalizedTargetMobile != null);
        if (targetValid) {
            try {
                targetSchemaRegistry.validate(
                        targetSchema.schemaCode(), targetSchema.schemaVersion(), normalizedTargetValues);
            } catch (FulfillmentTargetSchemaRegistry.InvalidTargetException invalid) {
                targetValid = false;
            }
        }
        EnvelopeCryptoPort.EncryptedPayload encrypted = null;
        if (targetValid) {
            encrypted = envelopeCrypto.encrypt(targetCanonical, associatedData(
                    buyer.id(), request.getBuyerOrderNo(),
                    targetSchema.schemaCode(), targetSchema.schemaVersion()));
        }

        byte[] invariant = sha256(canonicalRequest(
                request, normalizedBuyerUserId, targetSchema, targetDigest, false));
        byte[] fingerprint = sha256(canonicalRequest(
                request, normalizedBuyerUserId, targetSchema, targetDigest, true));
        byte[] buyerOrderDigest = sha256((request.getBuyerOrderNo() + '|'
                + normalizedBuyerUserId).getBytes(StandardCharsets.UTF_8));
        String fulfillmentAccountValue = FulfillmentTargetFields.mobileAccountValue(normalizedTargetValues);
        return new FulfillmentAcceptanceLifecycle.PreparedAcceptance(
                projectId, buyer.id(), buyer.buyerCode(), buyer.buyerName(), request.getBuyerOrderNo(),
                normalizedBuyerUserId, fulfillmentAccountValue, request.getBuyerSkuCode(),
                Math.toIntExact(request.getListingVersion()), request.getQuantity(),
                request.getPricingQuoteToken(),
                request.getPaidAt().atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime(),
                targetSchema.schemaCode(), targetSchema.schemaVersion(),
                encrypted == null ? null : encrypted.ciphertext(), targetDigest,
                encrypted == null ? null : encrypted.keyVersion(), targetValid,
                invariant, fingerprint, buyerOrderDigest, request.getPreviousReceiptVersion(), traceId);
    }

    private byte[] canonicalTarget(Map<String, String> targetValues, TargetSchemaSnapshot targetSchema) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("schemaCode", targetSchema.schemaCode());
        canonical.put("schemaVersion", targetSchema.schemaVersion());
        canonical.put("values", new TreeMap<>(targetValues));
        try {
            return objectMapper.writeValueAsBytes(canonical);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Fulfillment target cannot be canonicalized", exception);
        }
    }

    private byte[] canonicalRequest(AcceptFulfillmentReqDTO request, String normalizedBuyerUserId,
                                    TargetSchemaSnapshot targetSchema,
                                    byte[] targetDigest, boolean includeListingVersion) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("buyerOrderNo", request.getBuyerOrderNo());
        canonical.put("projectCode", request.getProjectCode());
        canonical.put("buyerUserId", normalizedBuyerUserId);
        canonical.put("buyerSkuCode", request.getBuyerSkuCode());
        canonical.put("quantity", request.getQuantity());
        canonical.put("pricingQuoteToken", request.getPricingQuoteToken());
        canonical.put("targetSchemaCode", targetSchema.schemaCode());
        canonical.put("targetSchemaVersion", targetSchema.schemaVersion());
        canonical.put("targetDigest", java.util.HexFormat.of().formatHex(targetDigest));
        canonical.put("paidAt", request.getPaidAt().toInstant().toString());
        if (includeListingVersion) {
            canonical.put("listingVersion", request.getListingVersion());
        }
        try {
            return objectMapper.writeValueAsBytes(canonical);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Fulfillment request cannot be canonicalized", exception);
        }
    }

    private static byte[] associatedData(Long buyerId, String buyerOrderNo,
                                         String schemaCode, int schemaVersion) {
        return (buyerId + "|" + buyerOrderNo + "|" + schemaCode + "|" + schemaVersion)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String requireBasicRequest(AcceptFulfillmentReqDTO request) {
        Objects.requireNonNull(request, "Fulfillment request is required");
        Objects.requireNonNull(request.getTarget(), "Fulfillment target is required");
        Objects.requireNonNull(request.getTarget().getValues(), "Fulfillment target values are required");
        Objects.requireNonNull(request.getListingVersion(), "Listing version is required");
        Objects.requireNonNull(request.getQuantity(), "Quantity is required");
        Objects.requireNonNull(request.getPaidAt(), "Paid time is required");
        if (blank(request.getSourceSystem()) || blank(request.getExternalTenantId())
                || blank(request.getBuyerOrderNo()) || blank(request.getBuyerUserId())
                || blank(request.getBuyerSkuCode()) || blank(request.getProjectCode())
                || blank(request.getPricingQuoteToken())
                || request.getListingVersion() <= 0 || request.getListingVersion() > Integer.MAX_VALUE
                || request.getQuantity() <= 0) {
            throw new IllegalArgumentException("Fulfillment request is incomplete");
        }
        String normalizedBuyerUserId = FulfillmentTargetFields.normalizeMobile(request.getBuyerUserId());
        if (normalizedBuyerUserId == null) {
            throw new IllegalArgumentException("采购商用户标识必须是有效的中国大陆手机号");
        }
        return normalizedBuyerUserId;
    }

    private FulfillmentAcceptanceReceiptRespDTO toResponse(
            String requestId, FulfillmentAcceptanceLifecycle.AcceptanceSnapshot snapshot) {
        FulfillmentAcceptanceReceiptRespDTO response = new FulfillmentAcceptanceReceiptRespDTO();
        response.setRequestId(requestId);
        response.setBusinessNo(snapshot.buyerOrderNo());
        response.setReceiptStatus(FulfillmentReceiptStatus.valueOf(
                AcceptanceReceiptStatus.fromCode(snapshot.status()).name()));
        response.setReceiptVersion(snapshot.receiptVersion());
        response.setOrderNo(snapshot.orderNo());
        response.setOrderStatus(snapshot.orderNo() == null ? null : SupplyChainOrderStatus.PROCESSING);
        response.setFulfillmentRequestNo(snapshot.fulfillmentRequestNo());
        response.setRejectionCode(snapshot.rejectionCode());
        response.setSupplierCode(snapshot.supplierCode());
        response.setSupplierName(snapshot.supplierName());
        response.setAcceptedAt(snapshot.acceptedAt() == null ? null
                : databaseClock.toInstant(snapshot.acceptedAt()).atOffset(ZoneOffset.UTC));
        response.setIdempotentReplay(snapshot.idempotentReplay());
        return response;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
