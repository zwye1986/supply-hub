package com.aifuli.module.supplychain.routing.application;

import com.aifuli.module.supplychain.routing.dal.dataobject.*;
import com.aifuli.module.supplychain.routing.dal.mysql.*;
import com.aifuli.module.supplychain.routing.domain.RoutingInput;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 只替换持久化输入，真实执行应用路由与内核，验证缺配置和损坏引用不会混为业务等待。
 * 见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#71-资格语义不变。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // 损坏链路提前失败，后续共用夹具不必被读取。
class SupplierRoutingQualificationFailureTest {
    @Mock SupplierRoutingPolicyMapper policyMapper;
    @Mock SupplierRoutingPolicyVersionMapper versionMapper;
    @Mock StdRoutingRuleMapper ruleMapper;
    @Mock StdRoutingRuleScopeMapper scopeMapper;
    @Mock StdRoutingCandidateMapper candidateMapper;
    @Mock BuyerMapper buyerMapper;
    @Mock BuyerProjectMapper projectMapper;
    @Mock PlatformSkuMapper skuMapper;
    @Mock PlatformSpuMapper spuMapper;
    @Mock SupplierMapper supplierMapper;
    @Mock SupplierOfferMapper offerMapper;
    @Mock SupplierOfferVersionMapper offerVersionMapper;
    @Mock SupplierSkuDailyQuotaMapper dailyQuotaMapper;
    @Mock SupplierDeliveryRegionMapper deliveryRegionMapper;
    @Mock SupplyChainOrderMapper orderMapper;
    @Mock SupplierConnectorMapper connectorMapper;
    @Mock SupplierConnectorVersionMapper connectorVersionMapper;
    @Mock InventorySourceMapper inventorySourceMapper;
    @InjectMocks SupplierRoutingApplicationService routing;

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 9, 12, 0);
    private RoutingInput input;

    @BeforeEach
    void activeSubjects() {
        input = new RoutingInput(1L, null, 2L, "DIRECT_RECHARGE", 1, now, Map.of());
        lenient().when(buyerMapper.selectById(1L)).thenReturn(BuyerDO.builder().id(1L)
                .buyerCode("BUYER").status((short) 10).build());
        lenient().when(skuMapper.selectById(2L)).thenReturn(PlatformSkuDO.builder().id(2L)
                .spuId(3L).fulfillmentType("DIRECT_RECHARGE").status((short) 10).build());
        lenient().when(spuMapper.selectById(3L)).thenReturn(PlatformSpuDO.builder().id(3L)
                .productType("EQUITY_PRODUCT").build());
    }

    @Test void missingPolicyIsRecoverable() { assertRecoverable("NO_ACTIVE_POLICY"); }

    @Test void missingEffectiveVersionIsRecoverable() {
        policy();
        assertRecoverable("NO_EFFECTIVE_POLICY_VERSION");
    }

    @Test void missingRulesIsRecoverable() {
        version();
        assertRecoverable("NO_PUBLISHED_RULE");
    }

    @Test void noMatchingEnabledRuleIsRecoverable() {
        rule().setStatus((short) 20);
        assertRecoverable("NO_MATCHED_RULE");
    }

    @Test void emptyDynamicOfferPoolIsRecoverable() {
        StdRoutingRuleDO rule = rule();
        rule.setRouteMode("AUTO");
        rule.setSupplierMode("ALL");
        assertRecoverable("NO_DYNAMIC_CANDIDATE");
    }

    @Test void duplicateEffectiveVersionsRemainTechnicalFailure() {
        SupplierRoutingPolicyVersionDO version = version();
        when(versionMapper.selectEffective(10L, now)).thenReturn(List.of(version, version));
        assertTechnicalFailure();
    }

    @Test void duplicateActivePoliciesRemainTechnicalFailure() {
        SupplierRoutingPolicyDO policy = policy();
        when(policyMapper.selectActiveByType("STANDARD_PRODUCT")).thenReturn(List.of(policy, policy));
        assertTechnicalFailure();
    }

    @Test void fixedRuleMissingPrimarySupplierRemainsTechnicalFailure() {
        rule().setPrimarySupplierId(null);
        assertTechnicalFailure();
    }

    @Test void missingOfferVersionRemainsTechnicalFailure() {
        offer();
        assertTechnicalFailure();
    }

    @Test void publishedOfferNullPointerRemainsTechnicalFailure() {
        offer().setCurrentVersionId(null);
        assertTechnicalFailure();
    }

    @Test void foreignOfferVersionRemainsTechnicalFailure() {
        offerVersion().setOfferId(999L);
        assertTechnicalFailure();
    }

    @Test void missingConnectorVersionRemainsTechnicalFailure() {
        offerVersion();
        assertTechnicalFailure();
    }

    @Test void foreignConnectorRemainsTechnicalFailure() {
        dependencies();
        when(connectorMapper.selectById(50L)).thenReturn(SupplierConnectorDO.builder()
                .id(50L).supplierId(999L).currentVersionId(40L).status((short) 10).build());
        assertTechnicalFailure();
    }

    @Test void foreignInventoryRemainsTechnicalFailure() {
        dependencies().setPlatformSkuId(999L);
        assertTechnicalFailure();
    }

    @Test void invalidFulfillmentJsonRemainsTechnicalFailure() {
        dependencies();
        offerVersionMapper.selectById(31L).setFulfillmentConfigJson("not-json");
        assertTechnicalFailure();
    }

    @Test void nonObjectConnectorJsonRemainsTechnicalFailure() {
        dependencies();
        connectorVersionMapper.selectById(40L).setAdapterConfigJson("[]");
        assertTechnicalFailure();
    }

    @Test void inactiveInventoryIsNormalUnavailability() {
        dependencies().setStatus((short) 20);
        assertThat(routing.route(input).decision().selected()).isFalse();
    }

    @Test void validFixedRemoteSupplyIsConfirmed() {
        dependencies();
        assertThat(routing.route(input).decision().selectedCandidate().availabilityClass())
                .isEqualTo("CONFIRMED");
    }

    @Test void activeConnectorMissingCurrentPointerIsTechnicalFailure() {
        dependencies();
        connectorMapper.selectById(50L).setCurrentVersionId(null);
        assertTechnicalFailure();
    }

    @Test void unsupportedCheckModeWithAgeIsTechnicalFailure() {
        dependencies();
        StdRoutingRuleDO rule = ruleMapper.selectByPolicyVersionId(11L).getFirst();
        rule.setRouteMode("AUTO");
        rule.setPrimarySupplierId(null);
        // 动态候选模式固定 UNSUPPORTED；专用候选夹具验证损坏检查模式。
        rule.setDistributorMode("SPECIFIED"); rule.setProjectMode("SPECIFIED");
        rule.setProductTypeMode("SPECIFIED"); rule.setSupplierMode("SPECIFIED");
        when(scopeMapper.selectByRuleIds(List.of(12L))).thenReturn(List.of(
                StdRoutingRuleScopeDO.builder().ruleId(12L).scopeKind("DISTRIBUTOR").scopeCode("BUYER").build(),
                StdRoutingRuleScopeDO.builder().ruleId(12L).scopeKind("PROJECT").scopeCode("PROJECT").build(),
                StdRoutingRuleScopeDO.builder().ruleId(12L).scopeKind("PRODUCT_TYPE").scopeCode("EQUITY_PRODUCT").build()));
        when(projectMapper.selectById(4L)).thenReturn(BuyerProjectDO.builder().id(4L).buyerId(1L)
                .status((short) 10).projectCode("PROJECT").build());
        input = new RoutingInput(1L, 4L, 2L, "DIRECT_RECHARGE", 1, now, Map.of());
        when(candidateMapper.selectByRuleId(12L)).thenReturn(List.of(StdRoutingCandidateDO.builder()
                .ruleId(12L).supplierId(20L).candidatePriority(0).inventoryCheckMode("UNSUPPORTED")
                .inventoryMaxAgeSeconds(10).balanceCheckMode("UNSUPPORTED").healthCheckMode("UNSUPPORTED").build()));
        assertTechnicalFailure();
    }

    private void assertRecoverable(String reason) {
        Throwable failure = catchThrowable(() -> routing.route(input));
        assertThat(failure).isNotNull();
        assertThat(failure.getClass().getSimpleName()).isEqualTo("SupplyQualificationUnavailableException");
        assertThat(failure).hasMessageContaining(reason);
    }

    private void assertTechnicalFailure() {
        Throwable failure = catchThrowable(() -> routing.route(input));
        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(failure.getClass().getSimpleName()).isNotEqualTo("SupplyQualificationUnavailableException");
    }

    private SupplierRoutingPolicyDO policy() {
        SupplierRoutingPolicyDO policy = SupplierRoutingPolicyDO.builder().id(10L).build();
        when(policyMapper.selectActiveByType("STANDARD_PRODUCT")).thenReturn(List.of(policy));
        return policy;
    }

    private SupplierRoutingPolicyVersionDO version() {
        policy();
        SupplierRoutingPolicyVersionDO version = SupplierRoutingPolicyVersionDO.builder()
                .id(11L).policyId(10L).versionNo(1).configDigest(new byte[32])
                .validFrom(now.minusDays(1)).validTo(now.plusDays(1)).build();
        when(versionMapper.selectEffective(10L, now)).thenReturn(List.of(version));
        return version;
    }

    private StdRoutingRuleDO rule() {
        version();
        StdRoutingRuleDO rule = StdRoutingRuleDO.builder().id(12L).ruleCode("FIXED")
                .policyVersionId(11L).status((short) 10).routeMode("FIXED").primarySupplierId(20L)
                .distributorMode("ALL").projectMode("ALL").productTypeMode("ALL")
                .supplierMode("ALL").strategyPriority1("STABLE")
                .strategyPriority2("PRICE").strategyPriority3("COMPOSITE").build();
        when(ruleMapper.selectByPolicyVersionId(11L)).thenReturn(List.of(rule));
        return rule;
    }

    private SupplierOfferDO offer() {
        rule();
        when(supplierMapper.selectById(20L)).thenReturn(SupplierDO.builder().id(20L)
                .supplierCode("SUP").supplierName("供应商").status((short) 10).build());
        SupplierOfferDO offer = SupplierOfferDO.builder().id(30L).supplierId(20L)
                .platformSkuId(2L).currentVersionId(31L).status((short) 10).build();
        when(offerMapper.selectPublishedBySupplierAndSku(20L, 2L)).thenReturn(List.of(offer));
        return offer;
    }

    private SupplierOfferVersionDO offerVersion() {
        offer();
        SupplierOfferVersionDO version = SupplierOfferVersionDO.builder().id(31L).offerId(30L)
                .connectorVersionId(40L).inventorySourceId(60L).currency("CNY")
                .supplierSettlementUnitPrice(100L).minQuantity(1L).maxQuantity(1L).quantityStep(1L)
                .fulfillmentConfigJson("{}").validFrom(now.minusDays(1)).build();
        when(offerVersionMapper.selectById(31L)).thenReturn(version);
        return version;
    }

    private InventorySourceDO dependencies() {
        offerVersion();
        when(connectorVersionMapper.selectById(40L)).thenReturn(SupplierConnectorVersionDO.builder()
                .id(40L).connectorId(50L).status((short) 10).adapterConfigJson("{}").build());
        when(connectorMapper.selectById(50L)).thenReturn(SupplierConnectorDO.builder()
                .id(50L).supplierId(20L).currentVersionId(40L).status((short) 10).build());
        InventorySourceDO inventory = InventorySourceDO.builder().id(60L).supplierId(20L)
                .platformSkuId(2L).sourceType("REMOTE_ON_DEMAND").status((short) 10).build();
        when(inventorySourceMapper.selectById(60L)).thenReturn(inventory);
        return inventory;
    }
}
