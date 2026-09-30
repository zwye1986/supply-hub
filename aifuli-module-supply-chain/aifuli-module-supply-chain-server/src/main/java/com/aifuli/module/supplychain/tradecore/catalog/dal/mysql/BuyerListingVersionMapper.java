package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BuyerListingVersionMapper extends BaseMapperX<BuyerListingVersionDO> {

    default BuyerListingVersionDO selectByListingAndVersionNo(Long listingId, int versionNo) {
        return selectOne(BuyerListingVersionDO::getListingId, listingId,
                BuyerListingVersionDO::getVersionNo, versionNo);
    }

    default BuyerListingVersionDO selectLatest(Long listingId) {
        return selectOne(new LambdaQueryWrapper<BuyerListingVersionDO>()
                .eq(BuyerListingVersionDO::getListingId, listingId)
                .orderByDesc(BuyerListingVersionDO::getVersionNo)
                .last("LIMIT 1"));
    }
}
