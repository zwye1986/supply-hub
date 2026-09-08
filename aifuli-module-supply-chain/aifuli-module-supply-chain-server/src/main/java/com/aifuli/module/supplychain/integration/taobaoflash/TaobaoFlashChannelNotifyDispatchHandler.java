package com.aifuli.module.supplychain.integration.taobaoflash;

import com.aifuli.module.supplychain.border.o2o.domain.O2OOutboxCodes;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxDispatchHandler;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventDO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 消费 O2O 支付/退款通知 Outbox 事件，按 eventType 路由到 {@link TaobaoFlashChannelPort}
 * 投递到渠道服务 CHANNEL_NOTIFY 端点。
 *
 * <p>targetName 对齐 {@link O2OOutboxCodes#TARGET_TAOGAO_FLASH_CHANNEL}。
 * 支付通知 → {@code notifyPayment}；退款通知 → {@code notifyRefund}。
 * 抛出异常视为投递失败，由 Outbox 引擎按退避重试。详见
 * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaobaoFlashChannelNotifyDispatchHandler implements OutboxDispatchHandler {

    public static final String TARGET_NAME = O2OOutboxCodes.TARGET_TAOGAO_FLASH_CHANNEL;

    private final TaobaoFlashChannelPort taobaoFlashChannelPort;
    private final ObjectMapper objectMapper;

    @Override
    public String targetName() {
        return TARGET_NAME;
    }

    @Override
    public void dispatch(OutboxEventDO event) {
        JsonNode payload = parsePayload(event);
        String eventType = event.getEventType();
        log.info("[O2O][outbox] dispatch 入口 eventType={}, eventId={}, aggregateId={}, traceId={}, payload={}",
                eventType, event.getEventId(), event.getAggregateId(), event.getTraceId(), event.getPayloadJson());

        if (O2OOutboxCodes.EVENT_O2O_PAYMENT_NOTIFY.equals(eventType)) {
            taobaoFlashChannelPort.notifyPayment(toPaymentNotify(payload, event));
        } else if (O2OOutboxCodes.EVENT_O2O_REFUND_NOTIFY.equals(eventType)) {
            taobaoFlashChannelPort.notifyRefund(toRefundNotify(payload, event));
        } else {
            throw new IllegalStateException("Unsupported O2O channel notify eventType: " + eventType);
        }
        log.info("[O2O][outbox] dispatch 完成 eventType={}, eventId={}", eventType, event.getEventId());
    }

    private JsonNode parsePayload(OutboxEventDO event) {
        try {
            return objectMapper.readTree(event.getPayloadJson());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse outbox payloadJson, eventId=" + event.getEventId(), e);
        }
    }

    private TaobaoFlashChannelPort.ChannelPaymentNotify toPaymentNotify(JsonNode payload, OutboxEventDO event) {
        return new TaobaoFlashChannelPort.ChannelPaymentNotify(
                text(payload, "channelCode"),
                text(payload, "transactionId"),
                text(payload, "outTradeNo"),
                payload.get("payAmount").asLong(),
                text(payload, "paymentStatus"),
                event.getTraceId());
    }

    private TaobaoFlashChannelPort.ChannelRefundNotify toRefundNotify(JsonNode payload, OutboxEventDO event) {
        return new TaobaoFlashChannelPort.ChannelRefundNotify(
                text(payload, "channelCode"),
                text(payload, "channelRefundNo"),
                text(payload, "transactionId"),
                payload.get("refundAmount").asLong(),
                text(payload, "refundStatus"),
                event.getTraceId());
    }

    private String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
