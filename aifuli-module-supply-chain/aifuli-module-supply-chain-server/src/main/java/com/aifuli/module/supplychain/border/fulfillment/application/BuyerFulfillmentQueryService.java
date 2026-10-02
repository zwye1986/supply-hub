package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAcceptanceReceiptRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAssetQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAssetRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptStatus;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentResultQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentResultRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentStatus;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAcceptanceReceiptDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAssetDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SensitiveAccessAuditDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAcceptanceReceiptMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAssetMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.SensitiveAccessAuditMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.BuyerFulfillmentScopes;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentAssetStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentRequestStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.SensitiveAccessAuditAccessType;
import com.aifuli.module.supplychain.border.fulfillment.domain.SensitiveAccessAuditResultStatus;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver.BuyerSnapshot;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver.ProjectSnapshot;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.FULFILLMENT_ASSET_SCOPE_DENIED;

/**
 * 采购商履约查询领域服务：处理受理回执查询、履约结果查询与授权敏感资产解密查询。
 *
 * <p>敏感资产查询严格遵循最小暴露原则：核验调用来源系统、租户与真实项目归属（项目在 RPC 服务端由
 * 真实来源与 projectCode 校验，不再接收购买人用户标识），解密交付卡密并记录不可变
 * {@code sc_sensitive_access_audit} 访问审计。回执与订单均按项目永久键 (projectId, buyerOrderNo) 查询。
 * 设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}、
 * {@code docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#1-双入口与共享命令}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerFulfillmentQueryService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final BuyerResolver buyerResolver;
    private final BuyerProjectAccessResolver projectAccessResolver;
    private final FulfillmentAcceptanceReceiptMapper receiptMapper;
    private final FulfillmentRequestMapper requestMapper;
    private final SupplyChainOrderMapper orderMapper;
    private final FulfillmentAssetMapper assetMapper;
    private final SensitiveAccessAuditMapper auditMapper;
    private final EnvelopeCryptoPort envelopeCrypto;
    private final HmacFingerprintPort hmacFingerprint;
    private final BusinessNoGenerator businessNoGenerator;
    private final SupplyChainDatabaseClock databaseClock;
    private final ObjectMapper objectMapper;

    /**
     * 按项目永久键查询履约受理回执：真实来源解析采购商后按 projectCode 校验项目归属，
     * 再按 (projectId, buyerOrderNo) 查询；不接收购买人用户标识。
     */
    public FulfillmentAcceptanceReceiptRespDTO getAcceptanceReceipt(FulfillmentReceiptQueryReqDTO request) {
        log.info("[履约查询] getAcceptanceReceipt sourceSystem={}, externalTenantId={}, projectCode={}, buyerOrderNo={}",
                request.getSourceSystem(), request.getExternalTenantId(),
                request.getProjectCode(), request.getBuyerOrderNo());
        LocalDateTime now = databaseClock.now();
        BuyerSnapshot buyer = buyerResolver.resolve(request.getSourceSystem(), request.getExternalTenantId(), now);
        ProjectSnapshot project = projectAccessResolver.requireActiveByCode(request.getProjectCode(), buyer.id());
        FulfillmentAcceptanceReceiptDO receipt = receiptMapper.selectByProjectKey(
                project.projectId(), request.getBuyerOrderNo());
        if (receipt == null) {
            return null;
        }
        SupplyChainOrderDO order = receipt.getResultOrderId() == null ? null : orderMapper.selectById(receipt.getResultOrderId());

        FulfillmentAcceptanceReceiptRespDTO resp = new FulfillmentAcceptanceReceiptRespDTO();
        resp.setRequestId(receipt.getReceiptNo());
        resp.setBusinessNo(receipt.getBuyerOrderNo());
        resp.setReceiptStatus(FulfillmentReceiptStatus.valueOf(AcceptanceReceiptStatus.fromCode(receipt.getStatus()).name()));
        resp.setReceiptVersion(receipt.getVersion());
        resp.setOrderNo(receipt.getResultOrderNo());
        if (order != null && order.getOrderStatus() != null) {
            resp.setOrderStatus(SupplyChainOrderStatus.fromCode(order.getOrderStatus()));
        }
        resp.setFulfillmentRequestNo(receipt.getResultFulfillmentRequestNo());
        resp.setRejectionCode(receipt.getRejectionCode());
        if (order != null) {
            resp.setSupplierCode(order.getDisplaySupplierCode());
            resp.setSupplierName(order.getDisplaySupplierName());
        }
        resp.setAcceptedAt(receipt.getAcceptedAt() == null ? null
                : databaseClock.toInstant(receipt.getAcceptedAt()).atOffset(ZoneOffset.UTC));
        resp.setIdempotentReplay(false);
        return resp;
    }

    /**
     * 按项目永久键查询履约结果摘要（不包含明文卡号/卡密/券码）。
     */
    public FulfillmentResultRespDTO getFulfillmentResult(FulfillmentResultQueryReqDTO request) {
        log.info("[履约查询] getFulfillmentResult sourceSystem={}, externalTenantId={}, projectCode={}, buyerOrderNo={}",
                request.getSourceSystem(), request.getExternalTenantId(),
                request.getProjectCode(), request.getBuyerOrderNo());
        LocalDateTime now = databaseClock.now();
        BuyerSnapshot buyer = buyerResolver.resolve(request.getSourceSystem(), request.getExternalTenantId(), now);
        ProjectSnapshot project = projectAccessResolver.requireActiveByCode(request.getProjectCode(), buyer.id());
        SupplyChainOrderDO order = orderMapper.selectOne(
                new LambdaQueryWrapper<SupplyChainOrderDO>()
                        .eq(SupplyChainOrderDO::getBuyerId, buyer.id())
                        .eq(SupplyChainOrderDO::getProjectId, project.projectId())
                        .eq(SupplyChainOrderDO::getBuyerOrderNo, request.getBuyerOrderNo()));
        if (order == null || order.getDetailId() == null) {
            return null;
        }

        FulfillmentRequestDO fulfillmentRequest = requestMapper.selectById(order.getDetailId());
        if (fulfillmentRequest == null) {
            return null;
        }

        FulfillmentResultRespDTO resp = new FulfillmentResultRespDTO();
        resp.setFulfillmentRequestNo(fulfillmentRequest.getRequestNo());
        resp.setOrderNo(order.getOrderNo());
        resp.setBuyerOrderNo(order.getBuyerOrderNo());
        resp.setFulfillmentStatus(mapFulfillmentStatus(fulfillmentRequest.getStatus()));
        resp.setRequestedQuantity(fulfillmentRequest.getRequestedQuantity());
        resp.setDeliveredQuantity(fulfillmentRequest.getFulfilledQuantity());
        resp.setAssetAvailable(fulfillmentRequest.getFulfilledQuantity() != null && fulfillmentRequest.getFulfilledQuantity() > 0);
        resp.setAssetReference(fulfillmentRequest.getRequestNo());
        if (order.getCompletedAt() != null) {
            resp.setCompletedAt(databaseClock.toInstant(order.getCompletedAt()).atOffset(ZoneOffset.UTC));
        }
        if (order.getUpdatedAt() != null) {
            resp.setUpdatedAt(databaseClock.toInstant(order.getUpdatedAt()).atOffset(ZoneOffset.UTC));
        }
        return resp;
    }

    /**
     * 授权查询履约敏感资产（信封解密并写入不可变访问审计）。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public List<FulfillmentAssetRespDTO> getFulfillmentAssets(FulfillmentAssetQueryReqDTO request) {
        log.info("[履约查询] getFulfillmentAssets 入口 sourceSystem={}, externalTenantId={}, fulfillmentRequestNo={}",
                request.getSourceSystem(), request.getExternalTenantId(), request.getFulfillmentRequestNo());

        LocalDateTime now = databaseClock.now();
        BuyerSnapshot buyer = buyerResolver.resolve(request.getSourceSystem(), request.getExternalTenantId(), now);
        if (!BuyerFulfillmentScopes.FULFILLMENT_ASSET_READ.equals(request.getScopeCode())) {
            recordAudit(request, buyer.id(), null, SensitiveAccessAuditResultStatus.REJECTED.getCode(),
                    "SCOPE_DENIED", now);
            throw exception(FULFILLMENT_ASSET_SCOPE_DENIED);
        }

        FulfillmentRequestDO fulfillmentRequest = requestMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentRequestDO>()
                        .eq(FulfillmentRequestDO::getRequestNo, request.getFulfillmentRequestNo()));
        if (fulfillmentRequest == null) {
            recordAudit(request, buyer.id(), null, SensitiveAccessAuditResultStatus.REJECTED.getCode(), "REQUEST_NOT_FOUND", now);
            throw exception(BUYER_AUTH_INVALID);
        }

        if (!Objects.equals(fulfillmentRequest.getBuyerId(), buyer.id())) {
            recordAudit(request, buyer.id(), fulfillmentRequest.getId(), SensitiveAccessAuditResultStatus.REJECTED.getCode(), "BUYER_MISMATCH", now);
            throw exception(BUYER_AUTH_INVALID);
        }

        // 项目在 RPC 服务端由真实来源与 projectCode 校验：订单固化项目必须属于解析出的启用项目，
        // 替代已删除的购买人用户标识比对。设计见
        // docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#1-双入口与共享命令。
        ProjectSnapshot project = projectAccessResolver.requireActiveByCode(request.getProjectCode(), buyer.id());
        SupplyChainOrderDO order = orderMapper.selectById(fulfillmentRequest.getOrderId());
        if (order == null || !Objects.equals(order.getProjectId(), project.projectId())) {
            recordAudit(request, buyer.id(), fulfillmentRequest.getId(), SensitiveAccessAuditResultStatus.REJECTED.getCode(), "PROJECT_MISMATCH", now);
            throw exception(BUYER_AUTH_INVALID);
        }

        List<FulfillmentAssetDO> assets = assetMapper.selectListByFulfillmentRequestId(fulfillmentRequest.getId());
        List<FulfillmentAssetRespDTO> results = new ArrayList<>();

        for (FulfillmentAssetDO asset : assets) {
            if (!isDeliverableToBuyer(asset.getLifecycleStatus())) {
                continue;
            }
            byte[] associatedData = (asset.getBOrderId() + "|" + asset.getSequenceNo() + "|" + asset.getAssetType())
                    .getBytes(StandardCharsets.UTF_8);
            byte[] decrypted;
            try {
                decrypted = envelopeCrypto.decrypt(asset.getEncryptedPayload(), asset.getKeyVersion(), associatedData);
            } catch (Exception e) {
                recordAudit(request, buyer.id(), asset.getId(), SensitiveAccessAuditResultStatus.FAILED.getCode(),
                        "DECRYPT_FAILED", now);
                throw new IllegalStateException("交付资产解密失败: " + asset.getAssetNo(), e);
            }

            FulfillmentAssetRespDTO dto = new FulfillmentAssetRespDTO();
            dto.setAssetNo(asset.getAssetNo());
            dto.setSequenceNo(asset.getSequenceNo());
            dto.setAssetType(asset.getAssetType());
            dto.setMaskedValue(asset.getMaskedValue());
            if (asset.getValidFrom() != null) {
                dto.setValidFrom(databaseClock.toInstant(asset.getValidFrom()).atOffset(ZoneOffset.UTC));
            }
            if (asset.getValidUntil() != null) {
                dto.setValidUntil(databaseClock.toInstant(asset.getValidUntil()).atOffset(ZoneOffset.UTC));
            }
            dto.setLifecycleStatus(asset.getLifecycleStatus() != null ? FulfillmentAssetStatus.fromCode(asset.getLifecycleStatus()).name() : FulfillmentAssetStatus.AVAILABLE.name());

            try {
                JsonNode payloadNode = objectMapper.readTree(decrypted);
                if (payloadNode.hasNonNull("cardCode")) {
                    dto.setCardCode(payloadNode.get("cardCode").asText());
                }
                if (payloadNode.hasNonNull("cardSecret")) {
                    dto.setCardSecret(payloadNode.get("cardSecret").asText());
                }
                if (payloadNode.hasNonNull("voucherCode")) {
                    dto.setVoucherCode(payloadNode.get("voucherCode").asText());
                }
                if (payloadNode.hasNonNull("voucherUrl")) {
                    dto.setVoucherUrl(payloadNode.get("voucherUrl").asText());
                }
            } catch (Exception e) {
                log.error("[履约查询] 反序列化资产载荷异常 assetNo={}", asset.getAssetNo(), e);
                throw new IllegalStateException("交付资产载荷格式异常: " + asset.getAssetNo(), e);
            }

            results.add(dto);
            recordAudit(request, buyer.id(), asset.getId(), SensitiveAccessAuditResultStatus.SUCCESS.getCode(), "AUTHORIZED_QUERY", now);
        }

        log.info("[履约查询] getFulfillmentAssets 完成 fulfillmentRequestNo={}, assetCount={}",
                request.getFulfillmentRequestNo(), results.size());
        return results;
    }

    private void recordAudit(FulfillmentAssetQueryReqDTO request, Long buyerId,
                             Long resourceId, short resultStatus, String reasonCode, LocalDateTime now) {
        // 请求级购买人用户标识已删除（052）：审计主体保持真实 BUYER_SERVICE 与 source:tenant，
        // 不再计算购买人用户摘要（buyerUserDigest 为 NULL，不以零长数组占位）。设计见
        // docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#1-双入口与共享命令。
        SensitiveAccessAuditDO audit = SensitiveAccessAuditDO.builder()
                .auditNo(businessNoGenerator.next(BusinessNoPrefixes.AUD))
                .accessType(SensitiveAccessAuditAccessType.ASSET_DECRYPT.name())
                .principalType("BUYER_SERVICE")
                .principalId(request.getSourceSystem() + ":" + request.getExternalTenantId())
                .buyerId(buyerId)
                .resourceType("FULFILLMENT_ASSET")
                .resourceId(resourceId != null ? resourceId : 0L)
                .scopeCode(request.getScopeCode() != null && !request.getScopeCode().isBlank()
                        ? request.getScopeCode() : BuyerFulfillmentScopes.FULFILLMENT_ASSET_READ)
                .resultStatus(resultStatus)
                .reasonCode(reasonCode)
                .traceId(request.getFulfillmentRequestNo())
                .accessedAt(now)
                .build();
        try {
            auditMapper.insert(audit);
        } catch (Exception e) {
            log.error("[履约查询] 记录敏感访问审计失败 fulfillmentRequestNo={}", request.getFulfillmentRequestNo(), e);
            throw new IllegalStateException("敏感访问审计写入失败，安全拒绝返回: " + request.getFulfillmentRequestNo(), e);
        }
    }

    private FulfillmentStatus mapFulfillmentStatus(Short status) {
        if (status == null) {
            return FulfillmentStatus.PROCESSING;
        }
        FulfillmentRequestStatus requestStatus;
        try {
            requestStatus = FulfillmentRequestStatus.fromCode(status);
        } catch (IllegalArgumentException e) {
            log.warn("[履约查询] 未知履约请求状态 status={}", status);
            return FulfillmentStatus.MANUAL;
        }
        return switch (requestStatus) {
            case PENDING_ROUTING, ROUTING -> FulfillmentStatus.ACCEPTED;
            case FULFILLING -> FulfillmentStatus.PROCESSING;
            case SUCCEEDED -> FulfillmentStatus.SUCCEEDED;
            case FAILED -> FulfillmentStatus.FAILED;
            case MANUAL -> FulfillmentStatus.MANUAL;
        };
    }

    private static boolean isDeliverableToBuyer(Short lifecycleStatus) {
        if (lifecycleStatus == null) {
            return false;
        }
        return lifecycleStatus == FulfillmentAssetStatus.AVAILABLE.getCode()
                || lifecycleStatus == FulfillmentAssetStatus.DELIVERED.getCode();
    }
}
