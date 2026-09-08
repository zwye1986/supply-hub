package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerContactDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BuyerContactMapper extends BaseMapperX<BuyerContactDO> {
    default List<BuyerContactDO> selectByBuyerId(Long buyerId) {
        return selectList(new LambdaQueryWrapper<BuyerContactDO>()
                .eq(BuyerContactDO::getBuyerId, buyerId)
                .orderByAsc(BuyerContactDO::getSortNo));
    }

    default void deleteByBuyerId(Long buyerId) {
        delete(new LambdaQueryWrapper<BuyerContactDO>()
                .eq(BuyerContactDO::getBuyerId, buyerId));
    }
}
