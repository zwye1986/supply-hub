package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BuyerSkuPricingVersionMapper extends BaseMapperX<BuyerSkuPricingVersionDO> {
    default BuyerSkuPricingVersionDO selectLatest(Long pricingId) {
        return selectOne(new LambdaQueryWrapper<BuyerSkuPricingVersionDO>()
                .eq(BuyerSkuPricingVersionDO::getPricingId, pricingId)
                .orderByDesc(BuyerSkuPricingVersionDO::getVersionNo).last("LIMIT 1"));
    }
}
