package com.aifuli.module.supplychain.routing.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.NormalizedRulePatch;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.Operation;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.PriorityInput;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.SaveRequest;
import com.aifuli.module.supplychain.routing.application.RoutingRuleSaveSupport.WeightInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RoutingRuleSaveSupport} 纯校验单测：四级范围、三优先级、权重和、
 * AUTO/FIXED 候选约束与 UPDATE 编码一致性。无库、无 Spring。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#55-保存校验}。</p>
 */
class RoutingRuleSaveSupportTest {

    private final RoutingRuleSaveSupport support = new RoutingRuleSaveSupport();

    @Test
    void normalizesValidAutoRuleWithSpecifiedScopes() {
        NormalizedRulePatch patch = support.validateAndNormalize(request().build(), null, Operation.CREATE);

        assertThat(patch.name()).isEqualTo("测试规则");
        assertThat(patch.routeMode()).isEqualTo(RoutingRuleRouteMode.AUTO);
        assertThat(patch.status()).isEqualTo(RoutingRuleStatus.ENABLED);
        assertThat(patch.distributorMode()).isEqualTo(RoutingRuleScopeMode.SPECIFIED);
        assertThat(patch.distributorCodes()).containsExactly("B001");
        assertThat(patch.projectCodes()).containsExactly("P001", "P002");
        assertThat(patch.productTypeCodes()).containsExactly("MOVIE");
        assertThat(patch.orderedSupplierCodes()).containsExactly("S001", "S002");
        assertThat(patch.strategies()).containsExactly(
                RoutingRuleStrategy.STABLE, RoutingRuleStrategy.PRICE, RoutingRuleStrategy.COMPOSITE);
        assertThat(patch.weights()).isEqualTo(new WeightInput(40, 30, 30));
        assertThat(patch.maxSupplyPriceCents()).isEqualTo(9_900L);
        assertThat(patch.ruleCode()).isNull();
    }

    @Test
    void scopeModeDefaultsByCodeListPresence() {
        // 未传 mode：编码列表空 → ALL，非空 → SPECIFIED（对齐前端提交约定）
        SaveRequest request = request()
                .distributorMode(null).distributorCodes(List.of())
                .projectMode(null).projectCodes(List.of("P001"))
                .supplierMode(RoutingRuleScopeMode.ALL).supplierCodes(List.of())
                .build();
        NormalizedRulePatch patch = support.validateAndNormalize(request, null, Operation.CREATE);
        assertThat(patch.distributorMode()).isEqualTo(RoutingRuleScopeMode.ALL);
        assertThat(patch.distributorCodes()).isEmpty();
        assertThat(patch.projectMode()).isEqualTo(RoutingRuleScopeMode.SPECIFIED);
    }

    @Test
    void rejectsBlankOrNullScopeCodesInSpecifiedMode() {
        SaveRequest request = request()
                .distributorMode(RoutingRuleScopeMode.SPECIFIED).distributorCodes(List.of("  ", ""))
                .build();
        assertThatThrownBy(() -> support.validateAndNormalize(request, null, Operation.CREATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("适用分销商指定范围不能为空");
    }

    @Test
    void rejectsCodesAttachedToAllScope() {
        SaveRequest request = request()
                .projectMode(RoutingRuleScopeMode.ALL).projectCodes(List.of("P001"))
                .build();
        assertThatThrownBy(() -> support.validateAndNormalize(request, null, Operation.CREATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("为全部时不能携带编码列表");
    }

    @Test
    void rejectsDuplicatedScopeCodes() {
        SaveRequest request = request()
                .productTypeMode(RoutingRuleScopeMode.SPECIFIED)
                .productTypeCodes(List.of("MOVIE", "MOVIE"))
                .build();
        assertThatThrownBy(() -> support.validateAndNormalize(request, null, Operation.CREATE))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("相同适用范围");
    }

    @Test
    void rejectsScopeCombinationOutsidePrdTiers() {
        SaveRequest distributorOnly = request()
                .projectMode(RoutingRuleScopeMode.ALL).projectCodes(List.of())
                .productTypeMode(RoutingRuleScopeMode.ALL).productTypeCodes(List.of())
                .supplierMode(RoutingRuleScopeMode.ALL).supplierCodes(List.of())
                .build();
        assertThatThrownBy(() -> support.validateAndNormalize(distributorOnly, null, Operation.CREATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("五级匹配层级");
    }

    @Test
    void rejectsPrioritiesNotCoveringAllStrategies() {
        SaveRequest request = request()
                .priorities(List.of(
                        new PriorityInput("1", RoutingRuleStrategy.STABLE),
                        new PriorityInput("2", RoutingRuleStrategy.PRICE),
                        new PriorityInput("3", RoutingRuleStrategy.PRICE)))
                .build();
        assertThatThrownBy(() -> support.validateAndNormalize(request, null, Operation.CREATE))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsWeightsNotSummingTo100() {
        SaveRequest request = request().weights(new WeightInput(40, 40, 30)).build();
        assertThatThrownBy(() -> support.validateAndNormalize(request, null, Operation.CREATE))
                .isInstanceOf(Exception.class);
    }

    @Test
    void requiresWeightsWhenCompositePresent() {
        SaveRequest request = request().weights(null).build();
        assertThatThrownBy(() -> support.validateAndNormalize(request, null, Operation.CREATE))
                .isInstanceOf(Exception.class);
    }

    @Test
    void weightsOptionalWhenCompositeAbsent() {
        SaveRequest request = request()
                .weights(null)
                .priorities(List.of(
                        new PriorityInput("1", RoutingRuleStrategy.PRICE),
                        new PriorityInput("2", RoutingRuleStrategy.STABLE),
                        new PriorityInput("3", null)))
                .build();
        // 第三位缺失策略也不允许：三优先级必须覆盖全部策略
        assertThatThrownBy(() -> support.validateAndNormalize(request, null, Operation.CREATE))
                .isInstanceOf(Exception.class);
    }

    @Test
    void fixedRuleRequiresPrimarySupplier() {
        SaveRequest request = request()
                .routeMode(RoutingRuleRouteMode.FIXED)
                .primarySupplierCode(null)
                .build();
        assertThatThrownBy(() -> support.validateAndNormalize(request, null, Operation.CREATE))
                .isInstanceOf(Exception.class);
    }

    @Test
    void fixedRuleNormalizesToSinglePrimaryCandidate() {
        SaveRequest request = request()
                .routeMode(RoutingRuleRouteMode.FIXED)
                .primarySupplierCode("S009")
                .supplierMode(RoutingRuleScopeMode.SPECIFIED)
                .supplierCodes(List.of("S001", "S002"))
                .build();
        NormalizedRulePatch patch = support.validateAndNormalize(request, null, Operation.CREATE);
        assertThat(patch.orderedSupplierCodes()).containsExactly("S009");
        assertThat(patch.primarySupplierCode()).isEqualTo("S009");
    }

    @Test
    void autoWithAllSuppliersRejectsCandidateList() {
        SaveRequest request = request()
                .supplierMode(RoutingRuleScopeMode.ALL)
                .supplierCodes(List.of("S001"))
                .build();
        assertThatThrownBy(() -> support.validateAndNormalize(request, null, Operation.CREATE))
                .isInstanceOf(Exception.class);
    }

    @Test
    void autoWithSpecifiedSuppliersRequiresAtLeastTwoDistinct() {
        SaveRequest single = request()
                .supplierMode(RoutingRuleScopeMode.SPECIFIED).supplierCodes(List.of("S001"))
                .build();
        assertThatThrownBy(() -> support.validateAndNormalize(single, null, Operation.CREATE))
                .isInstanceOf(Exception.class);

        SaveRequest duplicated = request()
                .supplierMode(RoutingRuleScopeMode.SPECIFIED).supplierCodes(List.of("S001", "S001"))
                .build();
        assertThatThrownBy(() -> support.validateAndNormalize(duplicated, null, Operation.CREATE))
                .isInstanceOf(Exception.class);
    }

    @Test
    void updateRejectsPathBodyCodeMismatch() {
        SaveRequest request = request().ruleCode("RR20260923001").build();
        assertThatThrownBy(() -> support.validateAndNormalize(request, "RR20260923002", Operation.UPDATE))
                .isInstanceOf(Exception.class);
    }

    @Test
    void updateAcceptsMatchingBodyCode() {
        SaveRequest request = request().ruleCode("RR20260923001").build();
        NormalizedRulePatch patch = support.validateAndNormalize(request, "RR20260923001", Operation.UPDATE);
        assertThat(patch.ruleCode()).isEqualTo("RR20260923001");
    }

    @Test
    void rejectsBlankNameAndOverlongName() {
        assertThatThrownBy(() -> support.validateAndNormalize(
                request().name("  ").build(), null, Operation.CREATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("规则名称不能为空");
        assertThatThrownBy(() -> support.validateAndNormalize(
                request().name("长".repeat(129)).build(), null, Operation.CREATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("规则名称长度不能超过128");
    }

    @Test
    void scopeKeyIgnoresNameButCoversAllFourLevels() {
        NormalizedRulePatch one = support.validateAndNormalize(request().build(), null, Operation.CREATE);
        NormalizedRulePatch sameScopeDifferentName = support.validateAndNormalize(
                request().name("另一名称").build(), null, Operation.CREATE);
        NormalizedRulePatch differentSupplierOrder = support.validateAndNormalize(
                request().supplierCodes(List.of("S002", "S001")).build(), null, Operation.CREATE);
        assertThat(one.scopeKey()).isEqualTo(sameScopeDifferentName.scopeKey());
        // 候选顺序参与范围键：顺序不同视为不同配置
        assertThat(one.scopeKey()).isNotEqualTo(differentSupplierOrder.scopeKey());
    }

    /** 测试用可变请求构造器，build() 产出应用层 {@link SaveRequest} record；默认值即一条合法 AUTO 规则。 */
    private static TestRequest request() {
        return new TestRequest();
    }

    private static final class TestRequest {

        private String name = "测试规则";
        private String ruleCode;
        private RoutingRuleRouteMode routeMode = RoutingRuleRouteMode.AUTO;
        private RoutingRuleStatus status = RoutingRuleStatus.ENABLED;
        private RoutingRuleScopeMode distributorMode = RoutingRuleScopeMode.SPECIFIED;
        private List<String> distributorCodes = List.of("B001");
        private RoutingRuleScopeMode projectMode = RoutingRuleScopeMode.SPECIFIED;
        private List<String> projectCodes = List.of("P001", "P002");
        private RoutingRuleScopeMode productTypeMode = RoutingRuleScopeMode.SPECIFIED;
        private List<String> productTypeCodes = List.of("MOVIE");
        private RoutingRuleScopeMode supplierMode = RoutingRuleScopeMode.SPECIFIED;
        private List<String> supplierCodes = List.of("S001", "S002");
        private String primarySupplierCode;
        private List<PriorityInput> priorities = List.of(
                new PriorityInput("1", RoutingRuleStrategy.STABLE),
                new PriorityInput("2", RoutingRuleStrategy.PRICE),
                new PriorityInput("3", RoutingRuleStrategy.COMPOSITE));
        private WeightInput weights = new WeightInput(40, 30, 30);
        private Long maxSupplyPriceCents = 9_900L;

        TestRequest name(String value) {
            this.name = value;
            return this;
        }

        TestRequest ruleCode(String value) {
            this.ruleCode = value;
            return this;
        }

        TestRequest routeMode(RoutingRuleRouteMode value) {
            this.routeMode = value;
            return this;
        }

        TestRequest distributorMode(RoutingRuleScopeMode value) {
            this.distributorMode = value;
            return this;
        }

        TestRequest distributorCodes(List<String> value) {
            this.distributorCodes = value;
            return this;
        }

        TestRequest projectMode(RoutingRuleScopeMode value) {
            this.projectMode = value;
            return this;
        }

        TestRequest projectCodes(List<String> value) {
            this.projectCodes = value;
            return this;
        }

        TestRequest productTypeMode(RoutingRuleScopeMode value) {
            this.productTypeMode = value;
            return this;
        }

        TestRequest productTypeCodes(List<String> value) {
            this.productTypeCodes = value;
            return this;
        }

        TestRequest supplierMode(RoutingRuleScopeMode value) {
            this.supplierMode = value;
            return this;
        }

        TestRequest supplierCodes(List<String> value) {
            this.supplierCodes = value;
            return this;
        }

        TestRequest primarySupplierCode(String value) {
            this.primarySupplierCode = value;
            return this;
        }

        TestRequest priorities(List<PriorityInput> value) {
            this.priorities = value;
            return this;
        }

        TestRequest weights(WeightInput value) {
            this.weights = value;
            return this;
        }

        SaveRequest build() {
            return new SaveRequest(name, ruleCode, routeMode, status,
                    distributorMode, distributorCodes,
                    projectMode, projectCodes,
                    productTypeMode, productTypeCodes,
                    supplierMode, supplierCodes,
                    primarySupplierCode, priorities, weights, maxSupplyPriceCents);
        }
    }
}
