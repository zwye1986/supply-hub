package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerCooperationModeDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BuyerCooperationModeMapper extends BaseMapperX<BuyerCooperationModeDO> {
    default List<BuyerCooperationModeDO> selectByBuyerId(Long buyerId) {
        return selectList(BuyerCooperationModeDO::getBuyerId, buyerId);
    }

    default void deleteByBuyerId(Long buyerId) {
        delete(new LambdaQueryWrapper<BuyerCooperationModeDO>()
                .eq(BuyerCooperationModeDO::getBuyerId, buyerId));
    }
}
