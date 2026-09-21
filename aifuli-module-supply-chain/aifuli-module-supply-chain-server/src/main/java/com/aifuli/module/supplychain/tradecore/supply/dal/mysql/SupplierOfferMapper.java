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

    /**
     * 未退役 Offer：{@code PUBLISHED} + {@code SUSPENDED}。履约评分展示与写入范围。
     * 设计见 {@code docs/superpowers/specs/2026-09-21-offer-fulfillment-score-design.md#2-领域不变量}。
     */
    default List<SupplierOfferDO> selectLiveBySupplierId(Long supplierId) {
        return selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplierId)
                .in(SupplierOfferDO::getStatus, liveStatusCodes())
                .orderByAsc(SupplierOfferDO::getId));
    }

    /** 同一供应商对同一平台 SKU 当前至多一条未退役 Offer。 */
    default SupplierOfferDO selectLiveBySupplierAndSku(Long supplierId, Long platformSkuId) {
        return selectOne(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplierId)
                .eq(SupplierOfferDO::getPlatformSkuId, platformSkuId)
                .in(SupplierOfferDO::getStatus, liveStatusCodes()));
    }

    default List<SupplierOfferDO> selectLiveBySkuId(Long platformSkuId) {
        return selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getPlatformSkuId, platformSkuId)
                .in(SupplierOfferDO::getStatus, liveStatusCodes())
                .orderByAsc(SupplierOfferDO::getId));
    }

    private static List<Short> liveStatusCodes() {
        return List.of(OfferStatus.PUBLISHED.getCode(), OfferStatus.SUSPENDED.getCode());
    }
}
