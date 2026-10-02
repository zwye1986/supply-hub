package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAcceptanceReceiptDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SensitiveAccessAuditDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAcceptanceReceiptMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.SensitiveAccessAuditMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.border.fulfillment.domain.SensitiveAccessAuditAccessType;
import com.aifuli.module.supplychain.border.fulfillment.domain.SensitiveAccessAuditResultStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.StandardAcceptanceFingerprintPolicy;
import com.aifuli.module.supplychain.border.fulfillment.domain.StandardAcceptanceFingerprintPolicy.RequestIdentity;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.StandardPricingQuoteDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.StandardPricingQuoteMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingQuoteStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 标准订单历史身份与限购手机号的离线重建服务（052 升级后、直充限频门禁重新置 READY 前）。
 *
 * <p>职责与不变量（设计
 * docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#51-离线应用迁移与实际手机号覆盖）：
 * <ul>
 *   <li>无 HTTP/Timer 入口：仅供 {@link RechargeFrequencyMigrationService} 在 closeBeforePreflight
 *       后、publishReady 前于同一供应链事务（MANDATORY）内协调调用；所有更新与最终 READY 同事务提交，
 *       任意存储/解密/审计/证据故障全回滚，门禁保持已提交 CLOSED。</li>
 *   <li>全量扫描 CARD 总单（含已成功/失败/退款历史）：任何有效限购历史不得因不是限频活跃候选而
 *       遗漏；按总单完整关联核验回执/请求/报价后，从可信密文与报价重建格式 2 无用户指纹与冻结绑定，
 *       关联总单 hash 同步。</li>
 *   <li>目标解密以原 Request 密文与原 AAD（buyerId|buyerOrderNo|schemaCode|schemaVersion）恢复
 *       实际 accountType 与规范化 MOBILE 手机号，不解析可变 SKU、不从原购买人手机号推导充值目标；
 *       每次解密记录真实 SYSTEM_WORKER 敏感访问审计（无明文/用户占位），明文字节清零，安全原因与
 *       cause 保留。</li>
 *   <li>验证完整批后才统一更新手机快照/新指纹：先全批核验收集计划，再统一落库，不做边验证边提交。</li>
 *   <li>历史 NULL 项目不从 source 映射推测归属，不重建；首次拒绝/未建单缺少可信目标/报价关联的
 *       回执保持格式 0 历史标记，不开放条件重试；未建单孤立旧回执仅保留查询进度/拒绝。</li>
 *   <li>幂等再次执行只校验已重建绑定一致后跳过，不改已冻结目标/报价/原频率序号/资金，也不新建 SEND。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StandardOrderIdentityMigrationService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    /** 离线迁移审计主体：系统任务身份，非运营人员/项目应用。 */
    private static final String PRINCIPAL_TYPE_SYSTEM_WORKER = "SYSTEM_WORKER";
    private static final String PRINCIPAL_ID_IDENTITY_MIGRATION = "STANDARD_ORDER_IDENTITY_MIGRATION";
    /** 审计 scopeCode 记录操作编码而非权限 Scope（列语义允许「权限Scope或操作编码」）。 */
    private static final String AUDIT_SCOPE_OFFLINE_IDENTITY_MIGRATION = "OFFLINE_IDENTITY_MIGRATION";
    private static final String AUDIT_REASON_REBUILT = "IDENTITY_REBUILT";
    private static final String AUDIT_REASON_DECRYPT_FAILED = "DECRYPT_FAILED";

    private final FulfillmentAcceptanceReceiptMapper receipts;
    private final SupplyChainOrderMapper orders;
    private final FulfillmentRequestMapper requests;
    private final StandardPricingQuoteMapper quotes;
    private final BuyerProjectMapper projects;
    private final EnvelopeCryptoPort crypto;
    private final HmacFingerprintPort hmacFingerprint;
    private final FulfillmentTargetSchemaRegistry schemaRegistry;
    private final SensitiveAccessAuditMapper audits;
    private final BusinessNoGenerator businessNoGenerator;
    private final ObjectMapper json;
    private final SupplyChainDatabaseClock clock;

    /**
     * 离线重建入口：须在调用方供应链事务（MANDATORY）中执行。全批核验通过后统一更新回执格式 2
     * 冻结绑定与总单身份/手机快照；返回重建与幂等跳过计数。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, propagation = Propagation.MANDATORY,
            rollbackFor = Exception.class)
    public IdentityReport migrateIdentitySnapshots() {
        var now = clock.now();
        var plans = new ArrayList<RebuildPlan>();
        int skippedRebuilt = 0;
        int skippedUnverifiable = 0;
        // 任何有效限购历史（含已成功与 LIFETIME）不能因不是限频活跃候选而遗漏：全量 CARD 扫描。
        var cardOrders = orders.selectList(new LambdaQueryWrapper<SupplyChainOrderDO>()
                .eq(SupplyChainOrderDO::getOrderType, SupplyChainOrderTypes.ORDER_TYPE_CARD)
                .orderByAsc(SupplyChainOrderDO::getId));
        for (SupplyChainOrderDO order : cardOrders) {
            // 历史 NULL 项目不从 source 映射推测归属：不重建，仅计入不可核验跳过。
            if (order.getProjectId() == null) {
                skippedUnverifiable++;
                continue;
            }
            FulfillmentAcceptanceReceiptDO receipt = order.getAcceptanceReceiptId() == null
                    ? null : receipts.selectById(order.getAcceptanceReceiptId());
            if (receipt == null || !Objects.equals(receipt.getResultOrderId(), order.getId())) {
                throw failure(order.getOrderNo(), Code.ASSOCIATION_INVALID);
            }
            // 幂等重跑：已重建（格式 2）只校验冻结绑定一致后跳过，不改任何已冻结数据。
            if (receipt.getFingerprintVersion() != null
                    && receipt.getFingerprintVersion() == StandardAcceptanceFingerprintPolicy.FORMAT_VERSION) {
                verifyRebuiltBindings(order, receipt);
                skippedRebuilt++;
                continue;
            }
            // 格式 0 且未建单/首次拒绝：没有可信目标/报价关联，不开放条件重试，保留查询进度/拒绝语义。
            if (!Objects.equals(receipt.getStatus(), AcceptanceReceiptStatus.ACCEPTED.getCode())
                    || receipt.getResultOrderId() == null) {
                skippedUnverifiable++;
                continue;
            }
            plans.add(planRebuild(order, receipt, now));
        }
        // 验证完整批后才统一更新手机快照/新指纹；本事务与最终 READY 同提交，任意失败全回滚。
        for (RebuildPlan plan : plans) {
            apply(plan, now);
        }
        log.info("[标准订单身份迁移] 全批完成 rebuiltCount={}, rebuiltSkipped={}, unverifiable={}",
                plans.size(), skippedRebuilt, skippedUnverifiable);
        return new IdentityReport(plans.size(), skippedRebuilt, skippedUnverifiable);
    }

    /** 幂等重跑的绑定一致性校验：已重建回执的冻结绑定必须仍与真实请求/订单一致，矛盾即阻断。 */
    private void verifyRebuiltBindings(SupplyChainOrderDO order, FulfillmentAcceptanceReceiptDO receipt) {
        FulfillmentRequestDO request = requests.selectById(order.getDetailId());
        if (request == null
                || !Objects.equals(receipt.getPlatformSkuId(), request.getPlatformSkuId())
                || !Objects.equals(receipt.getTargetSchemaVersion(), request.getTargetSchemaVersion())
                || !Objects.equals(receipt.getPricingQuoteId(), order.getPricingQuoteId())
                // MOBILE 目标的已重建订单必须持有规范化手机快照；缺失说明历史覆盖不完整，阻断而非静默放过。
                || (FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE.equals(order.getFulfillmentAccountType())
                        && (order.getFulfillmentAccountValue() == null
                                || order.getFulfillmentAccountValue().isBlank()))) {
            throw failure(order.getOrderNo(), Code.ASSOCIATION_INVALID);
        }
    }

    /**
     * 单订单的完整核验与重建计划：关联链（回执/请求/报价/项目）→ 密文解密与封闭校验（含审计、
     * 明文清零）→ 格式 2 指纹与手机快照计算。只读不写，写入统一延后到全批验证之后。
     */
    private RebuildPlan planRebuild(SupplyChainOrderDO order, FulfillmentAcceptanceReceiptDO receipt,
                                    LocalDateTime now) {
        String orderNo = order.getOrderNo();
        FulfillmentRequestDO request = requests.selectById(order.getDetailId());
        if (request == null
                || !Objects.equals(request.getBuyerId(), order.getBuyerId())
                || !Objects.equals(request.getPlatformSkuId(), order.getPlatformSkuId())
                || !Objects.equals(request.getOrderId(), order.getId())
                || !Objects.equals(request.getRequestNo(), receipt.getResultFulfillmentRequestNo())
                || !Objects.equals(receipt.getBuyerId(), order.getBuyerId())
                || !Objects.equals(receipt.getProjectId(), order.getProjectId())
                || !Objects.equals(receipt.getBuyerOrderNo(), order.getBuyerOrderNo())
                || !Objects.equals(receipt.getResultOrderNo(), orderNo)
                || !Objects.equals(request.getTargetSchemaCode(), receipt.getTargetSchemaCode())
                || !Objects.equals(order.getQuantity(), request.getRequestedQuantity())) {
            throw failure(orderNo, Code.ASSOCIATION_INVALID);
        }
        BuyerProjectDO project = projects.selectById(order.getProjectId());
        if (project == null || project.getProjectCode() == null) {
            throw failure(orderNo, Code.ASSOCIATION_INVALID);
        }
        // 已建单订单的报价必须存在且唯一消费于本单；缺报价（早期历史）不重建、不开放条件重试。
        if (order.getPricingQuoteId() == null) {
            throw failure(orderNo, Code.ASSOCIATION_INVALID);
        }
        StandardPricingQuoteDO quote = quotes.selectById(order.getPricingQuoteId());
        if (quote == null
                || !Objects.equals(quote.getBuyerId(), order.getBuyerId())
                || !Objects.equals(quote.getProjectId(), order.getProjectId())
                || !Objects.equals(quote.getPlatformSkuId(), request.getPlatformSkuId())
                || !Objects.equals(quote.getBuyerOrderNo(), order.getBuyerOrderNo())
                || !Objects.equals(quote.getQuantity(), order.getQuantity())
                || !Objects.equals(quote.getConsumedOrderId(), order.getId())
                || !PricingQuoteStatus.CONSUMED.name().equals(quote.getStatus())) {
            throw failure(orderNo, Code.ASSOCIATION_INVALID);
        }
        RecoveredTarget target = recoverTarget(order, request, now);
        // 格式 2 指纹：与受理时同一规范化输入（含 MOBILE 规范化后的目标摘要），供重放比较与查单。
        byte[] canonical = StandardAcceptanceFingerprintPolicy.canonicalRequest(new RequestIdentity(
                order.getBuyerId(), project.getProjectCode(), order.getBuyerOrderNo(),
                receipt.getBuyerSkuCode(), request.getPlatformSkuId(), order.getQuantity(),
                request.getTargetSchemaCode(), request.getTargetSchemaVersion(),
                HexFormat.of().formatHex(target.targetDigest())));
        byte[] fingerprint = sha256(canonical);
        byte[] buyerOrderDigest = sha256(StandardAcceptanceFingerprintPolicy
                .canonicalBuyerOrderDigestInput(project.getProjectCode(), order.getBuyerOrderNo()));
        return new RebuildPlan(receipt, order, request, target.accountType(),
                target.mobileSnapshot(), fingerprint, buyerOrderDigest);
    }

    /**
     * 以原 Request 密文与原 AAD 解密并封闭校验真实目标，恢复实际 accountType 与规范化 MOBILE：
     * 与订单既有履约账号矛盾即阻断（不从原购买人推导）；每次解密记录 SYSTEM_WORKER 敏感审计，
     * 明文字节清零，不持久化任何明文/用户占位。
     */
    private RecoveredTarget recoverTarget(SupplyChainOrderDO order, FulfillmentRequestDO request,
                                          LocalDateTime now) {
        String orderNo = order.getOrderNo();
        if (request.getTargetSchemaCode() == null || request.getTargetSchemaCode().isBlank()
                || request.getTargetSchemaVersion() == null || request.getTargetSchemaVersion() <= 0
                || request.getTargetKeyVersion() == null
                || request.getTargetCiphertext() == null || request.getTargetCiphertext().length == 0
                || order.getBuyerOrderNo() == null) {
            throw failure(orderNo, Code.TARGET_UNRECOVERABLE);
        }
        byte[] plaintext = null;
        try {
            byte[] aad = (request.getBuyerId() + "|" + order.getBuyerOrderNo()
                    + "|" + request.getTargetSchemaCode() + "|" + request.getTargetSchemaVersion())
                    .getBytes(StandardCharsets.UTF_8);
            plaintext = crypto.decrypt(request.getTargetCiphertext(), request.getTargetKeyVersion(), aad);
            JsonNode target = json.readTree(plaintext);
            if (!request.getTargetSchemaCode().equals(target.path("schemaCode").asText())
                    || !target.path("schemaVersion").isIntegralNumber()
                    || request.getTargetSchemaVersion() != target.path("schemaVersion").asInt()) {
                throw failure(orderNo, Code.TARGET_SCHEMA_INVALID);
            }
            JsonNode valuesNode = target.path("values");
            if (!valuesNode.isObject()) {
                throw failure(orderNo, Code.TARGET_SCHEMA_INVALID);
            }
            Map<String, String> values = new TreeMap<>();
            valuesNode.fields().forEachRemaining(field -> {
                if (!field.getValue().isTextual()) {
                    throw failure(orderNo, Code.TARGET_SCHEMA_INVALID);
                }
                values.put(field.getKey(), field.getValue().textValue());
            });
            schemaRegistry.validate(request.getTargetSchemaCode(), request.getTargetSchemaVersion(), values);
            String accountType = values.get(FulfillmentTargetFields.ACCOUNT_TYPE);
            String accountValue = values.get(FulfillmentTargetFields.ACCOUNT_VALUE);
            String mobileSnapshot = null;
            if (FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE.equals(accountType)) {
                String normalized = FulfillmentTargetFields.normalizeMobile(accountValue);
                if (normalized == null) {
                    throw failure(orderNo, Code.TARGET_SCHEMA_INVALID);
                }
                values.put(FulfillmentTargetFields.ACCOUNT_VALUE, normalized);
                mobileSnapshot = normalized;
            }
            // 与受理期同一 canonicalTarget/digest 口径，保证重放比较与原请求身份同源。
            byte[] targetDigest = hmacFingerprint.fingerprint(FulfillmentTargetFields.FINGERPRINT_PURPOSE,
                    canonicalTarget(request.getTargetSchemaCode(), request.getTargetSchemaVersion(), values));
            // 既有履约账号快照与解密目标矛盾即数据不可信：阻断覆盖而非静默改写。
            if (order.getFulfillmentAccountValue() != null && !order.getFulfillmentAccountValue().isBlank()) {
                String expected = FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE.equals(accountType)
                        ? mobileSnapshot : accountValue;
                if (!Objects.equals(order.getFulfillmentAccountValue(), expected)) {
                    throw failure(orderNo, Code.ACCOUNT_CONFLICT);
                }
            }
            recordAudit(order, request, SensitiveAccessAuditResultStatus.SUCCESS.getCode(),
                    AUDIT_REASON_REBUILT, now);
            return new RecoveredTarget(accountType, mobileSnapshot, targetDigest);
        } catch (MigrationException exception) {
            throw exception;
        } catch (Exception exception) {
            recordAudit(order, request, SensitiveAccessAuditResultStatus.FAILED.getCode(),
                    AUDIT_REASON_DECRYPT_FAILED, now);
            throw failure(orderNo, Code.TARGET_UNRECOVERABLE, exception);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private byte[] canonicalTarget(String schemaCode, int schemaVersion, Map<String, String> values) {
        try {
            Map<String, Object> canonical = new TreeMap<>();
            canonical.put("schemaCode", schemaCode);
            canonical.put("schemaVersion", schemaVersion);
            canonical.put("values", values);
            return json.writeValueAsBytes(canonical);
        } catch (Exception exception) {
            throw failure(null, Code.TARGET_UNRECOVERABLE, exception);
        }
    }

    /** 目标解密的 SYSTEM_WORKER 审计：principal 为离线迁移任务身份，记录实际 project/order。 */
    private void recordAudit(SupplyChainOrderDO order, FulfillmentRequestDO request,
                             short resultStatus, String reasonCode, LocalDateTime now) {
        SensitiveAccessAuditDO audit = SensitiveAccessAuditDO.builder()
                .auditNo(businessNoGenerator.next(BusinessNoPrefixes.AUD))
                .accessType(SensitiveAccessAuditAccessType.TARGET_DECRYPT.name())
                .principalType(PRINCIPAL_TYPE_SYSTEM_WORKER)
                .principalId(PRINCIPAL_ID_IDENTITY_MIGRATION)
                .buyerId(order.getBuyerId())
                .projectId(order.getProjectId())
                .orderNo(order.getOrderNo())
                .resourceType("FULFILLMENT_REQUEST")
                .resourceId(request.getId() == null ? 0L : request.getId())
                .scopeCode(AUDIT_SCOPE_OFFLINE_IDENTITY_MIGRATION)
                .resultStatus(resultStatus)
                .reasonCode(reasonCode)
                .traceId(order.getOrderNo())
                .accessedAt(now)
                .build();
        audits.insert(audit);
    }

    /** 全批验证通过后的统一落库：回执冻结绑定+格式 2 指纹、总单 hash/真实账户类型与手机快照。 */
    private void apply(RebuildPlan plan, LocalDateTime now) {
        receipts.rebuildIdentityBindings(plan.receipt().getId(), plan.request().getPlatformSkuId(),
                plan.request().getTargetSchemaVersion(), plan.order().getPricingQuoteId(),
                plan.invariantFingerprint(), plan.requestFingerprint(), now);
        orders.rebuildIdentitySnapshot(plan.order().getId(), plan.requestFingerprint(),
                plan.buyerOrderDigest(), plan.accountType(), plan.mobileSnapshot(), now);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record IdentityReport(int rebuiltCount, int rebuiltSkipped, int unverifiableSkipped) {
    }

    private record RecoveredTarget(String accountType, String mobileSnapshot, byte[] targetDigest) {
    }

    private record RebuildPlan(FulfillmentAcceptanceReceiptDO receipt, SupplyChainOrderDO order,
                               FulfillmentRequestDO request, String accountType, String mobileSnapshot,
                               byte[] invariantFingerprint, byte[] buyerOrderDigest) {
        private byte[] requestFingerprint() {
            return invariantFingerprint.clone();
        }
    }

    /** 迁移安全错误码：不拼接解密/SQL原始报文，单号脱敏。 */
    public enum Code {
        ASSOCIATION_INVALID,
        TARGET_UNRECOVERABLE,
        TARGET_SCHEMA_INVALID,
        ACCOUNT_CONFLICT
    }

    private static MigrationException failure(String orderNo, Code code) {
        return new MigrationException(orderNo, code, null);
    }

    private static MigrationException failure(String orderNo, Code code, Throwable cause) {
        return new MigrationException(orderNo, code, cause);
    }

    /** 安全错误保留单号/类别及异常类型、位置，绝不拼接解密/SQL原始报文。 */
    public static final class MigrationException extends IllegalStateException {
        private final Code code;

        MigrationException(String orderNo, Code code, Throwable original) {
            super("标准订单身份迁移拒绝 code=" + code + ", orderNo=" + safeOrderNo(orderNo),
                    safeCause(original));
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    private static String safeOrderNo(String orderNo) {
        return orderNo == null
                ? "unavailable"
                : orderNo.replaceAll("(?<![0-9])1[3-9][0-9]{9}(?![0-9])", "[masked]");
    }

    private static Throwable safeCause(Throwable original) {
        if (original == null) {
            return null;
        }
        var safe = new IllegalStateException(original.getClass().getName());
        safe.setStackTrace(original.getStackTrace().length == 0
                ? new StackTraceElement[0]
                : new StackTraceElement[] {original.getStackTrace()[0]});
        if (original.getCause() != null && original.getCause() != original) {
            safe.initCause(safeCause(original.getCause()));
        }
        return safe;
    }
}
