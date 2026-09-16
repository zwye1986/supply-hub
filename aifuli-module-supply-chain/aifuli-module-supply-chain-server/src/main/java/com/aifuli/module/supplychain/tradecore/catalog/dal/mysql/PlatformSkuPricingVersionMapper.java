package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformSkuPricingVersionMapper extends BaseMapperX<PlatformSkuPricingVersionDO> {
    default PlatformSkuPricingVersionDO selectLatest(Long skuId) {
        return selectOne(new LambdaQueryWrapper<PlatformSkuPricingVersionDO>()
                .eq(PlatformSkuPricingVersionDO::getPlatformSkuId, skuId)
                .orderByDesc(PlatformSkuPricingVersionDO::getVersionNo).last("LIMIT 1"));
    }
}
