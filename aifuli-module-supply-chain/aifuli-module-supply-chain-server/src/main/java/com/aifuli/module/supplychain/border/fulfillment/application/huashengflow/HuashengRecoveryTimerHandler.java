package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandler;
import com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;

/**
 * 华昇即时恢复任务处理器（RESULT_UNKNOWN 30秒后驱动原单QUERY）。
 * 无 @Component，由 HuashengFulfillmentConfiguration 显式条件装配。
 */
public class HuashengRecoveryTimerHandler implements PersistentTimerHandler {

    private final HuashengFulfillmentRunner runner;
    private final ObjectProvider<HuashengFulfillmentRunner> runnerProvider;

    public HuashengRecoveryTimerHandler() {
        this.runner = null;
        this.runnerProvider = null;
    }

    public HuashengRecoveryTimerHandler(HuashengFulfillmentRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.runnerProvider = null;
    }

    public HuashengRecoveryTimerHandler(ObjectProvider<HuashengFulfillmentRunner> runnerProvider) {
        this.runner = null;
        this.runnerProvider = Objects.requireNonNull(runnerProvider, "runnerProvider must not be null");
    }

    private HuashengFulfillmentRunner getRunner() {
        if (runner != null) {
            return runner;
        }
        if (runnerProvider != null) {
            return runnerProvider.getIfAvailable();
        }
        return null;
    }

    @Override
    public String taskType() {
        return HuashengFlowCodes.RECOVERY_TASK;
    }

    @Override
    public String handle(TimerTaskContext context, JsonNode payload) {
        HuashengFulfillmentRunner activeRunner = getRunner();
        if (activeRunner == null) {
            throw new IllegalStateException("HuashengFulfillmentRunner is not available");
        }
        String operationNo = payload.path("operationNo").asText(null);
        Long expectedGeneration = payload.has("expectedGeneration") && !payload.path("expectedGeneration").isNull()
                ? payload.path("expectedGeneration").asLong() : null;
        String originTraceId = payload.path("originTraceId").asText(null);
        return activeRunner.runRecovery(operationNo, expectedGeneration, originTraceId);
    }

    @Override
    public int maxAttempts() {
        return 3;
    }
}
