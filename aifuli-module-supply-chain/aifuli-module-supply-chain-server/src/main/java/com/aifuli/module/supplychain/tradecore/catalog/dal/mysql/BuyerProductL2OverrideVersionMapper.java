package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BuyerProductL2OverrideVersionMapper extends BaseMapperX<BuyerProductL2OverrideVersionDO> {
    default List<BuyerProductL2OverrideVersionDO> selectByBuyerProductId(Long buyerProductId) {
        return selectList(new LambdaQueryWrapper<BuyerProductL2OverrideVersionDO>()
                .eq(BuyerProductL2OverrideVersionDO::getBuyerProductId, buyerProductId)
                .orderByAsc(BuyerProductL2OverrideVersionDO::getVersionNo));
    }
}
