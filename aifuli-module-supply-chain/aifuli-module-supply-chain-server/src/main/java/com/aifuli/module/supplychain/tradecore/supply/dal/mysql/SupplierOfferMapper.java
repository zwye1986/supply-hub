package com.aifuli.module.supplychain.tradecore.supply.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
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

    /**
     * 通用定价读取已发布供应商品。无当前版本时由应用层按身份列回退供货价。
     * 设计见 {@code docs/superpowers/specs/2026-09-22-effective-supply-price-identity-fallback-design.md}。
     */
    default List<SupplierOfferDO> selectPublishedBySku(Long platformSkuId) {
        return selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getPlatformSkuId, platformSkuId)
                .eq(SupplierOfferDO::getStatus, OfferStatus.PUBLISHED.getCode())
                .orderByAsc(SupplierOfferDO::getId));
    }

    /** 供应商启停会同时改变其全部已发布报价的有效性，含仅有身份列单价、尚无报价版本的合作商品。 */
    default List<SupplierOfferDO> selectPublishedBySupplier(Long supplierId) {
        return selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplierId)
                .eq(SupplierOfferDO::getStatus, OfferStatus.PUBLISHED.getCode())
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

    /**
     * 正常供应商名下全部未退役合作商品 SKU，用于在 SPU 分页前收窄商品候选集。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d47）。
     */
    default List<Long> selectLiveSkuIdsBySupplierIds(Collection<Long> supplierIds) {
        if (supplierIds == null || supplierIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                .select(SupplierOfferDO::getPlatformSkuId)
                .in(SupplierOfferDO::getSupplierId, supplierIds)
                .in(SupplierOfferDO::getStatus, liveStatusCodes()))
                .stream()
                .map(SupplierOfferDO::getPlatformSkuId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private static List<Short> liveStatusCodes() {
        return List.of(OfferStatus.PUBLISHED.getCode(), OfferStatus.SUSPENDED.getCode());
    }
}
