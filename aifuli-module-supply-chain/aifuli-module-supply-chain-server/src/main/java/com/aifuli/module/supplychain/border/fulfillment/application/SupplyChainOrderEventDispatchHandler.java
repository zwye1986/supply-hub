package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOutboxCodes;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxDispatchHandler;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventDO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 供应链中台订单事件 Outbox 派发处理器：消费投递到 {@link FulfillmentOutboxCodes#TARGET_SUPPLY_CHAIN_ORDER_EVENT}
 * 的总订单状态变更等本地事件。
 *
 * <p>消除 Outbox 派发引擎中因未注册 handler 导致的崩溃隐患。
 * 设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupplyChainOrderEventDispatchHandler implements OutboxDispatchHandler {

    public static final String TARGET_NAME = FulfillmentOutboxCodes.TARGET_SUPPLY_CHAIN_ORDER_EVENT;

    private final ObjectMapper objectMapper;

    @Override
    public String targetName() {
        return TARGET_NAME;
    }

    @Override
    public void dispatch(OutboxEventDO event) {
        log.info("[订单事件][outbox] dispatch 入口 eventType={}, eventId={}, aggregateId={}, traceId={}",
                event.getEventType(), event.getEventId(), event.getAggregateId(), event.getTraceId());
        try {
            JsonNode payload = objectMapper.readTree(event.getPayloadJson());
            String orderNo = payload.path("orderNo").asText(event.getAggregateId());
            String orderStatus = payload.path("orderStatus").asText(null);
            log.info("[订单事件][outbox] 处理订单状态事件 orderNo={}, orderStatus={}", orderNo, orderStatus);
        } catch (Exception e) {
            log.warn("[订单事件][outbox] 解析载荷异常 eventId={}, payloadLength={}",
                    event.getEventId(), event.getPayloadJson() == null ? 0 : event.getPayloadJson().length(), e);
            throw new IllegalStateException("Failed to parse supply chain order event payload, eventId=" + event.getEventId(), e);
        }
        log.info("[订单事件][outbox] dispatch 完成 eventId={}", event.getEventId());
    }
}
