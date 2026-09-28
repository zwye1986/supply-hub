package com.aifuli.module.supplychain.routing.application;

import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
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
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
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
import java.util.stream.Collectors;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ORDER_QUERY_TIME_RANGE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_NOT_FOUND;

/**
 * 管理端路由规则只读投影（PRD 四级范围模型）。只读当前启用标准策略的生效已发布版本；
 * 全局综合权重读策略身份行，三优先级策略读规则行。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#56-rest-契约}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingRuleAdminQueryService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final short RULE_ENABLED = 10;
    private static final String SCOPE_KIND_DISTRIBUTOR = "DISTRIBUTOR";
    private static final String SCOPE_KIND_PROJECT = "PROJECT";
    private static final String SCOPE_KIND_PRODUCT_TYPE = "PRODUCT_TYPE";
    private static final String SCOPE_ALL = "ALL";

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

    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public PageResult<RoutingRuleView> page(RoutingRulePageQuery query) {
        validateTimeRange(query.updateFrom(), query.updateTo());
        log.info("分页查询路由规则 name={} distributorCodes={} projectCodes={} productTypeCodes={} "
                        + "supplierCodes={} status={} updaters={} updateFrom={} updateTo={} pageNo={} pageSize={}",
                query.name(), query.distributorCodes(), query.projectCodes(), query.productTypeCodes(),
                query.supplierCodes(), query.status(), query.updaters(), query.updateFrom(),
                query.updateTo(), query.pageNo(), query.pageSize());
        EffectiveScope scope = loadEffectiveScope();
        if (scope == null) {
            return emptyPage();
        }
        List<RoutingRuleView> views = loadViews(scope);
        List<RoutingRuleView> matched = views.stream()
                .filter(view -> matches(view, query))
                .toList();
        int pageNo = query.pageNo() == null ? DEFAULT_PAGE_NO : query.pageNo();
        int pageSize = query.pageSize() == null ? DEFAULT_PAGE_SIZE : query.pageSize();
        int from = Math.max(0, (pageNo - 1) * pageSize);
        int to = Math.min(matched.size(), from + pageSize);
        List<RoutingRuleView> page = from >= matched.size() ? List.of() : matched.subList(from, to);
        return new PageResult<>(List.copyOf(page), (long) matched.size());
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, readOnly = true)
    public RoutingRuleView get(String ruleCode) {
        log.info("查询路由规则详情 ruleCode={}", ruleCode);
        EffectiveScope scope = loadEffectiveScope();
        if (scope == null) {
            throw exception(ROUTING_RULE_NOT_FOUND);
        }
        return loadViews(scope).stream()
                .filter(view -> ruleCode.equals(view.ruleCode()))
                .findFirst()
                .orElseThrow(() -> exception(ROUTING_RULE_NOT_FOUND));
    }

    private List<RoutingRuleView> loadViews(EffectiveScope scope) {
        List<StdRoutingRuleDO> rules = new ArrayList<>(
                ruleMapper.selectByPolicyVersionId(scope.version().getId()));
        rules.sort(Comparator.comparing(StdRoutingRuleDO::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(StdRoutingRuleDO::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        if (rules.isEmpty()) {
            return List.of();
        }
        List<Long> ruleIds = rules.stream().map(StdRoutingRuleDO::getId).toList();
        Map<Long, List<StdRoutingRuleScopeDO>> scopesByRule = groupScopes(ruleIds);
        Map<Long, List<StdRoutingCandidateDO>> candidatesByRule = groupCandidates(ruleIds);

        // 批量解析范围编码 → 名称。
        Map<String, String> buyerNames = resolveBuyerNames(rules, scopesByRule);
        Map<String, String> projectNames = resolveProjectNames(rules, scopesByRule);
        Map<String, String> productTypeNames = resolveProductTypeNames(rules, scopesByRule);
        Set<Long> supplierIds = new HashSet<>();
        for (List<StdRoutingCandidateDO> group : candidatesByRule.values()) {
            for (StdRoutingCandidateDO candidate : group) {
                supplierIds.add(candidate.getSupplierId());
            }
        }
        for (StdRoutingRuleDO rule : rules) {
            if (rule.getPrimarySupplierId() != null) {
                supplierIds.add(rule.getPrimarySupplierId());
            }
        }
        Map<Long, SupplierDO> suppliers = new HashMap<>();
        // AUTO + ALL（含 GLOBAL_DEFAULT）不持久化候选；空集合不应交给 selectBatchIds 生成 IN ()。
        // 设计见 docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#56-rest-契约。
        if (!supplierIds.isEmpty()) {
            for (SupplierDO supplier : supplierMapper.selectBatchIds(supplierIds)) {
                if (supplier != null && supplier.getId() != null) {
                    suppliers.put(supplier.getId(), supplier);
                }
            }
        }

        WeightView weights = new WeightView(
                scope.policy().getStabilityWeight() == null ? RoutingRuleSaveSupport.DEFAULT_WEIGHT_STABILITY
                        : scope.policy().getStabilityWeight(),
                scope.policy().getPriceWeight() == null ? RoutingRuleSaveSupport.DEFAULT_WEIGHT_PRICE
                        : scope.policy().getPriceWeight(),
                scope.policy().getTimelinessWeight() == null ? RoutingRuleSaveSupport.DEFAULT_WEIGHT_TIMELINESS
                        : scope.policy().getTimelinessWeight());

        List<RoutingRuleView> views = new ArrayList<>(rules.size());
        for (StdRoutingRuleDO rule : rules) {
            views.add(toView(rule, scopesByRule.getOrDefault(rule.getId(), List.of()),
                    candidatesByRule.getOrDefault(rule.getId(), List.of()),
                    buyerNames, projectNames, productTypeNames, suppliers, weights));
        }
        return views;
    }

    private Map<Long, List<StdRoutingRuleScopeDO>> groupScopes(List<Long> ruleIds) {
        Map<Long, List<StdRoutingRuleScopeDO>> grouped = new HashMap<>();
        for (StdRoutingRuleScopeDO scope : scopeMapper.selectByRuleIds(ruleIds)) {
            grouped.computeIfAbsent(scope.getRuleId(), ignored -> new ArrayList<>()).add(scope);
        }
        return grouped;
    }

    private Map<Long, List<StdRoutingCandidateDO>> groupCandidates(List<Long> ruleIds) {
        Map<Long, List<StdRoutingCandidateDO>> grouped = new HashMap<>();
        for (StdRoutingCandidateDO candidate : candidateMapper.selectByRuleIds(ruleIds)) {
            grouped.computeIfAbsent(candidate.getRuleId(), ignored -> new ArrayList<>()).add(candidate);
        }
        return grouped;
    }

    private Map<String, String> resolveBuyerNames(List<StdRoutingRuleDO> rules,
                                                  Map<Long, List<StdRoutingRuleScopeDO>> scopesByRule) {
        Set<String> codes = scopeCodes(rules, scopesByRule, SCOPE_KIND_DISTRIBUTOR);
        Map<String, String> names = new HashMap<>();
        for (String code : codes) {
            BuyerDO buyer = buyerMapper.selectByBuyerCode(code);
            if (buyer != null) {
                names.put(code, buyer.getBuyerName());
            }
        }
        return names;
    }

    private Map<String, String> resolveProjectNames(List<StdRoutingRuleDO> rules,
                                                    Map<Long, List<StdRoutingRuleScopeDO>> scopesByRule) {
        Set<String> codes = scopeCodes(rules, scopesByRule, SCOPE_KIND_PROJECT);
        Map<String, String> names = new HashMap<>();
        for (String code : codes) {
            BuyerProjectDO project = projectMapper.selectByProjectCode(code);
            if (project != null) {
                names.put(code, project.getProjectName());
            }
        }
        return names;
    }

    private Map<String, String> resolveProductTypeNames(List<StdRoutingRuleDO> rules,
                                                        Map<Long, List<StdRoutingRuleScopeDO>> scopesByRule) {
        Set<String> codes = scopeCodes(rules, scopesByRule, SCOPE_KIND_PRODUCT_TYPE);
        Map<String, String> names = new HashMap<>();
        for (String code : codes) {
            ProductTypeDO type = productTypeMapper.selectByCode(code);
            if (type != null) {
                names.put(code, type.getTypeName());
            }
        }
        return names;
    }

    private static Set<String> scopeCodes(List<StdRoutingRuleDO> rules,
                                          Map<Long, List<StdRoutingRuleScopeDO>> scopesByRule, String kind) {
        return rules.stream()
                .flatMap(rule -> scopesByRule.getOrDefault(rule.getId(), List.of()).stream())
                .filter(scope -> kind.equals(scope.getScopeKind()))
                .map(StdRoutingRuleScopeDO::getScopeCode)
                .collect(Collectors.toSet());
    }

    private static RoutingRuleView toView(StdRoutingRuleDO rule,
                                          List<StdRoutingRuleScopeDO> scopes,
                                          List<StdRoutingCandidateDO> candidates,
                                          Map<String, String> buyerNames,
                                          Map<String, String> projectNames,
                                          Map<String, String> productTypeNames,
                                          Map<Long, SupplierDO> suppliers,
                                          WeightView weights) {
        List<StdRoutingRuleScopeDO> distributorScopes = scopes.stream()
                .filter(scope -> SCOPE_KIND_DISTRIBUTOR.equals(scope.getScopeKind())).toList();
        List<StdRoutingRuleScopeDO> projectScopes = scopes.stream()
                .filter(scope -> SCOPE_KIND_PROJECT.equals(scope.getScopeKind())).toList();
        List<StdRoutingRuleScopeDO> productTypeScopes = scopes.stream()
                .filter(scope -> SCOPE_KIND_PRODUCT_TYPE.equals(scope.getScopeKind())).toList();

        List<String> supplierCodes = new ArrayList<>();
        List<String> supplierNames = new ArrayList<>();
        for (StdRoutingCandidateDO candidate : candidates) {
            SupplierDO supplier = suppliers.get(candidate.getSupplierId());
            supplierCodes.add(supplier == null ? null : supplier.getSupplierCode());
            supplierNames.add(supplier == null ? null : supplier.getSupplierName());
        }
        SupplierDO primary = rule.getPrimarySupplierId() == null ? null
                : suppliers.get(rule.getPrimarySupplierId());
        // FIXED 规则的适用供应商即主供应商。
        if ("FIXED".equals(rule.getRouteMode()) && primary != null && supplierCodes.isEmpty()) {
            supplierCodes.add(primary.getSupplierCode());
            supplierNames.add(primary.getSupplierName());
        }
        return new RoutingRuleView(
                rule.getRuleCode(),
                rule.getRuleName(),
                Boolean.TRUE.equals(rule.getProtectedRule()),
                scopeMode(rule.getDistributorMode()),
                codesOf(distributorScopes),
                namesOf(distributorScopes, buyerNames),
                scopeMode(rule.getProjectMode()),
                codesOf(projectScopes),
                namesOf(projectScopes, projectNames),
                scopeMode(rule.getProductTypeMode()),
                codesOf(productTypeScopes),
                namesOf(productTypeScopes, productTypeNames),
                routeMode(rule.getRouteMode()),
                scopeMode(rule.getSupplierMode()),
                supplierCodes,
                supplierNames,
                primary == null ? null : primary.getSupplierCode(),
                primary == null ? null : primary.getSupplierName(),
                priorities(rule),
                weights,
                rule.getStatus() != null && rule.getStatus() == RULE_ENABLED
                        ? RoutingRuleStatus.ENABLED : RoutingRuleStatus.DISABLED,
                rule.getUpdatedBy() == null ? null : Long.toString(rule.getUpdatedBy()),
                rule.getUpdatedAt());
    }

    private static List<String> codesOf(List<StdRoutingRuleScopeDO> scopes) {
        return scopes.stream().map(StdRoutingRuleScopeDO::getScopeCode).toList();
    }

    private static List<String> namesOf(List<StdRoutingRuleScopeDO> scopes, Map<String, String> names) {
        return scopes.stream()
                .map(scope -> names.getOrDefault(scope.getScopeCode(), scope.getScopeCode()))
                .toList();
    }

    private static RoutingRuleScopeMode scopeMode(String mode) {
        return SCOPE_ALL.equals(mode) ? RoutingRuleScopeMode.ALL : RoutingRuleScopeMode.SPECIFIED;
    }

    private static RoutingRuleRouteMode routeMode(String mode) {
        return "FIXED".equals(mode) ? RoutingRuleRouteMode.FIXED : RoutingRuleRouteMode.AUTO;
    }

    private static List<PriorityView> priorities(StdRoutingRuleDO rule) {
        return List.of(
                new PriorityView(rule.getStrategyPriority1(), strategy(rule.getStrategyPriority1())),
                new PriorityView(rule.getStrategyPriority2(), strategy(rule.getStrategyPriority2())),
                new PriorityView(rule.getStrategyPriority3(), strategy(rule.getStrategyPriority3())));
    }

    private static RoutingRuleStrategy strategy(String name) {
        return RoutingRuleStrategy.valueOf(name);
    }

    private static boolean matches(RoutingRuleView view, RoutingRulePageQuery query) {
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
        if (hasValues(query.projectCodes())) {
            boolean allProjects = view.projectMode() == RoutingRuleScopeMode.ALL;
            boolean overlap = view.projectCodes().stream().anyMatch(query.projectCodes()::contains);
            if (!allProjects && !overlap) {
                return false;
            }
        }
        if (hasValues(query.productTypeCodes())) {
            boolean allTypes = view.productTypeMode() == RoutingRuleScopeMode.ALL;
            boolean overlap = view.productTypeCodes().stream().anyMatch(query.productTypeCodes()::contains);
            if (!allTypes && !overlap) {
                return false;
            }
        }
        if (hasValues(query.supplierCodes())) {
            boolean allSuppliers = view.supplierMode() == RoutingRuleScopeMode.ALL;
            boolean overlap = view.supplierCodes().stream()
                    .anyMatch(code -> code != null && query.supplierCodes().contains(code));
            if (!allSuppliers && !overlap) {
                return false;
            }
        }
        if (query.status() != null && view.status() != query.status()) {
            return false;
        }
        if (hasValues(query.updaters())) {
            String updater = view.updater();
            if (updater == null || !query.updaters().contains(updater)) {
                return false;
            }
        }
        if (query.updateFrom() != null && (view.updateTime() == null
                || view.updateTime().isBefore(query.updateFrom()))) {
            return false;
        }
        if (query.updateTo() != null && (view.updateTime() == null
                || view.updateTime().isAfter(query.updateTo()))) {
            return false;
        }
        return true;
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
            @Schema(description = "优先级策略：STABLE稳定优先，PRICE价格优先，COMPOSITE综合排序")
            RoutingRuleStrategy strategy) {
    }

    public record WeightView(
            @Schema(description = "全局稳定性评分权重%，所有规则共享") int stability,
            @Schema(description = "全局价格权重%") int price,
            @Schema(description = "全局时效评分权重%，三者之和恒为100") int timeliness) {
    }

    public record RoutingRuleView(
            @Schema(description = "策略版本内稳定规则编码") String ruleCode,
            @Schema(description = "路由规则中文名称") String name,
            @Schema(description = "受保护规则标记：系统预置全局默认规则不可删除") boolean protectedRule,
            @Schema(description = "分销商范围：ALL/SPECIFIED") RoutingRuleScopeMode distributorMode,
            @Schema(description = "指定分销商业务编码") List<String> distributorCodes,
            @Schema(description = "指定分销商名称") List<String> distributorNames,
            @Schema(description = "项目范围：ALL/SPECIFIED") RoutingRuleScopeMode projectMode,
            @Schema(description = "指定项目业务编码") List<String> projectCodes,
            @Schema(description = "指定项目名称") List<String> projectNames,
            @Schema(description = "商品类型范围：ALL/SPECIFIED") RoutingRuleScopeMode productTypeMode,
            @Schema(description = "指定商品类型编码") List<String> productTypeCodes,
            @Schema(description = "指定商品类型名称") List<String> productTypeNames,
            @Schema(description = "路由模式：AUTO自动路由/FIXED固定供应商") RoutingRuleRouteMode routeMode,
            @Schema(description = "供应商范围：ALL全部（候选动态解析）/SPECIFIED指定") RoutingRuleScopeMode supplierMode,
            @Schema(description = "候选供应商业务编码，按候选顺序") List<String> supplierCodes,
            @Schema(description = "候选供应商名称，按候选顺序") List<String> supplierNames,
            @Schema(description = "FIXED时的主供应商编码") String primarySupplierCode,
            @Schema(description = "FIXED时的主供应商名称") String primarySupplierName,
            @Schema(description = "三个优先级位次，顺序即排序键优先级") List<PriorityView> priorities,
            @Schema(description = "全局综合权重，所有规则共享") WeightView weights,
            @Schema(description = "规则状态：ENABLED启用/DISABLED停用") RoutingRuleStatus status,
            @Schema(description = "updated_by的十进制字符串，本仓不查用户名") String updater,
            @Schema(description = "规则最后修改时间") LocalDateTime updateTime) {
    }
}
