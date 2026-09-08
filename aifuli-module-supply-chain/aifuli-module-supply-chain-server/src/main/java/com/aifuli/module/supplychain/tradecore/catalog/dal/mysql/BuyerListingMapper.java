package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BuyerListingMapper extends BaseMapperX<BuyerListingDO> {

    default BuyerListingDO selectByListingNo(String listingNo) {
        return selectOne(BuyerListingDO::getListingNo, listingNo);
    }

    default BuyerListingDO selectByListingNoForUpdate(String listingNo) {
        return selectOneForUpdate(new LambdaQueryWrapper<BuyerListingDO>()
                .eq(BuyerListingDO::getListingNo, listingNo));
    }

    default BuyerListingDO selectByBuyerAndSkuCode(Long buyerId, String buyerSkuCode) {
        return selectOne(BuyerListingDO::getBuyerId, buyerId,
                BuyerListingDO::getBuyerSkuCode, buyerSkuCode);
    }
}
