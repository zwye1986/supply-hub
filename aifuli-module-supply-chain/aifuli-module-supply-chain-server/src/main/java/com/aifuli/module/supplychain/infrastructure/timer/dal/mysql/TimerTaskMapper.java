package com.aifuli.module.supplychain.infrastructure.timer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.claim.ClaimRecoveryPolicy;
import com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TimerTaskMapper extends BaseMapperX<TimerTaskDO> {

    short STATUS_PENDING = 0;
    short STATUS_CLAIMED = 10;
    short STATUS_EXECUTING = 20;
    short STATUS_RETRY = 30;
    short STATUS_COMPLETED = 40;
    short STATUS_CANCELLED = 50;
    short STATUS_DEAD = 60;

    /**
     * 方言SKIP LOCKED使用参数化XML，恢复仅依据活动租约，保留优先级/时间/id排序。
     * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#31-扫描和预算。
     */
    List<TimerTaskDO> selectClaimable(@Param("now") LocalDateTime now,
                                     @Param("batchSize") int batchSize);

    /**
     * 非锁定有限诊断，不把缺租约的损坏活动行纳入领取批次，更不自动修复。
     * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#31-扫描和预算。
     */
    List<Long> selectBrokenActiveLeaseIds(@Param("limit") int limit);

    default TimerTaskDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(TimerTaskDO::getId, id);
    }

    default TimerTaskDO selectByTypeAndKey(String taskType, String idempotencyKey) {
        return selectOne(TimerTaskDO::getTaskType, taskType,
                TimerTaskDO::getIdempotencyKey, idempotencyKey);
    }

    default int claim(Long id, long expectedVersion, String leaseOwner, LocalDateTime leaseUntil,
                      LocalDateTime now) {
        return update(null, dueRow(id, expectedVersion, now)
                .apply("attempt_count < max_attempts")
                .set(TimerTaskDO::getStatus, STATUS_CLAIMED)
                .setSql("fencing_token = fencing_token + 1")
                .setSql("attempt_count = attempt_count + 1")
                .set(TimerTaskDO::getLeaseOwner, leaseOwner)
                .set(TimerTaskDO::getLeaseUntil, leaseUntil)
                .set(TimerTaskDO::getLastErrorCode, null)
                .setSql("version = version + 1")
                .set(TimerTaskDO::getUpdatedAt, now));
    }

    /**
     * 耗尽到期行死信，保留非空计划时间，不追加/覆盖execution或补startedAt。
     * 清owner/lease、终态及version递增使旧claim失格，不再消耗尝试预算。
     * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#33-timer审计与失败处理。
     */
    default int exhaustBudget(Long id, long expectedVersion, LocalDateTime now) {
        return update(null, dueRow(id, expectedVersion, now)
                .apply("attempt_count >= max_attempts")
                .set(TimerTaskDO::getStatus, STATUS_DEAD)
                .set(TimerTaskDO::getLeaseOwner, null)
                .set(TimerTaskDO::getLeaseUntil, null)
                .set(TimerTaskDO::getLastErrorCode, ClaimRecoveryPolicy.BUDGET_EXHAUSTED)
                .setSql("version = version + 1")
                .set(TimerTaskDO::getUpdatedAt, now));
    }

    /**
     * 领取与预算死信共享当前version/前态/到期CAS，列间预算比较用固定技术SQL，无用户拼接。
     * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#32-转换表。
     */
    default LambdaUpdateWrapper<TimerTaskDO> dueRow(Long id, long expectedVersion, LocalDateTime now) {
        return new LambdaUpdateWrapper<TimerTaskDO>()
                .eq(TimerTaskDO::getId, id)
                .eq(TimerTaskDO::getVersion, expectedVersion)
                .and(w -> w.in(TimerTaskDO::getStatus, STATUS_PENDING, STATUS_RETRY)
                        .le(TimerTaskDO::getNextFireAt, now)
                        .and(lease -> lease.isNull(TimerTaskDO::getLeaseUntil)
                                .or().le(TimerTaskDO::getLeaseUntil, now))
                        .or(active -> active.in(TimerTaskDO::getStatus, STATUS_CLAIMED, STATUS_EXECUTING)
                                .isNotNull(TimerTaskDO::getLeaseUntil)
                                .le(TimerTaskDO::getLeaseUntil, now)));
    }

    default int renewLease(Long id, Long expectedVersion, long fencingToken, String leaseOwner,
                           LocalDateTime leaseUntil, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<TimerTaskDO>()
                .set(TimerTaskDO::getLeaseUntil, leaseUntil)
                .setSql("version = version + 1")
                .set(TimerTaskDO::getUpdatedAt, now)
                .eq(TimerTaskDO::getId, id)
                .eq(expectedVersion != null, TimerTaskDO::getVersion, expectedVersion)
                .eq(TimerTaskDO::getFencingToken, fencingToken)
                .eq(TimerTaskDO::getLeaseOwner, leaseOwner)
                .gt(TimerTaskDO::getLeaseUntil, now)
                .in(TimerTaskDO::getStatus, STATUS_CLAIMED, STATUS_EXECUTING));
    }

    default int renewLease(Long id, long fencingToken, String leaseOwner, LocalDateTime leaseUntil,
                           LocalDateTime now) {
        return renewLease(id, null, fencingToken, leaseOwner, leaseUntil, now);
    }

    default int markExecuting(Long id, Long expectedVersion, long fencingToken, String leaseOwner, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<TimerTaskDO>()
                .set(TimerTaskDO::getStatus, STATUS_EXECUTING)
                .setSql("version = version + 1")
                .set(TimerTaskDO::getUpdatedAt, now)
                .eq(TimerTaskDO::getId, id)
                .eq(expectedVersion != null, TimerTaskDO::getVersion, expectedVersion)
                .eq(TimerTaskDO::getFencingToken, fencingToken)
                .eq(TimerTaskDO::getLeaseOwner, leaseOwner)
                .gt(TimerTaskDO::getLeaseUntil, now)
                .eq(TimerTaskDO::getStatus, STATUS_CLAIMED));
    }

    default int markExecuting(Long id, long fencingToken, String leaseOwner, LocalDateTime now) {
        return markExecuting(id, null, fencingToken, leaseOwner, now);
    }

    default int complete(Long id, Long expectedVersion, long fencingToken, String leaseOwner, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<TimerTaskDO>()
                .set(TimerTaskDO::getStatus, STATUS_COMPLETED)
                .set(TimerTaskDO::getLeaseOwner, null)
                .set(TimerTaskDO::getLeaseUntil, null)
                .setSql("version = version + 1")
                .set(TimerTaskDO::getUpdatedAt, now)
                .eq(TimerTaskDO::getId, id)
                .eq(expectedVersion != null, TimerTaskDO::getVersion, expectedVersion)
                .eq(TimerTaskDO::getFencingToken, fencingToken)
                .eq(TimerTaskDO::getLeaseOwner, leaseOwner)
                .gt(TimerTaskDO::getLeaseUntil, now)
                .in(TimerTaskDO::getStatus, STATUS_CLAIMED, STATUS_EXECUTING));
    }

    default int complete(Long id, long fencingToken, String leaseOwner, LocalDateTime now) {
        return complete(id, null, fencingToken, leaseOwner, now);
    }

    default int finishAttempt(Long id, Long expectedVersion, long fencingToken, String leaseOwner,
                              short newStatus, LocalDateTime nextFireAt, String errorCode, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<TimerTaskDO>()
                .set(TimerTaskDO::getStatus, newStatus)
                .set(TimerTaskDO::getLeaseOwner, null)
                .set(TimerTaskDO::getLeaseUntil, null)
                // 设计见 docs/superpowers/specs/2026-08-20-timer-outbox-schema-repair-design.md#31-timer-映射：
                // next_fire_at 非空；死信任务由 status 排除扫描，并保留最后计划时间供审计。
                .set(nextFireAt != null, TimerTaskDO::getNextFireAt, nextFireAt)
                .set(TimerTaskDO::getLastErrorCode, errorCode)
                .setSql("version = version + 1")
                .set(TimerTaskDO::getUpdatedAt, now)
                .eq(TimerTaskDO::getId, id)
                .eq(expectedVersion != null, TimerTaskDO::getVersion, expectedVersion)
                .eq(TimerTaskDO::getFencingToken, fencingToken)
                .eq(TimerTaskDO::getLeaseOwner, leaseOwner)
                .gt(TimerTaskDO::getLeaseUntil, now)
                .in(TimerTaskDO::getStatus, STATUS_CLAIMED, STATUS_EXECUTING));
    }

    default int finishAttempt(Long id, long fencingToken, String leaseOwner, short newStatus,
                              LocalDateTime nextFireAt, String errorCode, LocalDateTime now) {
        return finishAttempt(id, null, fencingToken, leaseOwner, newStatus, nextFireAt, errorCode, now);
    }

    default int cancel(Long id, long expectedVersion, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<TimerTaskDO>()
                .set(TimerTaskDO::getStatus, STATUS_CANCELLED)
                .set(TimerTaskDO::getLeaseOwner, null)
                .set(TimerTaskDO::getLeaseUntil, null)
                .setSql("version = version + 1")
                .set(TimerTaskDO::getUpdatedAt, now)
                .eq(TimerTaskDO::getId, id)
                .eq(TimerTaskDO::getVersion, expectedVersion)
                .in(TimerTaskDO::getStatus, STATUS_PENDING, STATUS_RETRY)
                .and(wrapper -> wrapper.isNull(TimerTaskDO::getLeaseUntil)
                        .or().lt(TimerTaskDO::getLeaseUntil, now)));
    }
}
