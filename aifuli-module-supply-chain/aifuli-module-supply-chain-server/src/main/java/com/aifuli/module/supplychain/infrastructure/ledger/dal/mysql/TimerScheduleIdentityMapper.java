package com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.dataobject.TimerScheduleIdentityDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * Timer调度身份账本 Mapper。
 * 设计依据：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#721-冻结排他建槽sql与首次所有权。
 */
@Mapper
public interface TimerScheduleIdentityMapper extends BaseMapperX<TimerScheduleIdentityDO> {

    /**
     * 单槽排他建槽语句：INSERT ... ON DUPLICATE KEY UPDATE id=id（显式省略 id，重复分支仅令 id=id，不修改任何字段、时间或状态）。
     */
    int insertSlotOnDuplicateKeyUpdate(TimerScheduleIdentityDO entity);

    /**
     * 语句执行后立即执行参数化 SELECT ... FOR UPDATE 当前锁读该槽。
     */
    TimerScheduleIdentityDO selectForUpdate(@Param("taskType") String taskType,
                                            @Param("idempotencyKey") String idempotencyKey);

    /**
     * 首次保留者在插入源表后执行一次原子 CAS 迁移为 BOUND 并绑定真实 taskId 与 taskNo（更新 1 行）。
     */
    int bindTaskCas(@Param("id") Long id,
                    @Param("reservationNonce") byte[] reservationNonce,
                    @Param("taskId") Long taskId,
                    @Param("taskNo") String taskNo,
                    @Param("boundAt") LocalDateTime boundAt);
}
