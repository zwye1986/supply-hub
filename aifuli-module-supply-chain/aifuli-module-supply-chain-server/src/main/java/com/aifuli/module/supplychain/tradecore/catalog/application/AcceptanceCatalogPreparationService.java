package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceReceiptStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerAcceptanceCatalogResolver.CatalogIdentity;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogRefreshReason;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CurrentSupplyEligibility;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.STANDARD_PRICING_QUOTE_MISSING;

/**
 * 首冻前目录与供给准备服务。
 *
 * <p>AUTO 路径执行单条短事务刷新；MANUAL 路径核验当前商业版本与真实项目供给。
 * 缺价优先抛出 STANDARD_PRICING_QUOTE_MISSING 保持 PROCESSING；
 * 暂停、退役或缺供给返回稳定拒绝事实，不新建报价。
 * 详见 docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#61-请求准备。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcceptanceCatalogPreparationService {
    private final SupplyChainDatabaseClock clock;
    private final BuyerSelectionCatalogLifecycle catalogLifecycle;
    private final CurrentSupplyEligibilityReader supplyEligibilityReader;
    private final BuyerListingMapper listingMapper;
    private final BuyerListingVersionMapper versionMapper;

    public Optional<PreparationFailure> prepare(CatalogIdentity identity, Long projectId, long receiptId) {
        if (identity == null || !identity.available()) {
            return Optional.of(new PreparationFailure(receiptId, null,
                    AcceptanceReceiptStatus.REJECTED_RETRYABLE, AcceptanceRejectionCode.LISTING_VERSION_EXPIRED));
        }

        BuyerSelectionCatalogLifecycle.RefreshResult refresh = null;
        if (BuyerListingManagementMode.AUTO_SELECTION.name().equals(identity.managementMode())) {
            refresh = catalogLifecycle.refreshListing(identity.listingNo(), null);
            if (BuyerListingStatus.SUSPENDED.name().equals(refresh.publicationState())
                    || BuyerListingStatus.RETIRED.name().equals(refresh.publicationState())) {
                return Optional.of(new PreparationFailure(receiptId, null,
                        AcceptanceReceiptStatus.REJECTED_RETRYABLE, AcceptanceRejectionCode.LISTING_VERSION_EXPIRED));
            }
            if (CatalogRefreshReason.DEFERRED_PRICE.name().equals(refresh.reason())) {
                throw exception(STANDARD_PRICING_QUOTE_MISSING);
            }
        }

        BuyerListingDO listing = listingMapper.selectById(identity.listingId());
        BuyerListingVersionDO version = listing == null || listing.getCurrentVersionId() == null
                ? null : versionMapper.selectById(listing.getCurrentVersionId());
        if (listing != null && listing.getCurrentVersionId() != null
                && (version == null || !Objects.equals(version.getListingId(), listing.getId()))) {
            throw new IllegalStateException("Preparation commercial current reference is missing or mismatched");
        }
        // 拒绝引用只取真实商业版本；买家级刷新结果绝不能作为本次项目授权或回执版本。
        // 详见 docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#91-2026-10-10-评审修复约束。
        if (refresh != null && !ListingAvailability.AVAILABLE.name().equals(refresh.availabilityStatus())) {
            return Optional.of(new PreparationFailure(receiptId, version == null ? null : version.getId(),
                    AcceptanceReceiptStatus.REJECTED_RETRYABLE, AcceptanceRejectionCode.INVENTORY_INSUFFICIENT));
        }
        if (listing == null || listing.getStatus() != BuyerListingStatus.PUBLISHED.getCode()
                || listing.getCurrentVersionId() == null) {
            return Optional.of(new PreparationFailure(receiptId, null,
                    AcceptanceReceiptStatus.REJECTED_RETRYABLE, AcceptanceRejectionCode.LISTING_VERSION_EXPIRED));
        }
        var now = clock.now();
        if (version.getValidFrom() == null) {
            throw new IllegalStateException("Preparation commercial validFrom is missing");
        }
        if (version.getValidFrom().isAfter(now)
                || version.getValidTo() != null && !version.getValidTo().isAfter(now)) {
            return Optional.of(new PreparationFailure(receiptId, version.getId(),
                    AcceptanceReceiptStatus.REJECTED_RETRYABLE, AcceptanceRejectionCode.LISTING_VERSION_EXPIRED));
        }

        CurrentSupplyEligibility eligibility = supplyEligibilityReader.evaluate(
                identity.buyerId(), projectId, identity.platformSkuId());
        if (!eligibility.confirmed()) {
            return Optional.of(new PreparationFailure(receiptId, version.getId(),
                    AcceptanceReceiptStatus.REJECTED_RETRYABLE, AcceptanceRejectionCode.INVENTORY_INSUFFICIENT));
        }
        return Optional.empty();
    }

    public record PreparationFailure(long receiptId, Long listingVersionId,
                                      AcceptanceReceiptStatus receiptStatus,
                                      AcceptanceRejectionCode rejectionCode) {
    }
}
