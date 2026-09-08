package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SupplierOfferVersionMapper extends BaseMapperX<SupplierOfferVersionDO> {

    default SupplierOfferVersionDO selectLatest(Long offerId) {
        return selectOne(new LambdaQueryWrapper<SupplierOfferVersionDO>()
                .eq(SupplierOfferVersionDO::getOfferId, offerId)
                .orderByDesc(SupplierOfferVersionDO::getVersionNo)
                .last("LIMIT 1"));
    }
}
