package com.aifuli.module.supplychain.routing.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
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
import com.aifuli.module.supplychain.routing.domain.RoutingStrategy;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 标准商品路由策略的草稿创建和不可变版本发布入口（PRD 四级范围模型）。
 *
 * <p>规则携带分销商/项目/商品类型三级范围明细与供应商候选；全局综合权重挂策略身份行，
 * 任一保存携带 COMPOSITE 时同步更新并全规则生效。
 * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#52 与 #55}。</p>
 */
@Service
@RequiredArgsConstructor
public class RoutingPolicyPublicationService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String ROUTING_TYPE = RoutingType.STANDARD_PRODUCT.code();
    private static final short POLICY_ACTIVE = 10;
    private static final short POLICY_INACTIVE = 20;
    private static final short POLICY_ARCHIVED = 90;
    private static final short VERSION_DRAFT = 0;
    private static final short VERSION_PUBLISHED = 10;
    private static final short RULE_ENABLED = 10;
    private static final short RULE_DISABLED = 20;
    private static final Set<String> CHECK_MODES = Set.of("REQUIRED", "OPTIONAL", "UNSUPPORTED");
    private static final Set<String> SCOPE_MODES = Set.of("ALL", "SPECIFIED");
    private static final Set<String> ROUTE_MODES = Set.of("AUTO", "FIXED");

    private final SupplierRoutingPolicyMapper policyMapper;
    private final SupplierRoutingPolicyVersionMapper versionMapper;
    private final StdRoutingRuleMapper ruleMapper;
    private final StdRoutingRuleScopeMapper scopeMapper;
    private final StdRoutingCandidateMapper candidateMapper;
    private final BuyerMapper buyerMapper;
    private final BuyerProjectMapper projectMapper;
    private final ProductTypeMapper productTypeMapper;
    private final SupplierMapper supplierMapper;
    private final OutboxEventRepository outboxRepository;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public DraftResult createDraft(CreateDraftCommand command) {
        validateDraftCommand(command);
        LocalDateTime now = now();
        SupplierRoutingPolicyDO policy = policyMapper.selectByCodeForUpdate(command.policyCode());
        if (policy == null) {
            policy = SupplierRoutingPolicyDO.builder()
                    .policyCode(command.policyCode()).policyName(command.policyName())
                    .routingType(ROUTING_TYPE).status(POLICY_INACTIVE).activeFlag(null).version(0L)
                    .stabilityWeight(command.stabilityWeight()).priceWeight(command.priceWeight())
                    .timelinessWeight(command.timelinessWeight())
                    .createdBy(command.operatorId()).createdAt(now)
                    .updatedBy(command.operatorId()).updatedAt(now).build();
            policyMapper.insert(policy);
        } else {
            if (!ROUTING_TYPE.equals(policy.getRoutingType()) || policy.getStatus() == POLICY_ARCHIVED) {
                throw new IllegalStateException("Routing policy cannot accept a standard-product draft");
            }
            if (!policy.getPolicyName().equals(command.policyName())) {
                throw new IllegalArgumentException("Routing policy name cannot be changed through a version draft");
            }
            // 全局综合权重随版本演进同步到策略身份行（前端语义：权重在所有规则中保持一致）。
            policy.setStabilityWeight(command.stabilityWeight());
            policy.setPriceWeight(command.priceWeight());
            policy.setTimelinessWeight(command.timelinessWeight());
            policy.setUpdatedBy(command.operatorId());
            policy.setUpdatedAt(now);
            if (policyMapper.updateById(policy) != 1) {
                throw new IllegalStateException("Routing policy weight sync lost optimistic lock");
            }
        }

        SupplierRoutingPolicyVersionDO latest = versionMapper.selectLatest(policy.getId());
        int versionNo = latest == null ? 1 : latest.getVersionNo() + 1;
        List<ResolvedRule> resolvedRules = resolveAndValidateRules(command.rules());
        byte[] digest = digest(command, resolvedRules);
        SupplierRoutingPolicyVersionDO version = SupplierRoutingPolicyVersionDO.builder()
                .policyId(policy.getId()).versionNo(versionNo).versionName(command.versionName())
                .validFrom(command.validFrom()).validTo(command.validTo()).configDigest(digest)
                .status(VERSION_DRAFT).version(0L).createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        versionMapper.insert(version);

        for (ResolvedRule resolved : resolvedRules) {
            RuleDraft draft = resolved.draft();
            StdRoutingRuleDO rule = StdRoutingRuleDO.builder()
                    .policyVersionId(version.getId()).ruleCode(draft.ruleCode())
                    .ruleName(draft.ruleName()).routeMode(draft.routeMode())
                    .primarySupplierId(resolved.primarySupplier() == null ? null : resolved.primarySupplier().getId())
                    .distributorMode(draft.distributorMode())
                    .projectMode(draft.projectMode())
                    .productTypeMode(draft.productTypeMode())
                    .supplierMode(draft.supplierMode())
                    .maxSupplyPriceCents(draft.maxSupplyPriceCents())
                    .strategyPriority1(draft.strategy1()).strategyPriority2(draft.strategy2())
                    .strategyPriority3(draft.strategy3())
                    .rulePriority(draft.rulePriority()).status(draft.status())
                    .protectedRule(draft.protectedRule()).version(0L)
                    .createdBy(command.operatorId()).createdAt(now)
                    .updatedBy(command.operatorId()).updatedAt(now).build();
            ruleMapper.insert(rule);
            insertScopes(rule.getId(), draft, command.operatorId(), now);
            for (ResolvedCandidate candidate : resolved.candidates()) {
                CandidateDraft candidateDraft = candidate.draft();
                candidateMapper.insert(StdRoutingCandidateDO.builder()
                        .ruleId(rule.getId()).supplierId(candidate.supplier().getId())
                        .candidatePriority(candidateDraft.candidatePriority())
                        .inventoryCheckMode(candidateDraft.inventoryCheckMode())
                        .inventoryMaxAgeSeconds(candidateDraft.inventoryMaxAgeSeconds())
                        .balanceCheckMode(candidateDraft.balanceCheckMode())
                        .balanceMaxAgeSeconds(candidateDraft.balanceMaxAgeSeconds())
                        .healthCheckMode(candidateDraft.healthCheckMode())
                        .healthMaxAgeSeconds(candidateDraft.healthMaxAgeSeconds())
                        .version(0L).createdBy(command.operatorId()).createdAt(now)
                        .updatedBy(command.operatorId()).updatedAt(now).build());
            }
        }
        return new DraftResult(policy.getId(), version.getId(), versionNo,
                HexFormat.of().formatHex(digest));
    }

    private void insertScopes(Long ruleId, RuleDraft draft, Long operatorId, LocalDateTime now) {
        insertScopeLines(ruleId, "DISTRIBUTOR", draft.distributorCodes(), operatorId, now);
        insertScopeLines(ruleId, "PROJECT", draft.projectCodes(), operatorId, now);
        insertScopeLines(ruleId, "PRODUCT_TYPE", draft.productTypeCodes(), operatorId, now);
    }

    private void insertScopeLines(Long ruleId, String kind, List<String> codes, Long operatorId, LocalDateTime now) {
        for (String code : codes) {
            scopeMapper.insert(StdRoutingRuleScopeDO.builder()
                    .ruleId(ruleId).scopeKind(kind).scopeCode(code).createdAt(now).build());
        }
    }

    /**
     * 关闭旧生效版本的 valid_to，再以完整规则集 createDraft + publish（同一事务）。
     * 不得改写已发布版本内的规则/范围/候选行；仅 UPDATE 旧版本区间终点。
     * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#55}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public SuccessorPublishResult publishSuccessorStandardProductRules(SuccessorPublishCommand command) {
        validateSuccessorCommand(command);
        SupplierRoutingPolicyVersionDO closing = null;
        if (command.closeVersionId() != null) {
            closing = versionMapper.selectByIdForUpdate(command.closeVersionId());
            if (closing == null || closing.getStatus() != VERSION_PUBLISHED
                    || closing.getVersion() != command.closeExpectedVersion()) {
                throw new IllegalStateException("Routing policy version cannot be closed for successor publish");
            }
            if (!command.effectiveAt().isAfter(closing.getValidFrom())) {
                throw new IllegalStateException(
                        "Routing policy version effective interval cannot close at or before valid_from");
            }
        }

        SupplierRoutingPolicyDO policy = policyMapper.selectByCodeForUpdate(command.policyCode());
        if (policy == null) {
            if (closing != null) {
                throw new IllegalStateException(
                        "Closing routing policy version does not belong to policy code " + command.policyCode());
            }
            LocalDateTime stamp = now();
            policy = SupplierRoutingPolicyDO.builder()
                    .policyCode(command.policyCode()).policyName(command.policyName())
                    .routingType(ROUTING_TYPE).status(POLICY_INACTIVE).activeFlag(null).version(0L)
                    .stabilityWeight(command.stabilityWeight()).priceWeight(command.priceWeight())
                    .timelinessWeight(command.timelinessWeight())
                    .createdBy(command.operatorId()).createdAt(stamp)
                    .updatedBy(command.operatorId()).updatedAt(stamp).build();
            policyMapper.insert(policy);
        }
        if (closing != null && !closing.getPolicyId().equals(policy.getId())) {
            throw new IllegalStateException(
                    "Closing routing policy version does not belong to policy code " + command.policyCode());
        }
        if (closing != null) {
            LocalDateTime stamp = now();
            closing.setValidTo(command.effectiveAt());
            closing.setUpdatedBy(command.operatorId());
            closing.setUpdatedAt(stamp);
            if (versionMapper.updateById(closing) != 1) {
                throw new IllegalStateException("Routing policy version close lost optimistic lock");
            }
        }

        DraftResult draft = createDraft(new CreateDraftCommand(
                command.policyCode(), command.policyName(),
                "规则页保存 " + command.effectiveAt(),
                command.effectiveAt(), null, command.rules(), command.operatorId(),
                command.stabilityWeight(), command.priceWeight(), command.timelinessWeight()));
        // createDraft 插入时乐观锁 version 固定为 0；本方法经 this 自调用，与外层同一事务。
        PublishResult published = publish(new PublishCommand(
                draft.policyVersionId(), 0L, "管理端路由规则页保存",
                command.operatorId(), command.traceId(), null, null));
        return new SuccessorPublishResult(published.policyCode(), published.policyVersionId(),
                published.versionNo());
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public PublishResult publish(PublishCommand command) {
        if (command.policyVersionId() == null || command.expectedVersion() < 0
                || command.operatorId() == null || isBlank(command.publishReason())
                || isBlank(command.traceId())) {
            throw new IllegalArgumentException("Routing policy publication command is incomplete");
        }
        SupplierRoutingPolicyVersionDO version = versionMapper.selectByIdForUpdate(command.policyVersionId());
        if (version == null || version.getStatus() != VERSION_DRAFT
                || version.getVersion() != command.expectedVersion()) {
            throw new IllegalStateException("Routing policy version is not an expected draft");
        }
        SupplierRoutingPolicyDO policy = policyMapper.selectByIdForUpdate(version.getPolicyId());
        if (policy == null || policy.getStatus() == POLICY_ARCHIVED
                || !ROUTING_TYPE.equals(policy.getRoutingType())) {
            throw new IllegalStateException("Routing policy is not publishable");
        }
        requireNoPublishedOverlap(version, versionMapper.selectPublished(policy.getId()));
        List<SupplierRoutingPolicyDO> active = policyMapper.selectActiveByType(ROUTING_TYPE);
        if (active.stream().anyMatch(item -> !item.getId().equals(policy.getId()))) {
            throw new IllegalStateException("Another standard-product routing policy is active");
        }
        requirePublishableReferences(version.getId());

        LocalDateTime now = now();
        version.setStatus(VERSION_PUBLISHED);
        version.setPublishReason(command.publishReason());
        version.setPublishedBy(command.operatorId());
        version.setPublishedAt(now);
        version.setUpdatedBy(command.operatorId());
        version.setUpdatedAt(now);
        if (versionMapper.updateById(version) != 1) {
            throw new IllegalStateException("Routing policy version publication lost optimistic lock");
        }

        policy.setStatus(POLICY_ACTIVE);
        policy.setActiveFlag((short) 1);
        policy.setUpdatedBy(command.operatorId());
        policy.setUpdatedAt(now);
        if (policyMapper.updateById(policy) != 1) {
            throw new IllegalStateException("Routing policy activation lost optimistic lock");
        }
        outboxRepository.appendLocal("ROUTING_POLICY", policy.getPolicyCode(), policy.getVersion(),
                "ROUTING_POLICY_PUBLISHED", "supply-availability-recalculation",
                new PolicyPublishedPayload(policy.getPolicyCode(), version.getId(), version.getVersionNo(),
                        HexFormat.of().formatHex(version.getConfigDigest())),
                command.traceId(), command.correlationId(), command.causationId(), now);
        return new PublishResult(policy.getPolicyCode(), version.getId(), version.getVersionNo(),
                policy.getVersion(), HexFormat.of().formatHex(version.getConfigDigest()));
    }

    private List<ResolvedRule> resolveAndValidateRules(List<RuleDraft> rules) {
        Set<String> ruleCodes = new HashSet<>();
        Set<String> enabledScopeKeys = new HashSet<>();
        Set<String> enabledNames = new HashSet<>();
        List<ResolvedRule> result = new ArrayList<>();
        for (RuleDraft rule : rules) {
            validateRuleStructure(rule, ruleCodes);
            if (rule.status() == RULE_ENABLED) {
                if (!enabledScopeKeys.add(scopeKey(rule))) {
                    throw new IllegalArgumentException("Overlapping routing rule scope: " + rule.ruleCode());
                }
                if (!enabledNames.add(rule.ruleName())) {
                    throw new IllegalArgumentException("Duplicated routing rule name: " + rule.ruleName());
                }
            }
            List<SupplierDO> scopeSuppliers = new ArrayList<>();
            for (String supplierCode : rule.supplierCodes()) {
                scopeSuppliers.add(requireActiveStandardSupplier(supplierCode));
            }
            SupplierDO primary = rule.primarySupplierCode() == null ? null
                    : requireActiveStandardSupplier(rule.primarySupplierCode());
            for (String buyerCode : rule.distributorCodes()) {
                requireActiveBuyer(buyerCode);
            }
            for (String projectCode : rule.projectCodes()) {
                requireProject(projectCode);
            }
            for (String typeCode : rule.productTypeCodes()) {
                requireProductType(typeCode);
            }
            List<ResolvedCandidate> candidates = new ArrayList<>();
            Set<String> supplierCodes = new HashSet<>();
            for (CandidateDraft candidate : rule.candidates()) {
                validateCandidate(candidate);
                if (!supplierCodes.add(candidate.supplierCode())) {
                    throw new IllegalArgumentException("Duplicate supplier in routing rule: "
                            + candidate.supplierCode());
                }
                candidates.add(new ResolvedCandidate(candidate,
                        requireActiveStandardSupplier(candidate.supplierCode())));
            }
            result.add(new ResolvedRule(rule, primary, List.copyOf(candidates), List.copyOf(scopeSuppliers)));
        }
        return List.copyOf(result);
    }

    private void validateRuleStructure(RuleDraft rule, Set<String> ruleCodes) {
        if (isBlank(rule.ruleCode()) || isBlank(rule.ruleName()) || !ROUTE_MODES.contains(rule.routeMode())
                || !SCOPE_MODES.contains(rule.distributorMode())
                || !SCOPE_MODES.contains(rule.projectMode())
                || !SCOPE_MODES.contains(rule.productTypeMode())
                || !SCOPE_MODES.contains(rule.supplierMode())
                || rule.rulePriority() < 0 || rule.status() != RULE_ENABLED && rule.status() != RULE_DISABLED
                || !ruleCodes.add(rule.ruleCode())) {
            throw new IllegalArgumentException("Routing rule is incomplete or duplicated: " + rule.ruleCode());
        }
        if (isBlank(rule.strategy1()) || isBlank(rule.strategy2()) || isBlank(rule.strategy3())
                || !isValidStrategyPermutation(rule.strategy1(), rule.strategy2(), rule.strategy3())) {
            throw new IllegalArgumentException("Routing rule strategies must be a permutation: " + rule.ruleCode());
        }
        if ("SPECIFIED".equals(rule.distributorMode()) && rule.distributorCodes().isEmpty()) {
            throw new IllegalArgumentException("Routing rule distributor scope is empty: " + rule.ruleCode());
        }
        if ("SPECIFIED".equals(rule.projectMode()) && rule.projectCodes().isEmpty()) {
            throw new IllegalArgumentException("Routing rule project scope is empty: " + rule.ruleCode());
        }
        if ("SPECIFIED".equals(rule.productTypeMode()) && rule.productTypeCodes().isEmpty()) {
            throw new IllegalArgumentException("Routing rule product type scope is empty: " + rule.ruleCode());
        }
        if ("FIXED".equals(rule.routeMode())) {
            if (isBlank(rule.primarySupplierCode())) {
                throw new IllegalArgumentException("Fixed routing rule lacks primary supplier: " + rule.ruleCode());
            }
            if (!rule.supplierCodes().isEmpty()) {
                throw new IllegalArgumentException("Fixed routing rule must not carry candidates: " + rule.ruleCode());
            }
        } else if ("SPECIFIED".equals(rule.supplierMode()) && rule.supplierCodes().size() < 2) {
            throw new IllegalArgumentException("Auto specified routing rule needs at least 2 suppliers: "
                    + rule.ruleCode());
        }
        if ((rule.maxSupplyPriceCents() != null && rule.maxSupplyPriceCents() <= 0)) {
            throw new IllegalArgumentException("Routing rule max supply price must be positive: " + rule.ruleCode());
        }
    }

    private static boolean isValidStrategyPermutation(String first, String second, String third) {
        try {
            RoutingStrategy.valueOf(first);
            RoutingStrategy.valueOf(second);
            RoutingStrategy.valueOf(third);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return Set.of(first, second, third).size() == 3;
    }

    private static String scopeKey(RuleDraft rule) {
        return rule.distributorMode() + ":" + String.join(",", rule.distributorCodes())
                + "|" + rule.projectMode() + ":" + String.join(",", rule.projectCodes())
                + "|" + rule.productTypeMode() + ":" + String.join(",", rule.productTypeCodes())
                + "|" + rule.supplierMode() + ":" + String.join(",", rule.supplierCodes());
    }

    private void requirePublishableReferences(Long versionId) {
        List<StdRoutingRuleDO> rules = ruleMapper.selectByPolicyVersionId(versionId);
        if (rules.isEmpty()) {
            throw new IllegalStateException("Published routing version must contain a rule");
        }
        Set<String> enabledScopeKeys = new HashSet<>();
        for (StdRoutingRuleDO rule : rules) {
            List<StdRoutingRuleScopeDO> scopes = scopeMapper.selectByRuleIds(List.of(rule.getId()));
            List<StdRoutingCandidateDO> candidates = candidateMapper.selectByRuleId(rule.getId());
            // 候选行仅 AUTO+SPECIFIED 必需；FIXED 由主供应商单候选表达（设计 §5.5 候选集构造）。
            if ("AUTO".equals(rule.getRouteMode())
                    && "SPECIFIED".equals(rule.getSupplierMode()) && candidates.isEmpty()) {
                throw new IllegalStateException("Published routing rule must contain ordered candidates: "
                        + rule.getRuleCode());
            }
            if (rule.getStatus() == RULE_ENABLED && !enabledScopeKeys.add(scopeKey(rule, scopes, candidates))) {
                throw new IllegalStateException("Published routing version contains overlapping enabled rules");
            }
            for (StdRoutingRuleScopeDO scope : scopes) {
                switch (scope.getScopeKind()) {
                    case "DISTRIBUTOR" -> requireActiveBuyerByIdRef(scope.getScopeCode());
                    case "PROJECT" -> requireProject(scope.getScopeCode());
                    case "PRODUCT_TYPE" -> requireProductType(scope.getScopeCode());
                    default -> throw new IllegalStateException("Unknown routing scope kind: "
                            + scope.getScopeKind());
                }
            }
            if (rule.getPrimarySupplierId() != null) {
                requireActiveSupplierById(rule.getPrimarySupplierId());
            }
            for (StdRoutingCandidateDO candidate : candidates) {
                requireActiveSupplierById(candidate.getSupplierId());
            }
        }
    }

    private static String scopeKey(StdRoutingRuleDO rule, List<StdRoutingRuleScopeDO> scopes,
                                   List<StdRoutingCandidateDO> candidates) {
        return rule.getDistributorMode() + ":" + scopeCodes(scopes, "DISTRIBUTOR")
                + "|" + rule.getProjectMode() + ":" + scopeCodes(scopes, "PROJECT")
                + "|" + rule.getProductTypeMode() + ":" + scopeCodes(scopes, "PRODUCT_TYPE")
                + "|" + rule.getSupplierMode() + ":" + candidates.size();
    }

    private static String scopeCodes(List<StdRoutingRuleScopeDO> scopes, String kind) {
        List<String> codes = scopes.stream()
                .filter(scope -> kind.equals(scope.getScopeKind()))
                .map(StdRoutingRuleScopeDO::getScopeCode).sorted().toList();
        return String.join(",", codes);
    }

    private void requireActiveBuyer(String buyerCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode);
        if (buyer == null || buyer.getStatus() != BuyerStatus.ACTIVE.getCode()) {
            throw new IllegalArgumentException("Active buyer does not exist: " + buyerCode);
        }
    }

    private void requireActiveBuyerByIdRef(String buyerCode) {
        requireActiveBuyer(buyerCode);
    }

    private void requireProject(String projectCode) {
        BuyerProjectDO project = projectMapper.selectByProjectCode(projectCode);
        if (project == null) {
            throw new IllegalArgumentException("Project does not exist: " + projectCode);
        }
    }

    private void requireProductType(String typeCode) {
        ProductTypeDO type = productTypeMapper.selectByCode(typeCode);
        if (type == null) {
            throw new IllegalArgumentException("Product type does not exist: " + typeCode);
        }
    }

    private SupplierDO requireActiveStandardSupplier(String supplierCode) {
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()
                || !"STANDARD_SUPPLIER".equals(supplier.getSupplierType())) {
            throw new IllegalArgumentException("Active standard supplier does not exist: " + supplierCode);
        }
        return supplier;
    }

    private void requireActiveSupplierById(Long supplierId) {
        SupplierDO supplier = supplierMapper.selectById(supplierId);
        if (supplier == null || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()
                || !"STANDARD_SUPPLIER".equals(supplier.getSupplierType())) {
            throw new IllegalArgumentException("Active standard supplier does not exist: id=" + supplierId);
        }
    }

    private static void requireNoPublishedOverlap(SupplierRoutingPolicyVersionDO draft,
                                                  List<SupplierRoutingPolicyVersionDO> published) {
        boolean overlap = published.stream().anyMatch(existing ->
                startsBeforeEnd(draft.getValidFrom(), existing.getValidTo())
                        && startsBeforeEnd(existing.getValidFrom(), draft.getValidTo()));
        if (overlap) {
            throw new IllegalStateException("Routing policy version effective interval overlaps a published version");
        }
    }

    private static boolean startsBeforeEnd(LocalDateTime start, LocalDateTime end) {
        return end == null || start.isBefore(end);
    }

    private static void validateDraftCommand(CreateDraftCommand command) {
        if (isBlank(command.policyCode()) || isBlank(command.policyName())
                || isBlank(command.versionName()) || command.validFrom() == null
                || (command.validTo() != null && !command.validTo().isAfter(command.validFrom()))
                || command.operatorId() == null || command.rules() == null || command.rules().isEmpty()
                || command.stabilityWeight() + command.priceWeight() + command.timelinessWeight()
                        != RoutingRuleSaveSupport.WEIGHT_SUM) {
            throw new IllegalArgumentException("Routing policy draft command is incomplete");
        }
    }

    private static void validateSuccessorCommand(SuccessorPublishCommand command) {
        if (command == null || isBlank(command.policyCode()) || isBlank(command.policyName())
                || command.operatorId() == null || isBlank(command.traceId())
                || command.rules() == null || command.rules().isEmpty()
                || command.effectiveAt() == null || command.closeExpectedVersion() < 0
                || command.stabilityWeight() + command.priceWeight() + command.timelinessWeight()
                        != RoutingRuleSaveSupport.WEIGHT_SUM) {
            throw new IllegalArgumentException("Routing policy successor publish command is incomplete");
        }
    }

    private static void validateCandidate(CandidateDraft candidate) {
        if (candidate == null || isBlank(candidate.supplierCode()) || candidate.candidatePriority() < 0) {
            throw new IllegalArgumentException("Routing candidate is incomplete");
        }
        requireCheckMode(candidate.inventoryCheckMode(), candidate.inventoryMaxAgeSeconds(), "inventory");
        requireCheckMode(candidate.balanceCheckMode(), candidate.balanceMaxAgeSeconds(), "balance");
        requireCheckMode(candidate.healthCheckMode(), candidate.healthMaxAgeSeconds(), "health");
    }

    private static void requireCheckMode(String mode, Integer maxAge, String factName) {
        if (!CHECK_MODES.contains(mode)
                || ("UNSUPPORTED".equals(mode) && maxAge != null)
                || (!"UNSUPPORTED".equals(mode) && (maxAge == null || maxAge <= 0))) {
            throw new IllegalArgumentException("Invalid " + factName + " check mode");
        }
    }

    private static byte[] digest(CreateDraftCommand command, List<ResolvedRule> resolvedRules) {
        StringBuilder canonical = new StringBuilder()
                .append(command.policyCode()).append('\u001f').append(command.versionName()).append('\u001f')
                .append(command.validFrom()).append('\u001f').append(command.validTo()).append('\u001f')
                .append(command.stabilityWeight()).append('\u001f').append(command.priceWeight())
                .append('\u001f').append(command.timelinessWeight()).append('\u001e');
        resolvedRules.stream().sorted(Comparator.comparing(item -> item.draft().ruleCode()))
                .forEach(rule -> {
                    RuleDraft draft = rule.draft();
                    canonical.append(draft.ruleCode()).append('\u001f').append(draft.ruleName()).append('\u001f')
                            .append(draft.routeMode()).append('\u001f')
                            .append(draft.primarySupplierCode()).append('\u001f')
                            .append(draft.distributorMode()).append('\u001f')
                            .append(String.join(",", draft.distributorCodes())).append('\u001f')
                            .append(draft.projectMode()).append('\u001f')
                            .append(String.join(",", draft.projectCodes())).append('\u001f')
                            .append(draft.productTypeMode()).append('\u001f')
                            .append(String.join(",", draft.productTypeCodes())).append('\u001f')
                            .append(draft.supplierMode()).append('\u001f')
                            .append(String.join(",", draft.supplierCodes())).append('\u001f')
                            .append(draft.maxSupplyPriceCents()).append('\u001f')
                            .append(draft.strategy1()).append('\u001f').append(draft.strategy2())
                            .append('\u001f').append(draft.strategy3()).append('\u001f')
                            .append(draft.rulePriority()).append('\u001f').append(draft.status())
                            .append('\u001f').append(draft.protectedRule()).append('\u001e');
                    rule.candidates().stream()
                            .sorted(Comparator.comparing(item -> item.supplier().getSupplierCode()))
                            .forEach(candidate -> appendCandidate(canonical, candidate));
                });
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void appendCandidate(StringBuilder canonical, ResolvedCandidate resolved) {
        CandidateDraft item = resolved.draft();
        canonical.append(item.supplierCode()).append('\u001f').append(item.candidatePriority())
                .append('\u001f').append(item.inventoryCheckMode()).append('\u001f')
                .append(item.inventoryMaxAgeSeconds()).append('\u001f').append(item.balanceCheckMode())
                .append('\u001f').append(item.balanceMaxAgeSeconds()).append('\u001f')
                .append(item.healthCheckMode()).append('\u001f').append(item.healthMaxAgeSeconds())
                .append('\u001d');
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private LocalDateTime now() {
        return databaseClock.now();
    }

    public record CreateDraftCommand(String policyCode, String policyName, String versionName,
                                     LocalDateTime validFrom, LocalDateTime validTo,
                                     List<RuleDraft> rules, Long operatorId,
                                     int stabilityWeight, int priceWeight, int timelinessWeight) {
        public CreateDraftCommand {
            rules = rules == null ? null : List.copyOf(rules);
        }
    }

    /**
     * PRD 四级范围规则草稿。supplierCodes 为 AUTO+SPECIFIED 的有序候选；
     * FIXED 时 primarySupplierCode 必填且 supplierCodes 为空。
     */
    public record RuleDraft(String ruleCode, String ruleName, String routeMode, String primarySupplierCode,
                            String distributorMode, List<String> distributorCodes,
                            String projectMode, List<String> projectCodes,
                            String productTypeMode, List<String> productTypeCodes,
                            String supplierMode, List<String> supplierCodes,
                            Long maxSupplyPriceCents,
                            String strategy1, String strategy2, String strategy3,
                            int rulePriority, short status, boolean protectedRule,
                            List<CandidateDraft> candidates) {
        public RuleDraft {
            distributorCodes = distributorCodes == null ? List.of() : List.copyOf(distributorCodes);
            projectCodes = projectCodes == null ? List.of() : List.copyOf(projectCodes);
            productTypeCodes = productTypeCodes == null ? List.of() : List.copyOf(productTypeCodes);
            supplierCodes = supplierCodes == null ? List.of() : List.copyOf(supplierCodes);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record CandidateDraft(String supplierCode, int candidatePriority,
                                 String inventoryCheckMode, Integer inventoryMaxAgeSeconds,
                                 String balanceCheckMode, Integer balanceMaxAgeSeconds,
                                 String healthCheckMode, Integer healthMaxAgeSeconds) {
    }

    public record PublishCommand(Long policyVersionId, long expectedVersion, String publishReason,
                                 Long operatorId, String traceId,
                                 String correlationId, String causationId) {
    }

    public record DraftResult(Long policyId, Long policyVersionId,
                              int versionNo, String configDigestHex) {
    }

    public record PublishResult(String policyCode, Long policyVersionId, int versionNo,
                                long policyAggregateVersion, String configDigestHex) {
    }

    /** 后继发布结果：新生效版本身份。 */
    public record SuccessorPublishResult(String policyCode, Long policyVersionId, int versionNo) {
    }

    /**
     * 后继发布命令：rules 为完整规则集；closeVersionId 为空表示无生效版本可关（引导首发）；
     * 三权为全局综合权重，随本次保存同步到策略身份行。
     */
    public record SuccessorPublishCommand(String policyCode, String policyName,
                                          Long operatorId, String traceId,
                                          List<RuleDraft> rules,
                                          Long closeVersionId, long closeExpectedVersion,
                                          LocalDateTime effectiveAt,
                                          int stabilityWeight, int priceWeight, int timelinessWeight) {
        public SuccessorPublishCommand {
            rules = rules == null ? null : List.copyOf(rules);
        }
    }

    public record PolicyPublishedPayload(String policyCode, Long policyVersionId,
                                         int versionNo, String configDigestHex) {
    }

    private record ResolvedRule(RuleDraft draft, SupplierDO primarySupplier,
                                List<ResolvedCandidate> candidates, List<SupplierDO> scopeSuppliers) {
    }

    private record ResolvedCandidate(CandidateDraft draft, SupplierDO supplier) {
    }
}
