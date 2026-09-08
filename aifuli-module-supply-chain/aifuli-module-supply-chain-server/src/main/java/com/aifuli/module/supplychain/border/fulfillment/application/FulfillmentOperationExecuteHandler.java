package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandler;
import com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 履约操作执行处理器：M1 阶段为桩实现，仅记录日志并完成定时任务。
 *
 * <p>真实执行（调用奇那梦适配器、推进 Operation/BOrder 状态、写资产与回调）在 Task 10/11 接入
 * 适配器后替换 {@link #handle} 方法体；恢复与重试语义由 PersistentTimer 保证。</p>
 *
 * <p>设计详见 {@code docs/superpowers/plans/2026-08-11-supply-chain-m1-qnm-vertical-slice.md#task-11}。</p>
 */
@Slf4j
@Component
public class FulfillmentOperationExecuteHandler implements PersistentTimerHandler {

    public static final String TASK_TYPE = FulfillmentTimerTaskTypes.OPERATION_EXECUTE;
    private static final String RESULT_PENDING_ADAPTER = "PENDING_ADAPTER";

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public String handle(TimerTaskContext context, JsonNode payload) {
        String operationNo = payload.path("operationNo").asText(null);
        // TODO Task 10/11：接入奇那梦适配器，执行 Operation 并推进 BOrder/资产/回调状态。
        //   见 docs/superpowers/plans/2026-08-11-supply-chain-m1-qnm-vertical-slice.md#task-11
        log.info("FulfillmentOperationExecuteHandler M1 stub: operationNo={}, taskId={} "
                + "(real adapter execution pending Task 10/11)", operationNo, context.taskId());
        return RESULT_PENDING_ADAPTER;
    }

    @Override
    public int maxAttempts() {
        return 3;
    }
}
