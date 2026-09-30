package com.aifuli.module.supplychain.infrastructure.outbox;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboxEventMapper extends BaseMapperX<OutboxEventDO> {

    short STATUS_PENDING = 0;
    short STATUS_CLAIMED = 10;
    short STATUS_DELIVERING = 20;
    short STATUS_RETRY = 30;
    short STATUS_PUBLISHED = 40;

    /**
     * 扫描可投递 Outbox 事件并加行锁。
     *
     * <p><b>为何保留 {@code @Select}（开发规范第 8 条例外）</b>：
     * <ul>
     *   <li>依赖 MySQL 方言 {@code FOR UPDATE SKIP LOCKED}，多实例并发领取时跳过已被他事务锁定的行；</li>
     *   <li>条件含 {@code lease_until IS NULL OR lease_until < now}、状态 IN、按 available_at/id 排序与 LIMIT；</li>
     *   <li>{@code LambdaQueryWrapper} 无法类型安全表达 SKIP LOCKED；用 {@code last(...)} 硬拼易漏改语义。</li>
     * </ul>
     * 若后续迁出注解，应优先落到 XML Mapper，而不是强行改成 Lambda。
     */
    @Select("SELECT * FROM sc_outbox_event WHERE status IN (0, 30) "
            + "AND available_at <= #{now} "
            + "AND (lease_until IS NULL OR lease_until < #{now}) "
            + "ORDER BY available_at ASC, id ASC "
            + "LIMIT #{batchSize} FOR UPDATE SKIP LOCKED")
    List<OutboxEventDO> selectDeliverable(@Param("now") LocalDateTime now,
                                          @Param("batchSize") int batchSize);

    default OutboxEventDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(OutboxEventDO::getId, id);
    }

    default int claim(Long id, long expectedVersion, String leaseOwner, LocalDateTime leaseUntil,
                      LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<OutboxEventDO>()
                .set(OutboxEventDO::getStatus, STATUS_CLAIMED)
                .setSql("fencing_token = fencing_token + 1")
                .setSql("attempt_count = attempt_count + 1")
                .set(OutboxEventDO::getLeaseOwner, leaseOwner)
                .set(OutboxEventDO::getLeaseUntil, leaseUntil)
                .set(OutboxEventDO::getLastErrorCode, null)
                .setSql("version = version + 1")
                .set(OutboxEventDO::getUpdatedAt, now)
                .eq(OutboxEventDO::getId, id)
                .eq(OutboxEventDO::getVersion, expectedVersion)
                .in(OutboxEventDO::getStatus, STATUS_PENDING, STATUS_RETRY));
    }

    default int markDelivering(Long id, long fencingToken, String leaseOwner, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<OutboxEventDO>()
                .set(OutboxEventDO::getStatus, STATUS_DELIVERING)
                .setSql("version = version + 1")
                .set(OutboxEventDO::getUpdatedAt, now)
                .eq(OutboxEventDO::getId, id)
                .eq(OutboxEventDO::getFencingToken, fencingToken)
                .eq(OutboxEventDO::getLeaseOwner, leaseOwner)
                .eq(OutboxEventDO::getStatus, STATUS_CLAIMED));
    }

    default int publish(Long id, long fencingToken, String leaseOwner, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<OutboxEventDO>()
                .set(OutboxEventDO::getStatus, STATUS_PUBLISHED)
                .set(OutboxEventDO::getLeaseOwner, null)
                .set(OutboxEventDO::getLeaseUntil, null)
                .set(OutboxEventDO::getPublishedAt, now)
                .setSql("version = version + 1")
                .set(OutboxEventDO::getUpdatedAt, now)
                .eq(OutboxEventDO::getId, id)
                .eq(OutboxEventDO::getFencingToken, fencingToken)
                .eq(OutboxEventDO::getLeaseOwner, leaseOwner)
                .in(OutboxEventDO::getStatus, STATUS_CLAIMED, STATUS_DELIVERING));
    }

    default int failDelivery(Long id, long fencingToken, String leaseOwner, short newStatus,
                             LocalDateTime nextAvailableAt, String errorCode, LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<OutboxEventDO>()
                .set(OutboxEventDO::getStatus, newStatus)
                .set(OutboxEventDO::getLeaseOwner, null)
                .set(OutboxEventDO::getLeaseUntil, null)
                .set(OutboxEventDO::getAvailableAt, nextAvailableAt)
                .set(OutboxEventDO::getLastErrorCode, errorCode)
                .setSql("version = version + 1")
                .set(OutboxEventDO::getUpdatedAt, now)
                .eq(OutboxEventDO::getId, id)
                .eq(OutboxEventDO::getFencingToken, fencingToken)
                .eq(OutboxEventDO::getLeaseOwner, leaseOwner)
                .in(OutboxEventDO::getStatus, STATUS_CLAIMED, STATUS_DELIVERING));
    }

    default int renewLease(Long id, long fencingToken, String leaseOwner, LocalDateTime leaseUntil,
                           LocalDateTime now) {
        return update(null, new LambdaUpdateWrapper<OutboxEventDO>()
                .set(OutboxEventDO::getLeaseUntil, leaseUntil)
                .setSql("version = version + 1")
                .set(OutboxEventDO::getUpdatedAt, now)
                .eq(OutboxEventDO::getId, id)
                .eq(OutboxEventDO::getFencingToken, fencingToken)
                .eq(OutboxEventDO::getLeaseOwner, leaseOwner)
                .in(OutboxEventDO::getStatus, STATUS_CLAIMED, STATUS_DELIVERING));
    }
}
