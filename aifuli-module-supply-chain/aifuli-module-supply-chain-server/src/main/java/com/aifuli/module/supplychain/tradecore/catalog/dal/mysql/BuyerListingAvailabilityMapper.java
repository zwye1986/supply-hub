package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BuyerListingAvailabilityMapper extends BaseMapperX<BuyerListingAvailabilityDO> {
    default BuyerListingAvailabilityDO selectByListingId(Long listingId) {
        return selectOne(BuyerListingAvailabilityDO::getListingId, listingId);
    }

    /**
     * LST当前锁取得后锁定供给投影行，供同事务CAS写入；见设计 §4.3。
     */
    default BuyerListingAvailabilityDO selectByListingIdForUpdate(Long listingId) {
        return selectOneForUpdate(BuyerListingAvailabilityDO::getListingId, listingId);
    }
}
