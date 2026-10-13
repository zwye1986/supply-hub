package com.aifuli.module.supplychain.routing.application;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferEligibilityService;
import com.aifuli.module.supplychain.supplieradapter.metadata.SupplierConnectorCapabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.StandardPricingQuoteService;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
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
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.InventorySourceDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierConnectorVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDeliveryRegionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSkuDailyQuotaDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.InventorySourceMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierDeliveryRegionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSkuDailyQuotaMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.ConnectorVersionStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.InventorySourceStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 读取已发布配置并构造不可变候选快照，最终选择只委托纯路由内核（PRD 六级范围模型）。
 *
 * <p>规则匹配按 PRD 六级范围逐级裁决，停用规则跳过；
 * 候选按 FIXED 主供应商 / AUTO+SPECIFIED 保序候选 / AUTO+ALL 动态解析三种来源装配；
 * STABLE/PRICE/COMPOSITE 三策略真实生效，评分（offer 人工录入稳定性/时效性）在本层读取后
 * 经 {@link RoutingCandidate} 快照注入引擎，domain 不依赖评分服务。
 * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#3-规则匹配}
 * 与 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#54-候选解析与三策略排序}。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
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
    private static final String CHECK_MODE_REQUIRED = "REQUIRED";
    private static final String CHECK_MODE_OPTIONAL = "OPTIONAL";
    private static final String INVENTORY_REMOTE_ON_DEMAND = "REMOTE_ON_DEMAND";
    private static final short CONNECTOR_ACTIVE = 10;
    private static final ObjectMapper CONFIG_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    /** 候选淘汰原因：供货价高于规则配置的最高可接受供货价。 */
    public static final String REJECT_PRICE_OVER_THRESHOLD = "PRICE_OVER_THRESHOLD";
    /** 候选淘汰原因：供应商与平台 SKU 的自然日累计供货配额已耗尽。 */
    public static final String REJECT_DAILY_QUOTA_EXCEEDED = "DAILY_QUOTA_EXCEEDED";
    /** 候选淘汰原因：供应商不覆盖订单收货行政区划。 */
    public static final String REJECT_DELIVERY_REGION_UNCOVERED = "DELIVERY_REGION_UNCOVERED";

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
    private final SupplierSkuDailyQuotaMapper dailyQuotaMapper;
    private final SupplierDeliveryRegionMapper deliveryRegionMapper;
    private final SupplyChainOrderMapper orderMapper;
    private final SupplierConnectorMapper connectorMapper;
    private final SupplierConnectorVersionMapper connectorVersionMapper;
    private final InventorySourceMapper inventorySourceMapper;
    private final SupplierOfferEligibilityService eligibilityService;
    private final SupplierConnectorCapabilityMapper capabilityMapper;

    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true,
            noRollbackFor = SupplyQualificationUnavailableException.class)
    public RoutingSnapshot route(RoutingInput input) {
        log.debug("[routeEvaluate][标准商品路由入口] buyerId={}, projectId={}, platformSkuId={}, quantity={}, businessTime={}",
                input.buyerId(), input.projectId(), input.platformSkuId(), input.quantity(), input.businessTime());
        if (input.quantity() != StandardPricingQuoteService.SUPPORTED_QUANTITY) {
            throw new IllegalArgumentException("M1 standard-product routing only accepts quantity one");
        }
        requireActiveSubject(input);
        SupplierRoutingPolicyDO policy = requireOnly(
                policyMapper.selectActiveByType(ROUTING_TYPE), "active standard-product routing policy",
                SupplyQualificationUnavailableException.Reason.NO_ACTIVE_POLICY);
        FactBoundary boundary = new FactBoundary(input.businessTime());
        for (SupplierRoutingPolicyVersionDO published : versionMapper.selectPublished(policy.getId())) {
            boundary.include(published.getValidFrom());
            boundary.include(published.getValidTo());
        }
        SupplierRoutingPolicyVersionDO version;
        MatchedRule matched;
        try {
            version = requireOnly(versionMapper.selectEffective(policy.getId(), input.businessTime()),
                    "effective published routing policy version",
                    SupplyQualificationUnavailableException.Reason.NO_EFFECTIVE_POLICY_VERSION);
            if (!Objects.equals(version.getPolicyId(), policy.getId())) {
                throw new IllegalStateException("Published routing version references a foreign policy");
            }
            boundary.include(version.getValidTo());
            matched = matchRule(version.getId(), input);
        } catch (SupplyQualificationUnavailableException missing) {
            throw new SupplyQualificationUnavailableException(missing.reason(), missing.getMessage(), boundary.next);
        }
        List<CandidateSpec> specs = candidateSpecs(matched.rule(), input);
        if (specs.isEmpty()) {
            if (ROUTE_MODE_AUTO.equals(matched.rule().getRouteMode())
                    && SCOPE_ALL.equals(matched.rule().getSupplierMode())) {
                throw new SupplyQualificationUnavailableException(
                        SupplyQualificationUnavailableException.Reason.NO_DYNAMIC_CANDIDATE,
                        "Matched routing rule has no dynamic candidates", boundary.next);
            }
            throw new IllegalStateException("Matched routing rule has no candidates: "
                    + matched.rule().getRuleCode());
        }
        List<RoutingCandidate> candidates = specs.stream()
                .map(spec -> resolveCandidate(spec, input, matched.rule().getMaxSupplyPriceCents(), boundary))
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
                version.getConfigDigest(), matched.rule().getId(), matched.rule().getRuleCode(), decision,
                boundary.next);
    }

    private static RoutingStrategy strategy(String name) {
        return RoutingStrategy.valueOf(name);
    }

    /**
     * 在启用规则中按 PRD 六级范围逐级匹配，同层再按配置优先级和编码裁决。
     * 供应商指定范围只限定候选池，不与尚未选定的供应商比较；停用规则不会阻断下一级。
     * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#3-规则匹配}。
     */
    private MatchedRule matchRule(Long policyVersionId, RoutingInput input) {
        List<StdRoutingRuleDO> rules = ruleMapper.selectByPolicyVersionId(policyVersionId);
        if (rules.isEmpty()) {
            throw new SupplyQualificationUnavailableException(
                    SupplyQualificationUnavailableException.Reason.NO_PUBLISHED_RULE,
                    "Routing requires at least one published rule, but found 0");
        }
        if (rules.stream().anyMatch(rule -> !Objects.equals(rule.getPolicyVersionId(), policyVersionId))) {
            throw new IllegalStateException("Published routing rule references a foreign policy version");
        }
        BuyerDO buyer = buyerMapper.selectById(input.buyerId());
        String buyerCode = buyer == null ? null : buyer.getBuyerCode();
        String projectCode = resolveProjectCode(input.projectId());
        String productTypeCode = resolveProductTypeCode(input.platformSkuId());

        List<StdRoutingRuleScopeDO> scopes = scopeMapper.selectByRuleIds(
                rules.stream().map(StdRoutingRuleDO::getId).toList());
        return new MatchedRule(selectMatchedRule(rules, scopes, buyerCode, projectCode, productTypeCode));
    }

    /**
     * 无数据库访问的已发布规则裁决核心；停用规则先跳过，再按层级、规则优先级和编码排序。
     * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#3-规则匹配}。
     */
    static StdRoutingRuleDO selectMatchedRule(List<StdRoutingRuleDO> rules,
                                              List<StdRoutingRuleScopeDO> scopeRows,
                                              String buyerCode, String projectCode,
                                              String productTypeCode) {
        Map<Long, List<StdRoutingRuleScopeDO>> scopesByRule = new java.util.HashMap<>();
        for (StdRoutingRuleScopeDO scope : scopeRows) {
            scopesByRule.computeIfAbsent(scope.getRuleId(), ignored -> new ArrayList<>()).add(scope);
        }

        List<RankedRule> matched = new ArrayList<>();
        for (StdRoutingRuleDO rule : rules) {
            if (rule.getStatus() == null || rule.getStatus() != RULE_ENABLED) {
                continue;
            }
            List<StdRoutingRuleScopeDO> scopes = scopesByRule.getOrDefault(rule.getId(), List.of());
            RoutingRuleScopeTier tier;
            try {
                tier = RoutingRuleScopeTier.require(rule.getDistributorMode(), rule.getProjectMode(),
                        rule.getProductTypeMode(), rule.getSupplierMode());
            } catch (IllegalArgumentException invalidScope) {
                throw new IllegalStateException("Published routing rule has unsupported scope: "
                        + rule.getRuleCode(), invalidScope);
            }
            if (!SCOPE_ALL.equals(rule.getDistributorMode())) {
                if (!containsCode(scopes, SCOPE_KIND_DISTRIBUTOR, buyerCode)) {
                    continue;
                }
            }
            if (!SCOPE_ALL.equals(rule.getProjectMode())) {
                if (!containsCode(scopes, SCOPE_KIND_PROJECT, projectCode)) {
                    continue;
                }
            }
            if (!SCOPE_ALL.equals(rule.getProductTypeMode())) {
                if (!containsCode(scopes, SCOPE_KIND_PRODUCT_TYPE, productTypeCode)) {
                    continue;
                }
            }
            matched.add(new RankedRule(rule, tier));
        }
        if (matched.isEmpty()) {
            throw new SupplyQualificationUnavailableException(
                    SupplyQualificationUnavailableException.Reason.NO_MATCHED_RULE,
                    "Routing requires a matched enabled rule or the global default rule");
        }
        matched.sort(Comparator
                .comparingInt((RankedRule item) -> item.tier().ordinal())
                .thenComparingInt(item -> item.rule().getRulePriority() == null ? 0 : item.rule().getRulePriority())
                .thenComparing(item -> item.rule().getRuleCode()));
        RankedRule best = matched.getFirst();
        if (matched.size() > 1) {
            RankedRule second = matched.get(1);
            if (best.tier() == second.tier()
                    && Objects.equals(best.rule().getRulePriority(), second.rule().getRulePriority())
                    && best.rule().getRuleCode().compareTo(second.rule().getRuleCode()) == 0) {
                throw new IllegalStateException("Routing matched conflicting rules: "
                        + best.rule().getRuleCode());
            }
        }
        return best.rule();
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
            throw new IllegalStateException("Routing SKU references a missing SPU identity");
        }
        PlatformSpuDO spu = spuMapper.selectById(sku.getSpuId());
        if (spu == null) {
            throw new IllegalStateException("Routing SKU references a missing SPU");
        }
        return spu.getProductType();
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
        if (!ROUTE_MODE_AUTO.equals(rule.getRouteMode())) {
            throw new IllegalStateException("Published routing rule has unsupported route mode");
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
                                               Long maxSupplyPriceCents, FactBoundary boundary) {
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
                .map(offer -> resolveOffer(offer, supplier, input, boundary))
                .filter(item -> item != null)
                .toList();
        if (effectiveOffers.isEmpty()) {
            structuralReasons.add("NO_EFFECTIVE_OFFER");
        } else if (effectiveOffers.size() > 1) {
            structuralReasons.add("AMBIGUOUS_EFFECTIVE_OFFER");
        }
        ResolvedOffer resolved = effectiveOffers.size() == 1 ? effectiveOffers.getFirst() : null;
        if (exceedsDailyQuota(supplier.getId(), input, boundary) && structuralReasons.isEmpty()) {
            structuralReasons.add(REJECT_DAILY_QUOTA_EXCEEDED);
        }
        if (input.deliveryAdcode() != null && !SupplierDeliveryRegionCoverage.covers(
                supplier.getDeliveryRegionType(), deliveryRegionMapper.selectBySupplierId(supplier.getId())
                        .stream().map(SupplierDeliveryRegionDO::getAdcode).toList(), input.deliveryAdcode())) {
            structuralReasons.add(REJECT_DELIVERY_REGION_UNCOVERED);
        }
        Eligibility eligibility = evaluateEligibility(configured, input, supplier.getSupplierCode(),
                resolved == null ? null : resolved.version().getSupplierSettlementUnitPrice(),
                maxSupplyPriceCents, structuralReasons);
        RoutingInput.CandidateFacts facts = input.factsFor(supplier.getSupplierCode());
        boundary.includeFact(configured.inventoryCheckMode(), configured.inventoryMaxAgeSeconds(), facts.inventory());
        boundary.includeFact(configured.balanceCheckMode(), configured.balanceMaxAgeSeconds(), facts.balance());
        boundary.includeFact(configured.healthCheckMode(), configured.healthMaxAgeSeconds(), facts.health());
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

    /**
     * 基于订单事实检查自然日累计配额。生产受理方必须已持有平台 SKU 锁，直到所选订单插入提交；
     * 失败/取消的订单退出统计，重试可重新使用释放的额度。
     * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#4-每日累计配额与并发}。
     */
    private boolean exceedsDailyQuota(Long supplierId, RoutingInput input, FactBoundary boundary) {
        SupplierSkuDailyQuotaDO quota = dailyQuotaMapper.selectBySupplierAndSku(
                supplierId, input.platformSkuId());
        if (quota == null) {
            return false;
        }
        LocalDateTime start = input.businessTime().toLocalDate().atStartOfDay();
        boundary.include(start.plusDays(1));
        Long reserved = orderMapper.sumReservedSupplierSkuQuantity(
                SupplyChainOrderTypes.ORDER_TYPE_CARD, supplierId, input.platformSkuId(),
                start, start.plusDays(1), SupplyChainOrderStatus.FAILED.getCode(),
                SupplyChainOrderStatus.CANCELLED.getCode());
        long used = reserved == null ? 0L : reserved;
        boolean exceeded = used > quota.getDailyLimit() - input.quantity();
        if (exceeded) {
            log.debug("[routeQuota][供应商SKU自然日配额不足] supplierId={}, platformSkuId={}, day={}, used={}, quantity={}, dailyLimit={}",
                    supplierId, input.platformSkuId(), start.toLocalDate(), used,
                    input.quantity(), quota.getDailyLimit());
        }
        return exceeded;
    }

    /** 已发布指针先验真，正常停用／过期才过滤；不能把配置损坏伪装成缺供给。见设计 §7.1。 */
    private ResolvedOffer resolveOffer(SupplierOfferDO offer, SupplierDO supplier, RoutingInput input,
                                       FactBoundary boundary) {
        if (offer.getStatus() != OfferStatus.PUBLISHED.getCode()) {
            return null;
        }
        if (!Objects.equals(offer.getSupplierId(), supplier.getId())
                || !Objects.equals(offer.getPlatformSkuId(), input.platformSkuId())
) {
            throw new IllegalStateException("Published offer has broken ownership or version pointer");
        }
        if (offer.getCurrentVersionId() == null) {
            return null;
        }
        SupplierOfferVersionDO version = offerVersionMapper.selectById(offer.getCurrentVersionId());
        if (version == null || !offer.getId().equals(version.getOfferId())) {
            throw new IllegalStateException("Published offer references a missing or foreign version");
        }
        SupplierConnectorVersionDO connectorVersion =
                connectorVersionMapper.selectById(version.getConnectorVersionId());
        SupplierConnectorDO connector = connectorVersion == null
                ? null : connectorMapper.selectById(connectorVersion.getConnectorId());
        InventorySourceDO inventory = inventorySourceMapper.selectById(version.getInventorySourceId());
        if (connectorVersion == null || connector == null || inventory == null
                || !Objects.equals(connector.getSupplierId(), supplier.getId())
                || !Objects.equals(inventory.getSupplierId(), supplier.getId())
                || !Objects.equals(inventory.getPlatformSkuId(), input.platformSkuId())) {
            throw new IllegalStateException("Published offer references missing or foreign supply dependencies");
        }
        requireObjectJson(version.getFulfillmentConfigJson(), "offer fulfillment");
        requireObjectJson(connectorVersion.getAdapterConfigJson(), "connector adapter");
        if (connector.getStatus() == CONNECTOR_ACTIVE) {
            SupplierConnectorVersionDO current = connector.getCurrentVersionId() == null ? null
                    : connectorVersionMapper.selectById(connector.getCurrentVersionId());
            if (current == null || !Objects.equals(current.getConnectorId(), connector.getId())) {
                throw new IllegalStateException("Active connector references a missing or foreign current version");
            }
        }
        boundary.include(version.getValidFrom());
        boundary.include(version.getValidTo());
        PlatformSkuDO sku = skuMapper.selectById(input.platformSkuId());
        PlatformSpuDO spu = sku == null ? null : spuMapper.selectById(sku.getSpuId());
        if (!eligibilityService.isStructurallyEligible(offer, supplier, version, connector, connectorVersion,
                inventory, sku, spu, capabilityMapper.selectByConnectorVersionId(connectorVersion.getId()),
                input.businessTime()) || !SupplierOfferEligibilityService.isQuantityEligible(version, input.quantity())) {
            return null;
        }
        return new ResolvedOffer(offer, version);
    }

    private static void requireObjectJson(String json, String description) {
        try {
            var node = json == null ? null : CONFIG_JSON.readTree(json);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("Published " + description + " configuration is not a JSON object");
            }
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Published " + description + " configuration JSON is invalid", failure);
        }
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
            if (maxAgeSeconds != null) {
                throw new IllegalStateException("Published unsupported fact check has a maximum age");
            }
            return new CheckResult("NOT_CHECKED", null, false);
        }
        if ((!CHECK_MODE_REQUIRED.equals(mode) && !CHECK_MODE_OPTIONAL.equals(mode))
                || maxAgeSeconds == null || maxAgeSeconds <= 0) {
            throw new IllegalStateException("Published routing candidate has invalid fact check mode");
        }
        boolean required = CHECK_MODE_REQUIRED.equals(mode);
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
            throw new SupplyQualificationUnavailableException(
                    SupplyQualificationUnavailableException.Reason.SUBJECT_NOT_ACTIVE_OR_EXACT,
                    "Routing buyer or platform SKU is not active and exact");
        }
        if (input.projectId() != null) {
            BuyerProjectDO project = projectMapper.selectById(input.projectId());
            if (project == null || !Objects.equals(project.getBuyerId(), input.buyerId())) {
                throw new IllegalStateException("Routing project is missing or belongs to another buyer");
            }
            if (project.getStatus() != BuyerProjectStatus.ACTIVE.getCode()) {
                throw new SupplyQualificationUnavailableException(
                        SupplyQualificationUnavailableException.Reason.SUBJECT_NOT_ACTIVE_OR_EXACT,
                        "Routing project is not active");
            }
        }
    }

    private static boolean effectiveAt(LocalDateTime from, LocalDateTime to, LocalDateTime at) {
        return !from.isAfter(at) && (to == null || to.isAfter(at));
    }

    private static <T> T requireOnly(List<T> values, String description,
                                     SupplyQualificationUnavailableException.Reason missingReason) {
        if (values.isEmpty()) {
            throw new SupplyQualificationUnavailableException(missingReason,
                    "Routing requires exactly one " + description + ", but found 0");
        }
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
                                   RoutingDecision decision, LocalDateTime nextFactBoundary) {
        public RoutingSnapshot(Long policyId, Long policyVersionId, int policyVersionNo,
                               byte[] configDigest, Long ruleId, String ruleCode, RoutingDecision decision) {
            this(policyId, policyVersionId, policyVersionNo, configDigest, ruleId, ruleCode, decision, null);
        }
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

    private record RankedRule(StdRoutingRuleDO rule, RoutingRuleScopeTier tier) {
    }

    private record ResolvedOffer(SupplierOfferDO offer, SupplierOfferVersionDO version) {
    }

    private record Eligibility(List<String> reasons, String availabilityClass,
                               String inventoryStatus, String balanceStatus, String healthStatus,
                               LocalDateTime latestFactAt) {
    }

    private record CheckResult(String snapshotStatus, String rejectionReason, boolean unknown) {
    }

    /** 每次路由独占的未来事实边界收集器，包含未选中候选，避免状态改变前投影误续期。 */
    private static final class FactBoundary {
        private final LocalDateTime at;
        private LocalDateTime next;

        private FactBoundary(LocalDateTime at) { this.at = at; }

        private void include(LocalDateTime value) {
            if (value != null && value.isAfter(at) && (next == null || value.isBefore(next))) {
                next = value;
            }
        }

        private void includeFact(String mode, Integer maxAge, RoutingInput.Fact fact) {
            if (!CHECK_MODE_UNSUPPORTED.equals(mode) && fact != null) {
                include(fact.validUntil());
                include(fact.observedAt().plusSeconds(maxAge));
                include(fact.observedAt());
            }
        }
    }
}
