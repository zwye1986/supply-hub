package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformSkuPricingMapper extends BaseMapperX<PlatformSkuPricingDO> {
    default PlatformSkuPricingDO selectBySkuIdForUpdate(Long skuId) {
        return selectOneForUpdate(PlatformSkuPricingDO::getPlatformSkuId, skuId);
    }
}
