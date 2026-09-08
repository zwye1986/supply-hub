package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SupplierOfferMapper extends BaseMapperX<SupplierOfferDO> {

    default SupplierOfferDO selectByNo(String offerNo) {
        return selectOne(SupplierOfferDO::getOfferNo, offerNo);
    }

    default SupplierOfferDO selectByNoForUpdate(String offerNo) {
        return selectOneForUpdate(SupplierOfferDO::getOfferNo, offerNo);
    }

    default List<SupplierOfferDO> selectPublishedBySupplierAndSku(Long supplierId, Long platformSkuId) {
        return selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplierId)
                .eq(SupplierOfferDO::getPlatformSkuId, platformSkuId)
                .eq(SupplierOfferDO::getStatus, OfferStatus.PUBLISHED.getCode())
                .isNotNull(SupplierOfferDO::getCurrentVersionId)
                .orderByAsc(SupplierOfferDO::getId));
    }
}
