package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerIntegrationModeDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BuyerIntegrationModeMapper extends BaseMapperX<BuyerIntegrationModeDO> {
    default List<BuyerIntegrationModeDO> selectByBuyerId(Long buyerId) {
        return selectList(BuyerIntegrationModeDO::getBuyerId, buyerId);
    }

    default void deleteByBuyerId(Long buyerId) {
        delete(new LambdaQueryWrapper<BuyerIntegrationModeDO>()
                .eq(BuyerIntegrationModeDO::getBuyerId, buyerId));
    }
}
