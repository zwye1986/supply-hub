package com.aifuli.module.supplychain.border.order.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.order.dto.DetailAvailability;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainAftersaleStatus;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentRequestStatus;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreChannelOrderDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreChannelOrderMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOrderStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstorePaymentFlowStatus;
import com.aifuli.module.supplychain.border.o2o.dal.dataobject.O2OChannelDetailDO;
import com.aifuli.module.supplychain.border.o2o.dal.mysql.O2OChannelDetailMapper;
import com.aifuli.module.supplychain.border.o2o.domain.O2OChannelFulfillmentStatus;
import com.aifuli.module.supplychain.border.o2o.domain.O2ODetailStatus;
import com.aifuli.module.supplychain.border.o2o.domain.O2OFundStatus;
import com.aifuli.module.supplychain.border.o2o.domain.O2OPaymentStatus;
import com.aifuli.module.supplychain.border.order.application.OrderAdminQueryService.OrderPageQuery;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderType;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.DETAIL_UNAVAILABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ORDER_QUERY_TIME_RANGE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLY_CHAIN_ORDER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 平台订单查询只读契约：列表只读总表，详情按类型单分支加载并剔除敏感字段。
 * 设计见 {@code docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md#7-测试与联调门禁}。
 */
class OrderAdminQueryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 12, 12, 0);

    private final SupplyChainOrderMapper orderMapper = mock(SupplyChainOrderMapper.class);
    private final FulfillmentRequestMapper fulfillmentMapper = mock(FulfillmentRequestMapper.class);
    private final O2OChannelDetailMapper o2oMapper = mock(O2OChannelDetailMapper.class);
    private final InstoreChannelOrderMapper instoreMapper = mock(InstoreChannelOrderMapper.class);
    private final InstorePaymentFlowMapper flowMapper = mock(InstorePaymentFlowMapper.class);
    private OrderAdminQueryService service;

    @BeforeEach
    void setUp() {
        service = new OrderAdminQueryService(orderMapper, fulfillmentMapper, o2oMapper, instoreMapper, flowMapper);
    }

    @Test
    void pageReadsOnlySummaryTableAndMapsStableStatuses() {
        SupplyChainOrderDO order = order(1L, "SC_CARD", SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT);
        when(orderMapper.selectPage(any(PageParam.class), any(Wrapper.class)))
                .thenReturn(new PageResult<>(List.of(order), 1L));

        var result = service.page(query());

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getList().getFirst().orderType()).isEqualTo(SupplyChainOrderType.CARD);
        assertThat(result.getList().getFirst().orderStatus()).isEqualTo(SupplyChainOrderStatus.PROCESSING);
        assertThat(result.getList().getFirst().aftersaleStatus()).isEqualTo(SupplyChainAftersaleStatus.NONE);
        verifyNoInteractions(fulfillmentMapper, o2oMapper, instoreMapper, flowMapper);
    }

    @Test
    void rejectsReversedCreatedTimeRange() {
        OrderPageQuery query = new OrderPageQuery(null, null, null, null, null, null, null, null,
                NOW, NOW.minusSeconds(1), 1, 20);

        assertThatThrownBy(() -> service.page(query))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ORDER_QUERY_TIME_RANGE_INVALID.getCode());
        verifyNoInteractions(orderMapper);
    }

    @Test
    void returnsCardDetailWithoutTargetCiphertextOrAssets() {
        SupplyChainOrderDO order = order(1L, "SC_CARD", SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT);
        when(orderMapper.selectByOrderNo("SC_CARD")).thenReturn(order);
        when(fulfillmentMapper.selectByOrderId(1L)).thenReturn(FulfillmentRequestDO.builder()
                .requestNo("FR_1").targetSchemaCode("VOUCHER_DELIVERY_TARGET").targetSchemaVersion(1)
                .targetCiphertext(new byte[]{1, 2, 3}).targetDigest(new byte[]{4, 5})
                .targetKeyVersion("secret-version").requestedQuantity(1L).fulfilledQuantity(0L)
                .status(FulfillmentRequestStatus.FULFILLING.getCode()).createdAt(NOW).updatedAt(NOW).build());

        var detail = service.get("SC_CARD");

        assertThat(detail.detailAvailability()).isEqualTo(DetailAvailability.AVAILABLE);
        assertThat(detail.card().requestNo()).isEqualTo("FR_1");
        assertThat(detail.card().status()).isEqualTo(FulfillmentRequestStatus.FULFILLING);
        assertThat(detail.o2o()).isNull();
        assertThat(detail.instore()).isNull();
        assertThat(detail.card().getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("targetCiphertext", "targetDigest", "targetKeyVersion", "assets");
        verifyNoInteractions(o2oMapper, instoreMapper, flowMapper);
    }

    @Test
    void returnsO2ODetailWithoutAnonymousUserAndRawStoreJson() {
        SupplyChainOrderDO order = order(2L, "SC_O2O", SupplyChainOrderTypes.ORDER_TYPE_O2O,
                SupplyChainOrderTypes.DETAIL_TYPE_O2O_CHANNEL);
        when(orderMapper.selectByOrderNo("SC_O2O")).thenReturn(order);
        when(o2oMapper.selectByOrderId(2L)).thenReturn(O2OChannelDetailDO.builder()
                .channelDetailNo("OD_1").channelCode("ELEME").transactionId("TX_1")
                .channelUserId("anonymous-secret").storeSnapshotJson("{\"address\":\"secret\"}")
                .supplierCodeSnapshot("SPL_1").supplierNameSnapshot("供应商")
                .externalStoreId("STORE_1").externalStoreName("门店")
                .outTradeNo("OUT_1").channelPayAmount(100L).buyerPaidAmount(100L)
                .buyerSettlementAmount(100L).currency("CNY").cashierReference("PAY_1")
                .paymentStatus(O2OPaymentStatus.PAID.getCode()).fundStatus(O2OFundStatus.COMMITTED.getCode())
                .channelFulfillmentStatus(O2OChannelFulfillmentStatus.PROCESSING.getCode())
                .totalChannelRefundAmount(0L).totalBuyerRefundAmount(0L).totalAccountReturnAmount(0L)
                .status(O2ODetailStatus.PROCESSING.getCode()).createdAt(NOW).updatedAt(NOW).build());

        var detail = service.get("SC_O2O");

        assertThat(detail.o2o().paymentStatus()).isEqualTo(O2OPaymentStatus.PAID);
        assertThat(detail.o2o().channelFulfillmentStatus()).isEqualTo(O2OChannelFulfillmentStatus.PROCESSING);
        assertThat(detail.o2o().getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("buyerUserId", "channelUserId", "storeSnapshotJson");
        verifyNoInteractions(fulfillmentMapper, instoreMapper, flowMapper);
    }

    @Test
    void returnsInstoreFlowsInMapperOrderWithoutCashierUrl() {
        SupplyChainOrderDO order = order(3L, "SC_INSTORE", SupplyChainOrderTypes.ORDER_TYPE_INSTORE,
                SupplyChainOrderTypes.DETAIL_TYPE_INSTORE_CHANNEL);
        when(orderMapper.selectByOrderNo("SC_INSTORE")).thenReturn(order);
        when(instoreMapper.selectByOrderId(3L)).thenReturn(InstoreChannelOrderDO.builder()
                .id(30L).instoreOrderNo("IO_1").channelCode("RT_MART").channelOrderId("RT_1")
                .companyCode("COMPANY").supplierCodeSnapshot("SPL_RT").supplierNameSnapshot("大润发")
                .externalStoreId("SHOP_1").externalStoreName("一店").totalAmount(200L).totalPaidAmount(200L)
                .currency("CNY").totalChannelRefundAmount(0L).totalBuyerRefundAmount(0L)
                .totalAccountReturnAmount(0L).orderStatus(InstoreOrderStatus.COMPLETED.getCode())
                .createdAt(NOW).updatedAt(NOW).completedAt(NOW).build());
        when(flowMapper.selectListByInstoreOrder(30L)).thenReturn(List.of(
                flow(1, "TRADE_1"), flow(2, "TRADE_2")));

        var detail = service.get("SC_INSTORE");

        assertThat(detail.instore().paymentFlows()).extracting(flow -> flow.flowSequence())
                .containsExactly(1, 2);
        assertThat(detail.instore().paymentFlows().getFirst().payStatus())
                .isEqualTo(InstorePaymentFlowStatus.PAID);
        assertThat(detail.instore().paymentFlows().getFirst().getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("cashierUrl", "userCode", "requestFingerprint", "fundReservationNo");
        verifyNoInteractions(fulfillmentMapper, o2oMapper);
    }

    @Test
    void degradesMissingDetailButRejectsMissingSummary() {
        SupplyChainOrderDO order = order(4L, "SC_MISSING_DETAIL", SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT);
        when(orderMapper.selectByOrderNo("SC_MISSING_DETAIL")).thenReturn(order);

        var detail = service.get("SC_MISSING_DETAIL");

        assertThat(detail.summary().orderNo()).isEqualTo("SC_MISSING_DETAIL");
        assertThat(detail.detailAvailability()).isEqualTo(DetailAvailability.UNAVAILABLE);
        assertThat(detail.detailErrorCode()).isEqualTo(DETAIL_UNAVAILABLE);
        assertThat(detail.card()).isNull();

        assertThatThrownBy(() -> service.get("SC_NOT_FOUND"))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLY_CHAIN_ORDER_NOT_FOUND.getCode());
    }

    private static OrderPageQuery query() {
        return new OrderPageQuery(null, null, null, null, null, null, null, null,
                null, null, 1, 20);
    }

    private static SupplyChainOrderDO order(long id, String orderNo, String type, String detailType) {
        return SupplyChainOrderDO.builder().id(id).orderNo(orderNo).orderType(type)
                .buyerCodeSnapshot("BYR_1").buyerNameSnapshot("分销商")
                .buyerOrderNo(type.equals(SupplyChainOrderTypes.ORDER_TYPE_O2O) ? null : "BUYER_1")
                .displaySupplierCode("SPL_1").displaySupplierName("供应商")
                .buyerSettlementAmount(100L).currency("CNY").skuCodeSnapshot("SKU_1")
                .skuNameSnapshot("商品").quantity(1L).orderStatus(SupplyChainOrderStatus.PROCESSING.getCode())
                .aftersaleStatus(SupplyChainAftersaleStatus.NONE.getCode()).businessStage("PROCESSING")
                .detailType(detailType).detailVersion(1L).createdAt(NOW).updatedAt(NOW).build();
    }

    private static InstorePaymentFlowDO flow(int sequence, String tradeId) {
        return InstorePaymentFlowDO.builder().paymentFlowNo("PF_" + sequence).flowSequence(sequence)
                .tradeId(tradeId).payAmount(100L).orderType(1)
                .payStatus(InstorePaymentFlowStatus.PAID.getCode()).outTradeNo("OUT_" + sequence)
                .cashierUrl("https://secret.example").cashierReference("PAY_" + sequence)
                .buyerPaidAmount(100L).payTime(NOW).externalTransactionId("EXT_" + sequence)
                .fundReservationNo("RES_SECRET").createdAt(NOW).updatedAt(NOW).completedAt(NOW).build();
    }
}
