package com.aifuli.module.supplychain.border.order.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 订单视图的编号投影契约：采购商单号属于总单，供应商摘要保持内部尝试身份。
 * 设计：docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#321-订单编号回显修订2026-10-14。
 */
@ExtendWith(MockitoExtension.class)
class OrderViewQueryServiceTest {
    @Mock SupplyChainOrderMapper orders;
    @Mock BOrderMapper attempts;
    @Mock SupplierMapper suppliers;
    @Mock FulfillmentRequestMapper requests;
    @Mock OrderFailureViewQuery failureViews;
    @InjectMocks OrderViewQueryService service;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void pageKeepsEachBuyerNumberIncludingOrdersWithoutAttemptsAndMissingNumbers() {
        var first = order(1L, "PLATFORM_A", "BUYER_A");
        var second = order(2L, "PLATFORM_B", "BUYER_B");
        var legacy = order(3L, "PLATFORM_LEGACY", null);
        when(orders.selectPage(any(PageParam.class), any(Wrapper.class)))
                .thenReturn(new PageResult<>(List.of(first, second, legacy), 3L));
        when(attempts.selectList(any(Wrapper.class))).thenReturn(List.of(attempt()));

        var page = service.page(new OrderViewQueryService.OrderViewPageQuery(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, 1, 20));
        JsonNode rows = json.valueToTree(page.getList());

        assertThat(rows.get(0).path("distributorOrderNo").textValue()).isEqualTo("BUYER_A");
        assertThat(rows.get(1).path("distributorOrderNo").textValue()).isEqualTo("BUYER_B");
        assertThat(rows.get(1).path("supplierOrders")).isEmpty();
        assertThat(rows.get(2).has("distributorOrderNo")).isTrue();
        assertThat(rows.get(2).path("distributorOrderNo").isNull()).isTrue();
        assertThat(rows.get(0).path("supplierOrders").get(0).path("supplierOrderNo").textValue())
                .isEqualTo("BO_ATTEMPT_A");
    }

    @Test
    void bothDetailViewsKeepTheBuyerNumberAndDoNotReplaceAttemptNumberWithExternalNumber() {
        var order = order(1L, "PLATFORM_A", "BUYER_A");
        var attempt = attempt();
        when(orders.selectByOrderNo("PLATFORM_A")).thenReturn(order);
        when(orders.selectById(1L)).thenReturn(order);
        when(attempts.selectOne(any(Wrapper.class))).thenReturn(attempt);
        when(attempts.selectList(any(Wrapper.class))).thenReturn(List.of(attempt));

        for (var view : List.of(service.get("PLATFORM_A", "PLATFORM", 9001L),
                service.get("BO_ATTEMPT_A", "SUPPLIER", 9001L))) {
            JsonNode result = json.valueToTree(view);
            assertThat(result.path("distributorOrderNo").textValue()).isEqualTo("BUYER_A");
            assertThat(result.path("orderNo").textValue()).isEqualTo("PLATFORM_A");
            assertThat(result.path("supplierOrders").get(0).path("supplierOrderNo").textValue())
                    .isEqualTo("BO_ATTEMPT_A");
        }
    }

    private static SupplyChainOrderDO order(long id, String platformNo, String buyerNo) {
        return SupplyChainOrderDO.builder().id(id).orderNo(platformNo).buyerOrderNo(buyerNo)
                .orderType(SupplyChainOrderTypes.ORDER_TYPE_CARD)
                .orderStatus(SupplyChainOrderStatus.SUCCEEDED.getCode()).quantity(1L).build();
    }

    private static BOrderDO attempt() {
        return BOrderDO.builder().id(11L).orderId(1L).bOrderNo("BO_ATTEMPT_A")
                .billno("OUTBOUND_REQUEST_A").externalOrderNo("EXTERNAL_SUPPLIER_A")
                .fulfillmentStatus(BOrderFulfillmentStatus.SUCCEEDED.getCode()).build();
    }
}
