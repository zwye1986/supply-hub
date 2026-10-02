package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyQueueDO;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 同供应链事务保存代次任务；延迟解析Timer避免Registry构造环，缺少Timer失败关闭。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#6-timer-与补偿。 */
@Component
@RequiredArgsConstructor
public class RechargeFrequencyWakeScheduler {
    private final ObjectProvider<PersistentTimer> timer;
    private final ObjectMapper json;
    private final SupplyChainDatabaseClock clock;
    @Transactional(transactionManager="supplyChainTransactionManager", propagation=Propagation.MANDATORY, rollbackFor=Exception.class)
    public void schedule(RechargeFrequencyQueueDO queue) {
        timer.getObject().scheduleOnce(FulfillmentTimerTaskTypes.RECHARGE_FREQUENCY_WAKE,
                queue.getId()+":"+queue.getWakeGeneration(), json.createObjectNode()
                        .put("queueId",queue.getId()).put("wakeGeneration",queue.getWakeGeneration()), clock.toInstant(queue.getNextCheckAt()));
    }
    @Transactional(transactionManager="supplyChainTransactionManager", propagation=Propagation.MANDATORY, rollbackFor=Exception.class)
    public void execute(RechargeFrequencyQueueDO queue, String operationNo, String bOrderNo) {
        timer.getObject().scheduleOnce(FulfillmentTimerTaskTypes.OPERATION_EXECUTE,
                operationNo+":"+queue.getWakeGeneration(), json.createObjectNode()
                        .put("operationNo",operationNo).put("bOrderNo",bOrderNo), clock.toInstant(clock.now()));
    }
}
