package com.aifuli.module.supplychain.border.order.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAssetMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.OrderStatusLogMapper;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.StandardPricingQuoteDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.StandardPricingQuoteMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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
    @Mock FulfillmentAssetMapper assets;
    @Mock SupplyChainDatabaseClock clock;
    @Mock OrderFailureViewQuery failureViews;
    @Mock OrderStatusLogMapper statusLogs;
    @Mock StandardPricingQuoteMapper quotes;
    @Mock ProjectSkuPricingVersionMapper projectVersions;
    @Mock BuyerSkuPricingVersionMapper buyerVersions;
    @InjectMocks OrderViewQueryService service;

    @Spy private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

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
        order.setBuyerSettlementAmount(2250L);
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
            assertThat(result.path("projectPayAmount").longValue()).isEqualTo(2250L);
            assertThat(result.path("distributorPayAmount").longValue()).isEqualTo(2250L);
        }
    }

    @ParameterizedTest
    @CsvSource(value = {"false,2250,2250", "true,2500,NULL", "false,0,0"}, nullValues = "NULL")
    void detailReadsFrozenBaseAndExplicitProjectPrice(boolean inherited, long finalPrice, Long exclusivePrice) {
        var order = order(1L, "PLATFORM_A", "BUYER_A");
        order.setPricingQuoteId(101L);
        order.setBuyerSettlementAmount(finalPrice);
        when(orders.selectByOrderNo("PLATFORM_A")).thenReturn(order);
        when(quotes.selectBatchIds(any())).thenReturn(List.of(StandardPricingQuoteDO.builder()
                .id(101L).projectPricingVersionId(201L).distributionUnitPriceCents(finalPrice).build()));
        when(projectVersions.selectById(201L)).thenReturn(ProjectSkuPricingVersionDO.builder()
                .id(201L).buyerPricingVersionId(301L).distributionPriceCents(finalPrice)
                .supplyPriceCents(100L).overrideJson("{\"distributionInherited\":" + inherited + "}").build());
        when(buyerVersions.selectById(301L)).thenReturn(BuyerSkuPricingVersionDO.builder()
                .id(301L).distributionPriceCents(2500L).build());

        JsonNode result = json.valueToTree(service.get("PLATFORM_A", "PLATFORM", 9001L));
        JsonNode item = result.path("items").get(0);
        assertThat(item.path("baseDistPrice").longValue()).isEqualTo(2500L);
        assertThat(item.path("distPrice").longValue()).isEqualTo(finalPrice);
        assertThat(result.path("distPrice").longValue()).isEqualTo(finalPrice);
        assertThat(result.path("projectPayAmount").longValue()).isEqualTo(finalPrice);
        if (exclusivePrice == null) assertThat(item.path("projectPrice").isNull()).isTrue();
        else assertThat(item.path("projectPrice").longValue()).isEqualTo(exclusivePrice);
        assertThat(item.path("supplyPrice").isNull()).isTrue();

        JsonNode replay = json.valueToTree(service.get("PLATFORM_A", "PLATFORM", 9001L));
        assertThat(replay).isEqualTo(result);
    }

    @Test
    void platformSuccessAndSupplierDetailUseTheirOwnAttemptUnitPriceAndName() {
        var order = order(1L, "PLATFORM_A", "BUYER_A");
        order.setDisplaySupplierName("首次供应商");
        order.setQuantity(5L);
        var failed = pricedAttempt(11L, 1, 41L, BOrderFulfillmentStatus.FAILED, 1900L);
        var succeeded = pricedAttempt(12L, 2, 42L, BOrderFulfillmentStatus.SUCCEEDED, 1800L);
        when(orders.selectByOrderNo("PLATFORM_A")).thenReturn(order);
        when(orders.selectById(1L)).thenReturn(order);
        when(attempts.selectList(any(Wrapper.class))).thenReturn(List.of(failed, succeeded));
        when(attempts.selectOne(any(Wrapper.class))).thenReturn(failed);
        var supplierA = SupplierDO.builder().id(41L).supplierName("供应商甲").build();
        var supplierB = SupplierDO.builder().id(42L).supplierName("供应商乙").build();
        when(suppliers.selectBatchIds(any())).thenReturn(List.of(supplierA, supplierB));
        when(suppliers.selectById(41L)).thenReturn(supplierA);

        JsonNode platform = json.valueToTree(service.get("PLATFORM_A", "PLATFORM", 9001L));
        assertThat(platform.path("supplierName").textValue()).isEqualTo("供应商乙");
        assertThat(platform.path("items").get(0).path("supplyPrice").longValue()).isEqualTo(1800L);
        assertThat(platform.path("supplierOrders").size()).isEqualTo(2);
        JsonNode supplier = json.valueToTree(service.get(failed.getBOrderNo(), "SUPPLIER", 9001L));
        assertThat(supplier.path("supplierName").textValue()).isEqualTo("供应商甲");
        assertThat(supplier.path("items").get(0).path("supplyPrice").longValue()).isEqualTo(1900L);
        assertThat(order.getDisplaySupplierName()).isEqualTo("首次供应商");
    }

    @Test
    void processingUsesRequestPointerRatherThanStaleActiveFlagsAndKeepsUnknownWhenPointerIsMissing() {
        var order = order(1L, "PLATFORM_A", "BUYER_A");
        order.setOrderStatus(SupplyChainOrderStatus.PROCESSING.getCode());
        order.setDisplaySupplierName("首次供应商");
        var old = pricedAttempt(11L, 1, 41L, BOrderFulfillmentStatus.FAILED, 1900L);
        var current = pricedAttempt(12L, 2, 42L, BOrderFulfillmentStatus.PROCESSING, 1750L);
        when(orders.selectByOrderNo("PLATFORM_A")).thenReturn(order);
        when(attempts.selectList(any(Wrapper.class))).thenReturn(List.of(old, current));
        when(suppliers.selectBatchIds(any())).thenReturn(List.of(
                SupplierDO.builder().id(41L).supplierName("供应商甲").build(),
                SupplierDO.builder().id(42L).supplierName("供应商乙").build()));
        var request = FulfillmentRequestDO.builder().orderId(1L).activeBOrderId(12L).build();
        when(requests.selectByOrderId(1L)).thenReturn(request);

        JsonNode result = json.valueToTree(service.get("PLATFORM_A", "PLATFORM", 9001L));
        assertThat(result.path("supplierName").textValue()).isEqualTo("供应商乙");
        assertThat(result.path("items").get(0).path("supplyPrice").longValue()).isEqualTo(1750L);
        request.setActiveBOrderId(999L);
        JsonNode unknown = json.valueToTree(service.get("PLATFORM_A", "PLATFORM", 9001L));
        assertThat(unknown.path("items").get(0).path("supplyPrice").isNull()).isTrue();
        assertThat(unknown.path("supplierName").isNull()).isTrue();
    }

    @Test
    void failedOrderUsesLastAttemptAndLegacyPricesStayNullInsteadOfZero() {
        var order = order(1L, "PLATFORM_A", "BUYER_A");
        order.setOrderStatus(SupplyChainOrderStatus.FAILED.getCode());
        when(orders.selectByOrderNo("PLATFORM_A")).thenReturn(order);
        when(attempts.selectList(any(Wrapper.class))).thenReturn(List.of(
                pricedAttempt(11L, 1, null, BOrderFulfillmentStatus.FAILED, 1900L),
                pricedAttempt(12L, 2, null, BOrderFulfillmentStatus.FAILED, 1700L)));

        JsonNode result = json.valueToTree(service.get("PLATFORM_A", "PLATFORM", 9001L));
        JsonNode item = result.path("items").get(0);
        assertThat(item.path("supplyPrice").longValue()).isEqualTo(1700L);
        assertThat(item.path("baseDistPrice").isNull()).isTrue();
        assertThat(item.path("projectPrice").isNull()).isTrue();
        assertThat(result.path("projectPayAmount").isNull()).isTrue();
        assertThat(result.path("distributorPayAmount").isNull()).isTrue();
    }

    private static BOrderDO pricedAttempt(long id, int number, Long supplierId,
                                         BOrderFulfillmentStatus status, long unitPrice) {
        return BOrderDO.builder().id(id).orderId(1L).bOrderNo("BO_" + id).attemptNo(number)
                .supplierId(supplierId).fulfillmentStatus(status.getCode()).activeFlag(true)
                .requestedQuantity(5L).supplierSettlementUnitPrice(unitPrice)
                .expectedSupplierSettlementAmount(unitPrice * 5).build();
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
