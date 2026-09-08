package com.aifuli.module.supplychain.infrastructure.timer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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

    /**
     * 扫描可领取 Timer 任务并加行锁。
     *
     * <p><b>为何保留 {@code @Select}（开发规范第 8 条例外）</b>：
     * <ul>
     *   <li>依赖 MySQL 方言 {@code FOR UPDATE SKIP LOCKED}，多实例并发领取时跳过已被他事务锁定的行；</li>
     *   <li>条件含租约过期判定、状态 IN、按 priority/next_fire_at/id 排序与 LIMIT；</li>
     *   <li>{@code LambdaQueryWrapper} 无法类型安全表达 SKIP LOCKED；用 {@code last(...)} 硬拼易漏改语义。</li>
     * </ul>
     * 若后续迁出注解，应优先落到 XML Mapper，而不是强行改成 Lambda。
     */
    @Select("SELECT * FROM sc_timer_task WHERE status IN (0, 30) "
            + "AND next_fire_at <= #{now} "
            + "AND (lease_until IS NULL OR lease_until < #{now}) "
            + "ORDER BY priority DESC, next_fire_at ASC, id ASC "
            + "LIMIT #{batchSize} FOR UPDATE SKIP LOCKED")
    List<TimerTaskDO> selectClaimable(@Param("now") LocalDateTime now,
                                     @Param("batchSize") int batchSize);

    default TimerTaskDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(TimerTaskDO::getId, id);
    }

    default TimerTaskDO selectByTypeAndKey(String taskType, String idempotencyKey) {
        return selectOne(TimerTaskDO::getTaskType, taskType,
                TimerTaskDO::getIdempotencyKey, idempotencyKey);
    }

    default int claim(Long id, long expectedVersion, String leaseOwner, LocalDateTime leaseUntil,
                      LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<TimerTaskDO>()
                .set(TimerTaskDO::getStatus, STATUS_CLAIMED)
                .setSql("fencing_token = fencing_token + 1")
                .setSql("attempt_count = attempt_count + 1")
                .set(TimerTaskDO::getLeaseOwner, leaseOwner)
                .set(TimerTaskDO::getLeaseUntil, leaseUntil)
                .set(TimerTaskDO::getLastErrorCode, null)
                .setSql("version = version + 1")
                .set(TimerTaskDO::getUpdatedAt, now)
                .eq(TimerTaskDO::getId, id)
                .eq(TimerTaskDO::getVersion, expectedVersion)
                .in(TimerTaskDO::getStatus, STATUS_PENDING, STATUS_RETRY));
    }

    default int renewLease(Long id, long fencingToken, String leaseOwner, LocalDateTime leaseUntil,
                           LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<TimerTaskDO>()
                .set(TimerTaskDO::getLeaseUntil, leaseUntil)
                .setSql("version = version + 1")
                .set(TimerTaskDO::getUpdatedAt, now)
                .eq(TimerTaskDO::getId, id)
                .eq(TimerTaskDO::getFencingToken, fencingToken)
                .eq(TimerTaskDO::getLeaseOwner, leaseOwner)
                .in(TimerTaskDO::getStatus, STATUS_CLAIMED, STATUS_EXECUTING));
    }

    default int markExecuting(Long id, long fencingToken, String leaseOwner, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<TimerTaskDO>()
                .set(TimerTaskDO::getStatus, STATUS_EXECUTING)
                .setSql("version = version + 1")
                .set(TimerTaskDO::getUpdatedAt, now)
                .eq(TimerTaskDO::getId, id)
                .eq(TimerTaskDO::getFencingToken, fencingToken)
                .eq(TimerTaskDO::getLeaseOwner, leaseOwner)
                .eq(TimerTaskDO::getStatus, STATUS_CLAIMED));
    }

    default int complete(Long id, long fencingToken, String leaseOwner, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<TimerTaskDO>()
                .set(TimerTaskDO::getStatus, STATUS_COMPLETED)
                .set(TimerTaskDO::getLeaseOwner, null)
                .set(TimerTaskDO::getLeaseUntil, null)
                .setSql("version = version + 1")
                .set(TimerTaskDO::getUpdatedAt, now)
                .eq(TimerTaskDO::getId, id)
                .eq(TimerTaskDO::getFencingToken, fencingToken)
                .eq(TimerTaskDO::getLeaseOwner, leaseOwner)
                .in(TimerTaskDO::getStatus, STATUS_CLAIMED, STATUS_EXECUTING));
    }

    default int finishAttempt(Long id, long fencingToken, String leaseOwner, short newStatus,
                              LocalDateTime nextFireAt, String errorCode, LocalDateTime now) {
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
                .eq(TimerTaskDO::getFencingToken, fencingToken)
                .eq(TimerTaskDO::getLeaseOwner, leaseOwner)
                .in(TimerTaskDO::getStatus, STATUS_CLAIMED, STATUS_EXECUTING));
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
