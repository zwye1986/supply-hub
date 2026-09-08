package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerCategoryScopeDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BuyerCategoryScopeMapper extends BaseMapperX<BuyerCategoryScopeDO> {
    default List<BuyerCategoryScopeDO> selectByBuyerId(Long buyerId) {
        return selectList(BuyerCategoryScopeDO::getBuyerId, buyerId);
    }

    default void deleteByBuyerId(Long buyerId) {
        delete(new LambdaQueryWrapper<BuyerCategoryScopeDO>()
                .eq(BuyerCategoryScopeDO::getBuyerId, buyerId));
    }
}
