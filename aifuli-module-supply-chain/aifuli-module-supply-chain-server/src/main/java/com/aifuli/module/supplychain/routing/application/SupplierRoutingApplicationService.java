package com.aifuli.module.supplychain.routing.application;

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
import com.aifuli.module.supplychain.routing.domain.RoutingCandidate;
import com.aifuli.module.supplychain.routing.domain.RoutingDecision;
import com.aifuli.module.supplychain.routing.domain.RoutingInput;
import com.aifuli.module.supplychain.routing.domain.RoutingSortConfig;
import com.aifuli.module.supplychain.routing.domain.RoutingStrategy;
import com.aifuli.module.supplychain.routing.domain.RoutingType;
import com.aifuli.module.supplychain.routing.domain.SupplierRoutingEngine;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.InventorySourceDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.InventorySourceMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.InventorySourceStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 读取已发布配置并构造不可变候选快照，最终选择只委托纯路由内核（PRD 四级范围模型）。
 *
 * <p>规则匹配按「分销商/项目/商品类型」三级范围 + 特异性得分裁决，未命中回落受保护全局默认规则；
 * 候选按 FIXED 主供应商 / AUTO+SPECIFIED 保序候选 / AUTO+ALL 动态解析三种来源装配；
 * STABLE/PRICE/COMPOSITE 三策略真实生效，评分（offer 人工录入稳定性/时效性）在本层读取后
 * 经 {@link RoutingCandidate} 快照注入引擎，domain 不依赖评分服务。
 * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#53 与 #54}。</p>
 */
@Service
@RequiredArgsConstructor
public class SupplierRoutingApplicationService {

    private static final String ROUTING_TYPE = RoutingType.STANDARD_PRODUCT.code();
    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final short RULE_ENABLED = 10;
    private static final String SCOPE_ALL = "ALL";
    private static final String SCOPE_KIND_DISTRIBUTOR = "DISTRIBUTOR";
    private static final String SCOPE_KIND_PROJECT = "PROJECT";
    private static final String SCOPE_KIND_PRODUCT_TYPE = "PRODUCT_TYPE";
    private static final String ROUTE_MODE_FIXED = "FIXED";
    private static final String ROUTE_MODE_AUTO = "AUTO";
    private static final String CHECK_MODE_UNSUPPORTED = "UNSUPPORTED";
    private static final String PROTECTED_RULE_CODE = "GLOBAL_DEFAULT";
    /** 候选淘汰原因：供货价高于规则配置的最高可接受供货价。 */
    public static final String REJECT_PRICE_OVER_THRESHOLD = "PRICE_OVER_THRESHOLD";

    private final SupplierRoutingPolicyMapper policyMapper;
    private final SupplierRoutingPolicyVersionMapper versionMapper;
    private final StdRoutingRuleMapper ruleMapper;
    private final StdRoutingRuleScopeMapper scopeMapper;
    private final StdRoutingCandidateMapper candidateMapper;
    private final BuyerMapper buyerMapper;
    private final BuyerProjectMapper projectMapper;
    private final PlatformSkuMapper skuMapper;
    private final PlatformSpuMapper spuMapper;
    private final SupplierMapper supplierMapper;
    private final SupplierOfferMapper offerMapper;
    private final SupplierOfferVersionMapper offerVersionMapper;
    private final SupplierConnectorMapper connectorMapper;
    private final SupplierConnectorVersionMapper connectorVersionMapper;
    private final InventorySourceMapper inventorySourceMapper;

    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public RoutingSnapshot route(RoutingInput input) {
        if (input.quantity() != 1) {
            throw new IllegalArgumentException("M1 standard-product routing only accepts quantity one");
        }
        requireActiveSubject(input);
        SupplierRoutingPolicyDO policy = requireOnly(
                policyMapper.selectActiveByType(ROUTING_TYPE), "active standard-product routing policy");
        SupplierRoutingPolicyVersionDO version = requireOnly(
                versionMapper.selectEffective(policy.getId(), input.businessTime()),
                "effective published routing policy version");
        MatchedRule matched = matchRule(version.getId(), input);
        List<CandidateSpec> specs = candidateSpecs(matched.rule(), input);
        if (specs.isEmpty()) {
            throw new IllegalStateException("Matched routing rule has no candidates: "
                    + matched.rule().getRuleCode());
        }
        List<RoutingCandidate> candidates = specs.stream()
                .map(spec -> resolveCandidate(spec, input, matched.rule().getMaxSupplyPriceCents()))
                .toList();
        RoutingSortConfig sortConfig = new RoutingSortConfig(
                strategy(matched.rule().getStrategyPriority1()),
                strategy(matched.rule().getStrategyPriority2()),
                strategy(matched.rule().getStrategyPriority3()),
                policy.getStabilityWeight() == null ? RoutingRuleSaveSupport.DEFAULT_WEIGHT_STABILITY
                        : policy.getStabilityWeight(),
                policy.getPriceWeight() == null ? RoutingRuleSaveSupport.DEFAULT_WEIGHT_PRICE
                        : policy.getPriceWeight(),
                policy.getTimelinessWeight() == null ? RoutingRuleSaveSupport.DEFAULT_WEIGHT_TIMELINESS
                        : policy.getTimelinessWeight());
        RoutingDecision decision = new SupplierRoutingEngine().decide(input, candidates, sortConfig);
        return new RoutingSnapshot(policy.getId(), version.getId(), version.getVersionNo(),
                version.getConfigDigest(), matched.rule().getId(), matched.rule().getRuleCode(), decision);
    }

    private static RoutingStrategy strategy(String name) {
        return RoutingStrategy.valueOf(name);
    }

    /**
     * 四级范围匹配：分销商/项目/商品类型三级命中 → 特异性得分（SPECIFIED 维度数）→
     * rulePriority → ruleCode 裁决；未命中回落受保护全局默认规则。
     */
    private MatchedRule matchRule(Long policyVersionId, RoutingInput input) {
        List<StdRoutingRuleDO> rules = ruleMapper.selectByPolicyVersionId(policyVersionId).stream()
                .filter(rule -> rule.getStatus() != null && rule.getStatus() == RULE_ENABLED)
                .toList();
        if (rules.isEmpty()) {
            throw new IllegalStateException("Routing requires at least one enabled rule, but found 0");
        }
        BuyerDO buyer = buyerMapper.selectById(input.buyerId());
        String buyerCode = buyer == null ? null : buyer.getBuyerCode();
        String projectCode = resolveProjectCode(input.projectId());
        String productTypeCode = resolveProductTypeCode(input.platformSkuId());

        Map<Long, List<StdRoutingRuleScopeDO>> scopesByRule = new java.util.HashMap<>();
        for (StdRoutingRuleScopeDO scope : scopeMapper.selectByRuleIds(
                rules.stream().map(StdRoutingRuleDO::getId).toList())) {
            scopesByRule.computeIfAbsent(scope.getRuleId(), ignored -> new ArrayList<>()).add(scope);
        }

        List<ScoredRule> scored = new ArrayList<>();
        StdRoutingRuleDO fallback = null;
        for (StdRoutingRuleDO rule : rules) {
            List<StdRoutingRuleScopeDO> scopes = scopesByRule.getOrDefault(rule.getId(), List.of());
            if (Boolean.TRUE.equals(rule.getProtectedRule()) && PROTECTED_RULE_CODE.equals(rule.getRuleCode())) {
                fallback = rule;
                continue;
            }
            int specificity = 0;
            if (!SCOPE_ALL.equals(rule.getDistributorMode())) {
                if (!containsCode(scopes, SCOPE_KIND_DISTRIBUTOR, buyerCode)) {
                    continue;
                }
                specificity++;
            }
            if (!SCOPE_ALL.equals(rule.getProjectMode())) {
                if (!containsCode(scopes, SCOPE_KIND_PROJECT, projectCode)) {
                    continue;
                }
                specificity++;
            }
            if (!SCOPE_ALL.equals(rule.getProductTypeMode())) {
                if (!containsCode(scopes, SCOPE_KIND_PRODUCT_TYPE, productTypeCode)) {
                    continue;
                }
                specificity++;
            }
            scored.add(new ScoredRule(rule, specificity));
        }
        if (scored.isEmpty()) {
            if (fallback != null) {
                return new MatchedRule(fallback);
            }
            throw new IllegalStateException("Routing requires a matched rule or the global default rule");
        }
        scored.sort(Comparator
                .comparingInt((ScoredRule item) -> -item.specificity())
                .thenComparingInt(item -> item.rule().getRulePriority() == null ? 0 : item.rule().getRulePriority())
                .thenComparing(item -> item.rule().getRuleCode()));
        ScoredRule best = scored.getFirst();
        if (scored.size() > 1) {
            ScoredRule second = scored.get(1);
            if (best.specificity() == second.specificity()
                    && Objects.equals(best.rule().getRulePriority(), second.rule().getRulePriority())
                    && best.rule().getRuleCode().compareTo(second.rule().getRuleCode()) == 0) {
                throw new IllegalStateException("Routing matched conflicting rules: "
                        + best.rule().getRuleCode());
            }
        }
        return new MatchedRule(best.rule());
    }

    private static boolean containsCode(List<StdRoutingRuleScopeDO> scopes, String kind, String code) {
        if (code == null) {
            return false;
        }
        for (StdRoutingRuleScopeDO scope : scopes) {
            if (kind.equals(scope.getScopeKind()) && code.equals(scope.getScopeCode())) {
                return true;
            }
        }
        return false;
    }

    private String resolveProjectCode(Long projectId) {
        if (projectId == null) {
            return null;
        }
        BuyerProjectDO project = projectMapper.selectById(projectId);
        return project == null ? null : project.getProjectCode();
    }

    /** 商品类型判定键：平台 SKU → SPU → sc_product_type.type_code（设计 §4.6）。 */
    private String resolveProductTypeCode(Long platformSkuId) {
        PlatformSkuDO sku = skuMapper.selectById(platformSkuId);
        if (sku == null || sku.getSpuId() == null) {
            return null;
        }
        PlatformSpuDO spu = spuMapper.selectById(sku.getSpuId());
        return spu == null ? null : spu.getProductType();
    }

    /** 候选来源：FIXED 主供应商 / AUTO+SPECIFIED 保序候选 / AUTO+ALL 按 SKU 有效 offer 动态解析。 */
    private List<CandidateSpec> candidateSpecs(StdRoutingRuleDO rule, RoutingInput input) {
        if (ROUTE_MODE_FIXED.equals(rule.getRouteMode())) {
            if (rule.getPrimarySupplierId() == null) {
                return List.of();
            }
            return List.of(new CandidateSpec(rule.getPrimarySupplierId(), 0,
                    CHECK_MODE_UNSUPPORTED, null, CHECK_MODE_UNSUPPORTED, null, CHECK_MODE_UNSUPPORTED, null));
        }
        if (SCOPE_ALL.equals(rule.getSupplierMode())) {
            Set<Long> supplierIds = new LinkedHashSet<>();
            for (SupplierOfferDO offer : offerMapper.selectPublishedBySku(input.platformSkuId())) {
                if (offer.getSupplierId() != null) {
                    supplierIds.add(offer.getSupplierId());
                }
            }
            List<CandidateSpec> specs = new ArrayList<>(supplierIds.size());
            for (Long supplierId : supplierIds) {
                specs.add(new CandidateSpec(supplierId, 0,
                        CHECK_MODE_UNSUPPORTED, null, CHECK_MODE_UNSUPPORTED, null,
                        CHECK_MODE_UNSUPPORTED, null));
            }
            return specs;
        }
        List<StdRoutingCandidateDO> configured = candidateMapper.selectByRuleId(rule.getId());
        List<CandidateSpec> specs = new ArrayList<>(configured.size());
        for (StdRoutingCandidateDO candidate : configured) {
            specs.add(new CandidateSpec(candidate.getSupplierId(), candidate.getCandidatePriority(),
                    candidate.getInventoryCheckMode(), candidate.getInventoryMaxAgeSeconds(),
                    candidate.getBalanceCheckMode(), candidate.getBalanceMaxAgeSeconds(),
                    candidate.getHealthCheckMode(), candidate.getHealthMaxAgeSeconds()));
        }
        return specs;
    }

    private RoutingCandidate resolveCandidate(CandidateSpec configured, RoutingInput input,
                                              Long maxSupplyPriceCents) {
        SupplierDO supplier = supplierMapper.selectById(configured.supplierId());
        if (supplier == null) {
            throw new IllegalStateException("Published routing candidate references a missing supplier");
        }
        List<String> structuralReasons = new ArrayList<>();
        if (supplier.getStatus() != SupplierStatus.ACTIVE.getCode()) {
            structuralReasons.add("SUPPLIER_NOT_ACTIVE");
        }
        List<ResolvedOffer> effectiveOffers = offerMapper
                .selectPublishedBySupplierAndSku(supplier.getId(), input.platformSkuId()).stream()
                .map(offer -> resolveOffer(offer, supplier, input))
                .filter(item -> item != null)
                .toList();
        if (effectiveOffers.isEmpty()) {
            structuralReasons.add("NO_EFFECTIVE_OFFER");
        } else if (effectiveOffers.size() > 1) {
            structuralReasons.add("AMBIGUOUS_EFFECTIVE_OFFER");
        }
        ResolvedOffer resolved = effectiveOffers.size() == 1 ? effectiveOffers.getFirst() : null;
        Eligibility eligibility = evaluateEligibility(configured, input, supplier.getSupplierCode(),
                resolved == null ? null : resolved.version().getSupplierSettlementUnitPrice(),
                maxSupplyPriceCents, structuralReasons);
        return new RoutingCandidate(configured.candidatePriority(), supplier.getId(),
                supplier.getSupplierCode(), supplier.getSupplierName(),
                resolved == null ? null : resolved.offer().getId(),
                resolved == null ? null : resolved.version().getId(),
                resolved == null ? null : resolved.version().getConnectorVersionId(),
                resolved == null ? null : resolved.version().getInventorySourceId(),
                resolved == null ? null : resolved.version().getSupplierSettlementUnitPrice(),
                resolved == null ? null : resolved.version().getCurrency(),
                resolved == null ? null : resolved.offer().getStabilityScore(),
                resolved == null ? null : resolved.offer().getTimelinessScore(),
                eligibility.reasons().isEmpty(), eligibility.availabilityClass(), eligibility.reasons(),
                eligibility.inventoryStatus(), eligibility.balanceStatus(), eligibility.healthStatus(),
                eligibility.latestFactAt());
    }

    private ResolvedOffer resolveOffer(SupplierOfferDO offer, SupplierDO supplier, RoutingInput input) {
        if (offer.getStatus() != OfferStatus.PUBLISHED.getCode() || offer.getCurrentVersionId() == null) {
            return null;
        }
        SupplierOfferVersionDO version = offerVersionMapper.selectById(offer.getCurrentVersionId());
        if (version == null || !offer.getId().equals(version.getOfferId())
                || !effectiveAt(version.getValidFrom(), version.getValidTo(), input.businessTime())
                || input.quantity() < version.getMinQuantity()
                || input.quantity() > version.getMaxQuantity()
                || (input.quantity() - version.getMinQuantity()) % version.getQuantityStep() != 0
                || !"CNY".equals(version.getCurrency())) {
            return null;
        }
        SupplierConnectorVersionDO connectorVersion =
                connectorVersionMapper.selectById(version.getConnectorVersionId());
        SupplierConnectorDO connector = connectorVersion == null
                ? null : connectorMapper.selectById(connectorVersion.getConnectorId());
        InventorySourceDO inventory = inventorySourceMapper.selectById(version.getInventorySourceId());
        if (connectorVersion == null || connector == null || inventory == null
                || connectorVersion.getStatus() != ConnectorVersionStatus.PUBLISHED.getCode()
                || connector.getStatus() != 10
                || !connectorVersion.getId().equals(connector.getCurrentVersionId())
                || !connector.getSupplierId().equals(supplier.getId())
                || inventory.getStatus() != InventorySourceStatus.ACTIVE.getCode()
                || !inventory.getSupplierId().equals(supplier.getId())
                || !inventory.getPlatformSkuId().equals(input.platformSkuId())
                || !"REMOTE_ON_DEMAND".equals(inventory.getSourceType())) {
            return null;
        }
        return new ResolvedOffer(offer, version);
    }

    private static Eligibility evaluateEligibility(CandidateSpec configured,
                                                   RoutingInput input, String supplierCode,
                                                   Long unitPrice, Long maxSupplyPriceCents,
                                                   List<String> structuralReasons) {
        List<String> reasons = new ArrayList<>(structuralReasons);
        if (maxSupplyPriceCents != null && unitPrice != null && unitPrice > maxSupplyPriceCents) {
            reasons.add(REJECT_PRICE_OVER_THRESHOLD);
        }
        RoutingInput.CandidateFacts facts = input.factsFor(supplierCode);
        CheckResult inventory = evaluateFact("INVENTORY", configured.inventoryCheckMode(),
                configured.inventoryMaxAgeSeconds(), facts.inventory(), input.businessTime(), null);
        Long requiredBalance = unitPrice == null ? null : Math.multiplyExact(unitPrice, input.quantity());
        CheckResult balance = evaluateFact("BALANCE", configured.balanceCheckMode(),
                configured.balanceMaxAgeSeconds(), facts.balance(), input.businessTime(), requiredBalance);
        CheckResult health = evaluateFact("HEALTH", configured.healthCheckMode(),
                configured.healthMaxAgeSeconds(), facts.health(), input.businessTime(), null);
        Stream.of(inventory, balance, health)
                .map(CheckResult::rejectionReason).filter(Objects::nonNull)
                .forEach(reasons::add);
        boolean unknown = Stream.of(inventory, balance, health)
                .anyMatch(CheckResult::unknown);
        String availability = reasons.isEmpty() ? (unknown ? "UNKNOWN" : "CONFIRMED") : "UNAVAILABLE";
        return new Eligibility(List.copyOf(reasons), availability,
                inventory.snapshotStatus(), balance.snapshotStatus(), health.snapshotStatus(),
                latestFactAt(facts));
    }

    private static CheckResult evaluateFact(String factName, String mode, Integer maxAgeSeconds,
                                            RoutingInput.Fact fact, LocalDateTime businessTime,
                                            Long requiredAmount) {
        if (CHECK_MODE_UNSUPPORTED.equals(mode)) {
            return new CheckResult("NOT_CHECKED", null, false);
        }
        boolean required = "REQUIRED".equals(mode);
        if (fact == null) {
            return new CheckResult("NOT_CAPTURED",
                    required ? factName + "_FACT_REQUIRED" : null, !required);
        }
        boolean fresh = !fact.observedAt().isAfter(businessTime)
                && !fact.observedAt().isBefore(businessTime.minusSeconds(maxAgeSeconds))
                && fact.validUntil().isAfter(businessTime);
        if (!fresh) {
            return new CheckResult("STALE", required ? factName + "_FACT_STALE" : null, !required);
        }
        if (fact.status() == RoutingInput.FactStatus.AVAILABLE) {
            if (requiredAmount != null && (fact.amount() == null || fact.amount() < requiredAmount)) {
                return new CheckResult("INSUFFICIENT", factName + "_INSUFFICIENT", false);
            }
            return new CheckResult("AVAILABLE", null, false);
        }
        if (fact.status() == RoutingInput.FactStatus.UNKNOWN) {
            return new CheckResult("UNKNOWN", required ? factName + "_FACT_UNKNOWN" : null, !required);
        }
        return new CheckResult(fact.status().name(), factName + "_" + fact.status().name(), false);
    }

    private void requireActiveSubject(RoutingInput input) {
        BuyerDO buyer = buyerMapper.selectById(input.buyerId());
        PlatformSkuDO sku = skuMapper.selectById(input.platformSkuId());
        if (buyer == null || buyer.getStatus() != BuyerStatus.ACTIVE.getCode()
                || sku == null || sku.getStatus() != CatalogEntityStatus.ACTIVE.getCode()
                || !input.fulfillmentType().equals(sku.getFulfillmentType())) {
            throw new IllegalArgumentException("Routing buyer or platform SKU is not active and exact");
        }
    }

    private static boolean effectiveAt(LocalDateTime from, LocalDateTime to, LocalDateTime at) {
        return !from.isAfter(at) && (to == null || to.isAfter(at));
    }

    private static <T> T requireOnly(List<T> values, String description) {
        if (values.size() != 1) {
            throw new IllegalStateException("Routing requires exactly one " + description
                    + ", but found " + values.size());
        }
        return values.getFirst();
    }

    private static LocalDateTime latestFactAt(RoutingInput.CandidateFacts facts) {
        return Stream.of(facts.inventory(), facts.balance(), facts.health())
                .filter(Objects::nonNull)
                .map(RoutingInput.Fact::observedAt)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    public record RoutingSnapshot(Long policyId, Long policyVersionId, int policyVersionNo,
                                  byte[] configDigest, Long ruleId, String ruleCode,
                                  RoutingDecision decision) {
        public RoutingSnapshot {
            configDigest = Arrays.copyOf(configDigest, configDigest.length);
        }

        @Override
        public byte[] configDigest() {
            return Arrays.copyOf(configDigest, configDigest.length);
        }
    }

    private record CandidateSpec(Long supplierId, int candidatePriority,
                                 String inventoryCheckMode, Integer inventoryMaxAgeSeconds,
                                 String balanceCheckMode, Integer balanceMaxAgeSeconds,
                                 String healthCheckMode, Integer healthMaxAgeSeconds) {
    }

    private record MatchedRule(StdRoutingRuleDO rule) {
    }

    private record ScoredRule(StdRoutingRuleDO rule, int specificity) {
    }

    private record ResolvedOffer(SupplierOfferDO offer, SupplierOfferVersionDO version) {
    }

    private record Eligibility(List<String> reasons, String availabilityClass,
                               String inventoryStatus, String balanceStatus, String healthStatus,
                               LocalDateTime latestFactAt) {
    }

    private record CheckResult(String snapshotStatus, String rejectionReason, boolean unknown) {
    }
}
