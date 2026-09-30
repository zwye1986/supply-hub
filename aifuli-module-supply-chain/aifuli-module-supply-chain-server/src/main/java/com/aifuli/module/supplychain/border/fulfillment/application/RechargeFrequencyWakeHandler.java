package com.aifuli.module.supplychain.border.fulfillment.application;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyRecoveryCodes;
import com.aifuli.module.supplychain.infrastructure.timer.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
/** Timer只委派应用入口，payload仅queueId/wakeGeneration；设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#6-timer-与补偿。 */
@Component
@RequiredArgsConstructor
public class RechargeFrequencyWakeHandler implements PersistentTimerHandler {
    private final RechargeFrequencyRecoveryService recovery;
    private final RechargeFrequencyQueryService query;
    public String taskType() { return FulfillmentTimerTaskTypes.RECHARGE_FREQUENCY_WAKE; }
    public String handle(TimerTaskContext context, JsonNode payload) {
        if (!payload.path("queueId").canConvertToLong() || !payload.path("wakeGeneration").canConvertToLong()) {
            throw new IllegalArgumentException(RechargeFrequencyRecoveryCodes.INVALID_WAKE_PAYLOAD);
        }
        var work = recovery.prepareWake(payload.path("queueId").longValue(), payload.path("wakeGeneration").longValue());
        if (work != null) { query.query(work, context.taskNo()); }
        return RechargeFrequencyRecoveryCodes.CHECKED;
    }
    public int maxAttempts() { return 3; }
}
