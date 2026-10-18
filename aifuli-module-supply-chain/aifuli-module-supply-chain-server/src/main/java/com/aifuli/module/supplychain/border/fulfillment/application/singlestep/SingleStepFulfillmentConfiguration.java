package com.aifuli.module.supplychain.border.fulfillment.application.singlestep;

import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes;
import com.aifuli.module.supplychain.infrastructure.timer.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 单步骤持久任务注册；延迟获取Runner/Lifecycle以避免Timer注册与业务调度的容器循环依赖。
 * 不注册供应商协议，不启动额外线程池；任务来源仍由显式Metadata及原单围栏控制。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#47-t03本地执行与恢复接线2026-10-18。
 */
@Configuration(proxyBeanMethods = false)
public class SingleStepFulfillmentConfiguration {
    @Bean PersistentTimerHandler singleStepQuery(ObjectProvider<SingleStepFulfillmentRunner> runner,
                                                ObjectProvider<SingleStepFulfillmentLifecycle> lifecycle) {
        return new Handler(FulfillmentTimerTaskTypes.SINGLE_STEP_QUERY, runner, lifecycle);
    }
    @Bean PersistentTimerHandler singleStepWatchdog(ObjectProvider<SingleStepFulfillmentRunner> runner,
                                                   ObjectProvider<SingleStepFulfillmentLifecycle> lifecycle) {
        return new Handler(FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG, runner, lifecycle);
    }
    @Bean PersistentTimerHandler singleStepPreparationWatchdog(ObjectProvider<SingleStepFulfillmentRunner> runner,
                                                              ObjectProvider<SingleStepFulfillmentLifecycle> lifecycle) {
        return new Handler(FulfillmentTimerTaskTypes.SINGLE_STEP_PREPARATION_WATCHDOG, runner, lifecycle);
    }

    private record Handler(String taskType, ObjectProvider<SingleStepFulfillmentRunner> runner,
                           ObjectProvider<SingleStepFulfillmentLifecycle> lifecycle) implements PersistentTimerHandler {
        @Override public String handle(TimerTaskContext context, JsonNode payload) {
            var operation = payload.path("operationNo");
            if (!operation.isTextual() || operation.textValue().isBlank()) throw new IllegalArgumentException("单步骤任务缺少原操作号");
            if (FulfillmentTimerTaskTypes.SINGLE_STEP_PREPARATION_WATCHDOG.equals(taskType)) {
                return lifecycle.getObject().preparationFailed(operation.textValue(), 0, true);
            }
            var generation = payload.path("expectedGeneration");
            if (!generation.isIntegralNumber() || !generation.canConvertToLong() || generation.longValue() <= 0) {
                throw new IllegalArgumentException("单步骤任务缺少合法围栏代次");
            }
            var traceId = payload.path("originTraceId").asText(null);
            return FulfillmentTimerTaskTypes.SINGLE_STEP_QUERY.equals(taskType)
                    ? runner.getObject().runRecovery(operation.textValue(), generation.longValue(), traceId)
                    : lifecycle.getObject().watchdog(operation.textValue(), generation.longValue(), traceId);
        }
        @Override public int maxAttempts() { return 3; }
    }
}
