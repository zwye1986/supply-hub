package com.aifuli.module.supplychain.border.order.domain;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 前端契约枚举映射全表单测：bizType / 平台状态 / 供应商状态 / 路由失败原因。
 * 映射表冻结于设计 §4，改动此测试须先改设计文档。
 * 设计见
 * {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#4-枚举映射表}。
 */
class OrderViewMappingsTest {

    // ---------------------------------------------------------------- bizType

    @Test
    void cardVoucherFulfillmentMapsToVirtualCardByDefault() {
        assertThat(OrderViewMappings.bizType("CARD", "CARD_CODE_SECRET", null))
                .isEqualTo("VIRTUAL_CARD");
        assertThat(OrderViewMappings.bizType("CARD", "VOUCHER_CODE", "FOOD"))
                .isEqualTo("VIRTUAL_CARD");
        assertThat(OrderViewMappings.bizType("CARD", "VOUCHER_URL", "EQUITY_PRODUCT"))
                .isEqualTo("VIRTUAL_CARD");
    }

    @Test
    void cardVoucherWithMovieOrDiningProductTypeOverridesVirtualCard() {
        // 决策5查证结论：电影票/点餐码走 CARD 券码履约，按 SPU 商品类型细分
        assertThat(OrderViewMappings.bizType("CARD", "VOUCHER_CODE", "MOVIE"))
                .isEqualTo("MOVIE");
        assertThat(OrderViewMappings.bizType("CARD", "CARD_CODE_SECRET", "DINING"))
                .isEqualTo("DINING");
    }

    @Test
    void cardRechargeFulfillmentMapsToVirtualRecharge() {
        assertThat(OrderViewMappings.bizType("CARD", "DIRECT_RECHARGE", null))
                .isEqualTo("VIRTUAL_RECHARGE");
        assertThat(OrderViewMappings.bizType("CARD", "ACCOUNT_BOUND_BENEFIT", "MOVIE"))
                .isEqualTo("VIRTUAL_RECHARGE");
    }

    @Test
    void o2oAlwaysMapsToHemaAndInstoreToInstore() {
        // 现网 O2O 渠道码仅 ELEME（场景 HEMA），见设计 §4.1 查证记录
        assertThat(OrderViewMappings.bizType("O2O", null, null)).isEqualTo("HEMA");
        assertThat(OrderViewMappings.bizType("INSTORE", null, null)).isEqualTo("INSTORE");
    }

    // ------------------------------------------------------------ 平台状态

    @Test
    void platformStatusGroupsProcessingLikeStates() {
        // 人工状态独立展示，不混入处理中。
        assertThat(OrderViewMappings.platformStatus(SupplyChainOrderStatus.CREATED))
                .isEqualTo("PROCESSING");
        assertThat(OrderViewMappings.platformStatus(SupplyChainOrderStatus.PROCESSING))
                .isEqualTo("PROCESSING");
        assertThat(OrderViewMappings.platformStatus(SupplyChainOrderStatus.PARTIALLY_SUCCEEDED))
                .isEqualTo("PROCESSING");
        assertThat(OrderViewMappings.platformStatus(SupplyChainOrderStatus.MANUAL))
                .isEqualTo("MANUAL");
    }

    @Test
    void platformStatusMapsTerminalStates() {
        assertThat(OrderViewMappings.platformStatus(SupplyChainOrderStatus.SUCCEEDED))
                .isEqualTo("SUCCESS");
        assertThat(OrderViewMappings.platformStatus(SupplyChainOrderStatus.FAILED))
                .isEqualTo("FAILED");
        assertThat(OrderViewMappings.platformStatus(SupplyChainOrderStatus.CANCELLED))
                .isEqualTo("CLOSED");
    }

    // ---------------------------------------------------------- 供应商状态

    @Test
    void supplierStatusGroupsWaitShipStates() {
        assertThat(OrderViewMappings.supplierStatus(BOrderFulfillmentStatus.WAITING))
                .isEqualTo("WAIT_SHIP");
        assertThat(OrderViewMappings.supplierStatus(BOrderFulfillmentStatus.MANUAL))
                .isEqualTo("MANUAL");
        assertThat(OrderViewMappings.supplierStatus(BOrderFulfillmentStatus.CANCELLED))
                .isEqualTo("CANCELLED");
    }

    @Test
    void supplierStatusGroupsShippedStates() {
        // 决策4：B 端 10 执行中 → SHIPPED（已提交供应商执行）
        assertThat(OrderViewMappings.supplierStatus(BOrderFulfillmentStatus.PENDING))
                .isEqualTo("SHIPPED");
        assertThat(OrderViewMappings.supplierStatus(BOrderFulfillmentStatus.PROCESSING))
                .isEqualTo("SHIPPED");
    }

    @Test
    void supplierStatusMapsTerminalStates() {
        assertThat(OrderViewMappings.supplierStatus(BOrderFulfillmentStatus.SUCCEEDED))
                .isEqualTo("FINISHED");
        assertThat(OrderViewMappings.supplierStatus(BOrderFulfillmentStatus.FAILED))
                .isEqualTo("FAILED");
    }

    // -------------------------------------------------------- 路由失败原因

    @Test
    void routeFailReasonMapsResultClasses() {
        assertThat(OrderViewMappings.routeFailReason("REJECTED"))
                .isEqualTo("SUPPLIER_REJECT");
        assertThat(OrderViewMappings.routeFailReason("RESULT_UNKNOWN"))
                .isEqualTo("API_TIMEOUT");
        // 兜底：无法归类的失败 → API_ERROR
        assertThat(OrderViewMappings.routeFailReason(null)).isEqualTo("API_ERROR");
        assertThat(OrderViewMappings.routeFailReason("ACCEPTED")).isEqualTo("API_ERROR");
        assertThat(OrderViewMappings.routeFailReason("MANUAL_REQUIRED")).isEqualTo("API_ERROR");
        assertThat(OrderViewMappings.routeFailReason("SOMETHING_ELSE")).isEqualTo("API_ERROR");
    }

    // ------------------------------------------------------ 履约类型判定

    @Test
    void fulfillmentTypeClassification() {
        assertThat(OrderViewMappings.isRechargeFulfillment("DIRECT_RECHARGE")).isTrue();
        assertThat(OrderViewMappings.isRechargeFulfillment("ACCOUNT_BOUND_BENEFIT")).isTrue();
        assertThat(OrderViewMappings.isRechargeFulfillment("VOUCHER_CODE")).isFalse();
        assertThat(OrderViewMappings.isVoucherFulfillment("CARD_CODE_SECRET")).isTrue();
        assertThat(OrderViewMappings.isVoucherFulfillment("VOUCHER_CODE")).isTrue();
        assertThat(OrderViewMappings.isVoucherFulfillment("VOUCHER_URL")).isTrue();
        assertThat(OrderViewMappings.isVoucherFulfillment("DIRECT_RECHARGE")).isFalse();
        assertThat(OrderViewMappings.isVoucherFulfillment(null)).isFalse();
    }
}
