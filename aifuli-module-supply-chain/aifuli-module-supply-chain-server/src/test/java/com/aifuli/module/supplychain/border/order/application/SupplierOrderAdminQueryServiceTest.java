package com.aifuli.module.supplychain.border.order.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentInitialStatuses;
import com.aifuli.module.supplychain.border.order.application.SupplierOrderAdminQueryService.SupplierOrderPageQuery;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus;
import com.aifuli.module.supplychain.border.order.domain.BOrderSettlementStatus;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderType;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ORDER_QUERY_TIME_RANGE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLY_CHAIN_B_ORDER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 供应商订单只读契约：列表只读 B 单，详情不暴露幂等键与内部主键。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-supplier-order-admin-readonly-design.md#7-测试门禁}。
 */
@ExtendWith(MockitoExtension.class)
class SupplierOrderAdminQueryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 21, 12, 0);

    @Mock private BOrderMapper bOrderMapper;
    @Mock private SupplyChainOrderMapper orderMapper;
    @Mock private SupplierMapper supplierMapper;
    @Mock private PlatformSkuMapper skus;
    @Mock private PlatformSpuMapper spus;
    @Mock private SupplierOfferMapper offers;
    @Mock private OrderFailureViewQuery failureViews;
    @InjectMocks private SupplierOrderAdminQueryService service;

    @Test
    void pageMapsAttemptsNewestFirstAndHidesInternalFields() {
        BOrderDO newer = attempt(2L, "BO_2", 1L, 11L, 2, false);
        newer.setCreatedAt(NOW.plusMinutes(1));
        when(bOrderMapper.selectPage(any(PageParam.class), any(Wrapper.class)))
                .thenReturn(new PageResult<>(List.of(newer, attempt(1L, "BO_1", 1L, 11L, 1, true)), 2L));
        when(orderMapper.selectBatchIds(any())).thenReturn(List.of(order(1L, "SC_CARD")));
        when(supplierMapper.selectBatchIds(any())).thenReturn(List.of(supplier(11L, "SPL_A", "甲供应商")));

        var result = service.page(query());

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getList()).extracting(view -> view.bOrderNo()).containsExactly("BO_2", "BO_1");
        assertThat(result.getList().getFirst().orderNo()).isEqualTo("SC_CARD");
        assertThat(result.getList().getFirst().orderType()).isEqualTo(SupplyChainOrderType.CARD);
        assertThat(result.getList().getFirst().supplierCode()).isEqualTo("SPL_A");
        assertThat(result.getList().getFirst().fulfillmentStatus()).isEqualTo(BOrderFulfillmentStatus.WAITING);
        assertThat(result.getList().getFirst().settlementStatus()).isEqualTo(BOrderSettlementStatus.PENDING);
        assertThat(result.getList().get(1).active()).isTrue();
        assertThat(result.getList().getFirst().supplierSettlementUnitPrice()).isEqualTo(9_800L);
        assertThat(result.getList().getFirst().getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("id", "idempotencyKey", "offerId", "offerVersionId",
                        "connectorVersionId", "inventorySourceId", "version");
    }

    @Test
    void pageReturnsEmptyWhenSupplierCodeDoesNotResolve() {
        when(supplierMapper.selectByCode("SPL_MISSING")).thenReturn(null);

        var result = service.page(new SupplierOrderPageQuery(null, null, null, "SPL_MISSING",
                null, null, null, 1, 20));

        assertThat(result.getList()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(bOrderMapper, never()).selectPage(any(PageParam.class), any(Wrapper.class));
    }

    @Test
    void pageReturnsEmptyWhenOrderNoDoesNotResolve() {
        when(orderMapper.selectByOrderNo("SC_MISSING")).thenReturn(null);

        var result = service.page(new SupplierOrderPageQuery(null, "SC_MISSING", null, null,
                null, null, null, 1, 20));

        assertThat(result.getList()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(bOrderMapper, never()).selectPage(any(PageParam.class), any(Wrapper.class));
    }

    @Test
    void rejectsReversedCreatedTimeRange() {
        SupplierOrderPageQuery query = new SupplierOrderPageQuery(null, null, null, null, null,
                NOW, NOW.minusSeconds(1), 1, 20);

        assertThatThrownBy(() -> service.page(query))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ORDER_QUERY_TIME_RANGE_INVALID.getCode());
        verifyNoInteractions(bOrderMapper);
    }

    @Test
    void getReturnsMappedAttemptAndRejectsMissing() {
        when(bOrderMapper.selectOne(any(Wrapper.class))).thenReturn(attempt(1L, "BO_1", 1L, 11L, 1, true));
        when(orderMapper.selectById(1L)).thenReturn(order(1L, "SC_CARD"));
        when(supplierMapper.selectById(11L)).thenReturn(supplier(11L, "SPL_A", "甲供应商"));

        var view = service.get("BO_1");

        assertThat(view.bOrderNo()).isEqualTo("BO_1");
        assertThat(view.attemptNo()).isEqualTo(1);
        assertThat(view.billno()).isEqualTo("BILL_1");
        assertThat(view.buyerCode()).isEqualTo("BYR_1");
        assertThat(view.skuCode()).isEqualTo("SKU_1");

        when(bOrderMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.get("BO_MISSING"))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLY_CHAIN_B_ORDER_NOT_FOUND.getCode());
    }

    @Test
    void listAndDetailDistinguishCardAndRechargeAndFollowSelectedOfferCurrentMode() {
        var card = order(1L, "SC_CARD");
        card.setPlatformSkuId(101L);
        var recharge = order(2L, "SC_RECHARGE");
        recharge.setPlatformSkuId(102L);
        var first = attempt(1L, "BO_1", 1L, 11L, 1, true);
        var second = attempt(2L, "BO_2", 2L, 11L, 1, true);
        second.setOfferId(25L);
        var selectedOffer = SupplierOfferDO.builder().id(21L).offerSupplyMode("DROP_SHIP").build();
        when(bOrderMapper.selectPage(any(PageParam.class), any(Wrapper.class)))
                .thenReturn(new PageResult<>(List.of(second, first), 2L));
        when(orderMapper.selectBatchIds(any())).thenReturn(List.of(card, recharge));
        when(skus.selectBatchIds(any())).thenReturn(List.of(
                PlatformSkuDO.builder().id(101L).spuId(201L).fulfillmentType("CARD_CODE_SECRET").build(),
                PlatformSkuDO.builder().id(102L).spuId(202L).fulfillmentType("DIRECT_RECHARGE").build()));
        when(spus.selectBatchIds(any())).thenReturn(List.of(
                PlatformSpuDO.builder().id(201L).productType("EQUITY_PRODUCT").build(),
                PlatformSpuDO.builder().id(202L).productType("EQUITY_PRODUCT").build()));
        when(offers.selectBatchIds(any())).thenReturn(List.of(selectedOffer,
                SupplierOfferDO.builder().id(25L).offerSupplyMode("BATCH").build()));
        var json = new ObjectMapper().findAndRegisterModules();
        var rows = json.valueToTree(service.page(query()).getList());
        assertThat(rows.get(0).path("bizType").textValue()).isEqualTo("VIRTUAL_RECHARGE");
        assertThat(rows.get(0).path("supplyMode").textValue()).isEqualTo("BATCH");
        assertThat(rows.get(1).path("bizType").textValue()).isEqualTo("VIRTUAL_CARD");
        assertThat(rows.get(1).path("supplyMode").textValue()).isEqualTo("DROP_SHIP");

        when(bOrderMapper.selectOne(any(Wrapper.class))).thenReturn(first);
        when(orderMapper.selectById(1L)).thenReturn(card);
        var detail = json.valueToTree(service.get("BO_1"));
        assertThat(detail.path("bizType")).isEqualTo(rows.get(1).path("bizType"));
        assertThat(detail.path("supplyMode")).isEqualTo(rows.get(1).path("supplyMode"));
        selectedOffer.setOfferSupplyMode("STORE_PICKUP");
        assertThat(json.valueToTree(service.get("BO_1")).path("supplyMode").textValue())
                .isEqualTo("STORE_PICKUP");
        selectedOffer.setOfferSupplyMode(null);
        assertThat(json.valueToTree(service.get("BO_1")).path("supplyMode").isNull()).isTrue();
    }

    @Test
    void missingSkuAndOfferStayUnknownRatherThanInventingCardOrApiMode() {
        when(bOrderMapper.selectOne(any(Wrapper.class))).thenReturn(attempt(1L, "BO_1", 1L, 11L, 1, true));
        when(orderMapper.selectById(1L)).thenReturn(order(1L, "SC_CARD"));
        var result = new ObjectMapper().findAndRegisterModules().valueToTree(service.get("BO_1"));
        assertThat(result.has("bizType")).isTrue();
        assertThat(result.path("bizType").isNull()).isTrue();
        assertThat(result.has("supplyMode")).isTrue();
        assertThat(result.path("supplyMode").isNull()).isTrue();
    }

    private static SupplierOrderPageQuery query() {
        return new SupplierOrderPageQuery(null, null, null, null, null, null, null, 1, 20);
    }

    private static BOrderDO attempt(long id, String bOrderNo, long orderId, long supplierId,
                                    int attemptNo, boolean active) {
        return BOrderDO.builder().id(id).bOrderNo(bOrderNo).fulfillmentRequestId(9L)
                .orderId(orderId).attemptNo(attemptNo).supplierId(supplierId)
                .offerId(21L).offerVersionId(22L).connectorVersionId(23L).inventorySourceId(24L)
                .billno("BILL_" + attemptNo).idempotencyKey("SECRET_" + attemptNo)
                .requestedQuantity(1L).fulfilledQuantity(0L)
                .supplierSettlementUnitPrice(9_800L).expectedSupplierSettlementAmount(9_800L)
                .currency("CNY").fulfillmentStatus(FulfillmentInitialStatuses.B_ORDER_PENDING)
                .settlementStatus(FulfillmentInitialStatuses.B_ORDER_SETTLEMENT_PENDING)
                .activeFlag(active ? Boolean.TRUE : null).version(0L)
                .createdAt(NOW).updatedAt(NOW).build();
    }

    private static SupplyChainOrderDO order(long id, String orderNo) {
        return SupplyChainOrderDO.builder().id(id).orderNo(orderNo)
                .orderType(SupplyChainOrderTypes.ORDER_TYPE_CARD)
                .buyerCodeSnapshot("BYR_1").buyerNameSnapshot("分销商").buyerOrderNo("BUYER_1")
                .skuCodeSnapshot("SKU_1").skuNameSnapshot("商品").build();
    }

    private static SupplierDO supplier(long id, String code, String name) {
        return SupplierDO.builder().id(id).supplierCode(code).supplierName(name).build();
    }
}
