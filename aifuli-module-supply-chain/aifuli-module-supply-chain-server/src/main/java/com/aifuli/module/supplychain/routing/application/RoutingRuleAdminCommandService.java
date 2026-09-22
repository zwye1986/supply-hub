package com.aifuli.module.supplychain.routing.application;

import com.aifuli.framework.common.util.monitor.TracerUtils;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService.CandidateDraft;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService.RuleDraft;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService.SuccessorPublishCommand;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.RoutingRuleView;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.NormalizedRulePatch;
import com.aifuli.module.supplychain.routing.dal.dataobject.StandardProductRoutingCandidateDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.StandardProductRoutingRuleDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO;
import com.aifuli.module.supplychain.routing.dal.mysql.StandardProductRoutingCandidateMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.StandardProductRoutingRuleMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyVersionMapper;
import com.aifuli.module.supplychain.routing.domain.RoutingType;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_DISABLED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SKU_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_CANDIDATE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_FULFILLMENT_MISMATCH;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_LAST_CANNOT_REMOVE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_MATCH_OVERLAP;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_VERSION_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SKU_NOT_SALEABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;

/**
 * 管理端路由规则页写入口：克隆生效版本 → 打补丁 → 后继发布。
 * <p>不原地 UPDATE 已发布规则行。设计见
 * {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#5-保存事务} 与
 * {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#4-请求体与页面字段}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingRuleAdminCommandService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    /** 无启用策略时引导首版的策略编码，见设计 w6 / §5。 */
    static final String BOOTSTRAP_POLICY_CODE = "STANDARD_PRODUCT_PRIMARY";
    /** 无启用策略时引导首版的策略名称，见设计 w6 / §5。 */
    static final String BOOTSTRAP_POLICY_NAME = "标准商品供给路由";
    private static final String SUPPLIER_TYPE_STANDARD = "STANDARD_SUPPLIER";
    private static final String RULE_CODE_PREFIX = "RR";
    private static final DateTimeFormatter RULE_CODE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int RULE_CODE_SEQ_MIN_WIDTH = 3;

    private final SupplyChainDatabaseClock clock;
    private final SupplierRoutingPolicyMapper policyMapper;
    private final SupplierRoutingPolicyVersionMapper versionMapper;
    private final StandardProductRoutingRuleMapper ruleMapper;
    private final StandardProductRoutingCandidateMapper candidateMapper;
    private final BuyerMapper buyerMapper;
    private final PlatformSkuMapper skuMapper;
    private final SupplierMapper supplierMapper;
    private final RoutingPolicyPublicationService publicationService;
    private final RoutingRuleAdminQueryService queryService;

    /**
     * 新增规则：无启用策略时引导创建 {@link #BOOTSTRAP_POLICY_CODE} 并发布第一版。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public RoutingRuleView create(NormalizedRulePatch patch, Long operatorId) {
        Objects.requireNonNull(patch, "patch");
        Objects.requireNonNull(operatorId, "operatorId");
        // 仅当发布前尚无启用中的 STANDARD_PRODUCT 策略时为引导首版；已有启用策略时后续 POST 不是 bootstrap。
        boolean bootstrapEligible = policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code()).isEmpty();
        log.info("路由规则页新增 operatorId={} routeMode={} buyerCode={} skuCode={} suppliers={} bootstrapEligible={}",
                operatorId, patch.routeMode(), patch.buyerCode(), patch.skuCode(),
                patch.orderedSupplierCodes(), bootstrapEligible);
        String focus = publishPatchedRules(true, (current, now) -> {
            String ruleCode = generateRuleCode(current, now);
            List<MutableRuleState> next = new ArrayList<>(current);
            next.add(buildPatchedState(ruleCode, patch, null));
            return new PatchOutcome(next, ruleCode);
        }, operatorId);
        return queryService.get(focus);
    }

    /**
     * 整行替换：保留路径 {@code ruleCode}，候选检查模式按供应商编码从旧行合并。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public RoutingRuleView update(String ruleCode, NormalizedRulePatch patch, Long operatorId) {
        Objects.requireNonNull(patch, "patch");
        Objects.requireNonNull(operatorId, "operatorId");
        String focus = requireRuleCode(ruleCode);
        log.info("路由规则页更新 operatorId={} ruleCode={} routeMode={} buyerCode={} skuCode={} suppliers={}",
                operatorId, focus, patch.routeMode(), patch.buyerCode(), patch.skuCode(),
                patch.orderedSupplierCodes());
        String publishedFocus = publishPatchedRules(false, (current, now) -> {
            int index = indexOfRule(current, focus);
            if (index < 0) {
                throw exception(ROUTING_RULE_NOT_FOUND);
            }
            MutableRuleState previous = current.get(index);
            List<MutableRuleState> next = new ArrayList<>(current);
            next.set(index, buildPatchedState(focus, patch, previous));
            return new PatchOutcome(next, focus);
        }, operatorId);
        return queryService.get(publishedFocus);
    }

    /**
     * 从下一版本移除规则；删最后一条拒绝。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void delete(String ruleCode, Long operatorId) {
        Objects.requireNonNull(operatorId, "operatorId");
        String focus = requireRuleCode(ruleCode);
        log.info("路由规则页删除 operatorId={} ruleCode={}", operatorId, focus);
        publishPatchedRules(false, (current, now) -> {
            int index = indexOfRule(current, focus);
            if (index < 0) {
                throw exception(ROUTING_RULE_NOT_FOUND);
            }
            List<MutableRuleState> next = new ArrayList<>(current);
            next.remove(index);
            return new PatchOutcome(next, focus);
        }, operatorId);
    }

    /**
     * {@link RoutingRuleStatus#DISABLED} 等同删除；{@link RoutingRuleStatus#ENABLED} 空操作仅返回详情。
     * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#35-启停前端兼容}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public RoutingRuleView changeStatus(String ruleCode, RoutingRuleStatus status, Long operatorId) {
        Objects.requireNonNull(operatorId, "operatorId");
        String focus = requireRuleCode(ruleCode);
        if (status == null) {
            throw exception(ENUM_NOT_OPEN);
        }
        log.info("路由规则页启停 operatorId={} ruleCode={} status={}", operatorId, focus, status);
        if (status == RoutingRuleStatus.ENABLED) {
            return queryService.get(focus);
        }
        if (status == RoutingRuleStatus.DISABLED) {
            delete(focus, operatorId);
            return null;
        }
        throw exception(ENUM_NOT_OPEN);
    }

    /**
     * 加载生效规则集、应用补丁、校验后继发布；返回焦点 ruleCode（删除后调用方不得再 get）。
     * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#5-保存事务}。
     */
    private String publishPatchedRules(boolean create, PatchApplier applier, Long operatorId) {
        LocalDateTime now = clock.now();
        WriteScope scope = resolveWriteScope(create, now);
        List<MutableRuleState> current = loadMutableRules(scope.version());
        PatchOutcome outcome = applier.apply(current, now);
        List<MutableRuleState> next = outcome.nextRules();
        if (next.isEmpty()) {
            throw exception(ROUTING_RULE_LAST_CANNOT_REMOVE);
        }
        requireUniqueMatchKeys(next);
        List<RuleDraft> drafts = toRuleDrafts(next);
        try {
            publicationService.publishSuccessorStandardProductRules(new SuccessorPublishCommand(
                    scope.policyCode(), scope.policyName(), operatorId, resolveTraceId(),
                    drafts, scope.closeVersionId(), scope.closeExpectedVersion(), now));
        } catch (IllegalStateException ex) {
            log.info("路由规则后继发布冲突 operatorId={} focusRuleCode={} reason={}",
                    operatorId, outcome.focusRuleCode(), ex.getMessage());
            throw exception(ROUTING_RULE_VERSION_CONFLICT);
        }
        return outcome.focusRuleCode();
    }

    private WriteScope resolveWriteScope(boolean create, LocalDateTime now) {
        List<SupplierRoutingPolicyDO> active =
                policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code());
        if (active.size() > 1) {
            throw new IllegalStateException("More than one active STANDARD_PRODUCT routing policy");
        }
        if (active.isEmpty()) {
            if (!create) {
                throw exception(ROUTING_RULE_NOT_FOUND);
            }
            log.info("路由规则页引导首版策略 policyCode={}", BOOTSTRAP_POLICY_CODE);
            return new WriteScope(BOOTSTRAP_POLICY_CODE, BOOTSTRAP_POLICY_NAME, null, 0L, null);
        }
        SupplierRoutingPolicyDO policy = active.getFirst();
        List<SupplierRoutingPolicyVersionDO> versions = versionMapper.selectEffective(policy.getId(), now);
        if (versions.size() > 1) {
            throw new IllegalStateException("More than one effective published routing policy version");
        }
        if (versions.isEmpty()) {
            if (!create) {
                throw exception(ROUTING_RULE_NOT_FOUND);
            }
            return new WriteScope(policy.getPolicyCode(), policy.getPolicyName(), null, 0L, null);
        }
        SupplierRoutingPolicyVersionDO version = versions.getFirst();
        return new WriteScope(policy.getPolicyCode(), policy.getPolicyName(),
                version.getId(), version.getVersion() == null ? 0L : version.getVersion(), version);
    }

    private List<MutableRuleState> loadMutableRules(SupplierRoutingPolicyVersionDO version) {
        if (version == null) {
            return new ArrayList<>();
        }
        List<StandardProductRoutingRuleDO> rules = ruleMapper.selectByPolicyVersionId(version.getId());
        List<MutableRuleState> result = new ArrayList<>(rules.size());
        for (StandardProductRoutingRuleDO rule : rules) {
            List<StandardProductRoutingCandidateDO> candidates = candidateMapper.selectByRuleId(rule.getId());
            if (candidates == null || candidates.isEmpty()) {
                throw new IllegalStateException("Published routing rule has no candidates: " + rule.getRuleCode());
            }
            List<MutableCandidateState> mutableCandidates = new ArrayList<>(candidates.size());
            for (StandardProductRoutingCandidateDO candidate : candidates) {
                SupplierDO supplier = supplierMapper.selectById(candidate.getSupplierId());
                String supplierCode = supplier == null ? null : supplier.getSupplierCode();
                mutableCandidates.add(new MutableCandidateState(
                        candidate.getSupplierId(), supplierCode,
                        candidate.getInventoryCheckMode(), candidate.getInventoryMaxAgeSeconds(),
                        candidate.getBalanceCheckMode(), candidate.getBalanceMaxAgeSeconds(),
                        candidate.getHealthCheckMode(), candidate.getHealthMaxAgeSeconds()));
            }
            result.add(new MutableRuleState(
                    rule.getRuleCode(), rule.getRuleName(),
                    rule.getBuyerId(), null, rule.getPlatformSkuId(), null,
                    rule.getFulfillmentType(),
                    rule.getRulePriority() == null ? 0 : rule.getRulePriority(),
                    List.copyOf(mutableCandidates), false));
        }
        return result;
    }

    private MutableRuleState buildPatchedState(String ruleCode, NormalizedRulePatch patch,
                                               MutableRuleState previous) {
        BuyerDO buyer = requireActiveBuyerByCode(patch.buyerCode());
        PlatformSkuDO sku = requireSaleableSku(patch.skuCode(), patch.fulfillmentType());
        Map<String, MutableCandidateState> previousBySupplier = new HashMap<>();
        if (previous != null) {
            for (MutableCandidateState candidate : previous.candidates()) {
                if (candidate.supplierCode() != null) {
                    previousBySupplier.putIfAbsent(candidate.supplierCode(), candidate);
                }
            }
        }
        List<MutableCandidateState> candidates = new ArrayList<>(patch.orderedSupplierCodes().size());
        for (String supplierCode : patch.orderedSupplierCodes()) {
            MutableCandidateState retained = previousBySupplier.get(supplierCode);
            if (retained != null) {
                candidates.add(new MutableCandidateState(
                        retained.supplierId(), supplierCode,
                        retained.inventoryCheckMode(), retained.inventoryMaxAgeSeconds(),
                        retained.balanceCheckMode(), retained.balanceMaxAgeSeconds(),
                        retained.healthCheckMode(), retained.healthMaxAgeSeconds()));
            } else {
                candidates.add(new MutableCandidateState(
                        null, supplierCode,
                        RoutingRuleSaveSupport.DEFAULT_INVENTORY_CHECK_MODE,
                        RoutingRuleSaveSupport.DEFAULT_INVENTORY_MAX_AGE_SECONDS,
                        RoutingRuleSaveSupport.DEFAULT_BALANCE_CHECK_MODE,
                        RoutingRuleSaveSupport.DEFAULT_BALANCE_MAX_AGE_SECONDS,
                        RoutingRuleSaveSupport.DEFAULT_HEALTH_CHECK_MODE,
                        RoutingRuleSaveSupport.DEFAULT_HEALTH_MAX_AGE_SECONDS));
            }
        }
        return new MutableRuleState(
                ruleCode, patch.name(), buyer.getId(), patch.buyerCode(),
                sku.getId(), patch.skuCode(), patch.fulfillmentType(), 0,
                List.copyOf(candidates), true);
    }

    private List<RuleDraft> toRuleDrafts(List<MutableRuleState> rules) {
        List<RuleDraft> drafts = new ArrayList<>(rules.size());
        for (MutableRuleState rule : rules) {
            String buyerCode;
            String skuCode;
            String fulfillmentType = rule.fulfillmentType();
            if (rule.fromPatch()) {
                buyerCode = requireActiveBuyerByCode(rule.buyerCode()).getBuyerCode();
                PlatformSkuDO sku = requireSaleableSku(rule.skuCode(), fulfillmentType);
                skuCode = sku.getSkuCode();
            } else {
                BuyerDO buyer = requireActiveBuyerById(rule.buyerId());
                PlatformSkuDO sku = requireSaleableSkuById(rule.platformSkuId(), fulfillmentType);
                buyerCode = buyer.getBuyerCode();
                skuCode = sku.getSkuCode();
            }
            List<CandidateDraft> candidates = new ArrayList<>(rule.candidates().size());
            for (int i = 0; i < rule.candidates().size(); i++) {
                MutableCandidateState candidate = rule.candidates().get(i);
                String supplierCode = resolveSupplierCode(candidate);
                requireActiveStandardSupplier(supplierCode);
                candidates.add(new CandidateDraft(
                        supplierCode, i,
                        candidate.inventoryCheckMode(), candidate.inventoryMaxAgeSeconds(),
                        candidate.balanceCheckMode(), candidate.balanceMaxAgeSeconds(),
                        candidate.healthCheckMode(), candidate.healthMaxAgeSeconds()));
            }
            drafts.add(new RuleDraft(
                    rule.ruleCode(), rule.name(), buyerCode, skuCode, fulfillmentType,
                    rule.rulePriority(), List.copyOf(candidates)));
        }
        return List.copyOf(drafts);
    }

    private static void requireUniqueMatchKeys(List<MutableRuleState> rules) {
        Set<String> keys = new HashSet<>();
        for (MutableRuleState rule : rules) {
            String key = rule.buyerId() + ":" + rule.platformSkuId() + ":" + rule.fulfillmentType();
            if (!keys.add(key)) {
                throw exception(ROUTING_RULE_MATCH_OVERLAP);
            }
        }
    }

    private BuyerDO requireActiveBuyerByCode(String buyerCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode);
        if (buyer == null) {
            throw exception(BUYER_NOT_FOUND);
        }
        return requireBuyerActive(buyer);
    }

    private BuyerDO requireActiveBuyerById(Long buyerId) {
        BuyerDO buyer = buyerMapper.selectById(buyerId);
        if (buyer == null) {
            throw exception(BUYER_NOT_FOUND);
        }
        return requireBuyerActive(buyer);
    }

    private static BuyerDO requireBuyerActive(BuyerDO buyer) {
        if (buyer.getStatus() == null || buyer.getStatus() != BuyerStatus.ACTIVE.getCode()) {
            throw exception(BUYER_DISABLED);
        }
        return buyer;
    }

    private PlatformSkuDO requireSaleableSku(String skuCode, String fulfillmentType) {
        PlatformSkuDO sku = skuMapper.selectByCode(skuCode);
        if (sku == null) {
            throw exception(PLATFORM_SKU_NOT_FOUND);
        }
        return requireSkuSaleableAndFulfillment(sku, fulfillmentType);
    }

    private PlatformSkuDO requireSaleableSkuById(Long skuId, String fulfillmentType) {
        PlatformSkuDO sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw exception(PLATFORM_SKU_NOT_FOUND);
        }
        return requireSkuSaleableAndFulfillment(sku, fulfillmentType);
    }

    private static PlatformSkuDO requireSkuSaleableAndFulfillment(PlatformSkuDO sku, String fulfillmentType) {
        if (sku.getStatus() == null || sku.getStatus() != CatalogEntityStatus.ACTIVE.getCode()) {
            throw exception(SKU_NOT_SALEABLE);
        }
        if (fulfillmentType == null || !fulfillmentType.equals(sku.getFulfillmentType())) {
            throw exception(ROUTING_RULE_FULFILLMENT_MISMATCH);
        }
        return sku;
    }

    private String resolveSupplierCode(MutableCandidateState candidate) {
        if (candidate.supplierCode() != null && !candidate.supplierCode().isBlank()) {
            return candidate.supplierCode();
        }
        if (candidate.supplierId() == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        SupplierDO supplier = supplierMapper.selectById(candidate.supplierId());
        if (supplier == null || supplier.getSupplierCode() == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        return supplier.getSupplierCode();
    }

    private void requireActiveStandardSupplier(String supplierCode) {
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        if (supplier.getStatus() == null || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()
                || !SUPPLIER_TYPE_STANDARD.equals(supplier.getSupplierType())) {
            throw exception(ROUTING_RULE_CANDIDATE_INVALID);
        }
    }

    static String generateRuleCode(List<MutableRuleState> existing, LocalDateTime now) {
        String prefix = RULE_CODE_PREFIX + now.format(RULE_CODE_DATE);
        int maxSeq = 0;
        for (MutableRuleState rule : existing) {
            String code = rule.ruleCode();
            if (code == null || !code.startsWith(prefix) || code.length() <= prefix.length()) {
                continue;
            }
            String suffix = code.substring(prefix.length());
            if (suffix.isEmpty() || !suffix.chars().allMatch(Character::isDigit)) {
                continue;
            }
            maxSeq = Math.max(maxSeq, Integer.parseInt(suffix));
        }
        int next = maxSeq + 1;
        int width = Math.max(RULE_CODE_SEQ_MIN_WIDTH, String.valueOf(next).length());
        return prefix + String.format("%0" + width + "d", next);
    }

    private static int indexOfRule(List<MutableRuleState> rules, String ruleCode) {
        for (int i = 0; i < rules.size(); i++) {
            if (ruleCode.equals(rules.get(i).ruleCode())) {
                return i;
            }
        }
        return -1;
    }

    private static String requireRuleCode(String ruleCode) {
        if (ruleCode == null || ruleCode.isBlank()) {
            throw exception(ROUTING_RULE_NOT_FOUND);
        }
        return ruleCode.trim();
    }

    private static String resolveTraceId() {
        String traceId = TracerUtils.getTraceId();
        return traceId == null || traceId.isBlank() ? "routing-rule-" + UUID.randomUUID() : traceId;
    }

    @FunctionalInterface
    private interface PatchApplier {
        PatchOutcome apply(List<MutableRuleState> current, LocalDateTime now);
    }

    private record PatchOutcome(List<MutableRuleState> nextRules, String focusRuleCode) {
    }

    private record WriteScope(String policyCode, String policyName,
                              Long closeVersionId, long closeExpectedVersion,
                              SupplierRoutingPolicyVersionDO version) {
    }

    /**
     * 内存中的规则快照；fromPatch=true 表示本补丁新建或整行替换，校验走编码解析。
     */
    record MutableRuleState(String ruleCode, String name,
                            Long buyerId, String buyerCode,
                            Long platformSkuId, String skuCode,
                            String fulfillmentType, int rulePriority,
                            List<MutableCandidateState> candidates,
                            boolean fromPatch) {
        MutableRuleState {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    record MutableCandidateState(Long supplierId, String supplierCode,
                                 String inventoryCheckMode, Integer inventoryMaxAgeSeconds,
                                 String balanceCheckMode, Integer balanceMaxAgeSeconds,
                                 String healthCheckMode, Integer healthMaxAgeSeconds) {
    }
}
