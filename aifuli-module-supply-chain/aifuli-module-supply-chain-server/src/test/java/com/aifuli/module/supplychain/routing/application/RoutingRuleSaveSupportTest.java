package com.aifuli.module.supplychain.routing.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.NormalizedRulePatch;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.Operation;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.PriorityInput;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.SaveRequest;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.WeightInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_BUYER_REQUIRED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_CANDIDATE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_CODE_MISMATCH;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_FULFILLMENT_MISMATCH;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_SCOPE_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_STRATEGY_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_SUPPLIER_ALL_NOT_OPEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路由规则保存请求校验与归一化：无库、仅拒绝与候选顺序。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#4-请求体与页面字段}。
 */
class RoutingRuleSaveSupportTest {

    private final RoutingRuleSaveSupport support = new RoutingRuleSaveSupport();

    @Test
    void happyFixedSingleBuyer() {
        NormalizedRulePatch patch = support.validateAndNormalize(
                baseFixed().build(), null, Operation.CREATE);

        assertThat(patch.name()).isEqualTo("话费固定规则");
        assertThat(patch.buyerCode()).isEqualTo("BYR_1");
        assertThat(patch.skuCode()).isEqualTo("SKU_1");
        assertThat(patch.fulfillmentType()).isEqualTo("DIRECT_RECHARGE");
        assertThat(patch.routeMode()).isEqualTo(RoutingRuleRouteMode.FIXED);
        assertThat(patch.orderedSupplierCodes()).containsExactly("SPL_A");
        assertThat(patch.ruleCode()).isNull();
    }

    @Test
    void zeroBuyerRejected() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                baseFixed().distributorCodes(List.of()).build(), null, Operation.CREATE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_BUYER_REQUIRED.getCode());
    }

    @Test
    void twoBuyersRejected() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                baseFixed().distributorCodes(List.of("BYR_1", "BYR_2")).build(),
                null, Operation.CREATE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_BUYER_REQUIRED.getCode());
    }

    @Test
    void projectCodesNonEmptyRejected() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                baseFixed().projectCodes(List.of("PRJ_1")).build(), null, Operation.CREATE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_SCOPE_NOT_OPEN.getCode());
    }

    @Test
    void supplierModeAllRejected() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                baseFixed().supplierMode(RoutingRuleScopeMode.ALL).build(),
                null, Operation.CREATE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_SUPPLIER_ALL_NOT_OPEN.getCode());
    }

    @Test
    void autoWithOneSupplierRejected() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                baseAuto().supplierCodes(List.of("SPL_A")).build(), null, Operation.CREATE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_CANDIDATE_INVALID.getCode());
    }

    @Test
    void weightsNotFortyThirtyThirtyRejected() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                baseFixed().weights(new WeightInput(50, 30, 20)).build(),
                null, Operation.CREATE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_STRATEGY_NOT_OPEN.getCode());
    }

    @Test
    void updatePathBodyRuleCodeMismatchRejected() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                baseFixed().ruleCode("RR_BODY").build(), "RR_PATH", Operation.UPDATE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_CODE_MISMATCH.getCode());
    }

    @Test
    void happyAutoTwoSuppliersPreservesOrder() {
        NormalizedRulePatch patch = support.validateAndNormalize(
                baseAuto().supplierCodes(List.of("SPL_B", "SPL_A")).build(),
                null, Operation.CREATE);

        assertThat(patch.routeMode()).isEqualTo(RoutingRuleRouteMode.AUTO);
        assertThat(patch.orderedSupplierCodes()).containsExactly("SPL_B", "SPL_A");
        assertThat(patch.ruleCode()).isNull();
    }

    @Test
    void projectModeSpecifiedRejected() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                baseFixed().projectMode(RoutingRuleScopeMode.SPECIFIED).build(),
                null, Operation.CREATE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_SCOPE_NOT_OPEN.getCode());
    }

    @Test
    void productTypeModeSpecifiedRejected() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                baseFixed().productTypeMode(RoutingRuleScopeMode.SPECIFIED).build(),
                null, Operation.CREATE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_SCOPE_NOT_OPEN.getCode());
    }

    @Test
    void productTypeCodesNonEmptyRejected() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                baseFixed().productTypeCodes(List.of("PT_1")).build(), null, Operation.CREATE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_SCOPE_NOT_OPEN.getCode());
    }

    @Test
    void statusDisabledRejected() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                baseFixed().status(RoutingRuleStatus.DISABLED).build(), null, Operation.CREATE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_STRATEGY_NOT_OPEN.getCode());
    }

    @Test
    void prioritiesFirstNotStableRejected() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                baseFixed().priorities(List.of(
                        new PriorityInput("p1", RoutingRuleStrategy.PRICE),
                        new PriorityInput("p2", RoutingRuleStrategy.STABLE),
                        new PriorityInput("p3", RoutingRuleStrategy.COMPOSITE))).build(),
                null, Operation.CREATE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_STRATEGY_NOT_OPEN.getCode());
    }

    @Test
    void fixedMissingPrimarySupplierRejected() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                baseFixed().primarySupplierCode(null).build(), null, Operation.CREATE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_CANDIDATE_INVALID.getCode());
    }

    @Test
    void illegalFulfillmentTypeRejected() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                baseFixed().fulfillmentType("PHYSICAL_SHIP").build(), null, Operation.CREATE))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ROUTING_RULE_FULFILLMENT_MISMATCH.getCode());
    }

    private static SaveRequestBuilder baseFixed() {
        return new SaveRequestBuilder()
                .name(" 话费固定规则 ")
                .distributorCodes(List.of("BYR_1"))
                .skuCode("SKU_1")
                .fulfillmentType("DIRECT_RECHARGE")
                .routeMode(RoutingRuleRouteMode.FIXED)
                .primarySupplierCode("SPL_A");
    }

    private static SaveRequestBuilder baseAuto() {
        return new SaveRequestBuilder()
                .name("话费自动规则")
                .distributorCodes(List.of("BYR_1"))
                .skuCode("SKU_1")
                .fulfillmentType("DIRECT_RECHARGE")
                .routeMode(RoutingRuleRouteMode.AUTO)
                .supplierCodes(List.of("SPL_A", "SPL_B"));
    }

    /** 测试用可变构造，避免每个用例手写超长 record 字面量。 */
    private static final class SaveRequestBuilder {
        private String name;
        private List<String> distributorCodes;
        private String skuCode;
        private String fulfillmentType;
        private RoutingRuleRouteMode routeMode;
        private String primarySupplierCode;
        private List<String> supplierCodes;
        private RoutingRuleScopeMode supplierMode;
        private RoutingRuleScopeMode distributorMode;
        private RoutingRuleStatus status;
        private String ruleCode;
        private List<String> projectCodes;
        private RoutingRuleScopeMode projectMode;
        private List<String> productTypeCodes;
        private RoutingRuleScopeMode productTypeMode;
        private List<PriorityInput> priorities;
        private WeightInput weights;

        SaveRequestBuilder name(String name) {
            this.name = name;
            return this;
        }

        SaveRequestBuilder distributorCodes(List<String> distributorCodes) {
            this.distributorCodes = distributorCodes;
            return this;
        }

        SaveRequestBuilder skuCode(String skuCode) {
            this.skuCode = skuCode;
            return this;
        }

        SaveRequestBuilder fulfillmentType(String fulfillmentType) {
            this.fulfillmentType = fulfillmentType;
            return this;
        }

        SaveRequestBuilder routeMode(RoutingRuleRouteMode routeMode) {
            this.routeMode = routeMode;
            return this;
        }

        SaveRequestBuilder primarySupplierCode(String primarySupplierCode) {
            this.primarySupplierCode = primarySupplierCode;
            return this;
        }

        SaveRequestBuilder supplierCodes(List<String> supplierCodes) {
            this.supplierCodes = supplierCodes;
            return this;
        }

        SaveRequestBuilder supplierMode(RoutingRuleScopeMode supplierMode) {
            this.supplierMode = supplierMode;
            return this;
        }

        SaveRequestBuilder projectCodes(List<String> projectCodes) {
            this.projectCodes = projectCodes;
            return this;
        }

        SaveRequestBuilder projectMode(RoutingRuleScopeMode projectMode) {
            this.projectMode = projectMode;
            return this;
        }

        SaveRequestBuilder productTypeCodes(List<String> productTypeCodes) {
            this.productTypeCodes = productTypeCodes;
            return this;
        }

        SaveRequestBuilder productTypeMode(RoutingRuleScopeMode productTypeMode) {
            this.productTypeMode = productTypeMode;
            return this;
        }

        SaveRequestBuilder status(RoutingRuleStatus status) {
            this.status = status;
            return this;
        }

        SaveRequestBuilder priorities(List<PriorityInput> priorities) {
            this.priorities = priorities;
            return this;
        }

        SaveRequestBuilder ruleCode(String ruleCode) {
            this.ruleCode = ruleCode;
            return this;
        }

        SaveRequestBuilder weights(WeightInput weights) {
            this.weights = weights;
            return this;
        }

        SaveRequest build() {
            return new SaveRequest(
                    name, distributorCodes, skuCode, fulfillmentType, routeMode,
                    primarySupplierCode, supplierCodes, supplierMode, distributorMode, status,
                    ruleCode, projectCodes, projectMode, productTypeCodes, productTypeMode,
                    priorities, weights);
        }
    }
}
