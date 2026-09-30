package com.aifuli.module.supplychain.border.instore.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreOperationDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/**
 * 到店购外部操作 Mapper。
 *
 * <p>遵循规范：优先 LambdaQuery/UpdateWrapper，不新增注解 SQL。
 * PREORDER 操作的 operation_no 同时作为唯一 invocationId。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#4-中台侧场景详情模块}。</p>
 */
@Mapper
public interface InstoreOperationMapper extends BaseMapperX<InstoreOperationDO> {

    /** 按幂等键查询操作；幂等键全局唯一。 */
    default InstoreOperationDO selectByIdempotencyKey(String idempotencyKey) {
        return selectOne(new LambdaQueryWrapper<InstoreOperationDO>()
                .eq(InstoreOperationDO::getIdempotencyKey, idempotencyKey));
    }

    /** 按操作号查询；PREORDER 操作号同时为 invocationId。 */
    default InstoreOperationDO selectByOperationNo(String operationNo) {
        return selectOne(new LambdaQueryWrapper<InstoreOperationDO>()
                .eq(InstoreOperationDO::getOperationNo, operationNo));
    }

    /** CAS 抢占操作：待执行(0) → 执行中(10)；PREORDER 与 BUYER_REFUND 共用。 */
    default int claimPendingExecution(Long id, long expectedVersion, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InstoreOperationDO>()
                .set(InstoreOperationDO::getStatus, (short) 10)
                .set(InstoreOperationDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(InstoreOperationDO::getId, id)
                .eq(InstoreOperationDO::getVersion, expectedVersion)
                .eq(InstoreOperationDO::getStatus, (short) 0));
    }

    /** CAS 抢占 PREORDER：待执行(0) → 执行中(10)。 */
    default int claimPreorder(Long id, long expectedVersion, LocalDateTime updatedAt) {
        return claimPendingExecution(id, expectedVersion, updatedAt);
    }

    /** 记录操作结果：按 invocationId + expectedVersion CAS 推进状态。 */
    default int recordResult(Long id, long expectedVersion, short status,
                             String externalOperationNo, String resultClass,
                             LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<InstoreOperationDO>()
                .set(InstoreOperationDO::getStatus, status)
                .set(externalOperationNo != null, InstoreOperationDO::getExternalOperationNo, externalOperationNo)
                .set(resultClass != null, InstoreOperationDO::getResultClass, resultClass)
                .set(InstoreOperationDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(InstoreOperationDO::getId, id)
                .eq(InstoreOperationDO::getVersion, expectedVersion));
    }
}
