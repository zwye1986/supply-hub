package com.aifuli.module.supplychain.routing.application;

import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.routing.dal.dataobject.*;
import com.aifuli.module.supplychain.routing.dal.mysql.*;
import com.aifuli.module.supplychain.routing.domain.RoutingInput;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** 精确缺条件与损坏引用分类，见最小业务设计 §5；不得按异常消息把系统故障降级。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierRoutingConfigurationClassificationTest {
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
    @InjectMocks SupplierRoutingApplicationService service;

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 9, 12, 0);

    @BeforeEach
    void subject() {
        when(buyerMapper.selectById(1L)).thenReturn(BuyerDO.builder().id(1L).buyerCode("BYR_TEST").status((short) 10).build());
        when(skuMapper.selectById(2L)).thenReturn(PlatformSkuDO.builder().id(2L).fulfillmentType("DIRECT_RECHARGE").status((short) 10).build());
    }

    @Test
    void missingPolicyIsTypedBusinessCondition() {
        assertMissing("NO_ACTIVE_POLICY");
    }

    @Test
    void duplicateActivePolicyRemainsSystemFailure() {
        when(policyMapper.selectActiveByType(anyString())).thenReturn(List.of(policy(), policy()));
        assertThatThrownBy(() -> service.route(input())).isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(failure.getClass().getSimpleName()).isNotEqualTo("RoutingConfigurationMissingException"));
    }

    @Test
    void missingEffectiveVersionIsTypedBusinessCondition() {
        when(policyMapper.selectActiveByType(anyString())).thenReturn(List.of(policy()));
        assertMissing("NO_EFFECTIVE_POLICY_VERSION");
    }

    @Test
    void noMatchedRuleIsTypedBusinessCondition() {
        configuredPolicy();
        assertMissing("NO_MATCHED_RULE");
    }

    @Test
    void noPublishedSupplyIsTypedBusinessCondition() {
        configuredRule();
        assertMissing("NO_CONFIGURED_SUPPLY");
    }

    @Test
    void publishedOfferWithoutCurrentVersionCannotDisappearAsNoSupply() {
        configuredOffer();
        assertThatThrownBy(() -> service.route(input())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current version");
    }

    @Test
    void publishedOfferWithDanglingVersionCannotDisappearAsNoSupply() {
        configuredOffer();
        when(offerMapper.selectPublishedBySupplierAndSku(5L, 2L)).thenReturn(List.of(
                SupplierOfferDO.builder().id(6L).supplierId(5L).platformSkuId(2L)
                        .status((short) 10).currentVersionId(7L).build()));
        assertThatThrownBy(() -> service.route(input())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current version");
    }

    private void assertMissing(String reason) {
        assertThatThrownBy(() -> service.route(input())).satisfies(failure -> {
            assertThat(failure.getClass().getSimpleName()).isEqualTo("RoutingConfigurationMissingException");
            assertThat(failure).hasFieldOrPropertyWithValue("reason", reason);
        });
    }

    private void configuredPolicy() {
        when(policyMapper.selectActiveByType(anyString())).thenReturn(List.of(policy()));
        when(versionMapper.selectEffective(eq(3L), any())).thenReturn(List.of(
                SupplierRoutingPolicyVersionDO.builder().id(4L).policyId(3L).versionNo(1).configDigest(new byte[32]).build()));
    }

    private void configuredRule() {
        configuredPolicy();
        when(ruleMapper.selectByPolicyVersionId(4L)).thenReturn(List.of(StdRoutingRuleDO.builder()
                .id(8L).ruleCode("GLOBAL").status((short) 10).routeMode("AUTO")
                .distributorMode("ALL").projectMode("ALL").productTypeMode("ALL").supplierMode("ALL")
                .strategyPriority1("STABLE").strategyPriority2("PRICE").strategyPriority3("COMPOSITE").build()));
    }

    private void configuredOffer() {
        configuredRule();
        SupplierOfferDO offer = SupplierOfferDO.builder().id(6L).supplierId(5L).platformSkuId(2L).status((short) 10).build();
        when(offerMapper.selectPublishedBySku(2L)).thenReturn(List.of(offer));
        when(offerMapper.selectPublishedBySupplierAndSku(5L, 2L)).thenReturn(List.of(offer));
        when(supplierMapper.selectById(5L)).thenReturn(SupplierDO.builder().id(5L)
                .supplierCode("SUP_TEST").supplierName("测试供应商").status((short) 10).build());
    }

    private SupplierRoutingPolicyDO policy() {
        return SupplierRoutingPolicyDO.builder().id(3L).build();
    }

    private RoutingInput input() {
        return new RoutingInput(1L, null, 2L, "DIRECT_RECHARGE", 1L, now, Map.of());
    }
}
