package com.aifuli.module.supplychain.application.facade;

import com.aifuli.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptStatus;
import com.aifuli.module.supplychain.api.fulfillment.dto.ProjectStandardOrderCreateRespDTO;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.AcceptanceSnapshot;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.BusinessRejection;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.ClaimResult;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.IdempotencyConflictException;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.PreparedAcceptance;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.ProjectKeyClaimRaceException;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentAcceptanceLifecycle.TargetSchemaSnapshot;
import com.aifuli.module.supplychain.border.fulfillment.application.FrozenAcceptanceIdentityReader;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyAcceptanceCoordinator;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.border.fulfillment.domain.StandardAcceptanceFingerprintPolicy;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.StandardProductContext;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.interfaceapi.open.project.dto.ProjectStandardOrderCreateReqDTO;
import com.aifuli.module.supplychain.interfaceapi.open.project.dto.ProjectPlatformSkuOrderCreateReqDTO;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver.ProjectSnapshot;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver.BuyerSnapshot;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.ProjectQuoteKeyContext;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogRequestCoordinator;
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
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.IDEMPOTENCY_CONFLICT;

/**
 * 项目标准下单命令门面。
 *
 * <p>负责对接已通过项目应用认证的 StandardProductContext，完成项目与采购商快照校验、目标规范化、
 * Schema 校验、目标加密、格式 2 指纹生成，委派 Lifecycle 与限频排队协调器执行受理，
 * 并将结果转换为封闭的 ProjectStandardOrderCreateRespDTO，杜绝向外部暴露内部供应商和履约链路细节。
 * 详见 {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#1-controller与严格请求解析}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectStandardOrderCommandFacade {

    private final SupplyChainDatabaseClock databaseClock;
    private final BuyerResolver buyerResolver;
    private final BuyerProjectAccessResolver projectAccessResolver;
    private final FulfillmentTargetSchemaRegistry targetSchemaRegistry;
    private final EnvelopeCryptoPort envelopeCrypto;
    private final HmacFingerprintPort hmacFingerprint;
    private final FulfillmentAcceptanceLifecycle lifecycle;
    private final FrozenAcceptanceIdentityReader frozenIdentityReader;
    private final com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogRequestCoordinator requestCoordinator;
    private final RechargeFrequencyAcceptanceCoordinator acceptanceCoordinator;
    private final StandardPricingQuoteService pricingQuoteService;
    private final BusinessNoGenerator businessNoGenerator;
    private final ObjectMapper objectMapper;

    /**
     * 标准商品项目下单入口。
     *
     * @param context 经 HMAC 认证的可信项目应用快照
     * @param request 经严格解析后的下单请求输入
     * @return 封闭的标准下单响应
     */
    public ProjectStandardOrderCreateRespDTO create(StandardProductContext context, ProjectStandardOrderCreateReqDTO request) {
        validateContext(context);
        return createPrepared(context.projectId(), context.buyerId(), request, null);
    }

    /**
     * 独立v2适配：原SKU请求码用于格式3指纹，内部BPD只由可信商业关系解析，不接受调用方覆盖。
     * 设计见 docs/superpowers/specs/2026-10-12-platform-sku-order-contract-design.md#42-共享命令及执行分支。
     */
    public ProjectStandardOrderCreateRespDTO createV2(StandardProductContext context, ProjectPlatformSkuOrderCreateReqDTO request) {
        if (context == null || !ProjectAuthOperations.STANDARD_ORDER_CREATE_V2.equals(context.operationCode())) {
            throw exception(BUYER_AUTH_INVALID);
        }
        return createPlatformSku(context.projectId(), context.buyerId(), request);
    }

    /**
     * 管理端独立身份入口：仅接收服务端解析的项目快照，不伪造应用凭证；共享编排再次校验归属与分销商状态。
     * 永久格式3幂等、首冻与受理事务不区分入口，见
     * docs/superpowers/specs/2026-10-14-admin-order-entry-design.md#2-身份权限与共享命令。
     */
    public ProjectStandardOrderCreateRespDTO createFromAdmin(ProjectSnapshot project, ProjectPlatformSkuOrderCreateReqDTO request) {
        if (project == null) { throw exception(BUYER_AUTH_INVALID); }
        return createPlatformSku(project.projectId(), project.buyerId(), request);
    }

    private ProjectStandardOrderCreateRespDTO createPlatformSku(Long projectId, Long buyerId,
                                                               ProjectPlatformSkuOrderCreateReqDTO request) {
        if (request == null) { throw exception(GlobalErrorCodeConstants.BAD_REQUEST); }
        ProjectStandardOrderCreateReqDTO internal = new ProjectStandardOrderCreateReqDTO();
        internal.setBuyerOrderNo(request.getBuyerOrderNo());
        internal.setQuantity(request.getQuantity());
        internal.setTarget(request.getTarget());
        internal.setPreviousReceiptVersion(request.getPreviousReceiptVersion());
        return createPrepared(projectId, buyerId, internal, request.getPlatformSkuCode());
    }

    private ProjectStandardOrderCreateRespDTO createPrepared(Long projectId, Long buyerId,
            ProjectStandardOrderCreateReqDTO request, String requestedPlatformSkuCode) {
        if (request == null) {
            throw exception(GlobalErrorCodeConstants.BAD_REQUEST);
        }

        log.info("[standardOrderCreate][标准下单入口] projectId={}, buyerId={}, buyerOrderNo={}, buyerSkuCode={}, quantity={}",
                projectId, buyerId, request.getBuyerOrderNo(), request.getBuyerSkuCode(), request.getQuantity());

        ProjectSnapshot project = projectAccessResolver.requireActive(projectId, buyerId);
        BuyerSnapshot buyer = buyerResolver.requireActiveById(buyerId);
        // 当前Schema/目录解析前先读冻结回执身份：已受理同参与PROCESSING/可重试续跑均用冻结SKU/Schema
        // 计算各自格式2/3指纹，不因当前商品删除、Schema/价格变化、目录暂停或供给变化变成冲突。
        // 设计见 docs/superpowers/specs/2026-10-12-platform-sku-order-contract-design.md#41-冻结优先。
        FrozenAcceptanceIdentityReader.FrozenAcceptanceIdentity frozen;
        if (requestedPlatformSkuCode != null) {
            var receipt = frozenIdentityReader.readIdentity(project.projectId(), request.getBuyerOrderNo());
            if (receipt != null && (!Short.valueOf(StandardAcceptanceFingerprintPolicy.PLATFORM_SKU_FORMAT_VERSION)
                    .equals(receipt.fingerprintVersion()) || receipt.binding() == null)) {
                throw exception(IDEMPOTENCY_CONFLICT);
            }
            frozen = receipt == null ? null : receipt.binding();
            if (receipt != null) { request.setBuyerSkuCode(receipt.buyerSkuCode()); }
        } else {
            frozen = frozenIdentityReader.read(project.projectId(), request.getBuyerOrderNo());
        }
        TargetSchemaSnapshot targetSchema;
        if (frozen != null) {
            // claim 前只比较冻结事实；当前映射损坏不能阻断已受理回放。执行分支再解析与校验，见设计 §3.1/§8。
            targetSchema = new TargetSchemaSnapshot(frozen.platformSkuId(), frozen.targetSchemaCode(),
                    frozen.targetSchemaVersion(), true, null);
        } else {
            targetSchema = requestedPlatformSkuCode == null
                    ? lifecycle.resolveTargetSchema(buyer.id(), request.getBuyerSkuCode())
                    : lifecycle.resolvePlatformSkuTargetSchema(buyer.id(), requestedPlatformSkuCode);
            if (requestedPlatformSkuCode != null) {
                request.setBuyerSkuCode(targetSchema.catalogIdentity().buyerProductCode());
            }
        }

        String traceId = businessNoGenerator.next(BusinessNoPrefixes.REQ);
        PreparedAcceptance prepared = prepare(request, buyer, project, targetSchema, traceId, frozen == null, requestedPlatformSkuCode);

        ClaimResult claim;
        try {
            claim = claimWithProjectKeyRaceRetry(prepared);
        } catch (IdempotencyConflictException conflict) {
            log.warn("[标准下单][facade] 幂等冲突 projectId={}, buyerOrderNo={}",
                    project.projectId(), request.getBuyerOrderNo());
            throw exception(IDEMPOTENCY_CONFLICT);
        }

        if (!claim.executeAcceptance()) {
            log.info("[标准下单][facade] 抢占后回放 receiptId={}", claim.receiptId());
            return toResponse(lifecycle.replay(claim.receiptId()));
        }

        if (frozen != null) {
            TargetSchemaSnapshot current = requestedPlatformSkuCode == null
                    ? lifecycle.resolveTargetSchema(buyer.id(), request.getBuyerSkuCode())
                    : lifecycle.resolvePlatformSkuTargetSchema(buyer.id(), requestedPlatformSkuCode);
            if (requestedPlatformSkuCode != null && (!java.util.Objects.equals(current.platformSkuId(), frozen.platformSkuId())
                    || !java.util.Objects.equals(current.catalogIdentity().buyerProductCode(), request.getBuyerSkuCode()))) {
                throw new IllegalStateException("当前商业商品身份与格式3冻结身份不一致");
            }
            targetSchema = new TargetSchemaSnapshot(frozen.platformSkuId(), frozen.targetSchemaCode(),
                    frozen.targetSchemaVersion(), true, current.catalogIdentity());
            prepared = prepare(request, buyer, project, targetSchema, traceId, true, requestedPlatformSkuCode);
        }

        // 首冻前准备：仅需执行的有效新尝试。AUTO刷新目录，MANUAL零写核商业发布；目录未就绪/供给明确
        // 不可用保存真实可重试拒绝且不创建新报价；缺价沿原PROCESSING契约。见设计 §7.2。
        if (prepared.quantity() == StandardPricingQuoteService.SUPPORTED_QUANTITY && prepared.targetValid() && targetSchema.available()) {
            BuyerSelectionCatalogRequestCoordinator.Preparation preparation =
                    requestCoordinator.prepare(targetSchema.catalogIdentity(), project.projectId(), prepared.quantity());
            if (!preparation.proceed()) {
                log.info("[标准下单][facade] 首冻前目录未就绪 buyerOrderNo={}, listingNo={}",
                        prepared.buyerOrderNo(), targetSchema.catalogIdentity() == null
                                ? null : targetSchema.catalogIdentity().listingNo());
                return toResponse(lifecycle.rejectBeforeFreeze(claim.receiptId(),
                        preparation.listingVersionId(), preparation.rejectionCode()));
            }
        }

        // claim已提交且仅本次需要执行受理时首冻；无效目标/数量仍沿原受理顺序保存拒绝回执。
        // 设计见 docs/superpowers/specs/2026-10-03-standard-project-direct-order-price-freeze-design.md#2-http-编排与两段事务。
        if (prepared.quantity() == StandardPricingQuoteService.SUPPORTED_QUANTITY && prepared.targetValid() && targetSchema.available()) {
            pricingQuoteService.freezeForProject(new ProjectQuoteKeyContext(
                    buyer.id(), project.projectId(), targetSchema.platformSkuId(), prepared.buyerOrderNo(), prepared.quantity()));
        }
        try {
            AcceptanceSnapshot snapshot = acceptanceCoordinator.acceptDirectProjectOrder(claim.receiptId(), prepared);
            log.info("[标准下单][facade] accept 完成 receiptNo={}, businessNo={}, receiptStatus={}, orderNo={}",
                    snapshot.receiptNo(), snapshot.buyerOrderNo(), snapshot.status(), snapshot.orderNo());
            return toResponse(snapshot);
        } catch (BusinessRejection rejection) {
            log.warn("[标准下单][facade] 业务拒绝 receiptId={}, code={}",
                    rejection.receiptId(), rejection.rejectionCode());
            return toResponse(lifecycle.reject(rejection));
        }
    }

    private void validateContext(StandardProductContext context) {
        if (context == null || !ProjectAuthOperations.STANDARD_ORDER_CREATE.equals(context.operationCode())) {
            throw exception(BUYER_AUTH_INVALID);
        }
    }

    private ClaimResult claimWithProjectKeyRaceRetry(PreparedAcceptance prepared) {
        try {
            return lifecycle.claim(prepared);
        } catch (ProjectKeyClaimRaceException projectKeyRace) {
            log.info("[标准下单][facade] 项目键并发首创冲突，新事务按项目键重探测 buyerOrderNo={}, traceId={}",
                    prepared.buyerOrderNo(), prepared.traceId(), projectKeyRace.getCause());
            return lifecycle.claimAfterProjectKeyRace(prepared);
        } catch (CannotAcquireLockException lockRace) {
            log.info("[标准下单][facade] 并发首创锁竞争（死锁/锁超时），按项目键新事务重试 buyerOrderNo={}, traceId={}",
                    prepared.buyerOrderNo(), prepared.traceId(), lockRace);
            return lifecycle.claim(prepared);
        }
    }

    private PreparedAcceptance prepare(
            ProjectStandardOrderCreateReqDTO request, BuyerSnapshot buyer,
            ProjectSnapshot project, TargetSchemaSnapshot targetSchema, String traceId, boolean validateAndEncrypt,
            String requestedPlatformSkuCode) {
        Map<String, String> targetValues = request.getTarget();
        Map<String, String> normalizedTargetValues = new TreeMap<>(targetValues);

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
        if (targetValid && validateAndEncrypt) {
            try {
                targetSchemaRegistry.validate(
                        targetSchema.schemaCode(), targetSchema.schemaVersion(), normalizedTargetValues);
            } catch (FulfillmentTargetSchemaRegistry.InvalidTargetException invalid) {
                targetValid = false;
            }
        }

        EnvelopeCryptoPort.EncryptedPayload encrypted = null;
        if (targetValid && validateAndEncrypt) {
            encrypted = envelopeCrypto.encrypt(targetCanonical, associatedData(
                    buyer.id(), request.getBuyerOrderNo(),
                    targetSchema.schemaCode(), targetSchema.schemaVersion()));
        }

        StandardAcceptanceFingerprintPolicy.RequestIdentity identity =
                new StandardAcceptanceFingerprintPolicy.RequestIdentity(
                        buyer.id(), project.projectCode(), request.getBuyerOrderNo(),
                        requestedPlatformSkuCode == null ? request.getBuyerSkuCode() : requestedPlatformSkuCode,
                        targetSchema.available() ? targetSchema.platformSkuId() : null,
                        request.getQuantity(),
                        targetSchema.schemaCode(), targetSchema.schemaVersion(),
                        HexFormat.of().formatHex(targetDigest));
        byte[] canonical = requestedPlatformSkuCode == null
                ? StandardAcceptanceFingerprintPolicy.canonicalRequest(identity)
                : StandardAcceptanceFingerprintPolicy.canonicalPlatformSkuRequest(identity);
        // 指纹格式2/3下 invariantFingerprint 与 requestFingerprint 同源（见 Lifecycle#claimExisting），
        // 共用一次 sha256 计算；两列保留是为了兼容历史格式与 CHECK 约束，不构成两个独立语义。
        byte[] requestFingerprint = sha256(canonical);
        byte[] invariantFingerprint = requestFingerprint;
        byte[] buyerOrderDigest = sha256(StandardAcceptanceFingerprintPolicy
                .canonicalBuyerOrderDigestInput(project.projectCode(), request.getBuyerOrderNo()));
        String fulfillmentAccountValue = FulfillmentTargetFields.mobileAccountValue(normalizedTargetValues);

        return new PreparedAcceptance(
                project.projectId(), project.projectCode(), project.projectName(),
                buyer.id(), buyer.buyerCode(), buyer.buyerName(), request.getBuyerOrderNo(),
                fulfillmentAccountValue,
                normalizedTargetValues.get(FulfillmentTargetFields.ACCOUNT_TYPE), request.getBuyerSkuCode(),
                request.getQuantity(),
                targetSchema.schemaCode(), targetSchema.schemaVersion(),
                encrypted == null ? null : encrypted.ciphertext(), targetDigest,
                encrypted == null ? null : encrypted.keyVersion(), targetValid,
                invariantFingerprint, requestFingerprint, buyerOrderDigest, request.getPreviousReceiptVersion(), traceId,
                targetSchema.catalogIdentity(), requestedPlatformSkuCode == null
                        ? (short) StandardAcceptanceFingerprintPolicy.FORMAT_VERSION
                        : StandardAcceptanceFingerprintPolicy.PLATFORM_SKU_FORMAT_VERSION,
                requestedPlatformSkuCode, targetSchema.platformSkuId());
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

    private static byte[] associatedData(Long buyerId, String buyerOrderNo,
                                         String schemaCode, int schemaVersion) {
        return (buyerId + "|" + buyerOrderNo + "|" + schemaCode + "|" + schemaVersion)
                .getBytes(StandardCharsets.UTF_8);
    }

    private ProjectStandardOrderCreateRespDTO toResponse(AcceptanceSnapshot snapshot) {
        ProjectStandardOrderCreateRespDTO response = new ProjectStandardOrderCreateRespDTO();
        response.setRequestId(snapshot.receiptNo());
        response.setBuyerOrderNo(snapshot.buyerOrderNo());
        response.setReceiptStatus(FulfillmentReceiptStatus.valueOf(
                AcceptanceReceiptStatus.fromCode(snapshot.status()).name()));
        response.setReceiptVersion(snapshot.receiptVersion());
        response.setOrderNo(snapshot.orderNo());
        response.setOrderStatus(snapshot.orderStatus() == null
                ? null : SupplyChainOrderStatus.fromCode(snapshot.orderStatus()));
        response.setRejectionCode(snapshot.rejectionCode());
        response.setIdempotentReplay(snapshot.idempotentReplay());
        response.setAcceptedAt(snapshot.acceptedAt() == null ? null
                : databaseClock.toInstant(snapshot.acceptedAt()).atOffset(ZoneOffset.UTC));
        return response;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
