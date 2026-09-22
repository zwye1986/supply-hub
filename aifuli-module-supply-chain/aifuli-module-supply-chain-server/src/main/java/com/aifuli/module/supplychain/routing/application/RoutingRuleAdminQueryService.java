package com.aifuli.module.supplychain.routing.application;

import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
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
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ORDER_QUERY_TIME_RANGE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_NOT_FOUND;

/**
 * 管理端路由规则只读投影。只读当前启用标准策略的生效已发布版本，不改选供、不落权重。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-readonly-design.md#4-分层}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingRuleAdminQueryService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final WeightView DISPLAY_WEIGHTS = new WeightView(40, 30, 30);
    private static final List<PriorityView> DISPLAY_PRIORITIES = List.of(
            new PriorityView(RoutingRuleStrategy.STABLE.name(), RoutingRuleStrategy.STABLE),
            new PriorityView(RoutingRuleStrategy.PRICE.name(), RoutingRuleStrategy.PRICE),
            new PriorityView(RoutingRuleStrategy.COMPOSITE.name(), RoutingRuleStrategy.COMPOSITE));

    private final SupplyChainDatabaseClock clock;
    private final SupplierRoutingPolicyMapper policyMapper;
    private final SupplierRoutingPolicyVersionMapper versionMapper;
    private final StandardProductRoutingRuleMapper ruleMapper;
    private final StandardProductRoutingCandidateMapper candidateMapper;
    private final BuyerMapper buyerMapper;
    private final PlatformSkuMapper skuMapper;
    private final SupplierMapper supplierMapper;

    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public PageResult<RoutingRuleView> page(RoutingRulePageQuery query) {
        validateTimeRange(query.updateFrom(), query.updateTo());
        log.info("分页查询路由规则 name={} distributorCodes={} supplierCodes={} status={} "
                        + "updaters={} updateFrom={} updateTo={} pageNo={} pageSize={}",
                query.name(), query.distributorCodes(), query.supplierCodes(), query.status(),
                query.updaters(), query.updateFrom(), query.updateTo(), query.pageNo(), query.pageSize());
        EffectiveScope scope = loadEffectiveScope();
        if (scope == null) {
            return emptyPage();
        }
        List<StandardProductRoutingRuleDO> rules = new ArrayList<>(
                ruleMapper.selectByPolicyVersionId(scope.version().getId()));
        rules.sort(Comparator.comparing(StandardProductRoutingRuleDO::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(StandardProductRoutingRuleDO::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        Map<Long, List<StandardProductRoutingCandidateDO>> candidates = loadCandidates(rules);
        Map<Long, BuyerDO> buyers = loadBuyers(rules);
        Map<Long, PlatformSkuDO> skus = loadSkus(rules);
        Map<Long, SupplierDO> suppliers = loadSuppliers(candidates);
        List<RoutingRuleView> views = new ArrayList<>();
        for (StandardProductRoutingRuleDO rule : rules) {
            RoutingRuleView view = toView(rule, candidates.getOrDefault(rule.getId(), List.of()),
                    buyers, skus, suppliers);
            if (matches(view, rule, query)) {
                views.add(view);
            }
        }
        int pageNo = query.pageNo() == null ? DEFAULT_PAGE_NO : query.pageNo();
        int pageSize = query.pageSize() == null ? DEFAULT_PAGE_SIZE : query.pageSize();
        int from = Math.max(0, (pageNo - 1) * pageSize);
        int to = Math.min(views.size(), from + pageSize);
        List<RoutingRuleView> page = from >= views.size() ? List.of() : views.subList(from, to);
        return new PageResult<>(List.copyOf(page), (long) views.size());
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public RoutingRuleView get(String ruleCode) {
        log.info("查询路由规则详情 ruleCode={}", ruleCode);
        EffectiveScope scope = loadEffectiveScope();
        if (scope == null) {
            throw exception(ROUTING_RULE_NOT_FOUND);
        }
        StandardProductRoutingRuleDO rule = ruleMapper.selectOne(new LambdaQueryWrapper<StandardProductRoutingRuleDO>()
                .eq(StandardProductRoutingRuleDO::getPolicyVersionId, scope.version().getId())
                .eq(StandardProductRoutingRuleDO::getRuleCode, ruleCode));
        if (rule == null) {
            throw exception(ROUTING_RULE_NOT_FOUND);
        }
        List<StandardProductRoutingCandidateDO> candidates = candidateMapper.selectByRuleId(rule.getId());
        Map<Long, BuyerDO> buyers = new HashMap<>();
        if (rule.getBuyerId() != null) {
            BuyerDO buyer = buyerMapper.selectById(rule.getBuyerId());
            if (buyer != null && buyer.getId() != null) {
                buyers.put(buyer.getId(), buyer);
            }
        }
        Map<Long, PlatformSkuDO> skus = new HashMap<>();
        if (rule.getPlatformSkuId() != null) {
            PlatformSkuDO sku = skuMapper.selectById(rule.getPlatformSkuId());
            if (sku != null && sku.getId() != null) {
                skus.put(sku.getId(), sku);
            }
        }
        Map<Long, SupplierDO> suppliers = new HashMap<>();
        for (StandardProductRoutingCandidateDO candidate : candidates) {
            if (candidate.getSupplierId() == null) {
                continue;
            }
            SupplierDO supplier = supplierMapper.selectById(candidate.getSupplierId());
            if (supplier != null && supplier.getId() != null) {
                suppliers.put(supplier.getId(), supplier);
            }
        }
        return toView(rule, candidates, buyers, skus, suppliers);
    }

    private EffectiveScope loadEffectiveScope() {
        List<SupplierRoutingPolicyDO> policies = policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code());
        if (policies.isEmpty()) {
            return null;
        }
        if (policies.size() > 1) {
            throw new IllegalStateException("More than one active STANDARD_PRODUCT routing policy");
        }
        SupplierRoutingPolicyDO policy = policies.getFirst();
        List<SupplierRoutingPolicyVersionDO> versions = versionMapper.selectEffective(policy.getId(), clock.now());
        if (versions.isEmpty()) {
            return null;
        }
        if (versions.size() > 1) {
            throw new IllegalStateException("More than one effective published routing policy version");
        }
        return new EffectiveScope(policy, versions.getFirst());
    }

    private Map<Long, List<StandardProductRoutingCandidateDO>> loadCandidates(
            List<StandardProductRoutingRuleDO> rules) {
        Map<Long, List<StandardProductRoutingCandidateDO>> grouped = new HashMap<>();
        Set<Long> ruleIds = new HashSet<>();
        for (StandardProductRoutingRuleDO rule : rules) {
            if (rule.getId() != null) {
                ruleIds.add(rule.getId());
            }
        }
        if (ruleIds.isEmpty()) {
            return grouped;
        }
        List<StandardProductRoutingCandidateDO> candidates = candidateMapper.selectList(
                new LambdaQueryWrapper<StandardProductRoutingCandidateDO>()
                        .in(StandardProductRoutingCandidateDO::getRuleId, ruleIds)
                        .orderByAsc(StandardProductRoutingCandidateDO::getCandidatePriority)
                        .orderByAsc(StandardProductRoutingCandidateDO::getSupplierId)
                        .orderByAsc(StandardProductRoutingCandidateDO::getId));
        for (StandardProductRoutingCandidateDO candidate : candidates) {
            grouped.computeIfAbsent(candidate.getRuleId(), ignored -> new ArrayList<>()).add(candidate);
        }
        return grouped;
    }

    private Map<Long, BuyerDO> loadBuyers(List<StandardProductRoutingRuleDO> rules) {
        Set<Long> ids = new HashSet<>();
        for (StandardProductRoutingRuleDO rule : rules) {
            if (rule.getBuyerId() != null) {
                ids.add(rule.getBuyerId());
            }
        }
        Map<Long, BuyerDO> buyers = new HashMap<>();
        if (ids.isEmpty()) {
            return buyers;
        }
        for (BuyerDO buyer : buyerMapper.selectBatchIds(ids)) {
            if (buyer != null && buyer.getId() != null) {
                buyers.put(buyer.getId(), buyer);
            }
        }
        return buyers;
    }

    private Map<Long, PlatformSkuDO> loadSkus(List<StandardProductRoutingRuleDO> rules) {
        Set<Long> ids = new HashSet<>();
        for (StandardProductRoutingRuleDO rule : rules) {
            if (rule.getPlatformSkuId() != null) {
                ids.add(rule.getPlatformSkuId());
            }
        }
        Map<Long, PlatformSkuDO> skus = new HashMap<>();
        if (ids.isEmpty()) {
            return skus;
        }
        for (PlatformSkuDO sku : skuMapper.selectBatchIds(ids)) {
            if (sku != null && sku.getId() != null) {
                skus.put(sku.getId(), sku);
            }
        }
        return skus;
    }

    private Map<Long, SupplierDO> loadSuppliers(Map<Long, List<StandardProductRoutingCandidateDO>> candidates) {
        Set<Long> ids = new HashSet<>();
        for (List<StandardProductRoutingCandidateDO> group : candidates.values()) {
            for (StandardProductRoutingCandidateDO candidate : group) {
                if (candidate.getSupplierId() != null) {
                    ids.add(candidate.getSupplierId());
                }
            }
        }
        Map<Long, SupplierDO> suppliers = new HashMap<>();
        if (ids.isEmpty()) {
            return suppliers;
        }
        for (SupplierDO supplier : supplierMapper.selectBatchIds(ids)) {
            if (supplier != null && supplier.getId() != null) {
                suppliers.put(supplier.getId(), supplier);
            }
        }
        return suppliers;
    }

    private static RoutingRuleView toView(StandardProductRoutingRuleDO rule,
                                          List<StandardProductRoutingCandidateDO> candidates,
                                          Map<Long, BuyerDO> buyers, Map<Long, PlatformSkuDO> skus,
                                          Map<Long, SupplierDO> suppliers) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalStateException("Published routing rule has no candidates: " + rule.getRuleCode());
        }
        BuyerDO buyer = rule.getBuyerId() == null ? null : buyers.get(rule.getBuyerId());
        PlatformSkuDO sku = rule.getPlatformSkuId() == null ? null : skus.get(rule.getPlatformSkuId());
        List<String> supplierCodes = new ArrayList<>();
        List<String> supplierNames = new ArrayList<>();
        for (StandardProductRoutingCandidateDO candidate : candidates) {
            SupplierDO supplier = candidate.getSupplierId() == null ? null : suppliers.get(candidate.getSupplierId());
            supplierCodes.add(supplier == null ? null : supplier.getSupplierCode());
            supplierNames.add(supplier == null ? null : supplier.getSupplierName());
        }
        boolean fixed = candidates.size() == 1;
        String primaryCode = fixed ? supplierCodes.getFirst() : null;
        String primaryName = fixed ? supplierNames.getFirst() : null;
        boolean specifiedBuyer = rule.getBuyerId() != null;
        return new RoutingRuleView(
                rule.getRuleCode(),
                rule.getRuleName(),
                false,
                specifiedBuyer ? RoutingRuleScopeMode.SPECIFIED : RoutingRuleScopeMode.ALL,
                specifiedBuyer ? listOfNullable(buyer == null ? null : buyer.getBuyerCode()) : List.of(),
                specifiedBuyer ? listOfNullable(buyer == null ? null : buyer.getBuyerName()) : List.of(),
                RoutingRuleScopeMode.ALL,
                List.of(),
                List.of(),
                RoutingRuleScopeMode.ALL,
                List.of(),
                List.of(),
                fixed ? RoutingRuleRouteMode.FIXED : RoutingRuleRouteMode.AUTO,
                RoutingRuleScopeMode.SPECIFIED,
                supplierCodes,
                supplierNames,
                primaryCode,
                primaryName,
                sku == null ? null : sku.getSkuCode(),
                sku == null ? null : sku.getSkuName(),
                rule.getFulfillmentType(),
                DISPLAY_PRIORITIES,
                DISPLAY_WEIGHTS,
                RoutingRuleStatus.ENABLED,
                rule.getUpdatedBy() == null ? null : Long.toString(rule.getUpdatedBy()),
                rule.getUpdatedAt());
    }

    private static boolean matches(RoutingRuleView view, StandardProductRoutingRuleDO rule,
                                   RoutingRulePageQuery query) {
        if (StringUtils.hasText(query.name())
                && (view.name() == null || !view.name().contains(query.name().trim()))) {
            return false;
        }
        if (hasValues(query.distributorCodes())) {
            boolean allBuyers = view.distributorMode() == RoutingRuleScopeMode.ALL;
            boolean overlap = view.distributorCodes().stream().anyMatch(query.distributorCodes()::contains);
            if (!allBuyers && !overlap) {
                return false;
            }
        }
        if (hasValues(query.supplierCodes())) {
            boolean overlap = view.supplierCodes().stream()
                    .anyMatch(code -> code != null && query.supplierCodes().contains(code));
            if (!overlap) {
                return false;
            }
        }
        if (query.status() == RoutingRuleStatus.DISABLED) {
            return false;
        }
        if (hasValues(query.updaters())) {
            String updater = view.updater();
            if (updater == null || !query.updaters().contains(updater)) {
                return false;
            }
        }
        if (query.updateFrom() != null && (rule.getUpdatedAt() == null
                || rule.getUpdatedAt().isBefore(query.updateFrom()))) {
            return false;
        }
        if (query.updateTo() != null && (rule.getUpdatedAt() == null
                || rule.getUpdatedAt().isAfter(query.updateTo()))) {
            return false;
        }
        return true;
    }

    private static List<String> listOfNullable(String value) {
        return value == null ? List.of() : List.of(value);
    }

    private static boolean hasValues(List<String> values) {
        return values != null && !values.isEmpty();
    }

    private static PageResult<RoutingRuleView> emptyPage() {
        return new PageResult<>(List.of(), 0L);
    }

    private static void validateTimeRange(LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw exception(ORDER_QUERY_TIME_RANGE_INVALID);
        }
    }

    private record EffectiveScope(SupplierRoutingPolicyDO policy, SupplierRoutingPolicyVersionDO version) {
    }

    public record RoutingRulePageQuery(String name, List<String> distributorCodes, List<String> projectCodes,
                                       List<String> productTypeCodes, List<String> supplierCodes,
                                       RoutingRuleStatus status, List<String> updaters,
                                       LocalDateTime updateFrom, LocalDateTime updateTo,
                                       Integer pageNo, Integer pageSize) {
    }

    public record PriorityView(
            @Schema(description = "占位项稳定标识，等于策略枚举名") String id,
            @Schema(description = "优先级策略：STABLE与引擎一致，PRICE/COMPOSITE仅占位") RoutingRuleStrategy strategy) {
    }

    public record WeightView(
            @Schema(description = "稳定性评分权重展示占位，不落库") int stability,
            @Schema(description = "价格权重展示占位，不落库") int price,
            @Schema(description = "时效评分权重展示占位，不落库") int timeliness) {
    }

    public record RoutingRuleView(
            @Schema(description = "策略版本内稳定规则编码") String ruleCode,
            @Schema(description = "标准商品路由规则中文名称") String name,
            @Schema(description = "系统预置不可删标记；本期恒为false") boolean protectedRule,
            @Schema(description = "分销商范围：buyer_id空为ALL，否则SPECIFIED") RoutingRuleScopeMode distributorMode,
            @Schema(description = "指定分销商业务编码") List<String> distributorCodes,
            @Schema(description = "指定分销商名称") List<String> distributorNames,
            @Schema(description = "项目范围；本期恒为ALL") RoutingRuleScopeMode projectMode,
            @Schema(description = "指定项目业务编码；本期恒空") List<String> projectCodes,
            @Schema(description = "指定项目名称；本期恒空") List<String> projectNames,
            @Schema(description = "商品类型范围；本期恒为ALL") RoutingRuleScopeMode productTypeMode,
            @Schema(description = "指定商品类型编码；本期恒空") List<String> productTypeCodes,
            @Schema(description = "指定商品类型名称；本期恒空") List<String> productTypeNames,
            @Schema(description = "路由模式：1个候选FIXED，不少于2个AUTO") RoutingRuleRouteMode routeMode,
            @Schema(description = "供应商范围；本期恒为SPECIFIED") RoutingRuleScopeMode supplierMode,
            @Schema(description = "候选供应商业务编码，按候选优先级升序") List<String> supplierCodes,
            @Schema(description = "候选供应商名称，按候选优先级升序") List<String> supplierNames,
            @Schema(description = "FIXED时的唯一候选供应商编码") String primarySupplierCode,
            @Schema(description = "FIXED时的唯一候选供应商名称") String primarySupplierName,
            @Schema(description = "规则绑定的平台SKU编码；未限定或档案缺失为空") String skuCode,
            @Schema(description = "规则绑定的平台SKU名称；未限定或档案缺失为空") String skuName,
            @Schema(description = "履约类型条件：DIRECT_RECHARGE直充，VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益")
            String fulfillmentType,
            @Schema(description = "优先级占位，仅STABLE与引擎一致") List<PriorityView> priorities,
            @Schema(description = "综合权重展示占位，不落库") WeightView weights,
            @Schema(description = "当前生效规则为ENABLED") RoutingRuleStatus status,
            @Schema(description = "updated_by的十进制字符串，本仓不查用户名") String updater,
            @Schema(description = "规则最后修改时间") LocalDateTime updateTime) {
    }
}
