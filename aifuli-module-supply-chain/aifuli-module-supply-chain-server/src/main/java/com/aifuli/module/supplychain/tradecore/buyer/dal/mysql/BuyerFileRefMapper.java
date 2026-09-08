package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerFileRefDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BuyerFileRefMapper extends BaseMapperX<BuyerFileRefDO> {
    default List<BuyerFileRefDO> selectByBuyerId(Long buyerId) {
        return selectList(new LambdaQueryWrapper<BuyerFileRefDO>()
                .eq(BuyerFileRefDO::getBuyerId, buyerId)
                .orderByAsc(BuyerFileRefDO::getFileRole)
                .orderByAsc(BuyerFileRefDO::getSortNo));
    }

    default void deleteByBuyerId(Long buyerId) {
        delete(new LambdaQueryWrapper<BuyerFileRefDO>()
                .eq(BuyerFileRefDO::getBuyerId, buyerId));
    }
}
