package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.StandardPricingQuoteDO;
import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

@Mapper
public interface StandardPricingQuoteMapper extends BaseMapperX<StandardPricingQuoteDO> {
    default StandardPricingQuoteDO selectByTokenForUpdate(String token) {
        return selectOneForUpdate(StandardPricingQuoteDO::getQuoteToken, token);
    }
    default StandardPricingQuoteDO selectByOrder(String sourceSystem, String externalTenantId, String buyerOrderNo) {
        return selectOne(new LambdaQueryWrapper<StandardPricingQuoteDO>()
                .eq(StandardPricingQuoteDO::getSourceSystem, sourceSystem)
                .eq(StandardPricingQuoteDO::getExternalTenantId, externalTenantId)
                .eq(StandardPricingQuoteDO::getBuyerOrderNo, buyerOrderNo)
                .orderByDesc(StandardPricingQuoteDO::getId).last("LIMIT 1"));
    }
}
