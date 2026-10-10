package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOutboxCodes;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxDispatchHandler;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventDO;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 消费 {@link FulfillmentOutboxCodes#EVENT_FULFILLMENT_OPERATION_READY} 事件并调度一次
 * {@link FulfillmentTimerTaskTypes#OPERATION_EXECUTE} 持久化任务；执行与崩溃恢复由 PersistentTimer 保证，
 * 实际适配器调用在 Task 11 接入。
 *
 * <p>设计详见 {@code docs/superpowers/plans/2026-08-11-supply-chain-m1-qnm-vertical-slice.md#task-9}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FulfillmentOperationDispatchHandler implements OutboxDispatchHandler {

    public static final String TARGET_NAME = FulfillmentOutboxCodes.TARGET_FULFILLMENT_OPERATION_WORKER;
    public static final String TASK_TYPE = FulfillmentTimerTaskTypes.OPERATION_EXECUTE;

    private final PersistentTimer persistentTimer;
    private final ObjectMapper objectMapper;
    private final SupplyChainDatabaseClock databaseClock;

    @Override
    public String targetName() {
        return TARGET_NAME;
    }

    @Override
    public void dispatch(OutboxEventDO event) {
        String operationNo = extractOperationNo(event);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("operationNo", operationNo);
        payload.put("bOrderNo", event.getAggregateId());
        // 保留原受理来源；重试调度线程的 MDC 不是来源（生产闭环设计 §5.2、日志规范 §5.3）。
        if (event.getTraceId() != null && !event.getTraceId().isBlank()) {
            payload.put("originTraceId", event.getTraceId());
        }
        // “立即执行”也以数据库当前时间为准，避免应用节点时钟漂移把任务错误地推迟或提前。
        // 设计见 docs/superpowers/specs/2026-09-04-supply-chain-database-local-time-design.md#22-当前时间来源。
        persistentTimer.scheduleOnce(TASK_TYPE, operationNo, (JsonNode) payload,
                databaseClock.toInstant(databaseClock.now()));
        log.debug("Scheduled {} for operationNo={}", TASK_TYPE, operationNo);
    }

    private String extractOperationNo(OutboxEventDO event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayloadJson());
            JsonNode node = payload.get("operationNo");
            if (node == null || node.isMissingNode()) {
                throw new IllegalStateException(
                        FulfillmentOutboxCodes.EVENT_FULFILLMENT_OPERATION_READY
                                + " payload missing operationNo");
            }
            return node.asText();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse outbox payload for operation ready", e);
        }
    }
}
