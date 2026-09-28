package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BuyerSkuPricingMapper extends BaseMapperX<BuyerSkuPricingDO> {
    default BuyerSkuPricingDO selectByBuyerProductId(Long buyerProductId) {
        return selectOne(BuyerSkuPricingDO::getBuyerProductId, buyerProductId);
    }
    default BuyerSkuPricingDO selectByBuyerProductIdForUpdate(Long buyerProductId) {
        return selectOneForUpdate(BuyerSkuPricingDO::getBuyerProductId, buyerProductId);
    }
}
