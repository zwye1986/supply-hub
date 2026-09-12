package com.aifuli.module.supplychain.border.order;

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
import com.aifuli.module.supplychain.border.order.application.OrderAdminQueryService;
import com.aifuli.module.supplychain.border.order.application.OrderAdminQueryService.OrderPageQuery;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderType;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MySQL/Liquibase 验证平台订单列表及 CARD、O2O、INSTORE 类型化详情。
 * 设计见 {@code docs/superpowers/specs/2026-09-12-supply-chain-order-admin-readonly-design.md#7-测试与联调门禁}。
 */
class OrderAdminIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("supply_chain")
            .withUsername("supply_chain")
            .withPassword("supply_chain");

    private static final String EXTERNAL_JDBC_URL = System.getProperty("supply-chain.test.jdbc-url");

    @BeforeAll
    static void startContainer() {
        if (EXTERNAL_JDBC_URL == null) {
            MYSQL.start();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (EXTERNAL_JDBC_URL == null && MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @Test
    void readsThreeOrderTypesAndDegradesMissingDetail() {
        try (ConfigurableApplicationContext context = applicationContext()) {
            String suffix = Long.toUnsignedString(System.nanoTime(), 36);
            SupplyChainOrderMapper orderMapper = context.getBean(SupplyChainOrderMapper.class);
            FulfillmentRequestMapper fulfillmentMapper = context.getBean(FulfillmentRequestMapper.class);
            O2OChannelDetailMapper o2oMapper = context.getBean(O2OChannelDetailMapper.class);
            InstoreChannelOrderMapper instoreMapper = context.getBean(InstoreChannelOrderMapper.class);
            InstorePaymentFlowMapper flowMapper = context.getBean(InstorePaymentFlowMapper.class);
            OrderAdminQueryService service = context.getBean(OrderAdminQueryService.class);
            LocalDateTime now = LocalDateTime.of(2026, 9, 12, 13, 0);

            SupplyChainOrderDO card = insertOrder(orderMapper, suffix, "CARD", now,
                    SupplyChainOrderTypes.ORDER_TYPE_CARD, SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT);
            FulfillmentRequestDO fulfillment = FulfillmentRequestDO.builder()
                    .requestNo("FR_" + suffix).orderId(card.getId()).buyerId(101L).listingId(201L)
                    .listingVersionId(301L).platformSkuId(401L).targetSchemaCode("VOUCHER_DELIVERY_TARGET")
                    .targetSchemaVersion(1).targetCiphertext(new byte[]{1}).targetDigest(bytes(1))
                    .targetKeyVersion("test-v1").requestedQuantity(1L).fulfilledQuantity(0L)
                    .status(FulfillmentRequestStatus.FULFILLING.getCode()).aggregateVersion(1L)
                    .createdAt(now).updatedAt(now).build();
            fulfillmentMapper.insert(fulfillment);
            attachDetail(orderMapper, card, fulfillment.getId());

            SupplyChainOrderDO o2o = insertOrder(orderMapper, suffix, "O2O", now.plusSeconds(1),
                    SupplyChainOrderTypes.ORDER_TYPE_O2O, SupplyChainOrderTypes.DETAIL_TYPE_O2O_CHANNEL);
            O2OChannelDetailDO o2oDetail = O2OChannelDetailDO.builder()
                    .channelDetailNo("OD_" + suffix).orderId(o2o.getId()).buyerId(102L).buyerUserId("user-o2o")
                    .channelCode("ELEME").channelUserBindingId(501L).channelUserId("anonymous")
                    .transactionId("TX_" + suffix).supplierChannelBindingId(601L).supplierId(701L)
                    .supplierCodeSnapshot("SPL_O2O").supplierNameSnapshot("O2O供应商")
                    .externalStoreId("STORE_1").externalStoreName("O2O门店").storeSnapshotJson("{}")
                    .outTradeNo("OUT_O2O_" + suffix).channelPayAmount(200L).buyerPaidAmount(200L)
                    .buyerSettlementAmount(200L).currency("CNY").cashierReference("PAY_O2O")
                    .paymentStatus(O2OPaymentStatus.PAID.getCode()).fundStatus(O2OFundStatus.COMMITTED.getCode())
                    .channelFulfillmentStatus(O2OChannelFulfillmentStatus.PROCESSING.getCode())
                    .totalChannelRefundAmount(0L).totalBuyerRefundAmount(0L).totalAccountReturnAmount(0L)
                    .status(O2ODetailStatus.PROCESSING.getCode()).version(0L).createdAt(now).updatedAt(now).build();
            o2oMapper.insert(o2oDetail);
            attachDetail(orderMapper, o2o, o2oDetail.getId());

            SupplyChainOrderDO instore = insertOrder(orderMapper, suffix, "INSTORE", now.plusSeconds(2),
                    SupplyChainOrderTypes.ORDER_TYPE_INSTORE, SupplyChainOrderTypes.DETAIL_TYPE_INSTORE_CHANNEL);
            InstoreChannelOrderDO instoreDetail = InstoreChannelOrderDO.builder()
                    .instoreOrderNo("IO_" + suffix).orderId(instore.getId()).buyerId(103L).projectId(801L)
                    .cashierProfileVersionId(901L).sourceSystem("AIFULI_MALL").externalTenantId("tenant-1")
                    .buyerUserId("user-instore").channelCode("RT_MART").channelUserBindingId(1001L)
                    .channelOrderId("RT_" + suffix).companyCode("COMPANY").supplierChannelBindingId(1101L)
                    .supplierId(1201L).supplierCodeSnapshot("SPL_RT").supplierNameSnapshot("大润发")
                    .externalStoreId("SHOP_1").externalStoreName("大润发一店").storeSnapshotJson("{}")
                    .totalAmount(300L).totalPaidAmount(300L).currency("CNY")
                    .totalChannelRefundAmount(0L).totalBuyerRefundAmount(0L).totalAccountReturnAmount(0L)
                    .orderStatus(InstoreOrderStatus.COMPLETED.getCode()).version(0L)
                    .createdAt(now).updatedAt(now).completedAt(now).build();
            instoreMapper.insert(instoreDetail);
            attachDetail(orderMapper, instore, instoreDetail.getId());
            flowMapper.insert(InstorePaymentFlowDO.builder()
                    .paymentFlowNo("PF_" + suffix).instoreOrderId(instoreDetail.getId()).orderId(instore.getId())
                    .flowSequence(1).channelCode("RT_MART").tradeId("TRADE_" + suffix).userCode("member-1")
                    .payAmount(300L).orderType(1).payStatus(InstorePaymentFlowStatus.PAID.getCode())
                    .outTradeNo("OUT_RT_" + suffix).cashierUrl("https://secret.invalid")
                    .cashierReference("PAY_RT").buyerPaidAmount(300L).payTime(now)
                    .externalTransactionId("EXT_RT_" + suffix).requestFingerprint(bytes(2)).version(0L)
                    .createdAt(now).updatedAt(now).completedAt(now).build());

            SupplyChainOrderDO missing = insertOrder(orderMapper, suffix, "MISSING", now.plusSeconds(3),
                    SupplyChainOrderTypes.ORDER_TYPE_CARD, SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT);

            var filtered = service.page(new OrderPageQuery(card.getOrderNo(), null, null, null, null,
                    SupplyChainOrderType.CARD, SupplyChainOrderStatus.PROCESSING,
                    SupplyChainAftersaleStatus.NONE, now.minusSeconds(1), now.plusSeconds(1), 1, 20));
            assertThat(filtered.getList()).hasSize(1);
            assertThat(filtered.getList().getFirst().orderNo()).isEqualTo(card.getOrderNo());

            var cardResult = service.get(card.getOrderNo());
            assertThat(cardResult.card().requestNo()).isEqualTo("FR_" + suffix);
            assertThat(cardResult.o2o()).isNull();

            var o2oResult = service.get(o2o.getOrderNo());
            assertThat(o2oResult.o2o().transactionId()).isEqualTo("TX_" + suffix);
            assertThat(o2oResult.card()).isNull();

            var instoreResult = service.get(instore.getOrderNo());
            assertThat(instoreResult.instore().paymentFlows()).hasSize(1);
            assertThat(instoreResult.instore().paymentFlows().getFirst().tradeId()).isEqualTo("TRADE_" + suffix);

            var unavailable = service.get(missing.getOrderNo());
            assertThat(unavailable.detailAvailability()).isEqualTo(DetailAvailability.UNAVAILABLE);
            assertThat(unavailable.card()).isNull();
        }
    }

    private static SupplyChainOrderDO insertOrder(SupplyChainOrderMapper mapper, String suffix, String label,
                                                   LocalDateTime createdAt, String orderType, String detailType) {
        boolean o2o = SupplyChainOrderTypes.ORDER_TYPE_O2O.equals(orderType);
        boolean card = SupplyChainOrderTypes.ORDER_TYPE_CARD.equals(orderType);
        long settlementAmount = card ? 100L : o2o ? 200L : 300L;
        SupplyChainOrderDO order = SupplyChainOrderDO.builder()
                .orderNo("SC_" + label + "_" + suffix).orderType(orderType)
                .buyerId((long) label.hashCode() & Integer.MAX_VALUE).buyerCodeSnapshot("BYR_" + label)
                .buyerNameSnapshot(label + "分销商").buyerUserId("user-" + label)
                .buyerOrderNo(o2o ? null : "BUYER_" + label + "_" + suffix)
                .acceptanceReceiptId(card ? Math.abs((long) (label + suffix).hashCode()) + 1L : null)
                .displaySupplierId(o2o || !card ? 701L : 702L).displaySupplierCode("SPL_" + label)
                .displaySupplierName(label + "供应商").buyerSettlementAmount(settlementAmount)
                .currency("CNY").platformSkuId(card ? 401L : null).skuCodeSnapshot(card ? "SKU_1" : null)
                .skuNameSnapshot(card ? "测试商品" : null).quantity(card ? 1L : null)
                .buyerOrderDigest(card ? bytes(3) : null).orderStatus(SupplyChainOrderStatus.PROCESSING.getCode())
                .aftersaleStatus(SupplyChainAftersaleStatus.NONE.getCode()).businessStage(label + "_PROCESSING")
                .detailType(detailType).detailId(null).detailVersion(1L).requestFingerprint(bytes(4))
                .version(0L).createdAt(createdAt).updatedAt(createdAt).build();
        mapper.insert(order);
        return order;
    }

    private static void attachDetail(SupplyChainOrderMapper mapper, SupplyChainOrderDO order, Long detailId) {
        order.setDetailId(detailId);
        assertThat(mapper.updateById(order)).isEqualTo(1);
    }

    private ConfigurableApplicationContext applicationContext() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aifuli.supply-chain.enabled", true);
        properties.put("aifuli.supply-chain.datasource.url", jdbcUrl());
        properties.put("aifuli.supply-chain.datasource.username", username());
        properties.put("aifuli.supply-chain.datasource.password", password());
        properties.put("aifuli.supply-chain.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        return new SpringApplicationBuilder(TestConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(properties)
                .run();
    }

    private static byte[] bytes(int seed) {
        byte[] value = new byte[32];
        value[0] = (byte) seed;
        return value;
    }

    private static String jdbcUrl() {
        return EXTERNAL_JDBC_URL != null ? EXTERNAL_JDBC_URL : MYSQL.getJdbcUrl();
    }

    private static String username() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-username", "root") : MYSQL.getUsername();
    }

    private static String password() {
        return EXTERNAL_JDBC_URL != null
                ? System.getProperty("supply-chain.test.jdbc-password", "") : MYSQL.getPassword();
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SupplyChainDataSourceConfiguration.class, OrderAdminQueryService.class})
    static class TestConfiguration {
    }
}
