package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import static com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyConstants.*;
import static com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper.*;

/** 恢复只消费持久化事实：QUEUE锁内检查代次并安排任务，查单由调用方在事务外执行。
 * 不以租约、节点失联、查无订单推断未发送，不自动撤销PREPARED。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#6-timer-与补偿、#9-告警与观测。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeFrequencyRecoveryService {
    private final RechargeFrequencyQueueMapper queues;
    private final RechargeFrequencyEntryMapper entries;
    private final RechargeFrequencyLifecycle lifecycle;
    private final RechargeFrequencyWakeScheduler scheduler;
    private final TimerTaskMapper tasks;
    private final SupplyChainDatabaseClock clock;
    private final ObjectProvider<RechargeFrequencyRecoveryService> self;

    /** 每批至多100个到期控制行，使用next_check_at索引；失败向调度器传播，依据仍保留在数据库。 */
    public int compensate() {
        log.debug("[直充限频恢复] 补偿扫描入口 batchSize={}", RECOVERY_BATCH_SIZE);
        var due = queues.selectList(new LambdaQueryWrapper<RechargeFrequencyQueueDO>()
                .le(RechargeFrequencyQueueDO::getNextCheckAt, clock.now())
                .orderByAsc(RechargeFrequencyQueueDO::getNextCheckAt, RechargeFrequencyQueueDO::getId)
                .last("LIMIT " + RECOVERY_BATCH_SIZE));
        int repaired = 0;
        RuntimeException failure = null;
        for (var queue : due) {
            try { if (self.getObject().repair(queue.getId())) { repaired++; } }
            catch (RuntimeException exception) {
                // 保留首个安全因果，单个异常不阻断其他队列，也不伪装为成功；日志由批次调度器限频。
                var safe = new SafeRecoveryFailure(RechargeFrequencyRecoveryCodes.RECOVERY_FAILED, exception, List.of(queue.getId()));
                if (failure == null) { failure = safe; }
                else { failure.addSuppressed(safe); }
            }
        }
        if (failure != null) { throw failure; }
        return repaired;
    }

    @Transactional(transactionManager="supplyChainTransactionManager", isolation=Isolation.READ_COMMITTED, rollbackFor=Exception.class)
    public boolean repair(Long queueId) {
        log.debug("[直充限频恢复] 队列补偿入口 queueId={}", queueId);
        var queue = queues.selectByIdForUpdate(queueId);
        var now = clock.now();
        if (queue == null || queue.getNextCheckAt() == null || queue.getNextCheckAt().isAfter(now)) { return false; }
        alarms(queue, now);
        var task = tasks.selectByTypeAndKey(FulfillmentTimerTaskTypes.RECHARGE_FREQUENCY_WAKE, key(queue));
        if (task != null && (task.getStatus() == STATUS_PENDING || task.getStatus() == STATUS_RETRY
                || ((task.getStatus() == STATUS_CLAIMED || task.getStatus() == STATUS_EXECUTING)
                    && task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(now)))) {
            queue.setNextCheckAt(now.plusSeconds(RECOVERY_CHECK_SECONDS));
            save(queue); return false;
        }
        newIntent(queue, now);
        return true;
    }

    /** 旧代次只读退出；任务不能先claim操作，否则既有执行Handler无法再次认领。 */
    @Transactional(transactionManager="supplyChainTransactionManager", isolation=Isolation.READ_COMMITTED, rollbackFor=Exception.class)
    public QueryWork prepareWake(long queueId, long generation) {
        log.debug("[直充限频恢复] 唤醒入口 queueId={}, wakeGeneration={}", queueId, generation);
        var queue = queues.selectByIdForUpdate(queueId);
        if (queue == null || queue.getWakeGeneration() != generation) { return null; }
        var now = clock.now();
        var currentTask = tasks.selectByTypeAndKey(FulfillmentTimerTaskTypes.RECHARGE_FREQUENCY_WAKE, key(queue));
        // nextCheckAt可因补偿扫描公平性前移；业务到期依据当前代Timer，不能被扫描与执行竞速延期。
        if (queue.getNextCheckAt() == null || currentTask == null || currentTask.getNextFireAt().isAfter(now)) { return null; }
        var locator = queue.getHolderEntryId() == null ? entries.selectFirstWaiting(queueId)
                : entries.selectById(queue.getHolderEntryId());
        if (locator == null) {
            if (queue.getHolderEntryId() != null) { throw new IllegalStateException("限频持有队列项丢失"); }
            alarms(queue, now); queue.setNextCheckAt(null); save(queue); return null;
        }
        var locked = lifecycle.lockForResult(locator.getActiveOperationNo());
        if (locked == null || locked.entry() == null || !locked.entry().getId().equals(locator.getId())
                || !locked.queue().getId().equals(queueId)) { throw new IllegalStateException("限频恢复关联不一致"); }
        // lockForResult重新读取控制行；后续只使用该当前读对象，避免版本覆盖。
        queue = locked.queue();
        alarms(queue, now);
        var operation = locked.operation();
        if (queue.getHolderEntryId() != null && !RechargeFrequencyLifecycle.isCurrentHolder(locked)) {
            throw new IllegalStateException("限频占用关联不一致");
        }
        if (queue.getHolderEntryId() == null && (locked.request().getStatus() == FulfillmentRequestStatus.SUCCEEDED.getCode()
                || locked.request().getStatus() == FulfillmentRequestStatus.FAILED.getCode())
                && (operation.getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode()
                    || operation.getStatus() == SupplierOrderOperationStatus.FAILED.getCode())) {
            // WAITING的可信终态项已无发送风险，原序号仍留存，下一代选择后续有效队首。
            locked.entry().setStatus(RechargeFrequencyEntryStatus.FINISHED.getCode());
            locked.entry().setCompletedAt(now); locked.entry().setUpdatedAt(now);
            if (entries.updateById(locked.entry()) != 1) { throw new IllegalStateException("失效队首版本冲突"); }
            newIntent(queue, now); return null;
        }
        lifecycle.reconcile(locked);
        if (locked.entry().getStatus() == RechargeFrequencyEntryStatus.FINISHED.getCode()) { return null; }
        if (locked.request().getStatus() == FulfillmentRequestStatus.SUCCEEDED.getCode()) {
            // 已有minimumHoldUntil为原acquiredAt+5秒，重复唤醒不能刷新窗口。
            defer(queue, queue.getMinimumHoldUntil()); return null;
        }
        if (queue.getBlockedReason() == null && (operation.getStatus() == SupplierOrderOperationStatus.PENDING.getCode()
                || operation.getStatus() == SupplierOrderOperationStatus.CLAIMED.getCode())) {
            scheduler.execute(queue, operation.getOperationNo(), locked.bOrder().getBOrderNo());
            defer(queue, now.plusSeconds(RECOVERY_CHECK_SECONDS)); return null;
        }
        boolean query = queue.getHolderEntryId() != null
                && Objects.equals(locked.entry().getDispatchPhase(), RechargeFrequencyDispatchPhase.SEND_POSSIBLE.getCode())
                && (operation.getStatus() == SupplierOrderOperationStatus.EXECUTING.getCode()
                    || operation.getStatus() == SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode()
                    || operation.getStatus() == SupplierOrderOperationStatus.MANUAL.getCode());
        defer(queue, now.plusSeconds(RECOVERY_CHECK_SECONDS));
        if (!query) { return null; }
        return new QueryWork(locked, new RechargeFrequencyExecutionPermit(queueId, locked.entry().getId(),
                operation.getOperationNo(), queue.getOccupancyGeneration(), locked.entry().getVersion(), operation.getVersion()));
    }

    private void defer(RechargeFrequencyQueueDO queue, LocalDateTime fireAt) {
        newIntent(queue, fireAt);
    }
    private void newIntent(RechargeFrequencyQueueDO queue, LocalDateTime fireAt) {
        queue.setWakeGeneration(Math.incrementExact(queue.getWakeGeneration())); queue.setNextCheckAt(fireAt);
        save(queue); scheduler.schedule(queue);
    }
    private void save(RechargeFrequencyQueueDO queue) {
        queue.setUpdatedAt(clock.now());
        if (queues.updateById(queue) != 1) { throw new IllegalStateException("限频补偿控制行版本冲突"); }
    }
    private static String key(RechargeFrequencyQueueDO queue) { return queue.getId()+":"+queue.getWakeGeneration(); }

    /** 严格越界首次/30分钟重复/恢复均在QUEUE锁下更新，跨节点与重启共享限频状态。 */
    private void alarms(RechargeFrequencyQueueDO queue, LocalDateTime now) {
        long waiting = entries.selectCount(new LambdaQueryWrapper<RechargeFrequencyEntryDO>()
                .eq(RechargeFrequencyEntryDO::getQueueId, queue.getId())
                .eq(RechargeFrequencyEntryDO::getStatus, RechargeFrequencyEntryStatus.WAITING.getCode()));
        boolean backlog = RechargeFrequencyPolicy.isBacklogged(waiting);
        boolean occupied = queue.getHolderEntryId() != null && RechargeFrequencyPolicy.isOverdue(queue.getAcquiredAt(), now);
        if (alarmDue(backlog, queue.getBacklogAlertActive(), queue.getLastBacklogAlertAt(), now)) {
            emitAfterCommit(backlog ? RechargeFrequencyRecoveryCodes.BACKLOG_ACTIVE : RechargeFrequencyRecoveryCodes.BACKLOG_RECOVERED,
                    queue, waiting);
            queue.setLastBacklogAlertAt(now);
        }
        if (alarmDue(occupied, queue.getOccupiedAlertActive(), queue.getLastOccupiedAlertAt(), now)) {
            emitAfterCommit(occupied ? RechargeFrequencyRecoveryCodes.OCCUPIED_ACTIVE : RechargeFrequencyRecoveryCodes.OCCUPIED_RECOVERED,
                    queue, waiting);
            queue.setLastOccupiedAlertAt(now);
        }
        queue.setBacklogAlertActive(backlog); queue.setOccupiedAlertActive(occupied);
    }
    /** 只在激活状态/上次告警时间持久化成功后输出，回滚不产生虚假的首次事件。
     * 回调捕获安全不可变引用快照，不捕获含手机号摘要/脱敏手机号的整个DO。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#9-告警与观测。 */
    private void emitAfterCommit(String eventCode, RechargeFrequencyQueueDO queue, long waiting) {
        var event = new AlertEvent(eventCode, queue.getId(), queue.getPlatformSpuId(), queue.getHolderEntryId(), waiting);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                log.warn("[直充限频告警] event={}, queueId={}, platformSpuId={}, entryId={}, waiting={}",
                        event.code(), event.queueId(), event.platformSpuId(), event.entryId(), event.waiting());
            }
        });
    }
    private record AlertEvent(String code, Long queueId, Long platformSpuId, Long entryId, long waiting) { }

    static boolean alarmDue(boolean active, Boolean previous, LocalDateTime last, LocalDateTime now) {
        return active != Boolean.TRUE.equals(previous) || (active && (last == null || !now.isBefore(last.plusSeconds(ALERT_REPEAT_SECONDS))));
    }
    /** 安全异常载体只保存错误类型、代码位置与已知队列ID，原始异常正文不进入日志。
     * 私有类型标记已安全化因果，循环边界再次包装时保留真实类型及suppressed，而非擦除为包装类名。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#9-告警与观测。 */
    static IllegalStateException safeFailure(String code, Throwable cause) {
        return new SafeRecoveryFailure(code, cause, List.of());
    }
    static List<Long> failedQueueIds(Throwable failure) {
        var ids = new LinkedHashSet<Long>();
        collectQueueIds(failure, ids, Collections.newSetFromMap(new IdentityHashMap<>()));
        return List.copyOf(ids);
    }
    private static void collectQueueIds(Throwable failure, Set<Long> ids, Set<Throwable> seen) {
        if (failure == null || !seen.add(failure)) { return; }
        if (failure instanceof SafeRecoveryFailure safe) { ids.addAll(safe.queueIds); }
        collectQueueIds(failure.getCause(), ids, seen);
        for (var suppressed : failure.getSuppressed()) { collectQueueIds(suppressed, ids, seen); }
    }
    private static Throwable sanitizedCause(Throwable cause, int depth) {
        if (cause == null || depth >= 8) { return null; }
        if (cause instanceof SafeRecoveryFailure || cause instanceof SanitizedCause) { return cause; }
        var safe = new SanitizedCause(cause.getClass().getName(), sanitizedCause(cause.getCause(), depth+1));
        safe.setStackTrace(cause.getStackTrace());
        for (var suppressed : cause.getSuppressed()) {
            var sanitized = sanitizedCause(suppressed, depth+1);
            if (sanitized != null) { safe.addSuppressed(sanitized); }
        }
        return safe;
    }
    private static final class SafeRecoveryFailure extends IllegalStateException {
        private final List<Long> queueIds;
        private SafeRecoveryFailure(String code, Throwable cause, List<Long> queueIds) {
            super(code, sanitizedCause(cause, 0));
            this.queueIds = List.copyOf(queueIds);
        }
    }
    private static final class SanitizedCause extends IllegalStateException {
        private SanitizedCause(String originalType, Throwable cause) { super(originalType, cause); }
    }
    /** 快照不输出DO的toString（其持有加密目标等内部字段）。 */
    public record QueryWork(RechargeFrequencyLifecycle.LockedExecution locked, RechargeFrequencyExecutionPermit permit) {
        @Override public String toString() { return "QueryWork[operationNo="+permit.operationNo()+"]"; }
    }
}
