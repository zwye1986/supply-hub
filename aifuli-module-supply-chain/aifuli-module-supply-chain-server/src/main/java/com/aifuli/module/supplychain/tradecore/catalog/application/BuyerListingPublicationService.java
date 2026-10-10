package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.configuration.BuyerSelectionCatalogProperties;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerCatalogChangeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerCatalogChangeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_CATALOG_AUTO_MANAGED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_CATALOG_NOT_READY;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

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
    /** 人工恢复AUTO必须在同一LST当前锁内核对以下口径：真实价格链与买家聚合资格。见设计 §4.3。 */
    private final BuyerSkuPricingMapper buyerPricingMapper;
    private final BuyerSkuPricingVersionMapper buyerVersionMapper;
    private final PlatformSkuPricingMapper platformPricingMapper;
    private final CurrentSupplyEligibilityReader eligibilityReader;
    /** 人工恢复安全投影basis序列化，与刷新worker共用同一ObjectMapper口径。 */
    private final ObjectMapper objectMapper;
    /** 最大评估有效期：恢复投影期限与刷新worker同口径。 */
    private final BuyerSelectionCatalogProperties properties;

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
        log.info("[changeListingStatus][变更受理目录状态] listingNo={}, target={}, expectedVersion={}, operatorId={}",
                command.listingNo(), command.target(), command.expectedListingVersion(), command.operatorId());
        BuyerListingDO listing = requireListingForUpdate(command.listingNo());
        requireExpectedVersion(listing, command.expectedListingVersion());
        boolean automatic = BuyerListingManagementMode.AUTO_SELECTION.name().equals(listing.getManagementMode());
        // AUTO的真实价格与供给资格已由Task3交付：人工恢复在同一LST锁内核对，普通调用入口不再失败关闭。
        // 见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#43-真实来源与幂等。
        if (command.target() == BuyerListingStatus.PUBLISHED && !automatic
                && !BuyerListingManagementMode.MANUAL.name().equals(listing.getManagementMode())) {
            throw exception(BUYER_CATALOG_NOT_READY);
        }
        BuyerListingStatus current = BuyerListingStatus.fromCode(listing.getStatus());
        if (!current.canTransitionTo(command.target())) {
            throw new IllegalStateException("Listing status transition is not allowed: "
                    + current + " -> " + command.target());
        }
        LocalDateTime now = now();
        RecoveryCheck recovery = null;
        if (automatic && command.target() == BuyerListingStatus.PUBLISHED) {
            // AUTO无已发布商业版本不能恢复：直接业务拒绝，不先校验后技术失败。见设计 §4.3。
            if (listing.getCurrentVersionId() == null) {
                throw exception(BUYER_CATALOG_NOT_READY);
            }
            recovery = requireAutomaticRecoveryReady(listing);
        }
        if (command.target() == BuyerListingStatus.PUBLISHED && listing.getCurrentVersionId() == null) {
            throw new IllegalStateException("Listing without a published version cannot be resumed");
        }

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
        // AUTO恢复在同一事务内同步写安全投影（设计§4.3）：投影行缺失为技术失败，CAS失败整体回滚。
        BuyerListingAvailabilityDO availability = recovery == null
                ? availabilityMapper.selectByListingId(listing.getId())
                : writeRecoveryProjection(listing, recovery, now);
        String changeType = command.target() == BuyerListingStatus.PUBLISHED ? "UPSERT" : "TOMBSTONE";
        BuyerCatalogChangeDO change = appendChange(listing, version, availability, changeType, now);
        // AUTO新路径只记录本地可拉取目录游标，不追加buyer-catalog-change-fanout；旧MANUAL事件行为保持。
        if (!automatic) {
            CatalogChangePayload payload = new CatalogChangePayload(change.getId(), listing.getListingNo(),
                    version == null ? null : version.getVersionNo(),
                    availability == null ? null : availability.getVersion(), changeType, now);
            outboxRepository.appendLocal("BUYER_LISTING", listing.getListingNo(), nextAggregateVersion,
                    "BUYER_CATALOG_CHANGED", CATALOG_TARGET, payload, command.traceId(),
                    command.correlationId(), command.causationId(), now);
        }
    }

    /** 恢复校验通过后的真实依据：当前买方定价版本身份（basis来源）与买家聚合资格（未来事实边界）。 */
    private record RecoveryCheck(Long pricingVersionId,
                                 CurrentSupplyEligibilityReader.BuyerEligibility eligibility) {
    }

    /**
     * 人工恢复AUTO的真实价格链与买家聚合资格核对：必须由持有LST当前锁的事务调用，通过后才CAS改状态。
     * 以锁行当前读核对买方定价身份（锁序LST→BuyerPricing，与刷新worker同向，杜绝校验与CAS之间的前移竞态）；
     * 已指向商业版本必须存在且归属本LST，缺失或跨归属为技术失败。不在持锁时委派定价重建；依赖前移同样失败关闭。
     * 见设计 §4.3「Task 3 实施接口与事务边界」。
     */
    private RecoveryCheck requireAutomaticRecoveryReady(BuyerListingDO listing) {
        if (listing.getBuyerProductId() == null) {
            throw new IllegalStateException("Automatic listing is missing its buyer product identity");
        }
        BuyerListingVersionDO pointed = versionMapper.selectById(listing.getCurrentVersionId());
        if (pointed == null || !Objects.equals(pointed.getListingId(), listing.getId())) {
            throw new IllegalStateException("Listing points to a missing or foreign commercial version");
        }
        BuyerSkuPricingDO identity = buyerPricingMapper.selectByBuyerProductIdForUpdate(listing.getBuyerProductId());
        BuyerSkuPricingVersionDO version = identity == null || identity.getCurrentVersionId() == null ? null
                : buyerVersionMapper.selectById(identity.getCurrentVersionId());
        PlatformSkuPricingDO platform = platformPricingMapper.selectById(listing.getPlatformSkuId());
        BuyerPricingReadiness.Result readiness = BuyerPricingReadiness.evaluate(identity, version,
                platform == null ? null : platform.getCurrentVersionId());
        if (readiness.state() == BuyerPricingReadiness.State.BROKEN) {
            throw new IllegalStateException("Buyer pricing current version is missing or mismatched");
        }
        if (readiness.state() != BuyerPricingReadiness.State.READY) {
            throw exception(BUYER_CATALOG_NOT_READY);
        }
        CurrentSupplyEligibilityReader.BuyerEligibility eligibility =
                eligibilityReader.readBuyer(listing.getBuyerProductId());
        if (eligibility.qualification() != SupplyQualification.CONFIRMED) {
            throw exception(BUYER_CATALOG_NOT_READY);
        }
        return new RecoveryCheck(version.getId(), eligibility);
    }

    /**
     * 人工恢复通过校验后在同一事务内同步写安全投影：状态AVAILABLE、原因清空、评估时间本次库时钟、
     * validUntil取本次买家聚合未来事实边界、basis摘要变化才递增basisVersion；投影行缺失为技术失败。
     * 与刷新worker共用{@link BuyerAvailabilityBasis}口径，basis不因评估路径漂移。见设计 §4.3。
     */
    private BuyerListingAvailabilityDO writeRecoveryProjection(BuyerListingDO listing, RecoveryCheck recovery,
                                                               LocalDateTime now) {
        BuyerListingAvailabilityDO projection = availabilityMapper.selectByListingIdForUpdate(listing.getId());
        if (projection == null) {
            throw new IllegalStateException("Automatic listing is missing its availability projection");
        }
        byte[] digest = BuyerAvailabilityBasis.digest(listing.getId(), recovery.pricingVersionId(),
                ListingAvailability.AVAILABLE, null, recovery.eligibility());
        boolean basisChanged = projection.getBasisDigest() == null
                || !Arrays.equals(projection.getBasisDigest(), digest);
        long basisVersion = (projection.getBasisVersion() == null ? 0L : projection.getBasisVersion())
                + (basisChanged ? 1 : 0);
        // 与刷新worker同口径：期限为未来事实边界与最大评估有效期的最小值，无边界也有巡检上限。见设计 §6.4。
        LocalDateTime maxEvaluationDeadline = now.plusSeconds(properties.effectiveMaxEvaluationAgeSeconds());
        LocalDateTime boundary = recovery.eligibility() == null ? null : recovery.eligibility().nextFactBoundary();
        LocalDateTime validUntil = boundary == null || !boundary.isBefore(maxEvaluationDeadline)
                ? maxEvaluationDeadline : boundary;
        // 显式set允许清空原因并按投影版本CAS；updateById会忽略null字段，不能用于清空。
        int updated = availabilityMapper.update(null, new LambdaUpdateWrapper<BuyerListingAvailabilityDO>()
                .eq(BuyerListingAvailabilityDO::getId, projection.getId())
                .eq(BuyerListingAvailabilityDO::getVersion, projection.getVersion())
                .set(BuyerListingAvailabilityDO::getAvailability, ListingAvailability.AVAILABLE.getCode())
                .set(BuyerListingAvailabilityDO::getReasonCode, "")
                .set(BuyerListingAvailabilityDO::getEvaluatedAt, now)
                .set(BuyerListingAvailabilityDO::getBasisVersion, basisVersion)
                .set(BuyerListingAvailabilityDO::getBasisDigest, digest)
                .set(BuyerListingAvailabilityDO::getBasisJson, BuyerAvailabilityBasis.json(listing.getId(),
                        recovery.pricingVersionId(), ListingAvailability.AVAILABLE, null,
                        recovery.eligibility(), objectMapper))
                .set(BuyerListingAvailabilityDO::getValidUntil, validUntil)
                .set(BuyerListingAvailabilityDO::getUpdatedAt, now)
                .set(BuyerListingAvailabilityDO::getVersion, projection.getVersion() + 1));
        if (updated != 1) {
            throw new IllegalStateException("Availability projection lost optimistic lock");
        }
        return availabilityMapper.selectByListingId(listing.getId());
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
