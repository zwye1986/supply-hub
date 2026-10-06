package com.aifuli.module.supplychain.infrastructure.outbox;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.claim.ClaimRecoveryPolicy;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxEventMapper extends BaseMapperX<OutboxEventDO> {

    short STATUS_PENDING = 0;
    short STATUS_CLAIMED = 10;
    short STATUS_DELIVERING = 20;
    short STATUS_RETRY = 30;
    short STATUS_PUBLISHED = 40;
    short STATUS_DEAD = 50;

    /**
     * 方言SKIP LOCKED使用参数化XML，普通到期与活动租约恢复分组，保留原排序。
     * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#31-扫描和预算。
     */
    List<OutboxEventDO> selectDeliverable(@Param("now") LocalDateTime now,
                                          @Param("batchSize") int batchSize);

    /**
     * 非锁定有限诊断，不把缺租约的损坏活动行纳入领取批次，更不自动修复。
     * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#31-扫描和预算。
     */
    List<Long> selectBrokenActiveLeaseIds(@Param("limit") int limit);

    default OutboxEventDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(OutboxEventDO::getId, id);
    }

    default int claim(Long id, long expectedVersion, String leaseOwner, LocalDateTime leaseUntil,
                      LocalDateTime now) {
        return update(null, dueRow(id, expectedVersion, now)
                .apply("attempt_count < max_attempts")
                .set(OutboxEventDO::getStatus, STATUS_CLAIMED)
                .setSql("fencing_token = fencing_token + 1")
                .setSql("attempt_count = attempt_count + 1")
                .set(OutboxEventDO::getLeaseOwner, leaseOwner)
                .set(OutboxEventDO::getLeaseUntil, leaseUntil)
                .set(OutboxEventDO::getLastErrorCode, null)
                .setSql("version = version + 1")
                .set(OutboxEventDO::getUpdatedAt, now));
    }

    /**
     * 锁定的到期耗尽行直接死信，保留计划时间/次数/token，不生成新claim。
     * owner/lease清空及状态终结使旧claim失格，不制造第max+1次尝试。
     * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#31-扫描和预算。
     */
    default int exhaustBudget(Long id, long expectedVersion, LocalDateTime now) {
        return update(null, dueRow(id, expectedVersion, now)
                .apply("attempt_count >= max_attempts")
                .set(OutboxEventDO::getStatus, STATUS_DEAD)
                .set(OutboxEventDO::getLeaseOwner, null)
                .set(OutboxEventDO::getLeaseUntil, null)
                .set(OutboxEventDO::getLastErrorCode, ClaimRecoveryPolicy.BUDGET_EXHAUSTED)
                .setSql("version = version + 1")
                .set(OutboxEventDO::getUpdatedAt, now));
    }

    /**
     * 领取与预算死信共享当前version/前态/到期CAS，列间预算比较用固定技术SQL，无用户拼接。
     * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#32-转换表。
     */
    default LambdaUpdateWrapper<OutboxEventDO> dueRow(Long id, long expectedVersion, LocalDateTime now) {
        return new LambdaUpdateWrapper<OutboxEventDO>()
                .eq(OutboxEventDO::getId, id)
                .eq(OutboxEventDO::getVersion, expectedVersion)
                .and(w -> w.in(OutboxEventDO::getStatus, STATUS_PENDING, STATUS_RETRY)
                        .le(OutboxEventDO::getAvailableAt, now)
                        .and(lease -> lease.isNull(OutboxEventDO::getLeaseUntil)
                                .or().le(OutboxEventDO::getLeaseUntil, now))
                        .or(active -> active.in(OutboxEventDO::getStatus, STATUS_CLAIMED, STATUS_DELIVERING)
                                .isNotNull(OutboxEventDO::getLeaseUntil)
                                .le(OutboxEventDO::getLeaseUntil, now)));
    }

    default int markDelivering(Long id, Long expectedVersion, long fencingToken, String leaseOwner, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<OutboxEventDO>()
                .set(OutboxEventDO::getStatus, STATUS_DELIVERING)
                .setSql("version = version + 1")
                .set(OutboxEventDO::getUpdatedAt, now)
                .eq(OutboxEventDO::getId, id)
                .eq(expectedVersion != null, OutboxEventDO::getVersion, expectedVersion)
                .eq(OutboxEventDO::getFencingToken, fencingToken)
                .eq(OutboxEventDO::getLeaseOwner, leaseOwner)
                .gt(OutboxEventDO::getLeaseUntil, now)
                .eq(OutboxEventDO::getStatus, STATUS_CLAIMED));
    }

    default int markDelivering(Long id, long fencingToken, String leaseOwner, LocalDateTime now) {
        return markDelivering(id, null, fencingToken, leaseOwner, now);
    }

    default int publish(Long id, Long expectedVersion, long fencingToken, String leaseOwner, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<OutboxEventDO>()
                .set(OutboxEventDO::getStatus, STATUS_PUBLISHED)
                .set(OutboxEventDO::getLeaseOwner, null)
                .set(OutboxEventDO::getLeaseUntil, null)
                .set(OutboxEventDO::getPublishedAt, now)
                .setSql("version = version + 1")
                .set(OutboxEventDO::getUpdatedAt, now)
                .eq(OutboxEventDO::getId, id)
                .eq(expectedVersion != null, OutboxEventDO::getVersion, expectedVersion)
                .eq(OutboxEventDO::getFencingToken, fencingToken)
                .eq(OutboxEventDO::getLeaseOwner, leaseOwner)
                .gt(OutboxEventDO::getLeaseUntil, now)
                .in(OutboxEventDO::getStatus, STATUS_CLAIMED, STATUS_DELIVERING));
    }

    default int publish(Long id, long fencingToken, String leaseOwner, LocalDateTime now) {
        return publish(id, null, fencingToken, leaseOwner, now);
    }

    default int failDelivery(Long id, Long expectedVersion, long fencingToken, String leaseOwner,
                             short newStatus, LocalDateTime nextAvailableAt, String errorCode, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<OutboxEventDO>()
                .set(OutboxEventDO::getStatus, newStatus)
                .set(OutboxEventDO::getLeaseOwner, null)
                .set(OutboxEventDO::getLeaseUntil, null)
                .set(nextAvailableAt != null, OutboxEventDO::getAvailableAt, nextAvailableAt)
                .set(OutboxEventDO::getLastErrorCode, errorCode)
                .setSql("version = version + 1")
                .set(OutboxEventDO::getUpdatedAt, now)
                .eq(OutboxEventDO::getId, id)
                .eq(expectedVersion != null, OutboxEventDO::getVersion, expectedVersion)
                .eq(OutboxEventDO::getFencingToken, fencingToken)
                .eq(OutboxEventDO::getLeaseOwner, leaseOwner)
                .gt(OutboxEventDO::getLeaseUntil, now)
                .in(OutboxEventDO::getStatus, STATUS_CLAIMED, STATUS_DELIVERING));
    }

    default int failDelivery(Long id, long fencingToken, String leaseOwner, short newStatus,
                             LocalDateTime nextAvailableAt, String errorCode, LocalDateTime now) {
        return failDelivery(id, null, fencingToken, leaseOwner, newStatus, nextAvailableAt, errorCode, now);
    }

    default int renewLease(Long id, Long expectedVersion, long fencingToken, String leaseOwner,
                           LocalDateTime leaseUntil, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<OutboxEventDO>()
                .set(OutboxEventDO::getLeaseUntil, leaseUntil)
                .setSql("version = version + 1")
                .set(OutboxEventDO::getUpdatedAt, now)
                .eq(OutboxEventDO::getId, id)
                .eq(expectedVersion != null, OutboxEventDO::getVersion, expectedVersion)
                .eq(OutboxEventDO::getFencingToken, fencingToken)
                .eq(OutboxEventDO::getLeaseOwner, leaseOwner)
                .gt(OutboxEventDO::getLeaseUntil, now)
                .in(OutboxEventDO::getStatus, STATUS_CLAIMED, STATUS_DELIVERING));
    }

    default int renewLease(Long id, long fencingToken, String leaseOwner, LocalDateTime leaseUntil,
                           LocalDateTime now) {
        return renewLease(id, null, fencingToken, leaseOwner, leaseUntil, now);
    }
}
