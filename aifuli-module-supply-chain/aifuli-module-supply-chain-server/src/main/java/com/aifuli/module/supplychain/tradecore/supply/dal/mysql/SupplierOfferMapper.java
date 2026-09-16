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

    /** 通用定价只读取当前已发布且具有当前版本的供应商品候选，生效期与供应商状态由应用层继续校验。 */
    default List<SupplierOfferDO> selectPublishedBySku(Long platformSkuId) {
        return selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getPlatformSkuId, platformSkuId)
                .eq(SupplierOfferDO::getStatus, OfferStatus.PUBLISHED.getCode())
                .isNotNull(SupplierOfferDO::getCurrentVersionId)
                .orderByAsc(SupplierOfferDO::getId));
    }

    /** 供应商启停会同时改变其全部已发布报价的有效性，供通用定价按SKU去重重算。 */
    default List<SupplierOfferDO> selectPublishedBySupplier(Long supplierId) {
        return selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplierId)
                .eq(SupplierOfferDO::getStatus, OfferStatus.PUBLISHED.getCode())
                .isNotNull(SupplierOfferDO::getCurrentVersionId)
                .orderByAsc(SupplierOfferDO::getId));
    }
}
