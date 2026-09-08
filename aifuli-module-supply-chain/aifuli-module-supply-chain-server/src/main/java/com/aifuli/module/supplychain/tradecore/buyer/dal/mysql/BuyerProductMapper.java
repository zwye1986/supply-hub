package com.aifuli.module.supplychain.tradecore.buyer.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import org.apache.ibatis.annotations.Mapper;

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
}
