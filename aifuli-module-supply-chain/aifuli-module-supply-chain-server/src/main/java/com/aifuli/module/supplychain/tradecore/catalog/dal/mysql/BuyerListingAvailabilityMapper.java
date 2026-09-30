package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BuyerListingAvailabilityMapper extends BaseMapperX<BuyerListingAvailabilityDO> {
    default BuyerListingAvailabilityDO selectByListingId(Long listingId) {
        return selectOne(BuyerListingAvailabilityDO::getListingId, listingId);
    }
}
