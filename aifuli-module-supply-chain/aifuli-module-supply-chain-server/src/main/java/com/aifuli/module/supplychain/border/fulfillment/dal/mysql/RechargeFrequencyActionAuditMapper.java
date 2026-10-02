package com.aifuli.module.supplychain.border.fulfillment.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyActionAuditDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 直充限频持久化访问；普通查询使用方法引用，行锁由 XML 显式表达。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#3-持久化模型与不变量。
 */
@Mapper
public interface RechargeFrequencyActionAuditMapper extends BaseMapperX<RechargeFrequencyActionAuditDO> {
    /** 已失败的冲突事务回滚后，由新事务读取幂等操作的原始结果。 */
    default RechargeFrequencyActionAuditDO selectByActionNo(String actionNo) {
        return selectOne(new LambdaQueryWrapper<RechargeFrequencyActionAuditDO>()
                .eq(RechargeFrequencyActionAuditDO::getActionNo, actionNo));
    }
}
