package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BuyerMapper extends BaseMapperX<BuyerDO> {

    default BuyerDO selectByBuyerCode(String buyerCode) {
        return selectOne(BuyerDO::getBuyerCode, buyerCode);
    }

    default BuyerDO selectByBuyerCodeForUpdate(String buyerCode) {
        return selectOneForUpdate(new LambdaQueryWrapper<BuyerDO>()
                .eq(BuyerDO::getBuyerCode, buyerCode));
    }
}
