package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductSettlementPriceDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BuyerProductSettlementPriceMapper extends BaseMapperX<BuyerProductSettlementPriceDO> {
    default BuyerProductSettlementPriceDO selectByBuyerProductId(Long buyerProductId) {
        return selectById(buyerProductId);
    }
}
