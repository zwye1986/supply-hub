package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyQueueDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 直充限频持久化访问；普通查询使用方法引用，行锁由 XML 显式表达。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#3-持久化模型与不变量。
 */
@Mapper
public interface RechargeFrequencyQueueMapper extends BaseMapperX<RechargeFrequencyQueueDO> {
    /** 按冻结手机号摘要与平台 SPU 查询唯一控制行；创建前的不存在查询不承担行锁职责。 */
    default RechargeFrequencyQueueDO selectByIdentity(byte[] mobileDigest, Long platformSpuId) {
        return selectOne(new LambdaQueryWrapper<RechargeFrequencyQueueDO>()
                .eq(RechargeFrequencyQueueDO::getMobileDigest, mobileDigest)
                .eq(RechargeFrequencyQueueDO::getPlatformSpuId, platformSpuId));
    }

    /** 锁定已存在控制行；调用方须在供应链事务内先锁控制行再锁队列项。 */
    RechargeFrequencyQueueDO selectByIdForUpdate(@Param("id") Long id);
}
