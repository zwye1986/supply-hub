package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;

import java.util.List;

@Mapper
public interface BuyerProductMapper extends BaseMapperX<BuyerProductDO> {
    default BuyerProductDO selectByCode(String buyerProductCode) {
        return selectOne(BuyerProductDO::getBuyerProductCode, buyerProductCode);
    }

    default BuyerProductDO selectByCodeForUpdate(String buyerProductCode) {
        return selectOneForUpdate(BuyerProductDO::getBuyerProductCode, buyerProductCode);
    }

    default BuyerProductDO selectByBuyerAndSku(Long buyerId, Long platformSkuId) {
        return selectOne(BuyerProductDO::getBuyerId, buyerId,
                BuyerProductDO::getPlatformSkuId, platformSkuId);
    }

    default List<BuyerProductDO> selectActiveByPlatformSkuId(Long platformSkuId) {
        return selectList(new LambdaQueryWrapper<BuyerProductDO>()
                .eq(BuyerProductDO::getPlatformSkuId, platformSkuId)
                .eq(BuyerProductDO::getStatus, BuyerProductStatus.ACTIVE.getCode()));
    }
}
