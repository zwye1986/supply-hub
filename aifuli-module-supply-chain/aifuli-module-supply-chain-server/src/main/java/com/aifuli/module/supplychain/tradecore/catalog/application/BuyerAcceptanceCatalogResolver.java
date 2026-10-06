package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.Objects;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_CATALOG_CODE_CONFLICT;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 双编码受理身份定位；见 docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#31-双查与碰撞。 */
@Service
@RequiredArgsConstructor
public class BuyerAcceptanceCatalogResolver {
    private final BuyerProductMapper productMapper;
    private final BuyerListingMapper listingMapper;

    public CatalogIdentity resolve(Long buyerId, String requestedCode) {
        BuyerProductDO product = productMapper.selectByCode(requestedCode);
        BuyerListingDO exact = listingMapper.selectByBuyerAndSkuCode(buyerId, requestedCode);
        if (product != null && !Objects.equals(buyerId, product.getBuyerId())) {
            if (exact != null) {
                throw exception(BUYER_CATALOG_CODE_CONFLICT);
            }
            return new CatalogIdentity(buyerId, requestedCode, null, null, null, null, null, null);
        }
        BuyerListingDO canonical = product == null ? null : listingMapper.selectOne(
                new LambdaQueryWrapper<BuyerListingDO>().eq(BuyerListingDO::getBuyerId, buyerId)
                        .eq(BuyerListingDO::getPlatformSkuId, product.getPlatformSkuId()));
        if (product != null && exact != null && (!Objects.equals(product.getPlatformSkuId(), exact.getPlatformSkuId())
                || canonical == null || !Objects.equals(canonical.getId(), exact.getId()))) {
            throw exception(BUYER_CATALOG_CODE_CONFLICT);
        }
        BuyerListingDO listing = canonical != null ? canonical : exact;
        if (listing != null) {
            requireRowOwnership(listing, buyerId, product == null ? listing.getPlatformSkuId() : product.getPlatformSkuId());
        }
        return new CatalogIdentity(buyerId, requestedCode, product == null ? null : product.getId(),
                product == null ? null : product.getBuyerProductCode(), listing == null ? null : listing.getId(),
                listing == null ? null : listing.getListingNo(),
                listing == null ? (product == null ? null : product.getPlatformSkuId()) : listing.getPlatformSkuId(),
                listing == null ? null : listing.getManagementMode());
    }

    static void requireRowOwnership(BuyerListingDO listing, Long buyerId, Long skuId) {
        if (!Objects.equals(buyerId, listing.getBuyerId()) || !Objects.equals(skuId, listing.getPlatformSkuId())
                || !(BuyerListingManagementMode.MANUAL.name().equals(listing.getManagementMode()) && listing.getBuyerProductId() == null
                || BuyerListingManagementMode.AUTO_SELECTION.name().equals(listing.getManagementMode()) && listing.getBuyerProductId() != null)) {
            throw exception(BUYER_CATALOG_CODE_CONFLICT);
        }
    }

    /** 最终事务仅按可信LST ID当前读；此处不得普通读BPD提前建立RR视图，见桥接设计§3.1。 */
    public BuyerListingDO lockResolvedIdentity(CatalogIdentity identity) {
        if (identity == null || !identity.available()) { return null; }
        BuyerListingDO row = listingMapper.selectByIdForUpdate(identity.listingId());
        if (row == null) { return null; }
        requireRowOwnership(row, identity.buyerId(), identity.platformSkuId());
        if (!Objects.equals(row.getListingNo(), identity.listingNo())
                || !Objects.equals(row.getManagementMode(), identity.managementMode())) {
            throw exception(BUYER_CATALOG_CODE_CONFLICT);
        }
        if (BuyerListingManagementMode.AUTO_SELECTION.name().equals(row.getManagementMode())
                && identity.buyerProductId() != null && !Objects.equals(row.getBuyerProductId(), identity.buyerProductId())) {
            throw exception(BUYER_CATALOG_CODE_CONFLICT);
        }
        return row;
    }

    /** 涉及BPD的普通关联查询只能在SKU当前锁取得后执行，不扩旧RPC移除准入语义，见桥接设计§3.1/§12.1。 */
    public void verifyAssociationAfterSkuLock(CatalogIdentity identity, BuyerListingDO listing) {
        Long productId = BuyerListingManagementMode.AUTO_SELECTION.name().equals(listing.getManagementMode())
                ? listing.getBuyerProductId() : identity.buyerProductId();
        if (productId == null) { return; }
        BuyerProductDO product = productMapper.selectById(productId);
        if (product == null || !Objects.equals(listing.getBuyerId(), product.getBuyerId())
                || !Objects.equals(listing.getPlatformSkuId(), product.getPlatformSkuId())
                || identity.buyerProductId() != null && (!Objects.equals(product.getId(), identity.buyerProductId())
                || !Objects.equals(product.getBuyerProductCode(), identity.buyerProductCode()))
                || BuyerListingManagementMode.AUTO_SELECTION.name().equals(listing.getManagementMode())
                && !Objects.equals(product.getBuyerProductCode(), listing.getBuyerSkuCode())) {
            throw exception(BUYER_CATALOG_CODE_CONFLICT);
        }
    }

    /** 仅冻结可信不可变身份，不携带授权状态；请求编码原样保留。 */
    public record CatalogIdentity(Long buyerId, String requestedCode, Long buyerProductId,
                                  String buyerProductCode, Long listingId, String listingNo,
                                  Long platformSkuId, String managementMode) {
        public boolean available() { return listingId != null; }
    }
}
