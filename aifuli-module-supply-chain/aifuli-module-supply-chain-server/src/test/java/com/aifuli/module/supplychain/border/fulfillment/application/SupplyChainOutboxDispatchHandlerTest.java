package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOutboxCodes;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventDO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 履约与采购商 Outbox 派发处理器单测。
 */
class SupplyChainOutboxDispatchHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SupplyChainOrderEventDispatchHandler orderEventHandler = new SupplyChainOrderEventDispatchHandler(objectMapper);
    private final BuyerEventRpcDispatchHandler buyerEventHandler = new BuyerEventRpcDispatchHandler(objectMapper);

    @Test
    void supplyChainOrderEventTargetNameMatchesCode() {
        assertThat(orderEventHandler.targetName()).isEqualTo(FulfillmentOutboxCodes.TARGET_SUPPLY_CHAIN_ORDER_EVENT);
        assertThat(orderEventHandler.targetName()).isEqualTo("supply-chain-order-event");
    }

    @Test
    void supplyChainOrderEventDispatchesSuccessfully() {
        OutboxEventDO event = OutboxEventDO.builder()
                .eventId("EVT_001")
                .eventType("ORDER_STATUS_CHANGED")
                .aggregateId("SC_ORDER_001")
                .traceId("TRACE_001")
                .payloadJson("{\"orderNo\":\"SC_ORDER_001\",\"orderStatus\":\"30\"}")
                .build();

        assertThatCode(() -> orderEventHandler.dispatch(event)).doesNotThrowAnyException();
    }

    @Test
    void supplyChainOrderEventThrowsOnInvalidJson() {
        OutboxEventDO event = OutboxEventDO.builder()
                .eventId("EVT_002")
                .eventType("ORDER_STATUS_CHANGED")
                .aggregateId("SC_ORDER_002")
                .traceId("TRACE_002")
                .payloadJson("invalid json {{{")
                .build();

        assertThatThrownBy(() -> orderEventHandler.dispatch(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to parse supply chain order event payload");
    }

    @Test
    void buyerEventRpcTargetNameMatchesCode() {
        assertThat(buyerEventHandler.targetName()).isEqualTo(FulfillmentOutboxCodes.TARGET_BUYER_EVENT_RPC);
        assertThat(buyerEventHandler.targetName()).isEqualTo("buyer-event-rpc");
    }

    @Test
    void buyerEventRpcDispatchesSuccessfully() {
        OutboxEventDO event = OutboxEventDO.builder()
                .eventId("EVT_003")
                .eventType("BUYER_FULFILLMENT_NOTIFIED")
                .aggregateId("SC_ORDER_003")
                .traceId("TRACE_003")
                .payloadJson("{\"orderNo\":\"SC_ORDER_003\",\"buyerOrderNo\":\"BUYER_001\",\"fulfillmentRequestNo\":\"FR_001\",\"reservationNo\":\"RES_001\"}")
                .build();

        assertThatCode(() -> buyerEventHandler.dispatch(event)).doesNotThrowAnyException();
    }

    @Test
    void buyerEventRpcThrowsOnInvalidJson() {
        OutboxEventDO event = OutboxEventDO.builder()
                .eventId("EVT_004")
                .eventType("BUYER_FULFILLMENT_NOTIFIED")
                .aggregateId("SC_ORDER_004")
                .traceId("TRACE_004")
                .payloadJson("not a json")
                .build();

        assertThatThrownBy(() -> buyerEventHandler.dispatch(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to parse buyer event payload");
    }
}
