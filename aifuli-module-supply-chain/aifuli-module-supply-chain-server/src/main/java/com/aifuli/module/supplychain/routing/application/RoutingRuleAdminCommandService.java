package com.aifuli.module.supplychain.routing.application;

import com.aifuli.framework.common.util.monitor.TracerUtils;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService.CandidateDraft;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService.RuleDraft;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService.SuccessorPublishCommand;
import com.aifuli.module.supplychain.routing.application.RoutingRuleAdminQueryService.RoutingRuleView;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.NormalizedRulePatch;
import com.aifuli.module.supplychain.routing.dal.dataobject.StdRoutingCandidateDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.StdRoutingRuleDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.StdRoutingRuleScopeDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyDO;
import com.aifuli.module.supplychain.routing.dal.dataobject.SupplierRoutingPolicyVersionDO;
import com.aifuli.module.supplychain.routing.dal.mysql.StdRoutingCandidateMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.StdRoutingRuleMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.StdRoutingRuleScopeMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyMapper;
import com.aifuli.module.supplychain.routing.dal.mysql.SupplierRoutingPolicyVersionMapper;
import com.aifuli.module.supplychain.routing.domain.RoutingType;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
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
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_TYPE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_CANDIDATE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_NAME_DUPLICATED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_PROTECTED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_SCOPE_DUPLICATED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_VERSION_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;

/**
 * 管理端路由规则页写入口（PRD 四级范围模型）：克隆生效版本 → 打补丁 → 后继发布。
 *
 * <p>不原地 UPDATE 已发布规则行；DISABLED/ENABLED 为规则真实状态（不再等同删除）；
 * 受保护 GLOBAL_DEFAULT 不可删除、范围与路由模式不可改。设计见
 * {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#55-保存校验}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingRuleAdminCommandService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    /** 无启用策略时引导首版的策略编码（与 044 变更集预置一致）。 */
    static final String BOOTSTRAP_POLICY_CODE = "STANDARD_PRODUCT_PRIMARY";
    /** 无启用策略且库中尚无引导策略时的默认名称。 */
    static final String BOOTSTRAP_POLICY_NAME = "标准商品供给路由";
    private static final String SUPPLIER_TYPE_STANDARD = "STANDARD_SUPPLIER";
    private static final String ROUTE_MODE_FIXED = "FIXED";
    private static final String ROUTE_MODE_AUTO = "AUTO";
    private static final String SCOPE_ALL = "ALL";
    private static final String SCOPE_SPECIFIED = "SPECIFIED";
    private static final short RULE_ENABLED = 10;
    private static final short RULE_DISABLED = 20;
    private static final int DEFAULT_RULE_PRIORITY = 100;
    private static final String RULE_CODE_PREFIX = "RR";
    private static final DateTimeFormatter RULE_CODE_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int RULE_CODE_SEQ_MIN_WIDTH = 3;

    private final SupplyChainDatabaseClock clock;
    private final SupplierRoutingPolicyMapper policyMapper;
    private final SupplierRoutingPolicyVersionMapper versionMapper;
    private final StdRoutingRuleMapper ruleMapper;
    private final StdRoutingRuleScopeMapper scopeMapper;
    private final StdRoutingCandidateMapper candidateMapper;
    private final BuyerMapper buyerMapper;
    private final BuyerProjectMapper projectMapper;
    private final ProductTypeMapper productTypeMapper;
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
        boolean bootstrapEligible = policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code()).isEmpty();
        log.info("路由规则页新增 operatorId={} routeMode={} distributors={} projects={} productTypes={} "
                        + "suppliers={} strategies={} bootstrapEligible={}",
                operatorId, patch.routeMode(), patch.distributorCodes(), patch.projectCodes(),
                patch.productTypeCodes(), patch.orderedSupplierCodes(), patch.strategies(), bootstrapEligible);
        String focus = publishPatchedRules(true, (current, now) -> {
            String ruleCode = generateRuleCode(current, now);
            List<MutableRuleState> next = new ArrayList<>(current);
            next.add(buildPatchedState(ruleCode, patch, null));
            return new PatchOutcome(next, ruleCode);
        }, operatorId, patch);
        return queryService.get(focus);
    }

    /**
     * 整行替换：保留路径 {@code ruleCode}，候选检查模式按供应商编码从旧行合并；
     * 受保护规则的范围与路由模式不可改。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public RoutingRuleView update(String ruleCode, NormalizedRulePatch patch, Long operatorId) {
        Objects.requireNonNull(patch, "patch");
        Objects.requireNonNull(operatorId, "operatorId");
        String focus = requireRuleCode(ruleCode);
        log.info("路由规则页更新 operatorId={} ruleCode={} routeMode={} distributors={} projects={} "
                        + "productTypes={} suppliers={} strategies={}",
                operatorId, focus, patch.routeMode(), patch.distributorCodes(), patch.projectCodes(),
                patch.productTypeCodes(), patch.orderedSupplierCodes(), patch.strategies());
        String publishedFocus = publishPatchedRules(false, (current, now) -> {
            int index = indexOfRule(current, focus);
            if (index < 0) {
                throw exception(ROUTING_RULE_NOT_FOUND);
            }
            MutableRuleState previous = current.get(index);
            if (previous.protectedRule()) {
                requireProtectedScopeImmutable(previous, patch);
            }
            List<MutableRuleState> next = new ArrayList<>(current);
            next.set(index, buildPatchedState(focus, patch, previous));
            return new PatchOutcome(next, focus);
        }, operatorId, patch);
        return queryService.get(publishedFocus);
    }

    /**
     * 从下一版本移除规则；受保护 GLOBAL_DEFAULT 拒绝。
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
            if (current.get(index).protectedRule()) {
                throw exception(ROUTING_RULE_PROTECTED);
            }
            List<MutableRuleState> next = new ArrayList<>(current);
            next.remove(index);
            return new PatchOutcome(next, focus);
        }, operatorId, null);
    }

    /**
     * 真实启停：ENABLED → status 10，DISABLED → status 20（不再等同删除）。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public RoutingRuleView changeStatus(String ruleCode, RoutingRuleStatus status, Long operatorId) {
        Objects.requireNonNull(operatorId, "operatorId");
        String focus = requireRuleCode(ruleCode);
        if (status == null) {
            throw exception(ENUM_NOT_OPEN);
        }
        log.info("路由规则页启停 operatorId={} ruleCode={} status={}", operatorId, focus, status);
        short target = status == RoutingRuleStatus.ENABLED ? RULE_ENABLED : RULE_DISABLED;
        publishPatchedRules(false, (current, now) -> {
            int index = indexOfRule(current, focus);
            if (index < 0) {
                throw exception(ROUTING_RULE_NOT_FOUND);
            }
            MutableRuleState targetRule = current.get(index);
            List<MutableRuleState> next = new ArrayList<>(current);
            next.set(index, new MutableRuleState(targetRule, target));
            return new PatchOutcome(next, focus);
        }, operatorId, null);
        return queryService.get(focus);
    }

    private String publishPatchedRules(boolean create, PatchApplier applier, Long operatorId,
                                       NormalizedRulePatch patch) {
        LocalDateTime now = clock.now();
        WriteScope scope = resolveWriteScope(create, now);
        List<MutableRuleState> current = loadMutableRules(scope.version());
        PatchOutcome outcome = applier.apply(current, now);
        List<MutableRuleState> next = outcome.nextRules();
        if (next.isEmpty()) {
            throw exception(ROUTING_RULE_NOT_FOUND);
        }
        validateRuleSet(next);
        List<RuleDraft> drafts = toRuleDrafts(next);
        // 未携带权重的操作（删除/启停）沿用当前策略行权重，避免补丁发布意外改写全局配置。
        WeightHolder weights = patch != null && patch.weights() != null
                ? new WeightHolder(patch.weights().stability(), patch.weights().price(),
                        patch.weights().timeliness())
                : currentPolicyWeights(scope);
        try {
            publicationService.publishSuccessorStandardProductRules(new SuccessorPublishCommand(
                    scope.policyCode(), scope.policyName(), operatorId, resolveTraceId(),
                    drafts, scope.closeVersionId(), scope.closeExpectedVersion(), now,
                    weights.stability(), weights.price(), weights.timeliness()));
        } catch (IllegalStateException ex) {
            log.info("路由规则后继发布冲突 operatorId={} focusRuleCode={} reason={}",
                    operatorId, outcome.focusRuleCode(), ex.getMessage());
            throw exception(ROUTING_RULE_VERSION_CONFLICT);
        }
        return outcome.focusRuleCode();
    }

    private static WeightHolder currentPolicyWeights(WriteScope scope) {
        SupplierRoutingPolicyDO policy = scope.policy();
        if (policy != null && policy.getStabilityWeight() != null && policy.getPriceWeight() != null
                && policy.getTimelinessWeight() != null) {
            return new WeightHolder(policy.getStabilityWeight(), policy.getPriceWeight(),
                    policy.getTimelinessWeight());
        }
        return new WeightHolder(RoutingRuleSaveSupport.DEFAULT_WEIGHT_STABILITY,
                RoutingRuleSaveSupport.DEFAULT_WEIGHT_PRICE,
                RoutingRuleSaveSupport.DEFAULT_WEIGHT_TIMELINESS);
    }

    private record WeightHolder(int stability, int price, int timeliness) {
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
            SupplierRoutingPolicyDO existing = policyMapper.selectByCode(BOOTSTRAP_POLICY_CODE);
            String policyName = existing != null && existing.getPolicyName() != null
                    && !existing.getPolicyName().isBlank()
                    ? existing.getPolicyName()
                    : BOOTSTRAP_POLICY_NAME;
            log.info("路由规则页引导首版策略 policyCode={} policyName={} reusedExisting={}",
                    BOOTSTRAP_POLICY_CODE, policyName, existing != null);
            return new WriteScope(BOOTSTRAP_POLICY_CODE, policyName, null, 0L, null, null);
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
            return new WriteScope(policy.getPolicyCode(), policy.getPolicyName(), null, 0L, null, policy);
        }
        SupplierRoutingPolicyVersionDO version = versions.getFirst();
        return new WriteScope(policy.getPolicyCode(), policy.getPolicyName(),
                version.getId(), version.getVersion() == null ? 0L : version.getVersion(), version, policy);
    }

    private List<MutableRuleState> loadMutableRules(SupplierRoutingPolicyVersionDO version) {
        if (version == null) {
            return new ArrayList<>();
        }
        List<StdRoutingRuleDO> rules = ruleMapper.selectByPolicyVersionId(version.getId());
        List<StdRoutingRuleScopeDO> scopes = scopeMapper.selectByRuleIds(
                rules.stream().map(StdRoutingRuleDO::getId).toList());
        List<StdRoutingCandidateDO> candidates = candidateMapper.selectByRuleIds(
                rules.stream().map(StdRoutingRuleDO::getId).toList());
        Map<Long, List<StdRoutingRuleScopeDO>> scopesByRule = new HashMap<>();
        for (StdRoutingRuleScopeDO scope : scopes) {
            scopesByRule.computeIfAbsent(scope.getRuleId(), ignored -> new ArrayList<>()).add(scope);
        }
        Map<Long, List<StdRoutingCandidateDO>> candidatesByRule = new HashMap<>();
        for (StdRoutingCandidateDO candidate : candidates) {
            candidatesByRule.computeIfAbsent(candidate.getRuleId(), ignored -> new ArrayList<>()).add(candidate);
        }
        List<MutableRuleState> result = new ArrayList<>(rules.size());
        for (StdRoutingRuleDO rule : rules) {
            List<StdRoutingCandidateDO> ruleCandidates =
                    candidatesByRule.getOrDefault(rule.getId(), List.of());
            List<MutableCandidateState> mutableCandidates = new ArrayList<>(ruleCandidates.size());
            for (StdRoutingCandidateDO candidate : ruleCandidates) {
                SupplierDO supplier = supplierMapper.selectById(candidate.getSupplierId());
                String supplierCode = supplier == null ? null : supplier.getSupplierCode();
                mutableCandidates.add(new MutableCandidateState(
                        candidate.getSupplierId(), supplierCode,
                        candidate.getInventoryCheckMode(), candidate.getInventoryMaxAgeSeconds(),
                        candidate.getBalanceCheckMode(), candidate.getBalanceMaxAgeSeconds(),
                        candidate.getHealthCheckMode(), candidate.getHealthMaxAgeSeconds()));
            }
            result.add(MutableRuleState.fromPublished(rule,
                    scopeCodes(scopesByRule.getOrDefault(rule.getId(), List.of()), "DISTRIBUTOR"),
                    scopeCodes(scopesByRule.getOrDefault(rule.getId(), List.of()), "PROJECT"),
                    scopeCodes(scopesByRule.getOrDefault(rule.getId(), List.of()), "PRODUCT_TYPE"),
                    List.copyOf(mutableCandidates)));
        }
        return result;
    }

    private static List<String> scopeCodes(List<StdRoutingRuleScopeDO> scopes, String kind) {
        return scopes.stream()
                .filter(scope -> kind.equals(scope.getScopeKind()))
                .map(StdRoutingRuleScopeDO::getScopeCode).toList();
    }

    private MutableRuleState buildPatchedState(String ruleCode, NormalizedRulePatch patch,
                                               MutableRuleState previous) {
        // 范围编码与供应商的存在性校验（带业务错误码）。
        for (String buyerCode : patch.distributorCodes()) {
            requireActiveBuyerByCode(buyerCode);
        }
        for (String projectCode : patch.projectCodes()) {
            requireProjectByCode(projectCode);
        }
        for (String typeCode : patch.productTypeCodes()) {
            requireProductTypeByCode(typeCode);
        }
        if (patch.primarySupplierCode() != null) {
            requireActiveStandardSupplier(patch.primarySupplierCode());
        }
        for (String supplierCode : patch.orderedSupplierCodes()) {
            requireActiveStandardSupplier(supplierCode);
        }
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
                candidates.add(retained);
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
        return MutableRuleState.fromPatch(ruleCode, patch, previous, candidates);
    }

    /** 启用集内名称唯一、四级范围唯一；同时校验范围编码解析。 */
    private void validateRuleSet(List<MutableRuleState> rules) {
        Set<String> names = new HashSet<>();
        Set<String> scopeKeys = new HashSet<>();
        for (MutableRuleState rule : rules) {
            if (rule.status() != RULE_ENABLED) {
                continue;
            }
            if (!names.add(rule.name())) {
                throw exception(ROUTING_RULE_NAME_DUPLICATED);
            }
            if (!scopeKeys.add(rule.scopeKey())) {
                throw exception(ROUTING_RULE_SCOPE_DUPLICATED);
            }
        }
    }

    private List<RuleDraft> toRuleDrafts(List<MutableRuleState> rules) {
        List<RuleDraft> drafts = new ArrayList<>(rules.size());
        for (MutableRuleState rule : rules) {
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
            String primarySupplierCode = null;
            if (ROUTE_MODE_FIXED.equals(rule.routeMode())) {
                primarySupplierCode = resolvePrimarySupplierCode(rule);
            }
            String supplierMode = rule.supplierMode();
            if (ROUTE_MODE_FIXED.equals(rule.routeMode())) {
                // FIXED 规则不携带候选列表；主供应商单独表达。
                candidates = List.of();
            }
            List<String> supplierCodes = candidates.stream()
                    .map(CandidateDraft::supplierCode).toList();
            drafts.add(new RuleDraft(
                    rule.ruleCode(), rule.name(), rule.routeMode(), primarySupplierCode,
                    rule.distributorMode(), rule.distributorCodes(),
                    rule.projectMode(), rule.projectCodes(),
                    rule.productTypeMode(), rule.productTypeCodes(),
                    supplierMode, supplierCodes,
                    rule.maxSupplyPriceCents(),
                    rule.strategy1(), rule.strategy2(), rule.strategy3(),
                    rule.rulePriority(), rule.status(), rule.protectedRule(),
                    List.copyOf(candidates)));
        }
        return List.copyOf(drafts);
    }

    private String resolvePrimarySupplierCode(MutableRuleState rule) {
        if (rule.primarySupplierCode() != null) {
            return rule.primarySupplierCode();
        }
        if (rule.primarySupplierId() == null) {
            throw exception(ROUTING_RULE_CANDIDATE_INVALID);
        }
        SupplierDO supplier = supplierMapper.selectById(rule.primarySupplierId());
        if (supplier == null || supplier.getSupplierCode() == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        return supplier.getSupplierCode();
    }

    private static void requireProtectedScopeImmutable(MutableRuleState previous, NormalizedRulePatch patch) {
        if (!Objects.equals(previous.routeMode(), patch.routeMode().name())
                || !Objects.equals(previous.distributorMode(), patch.distributorMode().name())
                || !Objects.equals(previous.projectMode(), patch.projectMode().name())
                || !Objects.equals(previous.productTypeMode(), patch.productTypeMode().name())
                || !Objects.equals(previous.supplierMode(), patch.supplierMode().name())
                || !Objects.equals(previous.distributorCodes(), patch.distributorCodes())
                || !Objects.equals(previous.projectCodes(), patch.projectCodes())
                || !Objects.equals(previous.productTypeCodes(), patch.productTypeCodes())
                || !Objects.equals(previous.candidateSupplierCodes(), patch.orderedSupplierCodes())) {
            throw exception(ROUTING_RULE_PROTECTED);
        }
    }

    private BuyerDO requireActiveBuyerByCode(String buyerCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode);
        if (buyer == null) {
            throw exception(BUYER_NOT_FOUND);
        }
        if (buyer.getStatus() == null || buyer.getStatus() != BuyerStatus.ACTIVE.getCode()) {
            throw exception(BUYER_DISABLED);
        }
        return buyer;
    }

    private BuyerProjectDO requireProjectByCode(String projectCode) {
        BuyerProjectDO project = projectMapper.selectByProjectCode(projectCode);
        if (project == null) {
            throw exception(PROJECT_NOT_FOUND);
        }
        return project;
    }

    private ProductTypeDO requireProductTypeByCode(String typeCode) {
        ProductTypeDO type = productTypeMapper.selectByCode(typeCode);
        if (type == null) {
            throw exception(PRODUCT_TYPE_NOT_FOUND);
        }
        return type;
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
                              SupplierRoutingPolicyVersionDO version,
                              SupplierRoutingPolicyDO policy) {
    }

    /**
     * 内存中的规则快照；fromPatch=true 表示本补丁新建或整行替换，校验走编码解析。
     */
    record MutableRuleState(String ruleCode, String name,
                            String routeMode, Long primarySupplierId, String primarySupplierCode,
                            String distributorMode, List<String> distributorCodes,
                            String projectMode, List<String> projectCodes,
                            String productTypeMode, List<String> productTypeCodes,
                            String supplierMode, List<MutableCandidateState> candidates,
                            String strategy1, String strategy2, String strategy3,
                            int rulePriority, short status, boolean protectedRule,
                            Long maxSupplyPriceCents, boolean fromPatch) {
        MutableRuleState {
            distributorCodes = List.copyOf(distributorCodes == null ? List.of() : distributorCodes);
            projectCodes = List.copyOf(projectCodes == null ? List.of() : projectCodes);
            productTypeCodes = List.copyOf(productTypeCodes == null ? List.of() : productTypeCodes);
            candidates = List.copyOf(candidates == null ? List.of() : candidates);
        }

        private MutableRuleState(MutableRuleState source, short newStatus) {
            this(source.ruleCode, source.name, source.routeMode, source.primarySupplierId,
                    source.primarySupplierCode, source.distributorMode, source.distributorCodes(),
                    source.projectMode, source.projectCodes(), source.productTypeMode,
                    source.productTypeCodes(), source.supplierMode, source.candidates(),
                    source.strategy1, source.strategy2, source.strategy3,
                    source.rulePriority, newStatus, source.protectedRule,
                    source.maxSupplyPriceCents, source.fromPatch);
        }

        static MutableRuleState fromPublished(StdRoutingRuleDO rule, List<String> distributorCodes,
                                              List<String> projectCodes, List<String> productTypeCodes,
                                              List<MutableCandidateState> candidates) {
            return new MutableRuleState(rule.getRuleCode(), rule.getRuleName(),
                    rule.getRouteMode(), rule.getPrimarySupplierId(), null,
                    rule.getDistributorMode(), distributorCodes,
                    rule.getProjectMode(), projectCodes,
                    rule.getProductTypeMode(), productTypeCodes,
                    rule.getSupplierMode(), candidates,
                    rule.getStrategyPriority1(), rule.getStrategyPriority2(), rule.getStrategyPriority3(),
                    rule.getRulePriority() == null ? DEFAULT_RULE_PRIORITY : rule.getRulePriority(),
                    rule.getStatus() == null ? RULE_ENABLED : rule.getStatus(),
                    Boolean.TRUE.equals(rule.getProtectedRule()),
                    rule.getMaxSupplyPriceCents(), false);
        }

        static MutableRuleState fromPatch(String ruleCode, NormalizedRulePatch patch, MutableRuleState previous,
                                          List<MutableCandidateState> candidates) {
            int rulePriority = previous == null || previous.rulePriority() < 0
                    ? DEFAULT_RULE_PRIORITY : previous.rulePriority();
            return new MutableRuleState(ruleCode, patch.name(),
                    patch.routeMode().name(), null, patch.primarySupplierCode(),
                    patch.distributorMode().name(), patch.distributorCodes(),
                    patch.projectMode().name(), patch.projectCodes(),
                    patch.productTypeMode().name(), patch.productTypeCodes(),
                    patch.supplierMode().name(), candidates,
                    patch.strategies().get(0).name(), patch.strategies().get(1).name(),
                    patch.strategies().get(2).name(),
                    rulePriority,
                    patch.status() == RoutingRuleStatus.ENABLED ? RULE_ENABLED : RULE_DISABLED,
                    previous != null && previous.protectedRule(),
                    patch.maxSupplyPriceCents(), true);
        }

        List<String> candidateSupplierCodes() {
            return candidates.stream()
                    .map(MutableCandidateState::supplierCode).toList();
        }

        String scopeKey() {
            return distributorMode + ":" + String.join(",", distributorCodes)
                    + "|" + projectMode + ":" + String.join(",", projectCodes)
                    + "|" + productTypeMode + ":" + String.join(",", productTypeCodes)
                    + "|" + supplierMode + ":" + String.join(",", candidateSupplierCodes());
        }
    }

    record MutableCandidateState(Long supplierId, String supplierCode,
                                 String inventoryCheckMode, Integer inventoryMaxAgeSeconds,
                                 String balanceCheckMode, Integer balanceMaxAgeSeconds,
                                 String healthCheckMode, Integer healthMaxAgeSeconds) {
    }
}
