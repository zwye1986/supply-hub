package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerTenantBindingDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;

@Mapper
public interface BuyerTenantBindingMapper extends BaseMapperX<BuyerTenantBindingDO> {

    default BuyerTenantBindingDO selectCurrent(String sourceSystem, String externalTenantId,
                                                LocalDateTime occurredAt) {
        return selectOne(new LambdaQueryWrapper<BuyerTenantBindingDO>()
                .eq(BuyerTenantBindingDO::getSourceSystem, sourceSystem)
                .eq(BuyerTenantBindingDO::getExternalTenantId, externalTenantId)
                .eq(BuyerTenantBindingDO::getCurrentFlag, 1)
                .eq(BuyerTenantBindingDO::getStatus, 10)
                .le(BuyerTenantBindingDO::getValidFrom, occurredAt)
                .and(wrapper -> wrapper.isNull(BuyerTenantBindingDO::getValidTo)
                        .or().gt(BuyerTenantBindingDO::getValidTo, occurredAt)));
    }
}
