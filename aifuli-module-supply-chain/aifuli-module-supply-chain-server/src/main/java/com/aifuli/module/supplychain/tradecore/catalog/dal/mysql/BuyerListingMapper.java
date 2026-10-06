package com.aifuli.module.supplychain.tradecore.catalog.dal.mysql;

import com.aifuli.framework.mybatis.core.mapper.BaseMapperX;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BuyerListingMapper extends BaseMapperX<BuyerListingDO> {

    /** 排序规则专属标量比较，Lambda不能表达有界字符前缀；见桥接设计§3.1。 */
    boolean isReservedBuyerProductCode(@org.apache.ibatis.annotations.Param("normalizedCode") String normalizedCode,
                                      @org.apache.ibatis.annotations.Param("reservedPrefix") String reservedPrefix);

    default BuyerListingDO selectByBuyerAndSku(Long buyerId, Long skuId) {
        return selectOne(new LambdaQueryWrapper<BuyerListingDO>().eq(BuyerListingDO::getBuyerId, buyerId)
                .eq(BuyerListingDO::getPlatformSkuId, skuId));
    }

    default BuyerListingDO selectByBuyerAndSkuForUpdate(Long buyerId, Long skuId) {
        return selectOneForUpdate(new LambdaQueryWrapper<BuyerListingDO>().eq(BuyerListingDO::getBuyerId, buyerId)
                .eq(BuyerListingDO::getPlatformSkuId, skuId));
    }

    default BuyerListingDO selectByIdForUpdate(Long id) {
        return selectOneForUpdate(BuyerListingDO::getId, id);
    }

    default BuyerListingDO selectByListingNo(String listingNo) {
        return selectOne(BuyerListingDO::getListingNo, listingNo);
    }

    default BuyerListingDO selectByListingNoForUpdate(String listingNo) {
        return selectOneForUpdate(new LambdaQueryWrapper<BuyerListingDO>()
                .eq(BuyerListingDO::getListingNo, listingNo));
    }

    default BuyerListingDO selectByBuyerAndSkuCode(Long buyerId, String buyerSkuCode) {
        return selectOne(BuyerListingDO::getBuyerId, buyerId,
                BuyerListingDO::getBuyerSkuCode, buyerSkuCode);
    }

    /**
     * 悲观锁锁定采购商上架记录，防止并发重开与受理冲突。
     *
     * <p>设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。</p>
     */
    default BuyerListingDO selectByBuyerAndSkuCodeForUpdate(Long buyerId, String buyerSkuCode) {
        return selectOneForUpdate(new LambdaQueryWrapper<BuyerListingDO>()
                .eq(BuyerListingDO::getBuyerId, buyerId)
                .eq(BuyerListingDO::getBuyerSkuCode, buyerSkuCode));
    }
}
