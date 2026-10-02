package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyEntryDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 直充限频持久化访问；普通查询使用方法引用，行锁由 XML 显式表达。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#3-持久化模型与不变量。
 */
@Mapper
public interface RechargeFrequencyEntryMapper extends BaseMapperX<RechargeFrequencyEntryDO> {
    /** 同单重试复用原队列项，不能重新分配入队序号。 */
    default RechargeFrequencyEntryDO selectByOrderId(Long orderId) {
        return selectOne(new LambdaQueryWrapper<RechargeFrequencyEntryDO>()
                .eq(RechargeFrequencyEntryDO::getOrderId, orderId));
    }

    /** QUEUE锁取得后的认领定位；调用方必须在独立READ_COMMITTED事务内读取，并按PK锁定队首与候选再校验。 */
    default RechargeFrequencyEntryDO selectFirstWaiting(Long queueId) {
        return selectOne(new LambdaQueryWrapper<RechargeFrequencyEntryDO>()
                .eq(RechargeFrequencyEntryDO::getQueueId, queueId)
                .eq(RechargeFrequencyEntryDO::getStatus, com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyEntryStatus.WAITING.getCode())
                .orderByAsc(RechargeFrequencyEntryDO::getEnqueueSequence).last("LIMIT 1"));
    }

    /** 控制行加锁后锁定具体队列项，保持所有业务入口的统一锁序。 */
    RechargeFrequencyEntryDO selectByIdForUpdate(@Param("id") Long id);

    /** 以持久化序号选取等待队首；必须先持有对应控制行锁，不能按自增主键推断 FIFO。 */
    RechargeFrequencyEntryDO selectFirstWaitingForUpdate(@Param("queueId") Long queueId,
                                                         @Param("waitingStatus") short waitingStatus);
}
