package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerCatalogChangeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerCatalogChangeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
import lombok.extern.slf4j.Slf4j;
import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_CATALOG_AUTO_MANAGED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_CATALOG_NOT_READY;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class BuyerListingPublicationService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String CATALOG_TARGET = "buyer-catalog-change-fanout";

    private final BuyerListingMapper listingMapper;
    private final BuyerListingVersionMapper versionMapper;
    private final BuyerListingAvailabilityMapper availabilityMapper;
    private final BuyerCatalogChangeMapper changeMapper;
    private final BuyerMapper buyerMapper;
    private final ProductSellabilityService productSellabilityService;
    private final OutboxEventRepository outboxRepository;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public PublishResult publish(PublishListingCommand command) {
        log.info("发布手工受理目录 listingNo={} expectedVersion={} operatorId={}",
                command.listingNo(), command.expectedListingVersion(), command.operatorId());
        validateCommercialTerms(command);
        BuyerListingDO listing = requireListingForUpdate(command.listingNo());
        // 自动目录不能从兼容入口接受外部价；来源资格发布仅在B切片交付。
        // 见 docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#44-手工入口治理。
        if (!BuyerListingManagementMode.MANUAL.name().equals(listing.getManagementMode())) {
            throw exception(BUYER_CATALOG_AUTO_MANAGED);
        }
        requireExpectedVersion(listing, command.expectedListingVersion());
        BuyerListingStatus currentStatus = BuyerListingStatus.fromCode(listing.getStatus());
        if (currentStatus == BuyerListingStatus.RETIRED) {
            throw new IllegalStateException("Retired listing cannot publish a new version");
        }
        requireActiveMasterData(listing);

        BuyerListingVersionDO latest = versionMapper.selectLatest(listing.getId());
        int versionNo = latest == null ? 1 : latest.getVersionNo() + 1;
        LocalDateTime now = now();
        BuyerListingVersionDO version = BuyerListingVersionDO.builder()
                .listingId(listing.getId())
                .versionNo(versionNo)
                .buyerSettlementUnitPrice(command.buyerSettlementUnitPrice())
                .currency("CNY")
                .displayName(command.displayName())
                .minQuantity(1L)
                .maxQuantity(1L)
                .validFrom(command.validFrom())
                .validTo(command.validTo())
                .publishReason(command.publishReason())
                .publishedBy(command.operatorId())
                .publishedAt(now)
                .createdAt(now)
                .build();
        versionMapper.insert(version);

        long nextAggregateVersion = listing.getVersion() + 1;
        short nextStatus = currentStatus == BuyerListingStatus.DRAFT
                ? BuyerListingStatus.PUBLISHED.getCode() : currentStatus.getCode();
        int updated = listingMapper.update(null, new LambdaUpdateWrapper<BuyerListingDO>()
                .eq(BuyerListingDO::getId, listing.getId())
                .eq(BuyerListingDO::getVersion, listing.getVersion())
                .set(BuyerListingDO::getCurrentVersionId, version.getId())
                .set(BuyerListingDO::getStatus, nextStatus)
                .set(BuyerListingDO::getUpdatedBy, command.operatorId())
                .set(BuyerListingDO::getUpdatedAt, now)
                .set(BuyerListingDO::getVersion, nextAggregateVersion));
        if (updated != 1) {
            throw new IllegalStateException("Listing publication lost optimistic lock");
        }

        BuyerListingAvailabilityDO availability = availabilityMapper.selectByListingId(listing.getId());
        BuyerCatalogChangeDO change = appendChange(listing, version, availability, "UPSERT", now);
        CatalogChangePayload payload = new CatalogChangePayload(change.getId(), listing.getListingNo(),
                versionNo, availability == null ? null : availability.getVersion(), "UPSERT", now);
        outboxRepository.appendLocal("BUYER_LISTING", listing.getListingNo(), nextAggregateVersion,
                "BUYER_CATALOG_CHANGED", CATALOG_TARGET, payload, command.traceId(),
                command.correlationId(), command.causationId(), now);
        return new PublishResult(listing.getListingNo(), versionNo, nextAggregateVersion, change.getId());
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeStatus(ChangeListingStatusCommand command) {
        log.info("变更受理目录状态 listingNo={} target={} expectedVersion={} operatorId={}",
                command.listingNo(), command.target(), command.expectedListingVersion(), command.operatorId());
        BuyerListingDO listing = requireListingForUpdate(command.listingNo());
        requireExpectedVersion(listing, command.expectedListingVersion());
        // B真实价格及供给资格尚未交付；currentVersion非空也不能授权AUTO恢复。
        // 见 docs/superpowers/specs/2026-10-04-buyer-selection-acceptance-catalog-bridge-design.md#44-手工入口治理。
        if (command.target() == BuyerListingStatus.PUBLISHED
                && !BuyerListingManagementMode.MANUAL.name().equals(listing.getManagementMode())) {
            throw exception(BUYER_CATALOG_NOT_READY);
        }
        BuyerListingStatus current = BuyerListingStatus.fromCode(listing.getStatus());
        if (!current.canTransitionTo(command.target())) {
            throw new IllegalStateException("Listing status transition is not allowed: "
                    + current + " -> " + command.target());
        }
        if (command.target() == BuyerListingStatus.PUBLISHED && listing.getCurrentVersionId() == null) {
            throw new IllegalStateException("Listing without a published version cannot be resumed");
        }

        LocalDateTime now = now();
        long nextAggregateVersion = listing.getVersion() + 1;
        int updated = listingMapper.update(null, new LambdaUpdateWrapper<BuyerListingDO>()
                .eq(BuyerListingDO::getId, listing.getId())
                .eq(BuyerListingDO::getVersion, listing.getVersion())
                .set(BuyerListingDO::getStatus, command.target().getCode())
                .set(BuyerListingDO::getUpdatedBy, command.operatorId())
                .set(BuyerListingDO::getUpdatedAt, now)
                .set(BuyerListingDO::getVersion, nextAggregateVersion));
        if (updated != 1) {
            throw new IllegalStateException("Listing status update lost optimistic lock");
        }

        BuyerListingVersionDO version = listing.getCurrentVersionId() == null
                ? null : versionMapper.selectById(listing.getCurrentVersionId());
        BuyerListingAvailabilityDO availability = availabilityMapper.selectByListingId(listing.getId());
        String changeType = command.target() == BuyerListingStatus.PUBLISHED ? "UPSERT" : "TOMBSTONE";
        BuyerCatalogChangeDO change = appendChange(listing, version, availability, changeType, now);
        CatalogChangePayload payload = new CatalogChangePayload(change.getId(), listing.getListingNo(),
                version == null ? null : version.getVersionNo(),
                availability == null ? null : availability.getVersion(), changeType, now);
        outboxRepository.appendLocal("BUYER_LISTING", listing.getListingNo(), nextAggregateVersion,
                "BUYER_CATALOG_CHANGED", CATALOG_TARGET, payload, command.traceId(),
                command.correlationId(), command.causationId(), now);
    }

    private BuyerCatalogChangeDO appendChange(BuyerListingDO listing, BuyerListingVersionDO version,
                                               BuyerListingAvailabilityDO availability, String changeType,
                                               LocalDateTime occurredAt) {
        BuyerCatalogChangeDO change = BuyerCatalogChangeDO.builder()
                .buyerId(listing.getBuyerId())
                .changeType(changeType)
                .listingId(listing.getId())
                .listingVersionId(version == null ? null : version.getId())
                .availabilityVersion(availability == null ? null : availability.getVersion())
                .occurredAt(occurredAt)
                .payloadDigest(digest(listing, version, availability, changeType))
                .build();
        changeMapper.insert(change);
        return change;
    }

    private void requireActiveMasterData(BuyerListingDO listing) {
        BuyerDO buyer = buyerMapper.selectById(listing.getBuyerId());
        if (buyer == null || buyer.getStatus() != BuyerStatus.ACTIVE.getCode()) {
            throw new IllegalStateException("Listing buyer is not active");
        }
        productSellabilityService.requireSellableById(listing.getPlatformSkuId());
    }

    private BuyerListingDO requireListingForUpdate(String listingNo) {
        BuyerListingDO listing = listingMapper.selectByListingNoForUpdate(listingNo);
        if (listing == null) {
            throw new IllegalArgumentException("Buyer listing does not exist: " + listingNo);
        }
        return listing;
    }

    private static void requireExpectedVersion(BuyerListingDO listing, long expectedVersion) {
        if (listing.getVersion() != expectedVersion) {
            throw new IllegalStateException("Buyer listing version conflict");
        }
    }

    private static void validateCommercialTerms(PublishListingCommand command) {
        if (command.buyerSettlementUnitPrice() < 0) {
            throw new IllegalArgumentException("Buyer settlement unit price cannot be negative");
        }
        if (command.validFrom() == null
                || (command.validTo() != null && !command.validTo().isAfter(command.validFrom()))) {
            throw new IllegalArgumentException("Listing validity range is invalid");
        }
        if (command.traceId() == null || command.traceId().isBlank()) {
            throw new IllegalArgumentException("Trace ID is required for publication");
        }
    }

    private static byte[] digest(BuyerListingDO listing, BuyerListingVersionDO version,
                                 BuyerListingAvailabilityDO availability, String changeType) {
        String canonical = String.join("|", listing.getListingNo(), changeType,
                version == null ? "" : String.valueOf(version.getVersionNo()),
                version == null ? "" : String.valueOf(version.getBuyerSettlementUnitPrice()),
                availability == null ? "" : String.valueOf(availability.getVersion()),
                availability == null ? "" : String.valueOf(availability.getAvailability()));
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private LocalDateTime now() {
        return databaseClock.now();
    }

    public record PublishListingCommand(String listingNo, long expectedListingVersion,
                                        long buyerSettlementUnitPrice, String displayName,
                                        LocalDateTime validFrom, LocalDateTime validTo,
                                        String publishReason, Long operatorId, String traceId,
                                        String correlationId, String causationId) {
    }

    public record ChangeListingStatusCommand(String listingNo, long expectedListingVersion,
                                             BuyerListingStatus target, Long operatorId,
                                             String traceId, String correlationId, String causationId) {
    }

    public record PublishResult(String listingNo, int listingVersion,
                                long aggregateVersion, long catalogCursor) {
    }

    public record CatalogChangePayload(long latestCursor, String listingNo, Integer listingVersion,
                                       Long availabilityVersion, String changeType,
                                       LocalDateTime occurredAt) {
    }
}
