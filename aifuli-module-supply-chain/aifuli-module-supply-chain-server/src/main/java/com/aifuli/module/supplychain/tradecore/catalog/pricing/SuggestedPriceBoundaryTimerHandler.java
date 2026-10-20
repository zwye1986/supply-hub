package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.infrastructure.timer.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 旧/重复/迟到边界均重新解析当前事实，不使用任务中的历史金额，不直接写领域表。
 * 设计见 docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#62-报价生效到期与重启。
 */
@Component @RequiredArgsConstructor
public class SuggestedPriceBoundaryTimerHandler implements PersistentTimerHandler {
    public static final String TASK_TYPE = "SKU_SUGGESTED_PRICE_BOUNDARY";
    private final ObjectProvider<GenericBatchPricingService> pricing;
    @Override public String taskType() { return TASK_TYPE; }
    @Override public String handle(TimerTaskContext context, JsonNode payload) {
        JsonNode sku = payload.path("platformSkuId");
        if (!sku.isIntegralNumber() || !sku.canConvertToLong() || sku.longValue() <= 0) {
            throw new IllegalArgumentException("suggested price boundary requires platformSkuId");
        }
        // 后台执行建立独立身份，不能把复用线程上一次请求伪装成来源；异常也恢复原作用域。
        // 设计见 docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#8-计算错误与日志边界。
        var previous = org.slf4j.MDC.getCopyOfContextMap();
        try {
            org.slf4j.MDC.clear();
            org.slf4j.MDC.put("traceId", java.util.UUID.randomUUID().toString().replace("-", ""));
            org.slf4j.MDC.put("executionId", java.util.UUID.randomUUID().toString());
            if (context != null) org.slf4j.MDC.put("taskNo", context.taskNo());
            pricing.getObject().recalculatePlatformSku(new PricingDependencyChangedEvent(sku.longValue()));
            return "CURRENT_PRICE_REFRESHED";
        } finally {
            if (previous == null) org.slf4j.MDC.clear();
            else org.slf4j.MDC.setContextMap(previous);
        }
    }
}
