package com.aifuli.module.supplychain.application.facade;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.api.fulfillment.dto.AcceptFulfillmentReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAcceptanceReceiptRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptStatus;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentTargetDTO;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.application.FrozenAcceptanceIdentityReader;
import com.aifuli.module.supplychain.border.fulfillment.application.FrozenAcceptanceIdentityReader.FrozenAcceptanceIdentity;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyAcceptanceCoordinator;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.TargetSchemaSnapshot;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.border.fulfillment.domain.StandardAcceptanceFingerprintPolicy;
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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.IDEMPOTENCY_CONFLICT;

/**
 * RPC/HTTP 共用的标准履约受理门面；加密和 Schema 校验不占用数据库事务。
 *
 * <p>请求已无购买人身份、支付时间、调用方商品版本与报价 token：指纹按格式 2 无用户规范化
 * 请求计算（见 {@link StandardAcceptanceFingerprintPolicy}），手机号身份只经
 * target.accountType=MOBILE 的实际值表达。履约目标手机号按契约不得写入日志。详见
 * {@code docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#2-首次身份与重放优先}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerFulfillmentCommandFacade {

    private final SupplyChainDatabaseClock databaseClock;

    private final BuyerResolver buyerResolver;
    private final BuyerProjectAccessResolver projectAccessResolver;
    private final FrozenAcceptanceIdentityReader frozenIdentityReader;
    private final FulfillmentTargetSchemaRegistry targetSchemaRegistry;
    private final EnvelopeCryptoPort envelopeCrypto;
    private final HmacFingerprintPort hmacFingerprint;
    private final FulfillmentAcceptanceLifecycle lifecycle;
    private final RechargeFrequencyAcceptanceCoordinator acceptanceCoordinator;
    private final BusinessNoGenerator businessNoGenerator;
    private final ObjectMapper objectMapper;

    /**
     * 标准履约受理入口：按平台 SKU 派生并校验目标 Schema 与指纹后进入 Lifecycle 抢占/受理。
     *
     * <p>加密与 Schema 校验在数据库事务外完成。claim 按项目永久键 (projectId, buyerOrderNo) 收敛：
     * 并发首创落败方在 Lifecycle 内按项目键重探测并比较指纹——同参数回放/续跑，异参抛幂等冲突，
     * Facade 不捕获任意键冲突盲回放。响应 requestId 一律返回实际回执号
     * （AcceptanceSnapshot.receiptNo），首次与重放一致，不再以每次新生成的 REQ 业务号冒充稳定回执号
     * （该号仅作为 traceId 供链路审计）。
     * 详见 {@code docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#3-回执竞争、重试和真实返回}。</p>
     */
    public FulfillmentAcceptanceReceiptRespDTO acceptFulfillment(AcceptFulfillmentReqDTO request) {
        log.info("[履约][facade] acceptFulfillment 入口 sourceSystem={}, externalTenantId={}, projectCode={}, buyerOrderNo={}, buyerSkuCode={}, quantity={}",
                request.getSourceSystem(), request.getExternalTenantId(), request.getProjectCode(),
                request.getBuyerOrderNo(), request.getBuyerSkuCode(), request.getQuantity());
        requireBasicRequest(request);
        LocalDateTime now = databaseClock.now();
        BuyerResolver.BuyerSnapshot buyer = buyerResolver.resolve(
                request.getSourceSystem(), request.getExternalTenantId(), now);
        ProjectSnapshot project = projectAccessResolver.requireActiveByCode(request.getProjectCode(), buyer.id());
        Optional<FrozenAcceptanceIdentity> frozenIdentity;
        try {
            frozenIdentity = frozenIdentityReader.find(buyer.id(), project.projectId(), request.getBuyerOrderNo());
        } catch (FulfillmentAcceptanceLifecycle.IdempotencyConflictException untrusted) {
            log.warn("[履约][facade] 历史回执冻结身份不受信 projectId={}, buyerOrderNo={}",
                    project.projectId(), request.getBuyerOrderNo());
            throw exception(IDEMPOTENCY_CONFLICT);
        }
        TargetSchemaSnapshot targetSchema = frozenIdentity.map(FrozenAcceptanceIdentity::targetSchema)
                .orElseGet(() -> lifecycle.resolveTargetSchema(buyer.id(), request.getBuyerSkuCode()));
        // 仅作链路追踪与审计 traceId（outbox/资金/状态日志沿用）；对外稳定回执号见回执快照 receiptNo。
        String traceId = businessNoGenerator.next(BusinessNoPrefixes.REQ);
        boolean acceptedReplayOnly = frozenIdentity.map(FrozenAcceptanceIdentity::accepted).orElse(false);
        FulfillmentAcceptanceLifecycle.PreparedAcceptance prepared = prepare(
                request, buyer, project, targetSchema, traceId, acceptedReplayOnly);
        FulfillmentAcceptanceLifecycle.ClaimResult claim;
        try {
            claim = claimWithProjectKeyRaceRetry(prepared);
        } catch (FulfillmentAcceptanceLifecycle.IdempotencyConflictException conflict) {
            log.warn("[履约][facade] 幂等冲突 projectId={}, buyerOrderNo={}",
                    project.projectId(), request.getBuyerOrderNo());
            throw exception(IDEMPOTENCY_CONFLICT);
        }
        if (!claim.executeAcceptance()) {
            log.info("[履约][facade] 抢占后回放 receiptId={}", claim.receiptId());
            return toResponse(lifecycle.replay(claim.receiptId()));
        }
        if (frozenIdentity.isPresent()) {
            TargetSchemaSnapshot currentCatalog = lifecycle.resolveTargetSchema(buyer.id(), request.getBuyerSkuCode());
            if (!targetSchema.platformSkuId().equals(currentCatalog.platformSkuId())) {
                log.warn("[履约][facade] 续跑平台SKU与冻结不变量冲突 frozenSkuId={}, currentSkuId={}",
                        targetSchema.platformSkuId(), currentCatalog.platformSkuId());
                throw exception(IDEMPOTENCY_CONFLICT);
            }
            prepared = prepared.withCatalogIdentity(currentCatalog.catalogIdentity());
            if (!prepared.targetValid()) {
                prepared = prepare(request, buyer, project, targetSchema, traceId, false)
                        .withCatalogIdentity(currentCatalog.catalogIdentity());
            }
        }
        try {
            FulfillmentAcceptanceReceiptRespDTO response = toResponse(acceptanceCoordinator.accept(claim.receiptId(), prepared));
            log.info("[履约][facade] acceptFulfillment 完成 receiptNo={}, businessNo={}, receiptStatus={}, orderNo={}",
                    response.getRequestId(), response.getBusinessNo(), response.getReceiptStatus(), response.getOrderNo());
            return response;
        } catch (FulfillmentAcceptanceLifecycle.BusinessRejection rejection) {
            log.warn("[履约][facade] 业务拒绝 receiptId={}, code={}",
                    rejection.receiptId(), rejection.rejectionCode());
            return toResponse(lifecycle.reject(rejection));
        }
    }

    /**
     * 项目键并发首创竞争的收敛入口，两条路径均不捕获任意键冲突盲回放：
     * <ul>
     *   <li>{@link ProjectKeyClaimRaceException}：claim 的项目永久键唯一冲突已结束旧事务（释放
     *       insert 在其他唯一索引上残留的意向/gap 锁，避免与获胜方 accept 收敛互锁），此处以
     *       新事务按项目键重探测获胜回执并比较指纹——同参回放/续跑，异参幂等冲突。</li>
     *   <li>{@link CannotAcquireLockException}：并发首创双方在唯一索引上互等（死锁/锁超时），
     *       受害方整个 claim 事务已回滚，以新事务重试一次 claim 即收敛（重试中若获 duplicate
     *       仍走上一条路径），不构成递归循环。</li>
     * </ul>
     * 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#3-回执竞争、重试和真实返回。
     */
    private FulfillmentAcceptanceLifecycle.ClaimResult claimWithProjectKeyRaceRetry(
            FulfillmentAcceptanceLifecycle.PreparedAcceptance prepared) {
        try {
            return lifecycle.claim(prepared);
        } catch (FulfillmentAcceptanceLifecycle.ProjectKeyClaimRaceException projectKeyRace) {
            log.info("[履约][facade] 项目键并发首创冲突，新事务按项目键重探测 buyerOrderNo={}, traceId={}",
                    prepared.buyerOrderNo(), prepared.traceId(), projectKeyRace.getCause());
            return lifecycle.claimAfterProjectKeyRace(prepared);
        } catch (CannotAcquireLockException lockRace) {
            log.info("[履约][facade] 并发首创锁竞争（死锁/锁超时），按项目键新事务重试 buyerOrderNo={}, traceId={}",
                    prepared.buyerOrderNo(), prepared.traceId(), lockRace);
            return lifecycle.claim(prepared);
        }
    }

    /**
     * 目标规范化、封闭校验、加密与格式 2 指纹准备（全部在数据库事务外完成）。
     *
     * <p>invariant/request 指纹与 buyerOrderDigest 均按无用户格式 2 计算：服务端尝试版本类
     * 信息不再混入 fingerprint（052 增量将以独立列承载）；手机号身份只出现在 MOBILE 目标的
     * 规范化值与其摘要中，不在规范化请求里保留任何购买人身份副本。</p>
     */
    private FulfillmentAcceptanceLifecycle.PreparedAcceptance prepare(
            AcceptFulfillmentReqDTO request, BuyerResolver.BuyerSnapshot buyer,
            ProjectSnapshot project, TargetSchemaSnapshot targetSchema, String traceId, boolean acceptedReplayOnly) {
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
        boolean targetValid = !acceptedReplayOnly && targetSchema.available() && (!mobileTarget || normalizedTargetMobile != null);
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

        // 格式 2 无用户指纹：不变量与请求指纹同一规范化输入；服务端冻结商品身份仅在可解析时参与。
        StandardAcceptanceFingerprintPolicy.RequestIdentity identity =
                new StandardAcceptanceFingerprintPolicy.RequestIdentity(
                        buyer.id(), project.projectCode(), request.getBuyerOrderNo(),
                        request.getBuyerSkuCode(),
                        targetSchema.available() ? targetSchema.platformSkuId() : null,
                        request.getQuantity(),
                        targetSchema.schemaCode(), targetSchema.schemaVersion(),
                        HexFormat.of().formatHex(targetDigest));
        byte[] canonical = StandardAcceptanceFingerprintPolicy.canonicalRequest(identity);
        byte[] invariant = sha256(canonical);
        byte[] fingerprint = sha256(canonical);
        byte[] buyerOrderDigest = sha256(StandardAcceptanceFingerprintPolicy
                .canonicalBuyerOrderDigestInput(project.projectCode(), request.getBuyerOrderNo()));
        String fulfillmentAccountValue = FulfillmentTargetFields.mobileAccountValue(normalizedTargetValues);
        return new FulfillmentAcceptanceLifecycle.PreparedAcceptance(
                project.projectId(), project.projectCode(), project.projectName(),
                buyer.id(), buyer.buyerCode(), buyer.buyerName(), request.getBuyerOrderNo(),
                fulfillmentAccountValue,
                normalizedTargetValues.get(FulfillmentTargetFields.ACCOUNT_TYPE), request.getBuyerSkuCode(),
                request.getQuantity(),
                targetSchema.schemaCode(), targetSchema.schemaVersion(),
                encrypted == null ? null : encrypted.ciphertext(), targetDigest,
                encrypted == null ? null : encrypted.keyVersion(), targetValid,
                invariant, fingerprint, buyerOrderDigest, request.getPreviousReceiptVersion(), traceId, targetSchema.catalogIdentity());
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

    /** 目标加密 AAD 保持既有引用链 buyerId|buyerOrderNo|schemaCode|schemaVersion，旧密文解密不受影响。 */
    private static byte[] associatedData(Long buyerId, String buyerOrderNo,
                                         String schemaCode, int schemaVersion) {
        return (buyerId + "|" + buyerOrderNo + "|" + schemaCode + "|" + schemaVersion)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 基础完整性校验：不再要求购买人身份、调用方商品版本、报价 token 与支付时间。 */
    private static void requireBasicRequest(AcceptFulfillmentReqDTO request) {
        Objects.requireNonNull(request, "Fulfillment request is required");
        Objects.requireNonNull(request.getTarget(), "Fulfillment target is required");
        Objects.requireNonNull(request.getTarget().getValues(), "Fulfillment target values are required");
        Objects.requireNonNull(request.getQuantity(), "Quantity is required");
        if (blank(request.getSourceSystem()) || blank(request.getExternalTenantId())
                || blank(request.getBuyerOrderNo())
                || blank(request.getBuyerSkuCode()) || blank(request.getProjectCode())
                || request.getQuantity() <= 0) {
            throw new IllegalArgumentException("Fulfillment request is incomplete");
        }
    }

    /**
     * 回执响应组装：requestId=实际回执号 receiptNo（首次、重放、拒绝收敛路径一致）；
     * orderStatus=订单表当前真实状态，未建单显式 null，不再恒写 PROCESSING。
     */
    private FulfillmentAcceptanceReceiptRespDTO toResponse(
            FulfillmentAcceptanceLifecycle.AcceptanceSnapshot snapshot) {
        FulfillmentAcceptanceReceiptRespDTO response = new FulfillmentAcceptanceReceiptRespDTO();
        response.setRequestId(snapshot.receiptNo());
        response.setBusinessNo(snapshot.buyerOrderNo());
        response.setReceiptStatus(FulfillmentReceiptStatus.valueOf(
                AcceptanceReceiptStatus.fromCode(snapshot.status()).name()));
        response.setReceiptVersion(snapshot.receiptVersion());
        response.setOrderNo(snapshot.orderNo());
        response.setOrderStatus(snapshot.orderStatus() == null
                ? null : SupplyChainOrderStatus.fromCode(snapshot.orderStatus()));
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
