package com.aifuli.module.supplychain.border.o2o.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.o2o.dal.dataobject.O2OOperationDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

/**
 * O2O 外部操作 Mapper。
 *
 * <p>遵循规范：优先 LambdaQuery/UpdateWrapper，不新增注解 SQL。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6-关键编排与幂等}。</p>
 */
@Mapper
public interface O2OOperationMapper extends BaseMapperX<O2OOperationDO> {

    /** 按幂等键查询操作；幂等键全局唯一。 */
    default O2OOperationDO selectByIdempotencyKey(String idempotencyKey) {
        return selectOne(new LambdaQueryWrapper<O2OOperationDO>()
                .eq(O2OOperationDO::getIdempotencyKey, idempotencyKey));
    }

    /** 按操作号查询；PREORDER 操作号同时为 invocationId。 */
    default O2OOperationDO selectByOperationNo(String operationNo) {
        return selectOne(new LambdaQueryWrapper<O2OOperationDO>()
                .eq(O2OOperationDO::getOperationNo, operationNo));
    }

    /** CAS 抢占 PREORDER：待执行(0) → 执行中(10)。 */
    default int claimPreorder(Long id, long expectedVersion, LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<O2OOperationDO>()
                .set(O2OOperationDO::getStatus, (short) 10)
                .set(O2OOperationDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(O2OOperationDO::getId, id)
                .eq(O2OOperationDO::getVersion, expectedVersion)
                .eq(O2OOperationDO::getStatus, (short) 0));
    }

    /** 记录收银台结果：按 invocationId + expectedVersion CAS 推进状态。 */
    default int recordResult(Long id, long expectedVersion, short status,
                              String externalOperationNo, String resultClass,
                              LocalDateTime updatedAt) {
        return update(null, new LambdaUpdateWrapper<O2OOperationDO>()
                .set(O2OOperationDO::getStatus, status)
                .set(O2OOperationDO::getExternalOperationNo, externalOperationNo)
                .set(O2OOperationDO::getResultClass, resultClass)
                .set(O2OOperationDO::getUpdatedAt, updatedAt)
                .setSql("version = version + 1")
                .eq(O2OOperationDO::getId, id)
                .eq(O2OOperationDO::getVersion, expectedVersion));
    }
}
