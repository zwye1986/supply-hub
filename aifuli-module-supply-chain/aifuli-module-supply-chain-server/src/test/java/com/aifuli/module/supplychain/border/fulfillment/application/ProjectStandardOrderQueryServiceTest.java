package com.aifuli.module.supplychain.border.fulfillment.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.*;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.StandardProductContext;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

class ProjectStandardOrderQueryServiceTest {
    @Test
    void processingReceiptReturnsTrueReceiptAndNullOrderFieldsWithoutDecrypt() {
        var receipts = mock(FulfillmentAcceptanceReceiptMapper.class);
        var orders = mock(SupplyChainOrderMapper.class);
        var clock = mock(SupplyChainDatabaseClock.class);
        when(clock.toInstant(any())).thenCallRealMethod();
        when(clock.now()).thenReturn(LocalDateTime.of(2026, 10, 2, 12, 0));
        when(receipts.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(
                        List.of(
                                FulfillmentAcceptanceReceiptDO.builder()
                                        .id(1L)
                                        .receiptNo("AR_1")
                                        .buyerId(2L)
                                        .projectId(3L)
                                        .buyerOrderNo("ord")
                                        .status((short) 0)
                                        .targetSchemaCode("VOUCHER_DELIVERY_TARGET")
                                        .quantity(1L)
                                        .version(1L)
                                        .updatedAt(LocalDateTime.of(2026, 10, 2, 11, 0))
                                        .build()));
        when(orders.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of());
        var service =
                new ProjectStandardOrderQueryService(
                        receipts,
                        orders,
                        mock(FulfillmentRequestMapper.class),
                        mock(FulfillmentAssetMapper.class),
                        mock(BOrderMapper.class),
                        mock(FulfillmentOperationMapper.class),
                        clock);
        var result =
                service.query(
                        new StandardProductContext(
                                3L, 2L, "app", 1, ProjectAuthOperations.STANDARD_ORDER_QUERY),
                        "ord");
        assertThat(result).isNotNull();
        assertThat(result.getRequestId()).isEqualTo("AR_1");
        assertThat(result.getOrderNo()).isNull();
        assertThat(result.getOrderStatus()).isNull();
        assertThat(result.getFulfillmentStatus()).isNull();
        assertThat(result.getDeliveredQuantity()).isZero();
        assertThat(result.getAssetAvailable()).isFalse();
        assertThat(result.getUpdatedAt().toString()).isEqualTo("2026-10-02T11:00+08:00");
    }

    @Test
    void unknownAssetTypeIsMetadataInconsistencyAndNeverAdvertisedBySummary() {
        var f = new Fixture();
        f.asset.setAssetType("UNKNOWN_ASSET");
        assertThatThrownBy(() -> f.service.query(f.context, "ord"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void directRechargeSuccessHasDeliveredQuantityButNoAssetFlag() {
        var f = new Fixture();
        f.request.setTargetSchemaCode("DIRECT_RECHARGE_TARGET");
        f.receipt.setTargetSchemaCode("DIRECT_RECHARGE_TARGET");
        f.request.setFulfilledQuantity(1L);
        f.request.setStatus((short) 40);
        f.order.setOrderStatus((short) 30);
        var result = f.service.query(f.context, "ord");
        assertThat(result.getDeliveredQuantity()).isEqualTo(1);
        assertThat(result.getAssetAvailable()).isFalse();
        verifyNoInteractions(f.assets, f.borders, f.operations);
    }

    @Test
    void unknownReceiptOrderRequestLifecycleAndSchemaFailClosed() {
        var f = new Fixture();
        f.receipt.setStatus((short) 99);
        var receipt = f;
        assertThatThrownBy(() -> receipt.service.query(receipt.context, "ord"))
                .isInstanceOf(IllegalStateException.class);
        f = new Fixture();
        f.order.setOrderStatus((short) 99);
        var order = f;
        assertThatThrownBy(() -> order.service.query(order.context, "ord"))
                .isInstanceOf(IllegalStateException.class);
        f = new Fixture();
        f.request.setStatus((short) 99);
        var request = f;
        assertThatThrownBy(() -> request.service.query(request.context, "ord"))
                .isInstanceOf(IllegalStateException.class);
        f = new Fixture();
        f.asset.setLifecycleStatus((short) 99);
        var asset = f;
        assertThatThrownBy(() -> asset.service.query(asset.context, "ord"))
                .isInstanceOf(IllegalStateException.class);
        f = new Fixture();
        f.request.setTargetSchemaCode("UNKNOWN");
        var schema = f;
        assertThatThrownBy(() -> schema.service.query(schema.context, "ord"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unbuiltNullSchemaDoesNotBlockSummary() {
        var f = new Fixture();
        f.receipt.setStatus((short) 0);
        f.receipt.setTargetSchemaCode(null);
        f.receipt.setResultOrderId(null);
        f.receipt.setResultOrderNo(null);
        f.receipt.setResultFulfillmentRequestNo(null);
        when(f.orders.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of());
        var result = f.service.query(f.context, "ord");
        assertThat(result.getOrderNo()).isNull();
        assertThat(result.getReceiptStatus().name()).isEqualTo("PROCESSING");
        assertThat(result.getAssetAvailable()).isFalse();
    }

    private static class Fixture {
        final FulfillmentAcceptanceReceiptMapper receipts =
                mock(FulfillmentAcceptanceReceiptMapper.class);
        final SupplyChainOrderMapper orders = mock(SupplyChainOrderMapper.class);
        final FulfillmentRequestMapper requests = mock(FulfillmentRequestMapper.class);
        final FulfillmentAssetMapper assets = mock(FulfillmentAssetMapper.class);
        final BOrderMapper borders = mock(BOrderMapper.class);
        final FulfillmentOperationMapper operations = mock(FulfillmentOperationMapper.class);
        final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
        final FulfillmentAcceptanceReceiptDO receipt =
                FulfillmentAcceptanceReceiptDO.builder()
                        .id(1L)
                        .receiptNo("AR_1")
                        .buyerId(2L)
                        .projectId(3L)
                        .buyerOrderNo("ord")
                        .quantity(1L)
                        .version(1L)
                        .status((short) 10)
                        .targetSchemaCode("VOUCHER_DELIVERY_TARGET")
                        .targetDigest(new byte[] {1})
                        .resultOrderId(1L)
                        .resultOrderNo("SC_1")
                        .resultFulfillmentRequestNo("FR_1")
                        .build();
        final com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO order =
                com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO
                        .builder()
                        .id(1L)
                        .projectId(3L)
                        .buyerId(2L)
                        .orderNo("SC_1")
                        .orderType("CARD")
                        .platformSkuId(1L)
                        .quantity(1L)
                        .detailType("FULFILLMENT")
                        .detailId(1L)
                        .acceptanceReceiptId(1L)
                        .orderStatus((short) 10)
                        .build();
        final FulfillmentRequestDO request =
                FulfillmentRequestDO.builder()
                        .id(1L)
                        .orderId(1L)
                        .buyerId(2L)
                        .requestNo("FR_1")
                        .targetSchemaCode("VOUCHER_DELIVERY_TARGET")
                        .targetDigest(new byte[] {1})
                        .platformSkuId(1L)
                        .requestedQuantity(1L)
                        .fulfilledQuantity(0L)
                        .status((short) 20)
                        .build();
        final FulfillmentAssetDO asset =
                FulfillmentAssetDO.builder()
                        .id(10L)
                        .fulfillmentRequestId(1L)
                        .buyerId(2L)
                        .bOrderId(10L)
                        .operationId(10L)
                        .supplierId(9L)
                        .sequenceNo(1)
                        .assetType("VOUCHER_CODE")
                        .lifecycleStatus((short) 10)
                        .build();
        final StandardProductContext context =
                new StandardProductContext(
                        3L, 2L, "app", 1, ProjectAuthOperations.STANDARD_ORDER_QUERY);
        final ProjectStandardOrderQueryService service =
                new ProjectStandardOrderQueryService(
                        receipts, orders, requests, assets, borders, operations, clock);

        Fixture() {
            when(receipts.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                    .thenReturn(List.of(receipt));
            when(orders.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                    .thenReturn(List.of(order));
            when(requests.selectById(1L)).thenReturn(request);
            when(assets.selectListByFulfillmentRequestId(1L)).thenReturn(List.of(asset));
            when(borders.selectById(10L))
                    .thenReturn(
                            BOrderDO.builder()
                                    .id(10L)
                                    .fulfillmentRequestId(1L)
                                    .orderId(1L)
                                    .supplierId(9L)
                                    .build());
            when(operations.selectById(10L))
                    .thenReturn(FulfillmentOperationDO.builder().id(10L).bOrderId(10L).build());
            when(clock.now()).thenReturn(LocalDateTime.of(2026, 10, 2, 12, 0));
            when(clock.toInstant(any())).thenCallRealMethod();
        }
    }
}
