package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogRefreshReason;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 管理目录只读投影，canonical 编码保持内部 BPD 身份；不调用发布/刷新、不持行锁。
 * AUTO 的投影期限、当前定价来源与商业有效期共同决定展示，不能作为某项目实时准入证明。
 * MANUAL 保留既有可用性读契约，不受 AUTO 来源规则影响。
 * 设计见 {@code docs/superpowers/specs/2026-10-12-catalog-source-freshness-correction-design.md#4-auto-判定顺序与时间边界}。
 */
@Service
@RequiredArgsConstructor
public class BuyerAcceptanceCatalogReadService {
    private final BuyerListingMapper listings;
    private final BuyerListingVersionMapper versions;
    private final BuyerListingAvailabilityMapper projections;
    private final BuyerSkuPricingMapper pricing;
    private final SupplyChainDatabaseClock clock;

    @Transactional(transactionManager = "supplyChainTransactionManager", readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public CatalogView read(BuyerProductDO product) {
        LocalDateTime now = clock.now();
        BuyerListingDO listing = listings.selectByBuyerAndSku(product.getBuyerId(), product.getPlatformSkuId());
        if (listing == null) {
            return new CatalogView(product.getBuyerProductCode(), null, null, null,
                    ListingAvailability.UNKNOWN.name(), CatalogRefreshReason.NOT_EVALUATED.name(), null, null, null);
        }
        var mode = BuyerListingManagementMode.valueOf(listing.getManagementMode());
        var state = BuyerListingStatus.fromCode(listing.getStatus());
        if (!Objects.equals(listing.getBuyerId(), product.getBuyerId())
                || !Objects.equals(listing.getPlatformSkuId(), product.getPlatformSkuId())
                || mode == BuyerListingManagementMode.AUTO_SELECTION
                && (!Objects.equals(listing.getBuyerProductId(), product.getId())
                || !Objects.equals(listing.getBuyerSkuCode(), product.getBuyerProductCode()))) {
            throw new IllegalStateException("Acceptance catalog read ownership is invalid");
        }
        var version = listing.getCurrentVersionId() == null ? null : versions.selectById(listing.getCurrentVersionId());
        if (listing.getCurrentVersionId() != null && (version == null || !Objects.equals(version.getListingId(), listing.getId()))
                || state == BuyerListingStatus.PUBLISHED && version == null) {
            throw new IllegalStateException("Published acceptance catalog current reference is missing or mismatched");
        }
        var projection = projections.selectByListingId(listing.getId());
        if (projection == null || !Objects.equals(projection.getListingId(), listing.getId())) {
            throw new IllegalStateException("Acceptance catalog availability identity is missing or mismatched");
        }
        ListingAvailability status = ListingAvailability.fromCode(projection.getAvailability());
        String reason = projection.getReasonCode();
        if (mode == BuyerListingManagementMode.AUTO_SELECTION) {
            var source = pricing.selectByBuyerProductId(product.getId());
            if (source != null && !Objects.equals(source.getBuyerProductId(), product.getId())) {
                throw new IllegalStateException("Acceptance catalog pricing read ownership is invalid");
            }
            var decision = BuyerCatalogAutoReadPolicy.decide(new BuyerCatalogAutoReadPolicy.Input(
                    listing.getId(), state, source == null ? null : source.getPricingStatus(),
                    source == null ? null : source.getCurrentVersionId(), projection.getBasisJson(),
                    projection.getEvaluatedAt(), projection.getValidUntil(), version != null,
                    version == null ? null : version.getSourceBuyerPricingVersionId(),
                    version == null ? null : version.getValidFrom(), version == null ? null : version.getValidTo(),
                    status, reason), now);
            status = decision.availability();
            reason = decision.reason();
        }
        return new CatalogView(product.getBuyerProductCode(), listing.getListingNo(), mode.name(), state.name(),
                status.name(), reason, version == null ? null : version.getSourceBuyerPricingVersionId(),
                projection.getEvaluatedAt(), projection.getValidUntil());
    }

    /** 字段沿 060 DDL 语义，只用于管理展示，不接受客户端回写。 */
    public record CatalogView(String canonicalBuyerSkuCode, String acceptanceListingNo, String managementMode,
                              String publicationState, String availabilityStatus, String catalogRefreshReason,
                              Long sourceBuyerPricingVersionId, LocalDateTime lastEvaluatedAt, LocalDateTime nextEvaluateAt) { }
}
