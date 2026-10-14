package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentCaseVerifyExecutionService;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimerHandler;
import com.aifuli.module.supplychain.infrastructure.timer.TimerTaskContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;

/**
 * 华昇人工 Case 核实租约到期收尾 Timer 处理器（HS_CASE_VERIFY_FINALIZE）。
 * 到期仍无完整结果则把 action 终结为 UNKNOWN、清除活动意图；保留旧审计。
 * 无 @Component，由 HuashengFulfillmentConfiguration 显式条件装配。
 * 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#62-核实不是恢复购买。
 */
public class HuashengCaseVerifyFinalizeTimerHandler implements PersistentTimerHandler {

    private final FulfillmentCaseVerifyExecutionService executionService;
    private final ObjectProvider<FulfillmentCaseVerifyExecutionService> serviceProvider;

    public HuashengCaseVerifyFinalizeTimerHandler(FulfillmentCaseVerifyExecutionService executionService) {
        this.executionService = Objects.requireNonNull(executionService, "executionService must not be null");
        this.serviceProvider = null;
    }

    public HuashengCaseVerifyFinalizeTimerHandler(ObjectProvider<FulfillmentCaseVerifyExecutionService> serviceProvider) {
        this.executionService = null;
        this.serviceProvider = Objects.requireNonNull(serviceProvider, "serviceProvider must not be null");
    }

    private FulfillmentCaseVerifyExecutionService getService() {
        if (executionService != null) {
            return executionService;
        }
        if (serviceProvider != null) {
            return serviceProvider.getIfAvailable();
        }
        return null;
    }

    @Override
    public String taskType() {
        return com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes.HUASHENG_CASE_VERIFY_FINALIZE;
    }

    @Override
    public String handle(TimerTaskContext context, JsonNode payload) {
        FulfillmentCaseVerifyExecutionService service = getService();
        if (service == null) {
            throw new IllegalStateException("FulfillmentCaseVerifyExecutionService is not available");
        }
        String actionNo = payload.path("actionNo").asText(null);
        if (actionNo == null || actionNo.isBlank() || actionNo.length() > 32) {
            throw new IllegalArgumentException("Case 收尾 Timer 缺少合法 actionNo");
        }
        return service.finalizeExpiredVerify(actionNo);
    }

    @Override
    public int maxAttempts() {
        return 3;
    }
}
