package com.aifuli.module.supplychain.border.fulfillment.application.singlestep;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.SingleStepCapacityWaitDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.SingleStepCapacityWaitMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepAction;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;

/**
 * 容量等待持久化，只在调用者已持原单聚合锁的事务内推进；不制造供应商事实或QUERY认领。
 * 设计：docs/superpowers/specs/2026-10-18-single-step-merchant-quota-design.md#7-持久等待与唤醒。
 */
@Service @RequiredArgsConstructor
@Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
public class SingleStepCapacityWaitService {
    private final SingleStepCapacityWaitMapper waits;
    private final PersistentTimer timer;
    private final SupplyChainDatabaseClock clock;
    private final ObjectMapper json;

    public boolean pending(long operationId, SingleStepAction action, long generation, LocalDateTime now) {
        var row = waits.selectById(operationId);
        return same(row, action, generation) && now.isBefore(row.getNextWakeAt());
    }
    public LocalDateTime nextWakeAt(long operationId) { return waits.selectById(operationId).getNextWakeAt(); }
    public boolean active(long operationId, long generation) {
        var row = waits.selectById(operationId);
        return row != null && Boolean.TRUE.equals(row.getActiveFlag()) && row.getExpectedGeneration() == generation;
    }
    public boolean matches(long operationId, SingleStepAction action, long generation, long sequence, LocalDateTime now) {
        var row = waits.selectById(operationId);
        return same(row, action, generation) && row.getWakeSequence() == sequence && !now.isBefore(row.getNextWakeAt());
    }
    public void complete(long operationId) {
        waits.update(null, new LambdaUpdateWrapper<SingleStepCapacityWaitDO>().eq(SingleStepCapacityWaitDO::getOperationId, operationId)
                .eq(SingleStepCapacityWaitDO::getActiveFlag, true).set(SingleStepCapacityWaitDO::getActiveFlag, false)
                .set(SingleStepCapacityWaitDO::getUpdatedAt, clock.now()));
    }
    public void record(FulfillmentOperationDO op, SingleStepAction action, LocalDateTime retryAt, String originTraceId) {
        var now = clock.now(); var row = waits.selectById(op.getId());
        if (same(row, action, op.getExecutionGeneration()) && now.isBefore(row.getNextWakeAt())) return;
        var next = retryAt.isAfter(now) ? retryAt : now.plusNanos(1_000_000);
        if (action == SingleStepAction.QUERY && op.getRecoveryDeadlineAt().isBefore(next)) next = op.getRecoveryDeadlineAt();
        if (row == null) {
            row = SingleStepCapacityWaitDO.builder().operationId(op.getId()).wakeSequence(1L).version(0L).createdAt(now).build();
        } else {
            row.setWakeSequence(Math.incrementExact(row.getWakeSequence())); row.setVersion(Math.incrementExact(row.getVersion()));
        }
        row.setAction(action.name()); row.setExpectedGeneration(op.getExecutionGeneration());
        row.setNextWakeAt(next); row.setActiveFlag(true); row.setUpdatedAt(now);
        if (row.getWakeSequence() == 1L) waits.insert(row); else if (waits.updateById(row) != 1) throw new IllegalStateException("容量等待写入失败");
        var payload = json.createObjectNode().put("operationNo", op.getOperationNo()).put("action", action.name())
                .put("expectedGeneration", op.getExecutionGeneration()).put("wakeSequence", row.getWakeSequence());
        if (originTraceId != null && !originTraceId.isBlank()) payload.put("originTraceId", originTraceId);
        timer.scheduleOnce(FulfillmentTimerTaskTypes.SINGLE_STEP_CAPACITY_WAKE,
                FulfillmentTimerTaskTypes.SINGLE_STEP_CAPACITY_WAKE + ":" + op.getOperationNo() + ":" + op.getExecutionGeneration() + ":" + action + ":" + row.getWakeSequence(),
                payload, clock.toInstant(next));
    }
    private static boolean same(SingleStepCapacityWaitDO row, SingleStepAction action, long generation) {
        return row != null && Boolean.TRUE.equals(row.getActiveFlag()) && row.getExpectedGeneration() == generation && action.name().equals(row.getAction());
    }
}
