package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductSettlementPriceVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BuyerProductSettlementPriceVersionMapper extends BaseMapperX<BuyerProductSettlementPriceVersionDO> {
    default List<BuyerProductSettlementPriceVersionDO> selectByBuyerProductId(Long buyerProductId) {
        return selectList(new LambdaQueryWrapper<BuyerProductSettlementPriceVersionDO>()
                .eq(BuyerProductSettlementPriceVersionDO::getBuyerProductId, buyerProductId)
                .orderByAsc(BuyerProductSettlementPriceVersionDO::getVersionNo));
    }
}
