package com.aifuli.module.supplychain.routing.application;

import com.aifuli.framework.common.exception.ServiceException;
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
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ORDER_QUERY_TIME_RANGE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 路由规则只读投影（PRD 四级范围模型）：范围明细、三优先级、全局权重与受保护标记。
 * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#56-rest-契约}。
 */
class RoutingRuleAdminQueryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 23, 19, 40);

    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final SupplierRoutingPolicyMapper policyMapper = mock(SupplierRoutingPolicyMapper.class);
    private final SupplierRoutingPolicyVersionMapper versionMapper =
            mock(SupplierRoutingPolicyVersionMapper.class);
    private final StdRoutingRuleMapper ruleMapper = mock(StdRoutingRuleMapper.class);
    private final StdRoutingRuleScopeMapper scopeMapper = mock(StdRoutingRuleScopeMapper.class);
    private final StdRoutingCandidateMapper candidateMapper = mock(StdRoutingCandidateMapper.class);
    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
    private final ProductTypeMapper productTypeMapper = mock(ProductTypeMapper.class);
    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private RoutingRuleAdminQueryService service;

    @BeforeEach
    void setUp() {
        service = new RoutingRuleAdminQueryService(clock, policyMapper, versionMapper, ruleMapper,
                scopeMapper, candidateMapper, buyerMapper, projectMapper, productTypeMapper, supplierMapper);
        when(clock.now()).thenReturn(NOW);
    }

    @Test
    void pageMapsAutoAndFixedNewestFirst() {
        stubEffectiveVersion();
        StdRoutingRuleDO auto = rule(2L, "RR_AUTO", "双候选自动", NOW.plusMinutes(1), "AUTO", "ALL",
                "SPECIFIED", "SPECIFIED", null, "STABLE", "PRICE", "COMPOSITE");
        StdRoutingRuleDO fixed = rule(1L, "RR_FIXED", "单候选固定", NOW, "FIXED", "SPECIFIED",
                "ALL", "ALL", 11L, "PRICE", "STABLE", "COMPOSITE");
        when(ruleMapper.selectByPolicyVersionId(10L)).thenReturn(List.of(fixed, auto));
        when(scopeMapper.selectByRuleIds(anyCollection())).thenReturn(List.of(
                scope(31L, 2L, "DISTRIBUTOR", "BYR_1"),
                scope(32L, 2L, "PRODUCT_TYPE", "MOVIE"),
                scope(33L, 1L, "DISTRIBUTOR", "BYR_1")));
        when(candidateMapper.selectByRuleIds(anyCollection())).thenReturn(List.of(
                candidate(21L, 2L, 11L, 0), candidate(22L, 2L, 12L, 10)));
        when(buyerMapper.selectByBuyerCode("BYR_1")).thenReturn(buyer(1L, "BYR_1", "甲分销商"));
        when(productTypeMapper.selectByCode("MOVIE")).thenReturn(productType("MOVIE", "电影"));
        when(supplierMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                supplier(11L, "SPL_A", "甲供应商"), supplier(12L, "SPL_B", "乙供应商")));

        var result = service.page(query());

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getList()).extracting(view -> view.ruleCode())
                .containsExactly("RR_AUTO", "RR_FIXED");
        var first = result.getList().getFirst();
        assertThat(first.routeMode()).isEqualTo(RoutingRuleRouteMode.AUTO);
        assertThat(first.supplierMode()).isEqualTo(RoutingRuleScopeMode.SPECIFIED);
        assertThat(first.supplierCodes()).containsExactly("SPL_A", "SPL_B");
        assertThat(first.primarySupplierCode()).isNull();
        assertThat(first.distributorMode()).isEqualTo(RoutingRuleScopeMode.ALL);
        assertThat(first.productTypeMode()).isEqualTo(RoutingRuleScopeMode.SPECIFIED);
        assertThat(first.productTypeCodes()).containsExactly("MOVIE");
        assertThat(first.productTypeNames()).containsExactly("电影");
        assertThat(first.priorities()).extracting(item -> item.strategy())
                .containsExactly(RoutingRuleStrategy.STABLE, RoutingRuleStrategy.PRICE,
                        RoutingRuleStrategy.COMPOSITE);
        assertThat(first.weights()).isEqualTo(new RoutingRuleAdminQueryService.WeightView(40, 30, 30));
        assertThat(first.status()).isEqualTo(RoutingRuleStatus.ENABLED);
        assertThat(first.protectedRule()).isFalse();
        var second = result.getList().get(1);
        assertThat(second.routeMode()).isEqualTo(RoutingRuleRouteMode.FIXED);
        // FIXED 规则无候选行：适用供应商即主供应商
        assertThat(second.supplierCodes()).containsExactly("SPL_A");
        assertThat(second.primarySupplierCode()).isEqualTo("SPL_A");
        assertThat(second.priorities()).extracting(item -> item.strategy())
                .containsExactly(RoutingRuleStrategy.PRICE, RoutingRuleStrategy.STABLE,
                        RoutingRuleStrategy.COMPOSITE);
        assertThat(first.getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("id", "policyId", "policyVersionId", "configDigest");
    }

    @Test
    void pageFiltersByNameContains() {
        stubEffectiveVersion();
        when(ruleMapper.selectByPolicyVersionId(10L)).thenReturn(List.of(
                rule(1L, "RR_FIXED", "单候选固定", NOW, "FIXED", "ALL", "ALL", "ALL", 11L,
                        "STABLE", "PRICE", "COMPOSITE")));
        when(scopeMapper.selectByRuleIds(anyCollection())).thenReturn(List.of());
        when(candidateMapper.selectByRuleIds(anyCollection())).thenReturn(List.of());
        when(supplierMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(supplier(11L, "SPL_A", "甲供应商")));

        var hit = service.page(new RoutingRuleAdminQueryService.RoutingRulePageQuery(
                "固定", List.of(), List.of(), List.of(), List.of(), null, List.of(), null, null, 1, 20));
        var miss = service.page(new RoutingRuleAdminQueryService.RoutingRulePageQuery(
                "不存在", List.of(), List.of(), List.of(), List.of(), null, List.of(), null, null, 1, 20));

        assertThat(hit.getList()).extracting(view -> view.ruleCode()).containsExactly("RR_FIXED");
        assertThat(miss.getList()).isEmpty();
        assertThat(miss.getTotal()).isZero();
    }

    @Test
    void pageReturnsEmptyWhenNoActivePolicy() {
        when(policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code())).thenReturn(List.of());

        var result = service.page(query());

        assertThat(result.getList()).isEmpty();
        verifyNoInteractions(ruleMapper);
    }

    @Test
    void rejectsReversedUpdateTimeRange() {
        RoutingRuleAdminQueryService.RoutingRulePageQuery query =
                new RoutingRuleAdminQueryService.RoutingRulePageQuery(
                        null, List.of(), List.of(), List.of(), List.of(), null, List.of(),
                        NOW, NOW.minusSeconds(1), 1, 20);

        assertThatThrownBy(() -> service.page(query))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ORDER_QUERY_TIME_RANGE_INVALID.getCode());
        verifyNoInteractions(policyMapper);
    }

    @Test
    void getReturnsMappedRuleAndRejectsMissing() {
        stubEffectiveVersion();
        when(ruleMapper.selectByPolicyVersionId(10L)).thenReturn(List.of(
                rule(1L, "RR_FIXED", "单候选固定", NOW, "FIXED", "SPECIFIED", "ALL", "ALL", 11L,
                        "STABLE", "PRICE", "COMPOSITE")));
        when(scopeMapper.selectByRuleIds(anyCollection())).thenReturn(List.of(
                scope(33L, 1L, "DISTRIBUTOR", "BYR_1")));
        when(candidateMapper.selectByRuleIds(anyCollection())).thenReturn(List.of());
        when(buyerMapper.selectByBuyerCode("BYR_1")).thenReturn(buyer(1L, "BYR_1", "甲分销商"));
        when(supplierMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(supplier(11L, "SPL_A", "甲供应商")));

        var view = service.get("RR_FIXED");

        assertThat(view.ruleCode()).isEqualTo("RR_FIXED");
        assertThat(view.routeMode()).isEqualTo(RoutingRuleRouteMode.FIXED);
        assertThat(view.distributorCodes()).containsExactly("BYR_1");
        assertThat(view.distributorNames()).containsExactly("甲分销商");

        assertThatThrownBy(() -> service.get("RR_MISSING"))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_NOT_FOUND.getCode());
    }

    private void stubEffectiveVersion() {
        when(policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code()))
                .thenReturn(List.of(SupplierRoutingPolicyDO.builder().id(9L)
                        .policyCode("STANDARD_PRODUCT_PRIMARY").routingType(RoutingType.STANDARD_PRODUCT.code())
                        .status((short) 10).activeFlag((short) 1)
                        .stabilityWeight(40).priceWeight(30).timelinessWeight(30).build()));
        when(versionMapper.selectEffective(any(), any(LocalDateTime.class)))
                .thenReturn(List.of(SupplierRoutingPolicyVersionDO.builder().id(10L).policyId(9L)
                        .versionNo(1).status((short) 10).validFrom(NOW.minusDays(1)).build()));
    }

    private static RoutingRuleAdminQueryService.RoutingRulePageQuery query() {
        return new RoutingRuleAdminQueryService.RoutingRulePageQuery(
                null, List.of(), List.of(), List.of(), List.of(), null, List.of(), null, null, 1, 20);
    }

    private static StdRoutingRuleDO rule(long id, String code, String name, LocalDateTime updatedAt,
                                         String routeMode, String distributorMode, String projectMode,
                                         String productTypeMode, Long primarySupplierId,
                                         String strategy1, String strategy2, String strategy3) {
        return StdRoutingRuleDO.builder().id(id).policyVersionId(10L)
                .ruleCode(code).ruleName(name).routeMode(routeMode)
                .primarySupplierId(primarySupplierId)
                .distributorMode(distributorMode).projectMode(projectMode)
                .productTypeMode(productTypeMode).supplierMode("SPECIFIED")
                .strategyPriority1(strategy1).strategyPriority2(strategy2).strategyPriority3(strategy3)
                .rulePriority(100).status((short) 10).protectedRule(false).version(0L)
                .updatedBy(1016L).createdAt(NOW).updatedAt(updatedAt).build();
    }

    private static StdRoutingRuleScopeDO scope(long id, long ruleId, String kind, String code) {
        return StdRoutingRuleScopeDO.builder().id(id).ruleId(ruleId)
                .scopeKind(kind).scopeCode(code).build();
    }

    private static StdRoutingCandidateDO candidate(long id, long ruleId, long supplierId, int priority) {
        return StdRoutingCandidateDO.builder().id(id).ruleId(ruleId)
                .supplierId(supplierId).candidatePriority(priority)
                .inventoryCheckMode("UNSUPPORTED").balanceCheckMode("UNSUPPORTED")
                .healthCheckMode("OPTIONAL").healthMaxAgeSeconds(60).build();
    }

    private static BuyerDO buyer(long id, String code, String name) {
        return BuyerDO.builder().id(id).buyerCode(code).buyerName(name).build();
    }

    private static ProductTypeDO productType(String code, String name) {
        return ProductTypeDO.builder().typeCode(code).typeName(name).build();
    }

    private static SupplierDO supplier(long id, String code, String name) {
        return SupplierDO.builder().id(id).supplierCode(code).supplierName(name).build();
    }
}
