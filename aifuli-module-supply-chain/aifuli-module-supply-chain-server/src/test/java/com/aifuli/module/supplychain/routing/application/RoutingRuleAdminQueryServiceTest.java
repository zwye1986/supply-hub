package com.aifuli.module.supplychain.routing.application;

import com.aifuli.framework.common.exception.ServiceException;
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
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ORDER_QUERY_TIME_RANGE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 路由规则只读投影：只读当前生效标准规则，不把价格/综合占位当成引擎事实。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-readonly-design.md#8-测试门禁}。
 */
class RoutingRuleAdminQueryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 19, 40);

    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final SupplierRoutingPolicyMapper policyMapper = mock(SupplierRoutingPolicyMapper.class);
    private final SupplierRoutingPolicyVersionMapper versionMapper = mock(SupplierRoutingPolicyVersionMapper.class);
    private final StandardProductRoutingRuleMapper ruleMapper = mock(StandardProductRoutingRuleMapper.class);
    private final StandardProductRoutingCandidateMapper candidateMapper =
            mock(StandardProductRoutingCandidateMapper.class);
    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private RoutingRuleAdminQueryService service;

    @BeforeEach
    void setUp() {
        service = new RoutingRuleAdminQueryService(clock, policyMapper, versionMapper, ruleMapper,
                candidateMapper, buyerMapper, skuMapper, supplierMapper);
        when(clock.now()).thenReturn(NOW);
    }

    @Test
    void pageMapsAutoAndFixedNewestFirst() {
        stubEffectiveVersion();
        StandardProductRoutingRuleDO auto = rule(2L, "RR_AUTO", "双候选自动", NOW.plusMinutes(1), 1L);
        StandardProductRoutingRuleDO fixed = rule(1L, "RR_FIXED", "单候选固定", NOW, 1L);
        when(ruleMapper.selectByPolicyVersionId(10L)).thenReturn(List.of(fixed, auto));
        when(candidateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                candidate(21L, 2L, 11L, 0), candidate(22L, 2L, 12L, 10),
                candidate(23L, 1L, 11L, 0)));
        when(buyerMapper.selectBatchIds(any())).thenReturn(List.of(buyer(1L, "BYR_1", "甲分销商")));
        when(skuMapper.selectBatchIds(any())).thenReturn(List.of(sku(5L, "SKU_1", "话费100")));
        when(supplierMapper.selectBatchIds(any())).thenReturn(List.of(
                supplier(11L, "SPL_A", "甲供应商"), supplier(12L, "SPL_B", "乙供应商")));

        var result = service.page(query());

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getList()).extracting(view -> view.ruleCode()).containsExactly("RR_AUTO", "RR_FIXED");
        var first = result.getList().getFirst();
        assertThat(first.routeMode()).isEqualTo(RoutingRuleRouteMode.AUTO);
        assertThat(first.supplierCodes()).containsExactly("SPL_A", "SPL_B");
        assertThat(first.primarySupplierCode()).isNull();
        assertThat(first.projectMode()).isEqualTo(RoutingRuleScopeMode.ALL);
        assertThat(first.productTypeMode()).isEqualTo(RoutingRuleScopeMode.ALL);
        assertThat(first.priorities()).extracting(item -> item.strategy())
                .containsExactly(RoutingRuleStrategy.STABLE, RoutingRuleStrategy.PRICE, RoutingRuleStrategy.COMPOSITE);
        assertThat(first.weights()).isEqualTo(new RoutingRuleAdminQueryService.WeightView(40, 30, 30));
        assertThat(first.status()).isEqualTo(RoutingRuleStatus.ENABLED);
        assertThat(first.protectedRule()).isFalse();
        assertThat(first.skuCode()).isEqualTo("SKU_1");
        assertThat(result.getList().get(1).routeMode()).isEqualTo(RoutingRuleRouteMode.FIXED);
        assertThat(result.getList().get(1).primarySupplierCode()).isEqualTo("SPL_A");
        assertThat(first.getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("id", "policyId", "policyVersionId", "configDigest");
    }

    @Test
    void pageFiltersByNameContains() {
        stubEffectiveVersion();
        when(ruleMapper.selectByPolicyVersionId(10L)).thenReturn(List.of(
                rule(1L, "RR_FIXED", "单候选固定", NOW, 1L)));
        when(candidateMapper.selectList(any(Wrapper.class))).thenReturn(List.of(candidate(23L, 1L, 11L, 0)));
        when(buyerMapper.selectBatchIds(any())).thenReturn(List.of(buyer(1L, "BYR_1", "甲分销商")));
        when(skuMapper.selectBatchIds(any())).thenReturn(List.of());
        when(supplierMapper.selectBatchIds(any())).thenReturn(List.of(supplier(11L, "SPL_A", "甲供应商")));

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
        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(rule(1L, "RR_FIXED", "单候选固定", NOW, 1L));
        when(candidateMapper.selectByRuleId(1L)).thenReturn(List.of(candidate(23L, 1L, 11L, 0)));
        when(buyerMapper.selectById(1L)).thenReturn(buyer(1L, "BYR_1", "甲分销商"));
        when(skuMapper.selectById(5L)).thenReturn(sku(5L, "SKU_1", "话费100"));
        when(supplierMapper.selectById(11L)).thenReturn(supplier(11L, "SPL_A", "甲供应商"));

        var view = service.get("RR_FIXED");

        assertThat(view.ruleCode()).isEqualTo("RR_FIXED");
        assertThat(view.routeMode()).isEqualTo(RoutingRuleRouteMode.FIXED);
        assertThat(view.distributorCodes()).containsExactly("BYR_1");

        when(ruleMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.get("RR_MISSING"))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_NOT_FOUND.getCode());
    }

    private void stubEffectiveVersion() {
        when(policyMapper.selectActiveByType(RoutingType.STANDARD_PRODUCT.code()))
                .thenReturn(List.of(SupplierRoutingPolicyDO.builder().id(9L)
                        .policyCode("CARD_PRIMARY").routingType(RoutingType.STANDARD_PRODUCT.code())
                        .status((short) 10).activeFlag((short) 1).build()));
        when(versionMapper.selectEffective(eq(9L), eq(NOW)))
                .thenReturn(List.of(SupplierRoutingPolicyVersionDO.builder().id(10L).policyId(9L)
                        .versionNo(1).status((short) 10).validFrom(NOW.minusDays(1)).build()));
    }

    private static RoutingRuleAdminQueryService.RoutingRulePageQuery query() {
        return new RoutingRuleAdminQueryService.RoutingRulePageQuery(
                null, List.of(), List.of(), List.of(), List.of(), null, List.of(), null, null, 1, 20);
    }

    private static StandardProductRoutingRuleDO rule(long id, String code, String name,
                                                     LocalDateTime updatedAt, long buyerId) {
        return StandardProductRoutingRuleDO.builder().id(id).policyVersionId(10L)
                .ruleCode(code).ruleName(name).buyerId(buyerId).platformSkuId(5L)
                .fulfillmentType("DIRECT_RECHARGE").rulePriority(10).version(0L)
                .updatedBy(1016L).createdAt(NOW).updatedAt(updatedAt).build();
    }

    private static StandardProductRoutingCandidateDO candidate(long id, long ruleId, long supplierId, int priority) {
        return StandardProductRoutingCandidateDO.builder().id(id).ruleId(ruleId)
                .supplierId(supplierId).candidatePriority(priority)
                .inventoryCheckMode("UNSUPPORTED").balanceCheckMode("UNSUPPORTED")
                .healthCheckMode("OPTIONAL").healthMaxAgeSeconds(60).build();
    }

    private static BuyerDO buyer(long id, String code, String name) {
        return BuyerDO.builder().id(id).buyerCode(code).buyerName(name).build();
    }

    private static PlatformSkuDO sku(long id, String code, String name) {
        return PlatformSkuDO.builder().id(id).skuCode(code).skuName(name).build();
    }

    private static SupplierDO supplier(long id, String code, String name) {
        return SupplierDO.builder().id(id).supplierCode(code).supplierName(name).build();
    }
}
