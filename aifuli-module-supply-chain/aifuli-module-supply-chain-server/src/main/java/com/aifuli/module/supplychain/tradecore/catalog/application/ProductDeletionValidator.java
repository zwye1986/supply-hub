package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CANNOT_DELETE_HAS_BUYER_LISTING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CANNOT_DELETE_HAS_BUYER_PRODUCT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CANNOT_DELETE_HAS_PROJECT_LISTING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CANNOT_DELETE_HAS_SUPPLIER_OFFER;

/**
 * 平台商品删除引用校验器。项目商品必须通过该 SKU 的全部 BuyerProduct 查找，不能按 BuyerProduct 状态过滤。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#32-删除强校验规则4-表物理主键与关联路径检查}。
 */
@Component
@RequiredArgsConstructor
public class ProductDeletionValidator {

    private final PlatformSkuMapper skuMapper;
    private final SupplierOfferMapper offerMapper;
    private final BuyerListingMapper buyerListingMapper;
    private final BuyerProductMapper buyerProductMapper;
    private final ProjectListingMapper projectListingMapper;

    public void validateSpu(Long spuId) {
        for (PlatformSkuDO sku : skuMapper.selectList(new LambdaQueryWrapper<PlatformSkuDO>()
                .eq(PlatformSkuDO::getSpuId, spuId))) {
            validateSku(sku.getId());
        }
    }

    public void validateSku(Long skuId) {
        if (existsOffer(skuId)) throw exception(PRODUCT_CANNOT_DELETE_HAS_SUPPLIER_OFFER);
        if (existsBuyerListing(skuId)) throw exception(PRODUCT_CANNOT_DELETE_HAS_BUYER_LISTING);

        List<BuyerProductDO> allBuyerProducts = buyerProductMapper.selectList(
                new LambdaQueryWrapper<BuyerProductDO>().eq(BuyerProductDO::getPlatformSkuId, skuId));
        List<Long> allBuyerProductIds = allBuyerProducts.stream().map(BuyerProductDO::getId).toList();
        if (!allBuyerProductIds.isEmpty()) {
            Long projectCount = projectListingMapper.selectCount(new LambdaQueryWrapper<ProjectListingDO>()
                    .in(ProjectListingDO::getBuyerProductId, allBuyerProductIds)
                    .eq(ProjectListingDO::getStatus, ProjectListingStatus.AVAILABLE.getCode()));
            if (projectCount != null && projectCount > 0) {
                throw exception(PRODUCT_CANNOT_DELETE_HAS_PROJECT_LISTING);
            }
        }
        boolean activeBuyerProduct = allBuyerProducts.stream().anyMatch(product -> product.getStatus() != null
                && product.getStatus() == BuyerProductStatus.ACTIVE.getCode());
        if (activeBuyerProduct) throw exception(PRODUCT_CANNOT_DELETE_HAS_BUYER_PRODUCT);
    }

    private boolean existsOffer(Long skuId) {
        Long count = offerMapper.selectCount(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getPlatformSkuId, skuId)
                .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode()));
        return count != null && count > 0;
    }

    private boolean existsBuyerListing(Long skuId) {
        Long count = buyerListingMapper.selectCount(new LambdaQueryWrapper<BuyerListingDO>()
                .eq(BuyerListingDO::getPlatformSkuId, skuId)
                .ne(BuyerListingDO::getStatus, BuyerListingStatus.RETIRED.getCode()));
        return count != null && count > 0;
    }
}
