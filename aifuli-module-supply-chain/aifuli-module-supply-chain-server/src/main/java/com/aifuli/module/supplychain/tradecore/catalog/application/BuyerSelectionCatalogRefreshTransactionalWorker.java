package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerCatalogChangeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerCatalogChangeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerCatalogRefreshOutcome;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 单LST自动刷新独立短写事务worker：锁序LST→BPD→BuyerPricing→availability，不锁SKU/PLT/project/供给。
 * 只读供给资格经真实代理REQUIRES_NEW新视图；版本/current CAS/投影/本地游标同一事务，任一步失败全回滚。
 * MANUAL与RETIRED零写提前返回；价格依赖前移不发布并返回需重算身份由门面在事务外委派。
 * 设计见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#task-3-实施接口与事务边界。
 */
@Service
@RequiredArgsConstructor
public class BuyerSelectionCatalogRefreshTransactionalWorker {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String CHANGE_TYPE_UPSERT = "UPSERT";
    private static final String AUTO_PUBLISH_REASON = "AUTO_SELECTION_READY";
    private static final String REASON_BUYER_PRODUCT_REMOVED = "BUYER_PRODUCT_REMOVED";
    /** AUTO商业快照数量固定为1的业务不变量，版本字段与摘要同源引用，禁止散落字面量。 */
    private static final long AUTO_LISTING_QUANTITY = 1L;

    /** 内部结果：对外真实结果 + 需事务外重算的BPD身份（不进入对外契约）。 */
    public record Outcome(BuyerSelectionCatalogRefreshResult result, Long pricingRecalculationBuyerProductId) { }

    private final BuyerListingMapper listingMapper;
    private final BuyerListingVersionMapper versionMapper;
    private final BuyerListingAvailabilityMapper availabilityMapper;
    private final BuyerCatalogChangeMapper changeMapper;
    private final BuyerProductMapper productMapper;
    private final BuyerSkuPricingMapper buyerPricingMapper;
    private final BuyerSkuPricingVersionMapper buyerVersionMapper;
    private final PlatformSkuPricingMapper platformPricingMapper;
    private final PlatformSkuMapper skuMapper;
    private final PlatformSpuMapper spuMapper;
    private final CurrentSupplyEligibilityReader eligibilityReader;
    private final SupplyChainDatabaseClock databaseClock;
    private final ObjectMapper objectMapper;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public Outcome refresh(Long listingId, Long operatorId) {
        Objects.requireNonNull(listingId, "listingId");
        BuyerListingDO listing = listingMapper.selectByIdForUpdate(listingId);
        if (listing == null) {
            throw new IllegalArgumentException("Buyer listing does not exist: " + listingId);
        }
        LocalDateTime now = databaseClock.now();
        BuyerListingStatus status = BuyerListingStatus.fromCode(listing.getStatus());
        if (BuyerListingManagementMode.MANUAL.name().equals(listing.getManagementMode())) {
            return new Outcome(new BuyerSelectionCatalogRefreshResult(BuyerCatalogRefreshOutcome.SKIPPED_MANUAL,
                    status, currentAvailability(listing.getId()), null, now), null);
        }
        if (!BuyerListingManagementMode.AUTO_SELECTION.name().equals(listing.getManagementMode())) {
            throw new IllegalStateException("Buyer listing has an unknown management mode");
        }
        // RETIRED终态判断先于身份完整性校验：退役目录永不复活，身份字段缺损也按终态返回零写。
        if (status == BuyerListingStatus.RETIRED) {
            return new Outcome(new BuyerSelectionCatalogRefreshResult(BuyerCatalogRefreshOutcome.TERMINAL,
                    status, currentAvailability(listing.getId()), null, now), null);
        }
        requireAutomaticIdentity(listing);
        requireCurrentVersionOwned(listing);

        BuyerProductDO product = productMapper.selectByIdForUpdate(listing.getBuyerProductId());
        if (product == null || !Objects.equals(product.getBuyerId(), listing.getBuyerId())
                || !Objects.equals(product.getPlatformSkuId(), listing.getPlatformSkuId())) {
            throw new IllegalStateException("Automatic listing references a missing or foreign buyer product");
        }
        BuyerSkuPricingDO pricingIdentity =
                buyerPricingMapper.selectByBuyerProductIdForUpdate(listing.getBuyerProductId());
        BuyerSkuPricingVersionDO pricingVersion = pricingIdentity == null || pricingIdentity.getCurrentVersionId() == null
                ? null : buyerVersionMapper.selectById(pricingIdentity.getCurrentVersionId());
        PlatformSkuPricingDO platformPricing = platformPricingMapper.selectById(listing.getPlatformSkuId());
        BuyerPricingReadiness.Result price = BuyerPricingReadiness.evaluate(pricingIdentity, pricingVersion,
                platformPricing == null ? null : platformPricing.getCurrentVersionId());
        if (price.state() == BuyerPricingReadiness.State.BROKEN) {
            throw new IllegalStateException("Buyer pricing current version is missing or mismatched");
        }

        BuyerListingAvailabilityDO projection = availabilityMapper.selectByListingIdForUpdate(listing.getId());
        if (projection == null) {
            throw new IllegalStateException("Automatic listing is missing its availability projection");
        }
        boolean productActive = product.getStatus() != null
                && product.getStatus() == BuyerProductStatus.ACTIVE.getCode();
        PlatformSkuDO sku = skuMapper.selectById(listing.getPlatformSkuId());
        PlatformSpuDO spu = sku == null || sku.getSpuId() == null ? null : spuMapper.selectById(sku.getSpuId());
        boolean sellable = sku != null && sku.getStatus() != null
                && sku.getStatus() == CatalogEntityStatus.ACTIVE.getCode()
                && spu != null && spu.getStatus() != null
                && spu.getStatus() == CatalogEntityStatus.ACTIVE.getCode();
        CurrentSupplyEligibilityReader.BuyerEligibility eligibility = productActive
                ? eligibilityReader.readBuyer(listing.getBuyerProductId()) : null;

        Decision decision = decide(listing.getBuyerProductId(), productActive, sellable, price, eligibility, operatorId);
        boolean suspended = status == BuyerListingStatus.SUSPENDED;
        VersionWrite versionWrite = !suspended && decision.publish()
                ? publishOrReuseVersion(listing, price.version(), sku.getSkuName(), operatorId, now)
                : VersionWrite.unchanged(listing.getCurrentVersionId());
        ProjectionWrite projectionWrite = writeProjection(listing, projection, price, decision, eligibility, now);
        if (versionWrite.changed() || projectionWrite.changed()) {
            changeMapper.insert(BuyerCatalogChangeDO.builder()
                    .buyerId(listing.getBuyerId())
                    .changeType(CHANGE_TYPE_UPSERT)
                    .listingId(listing.getId())
                    .listingVersionId(versionWrite.currentVersionId())
                    .availabilityVersion(projectionWrite.newVersion())
                    .occurredAt(now)
                    .payloadDigest(payloadDigest(listing, projectionWrite, versionWrite))
                    .build());
        }
        boolean changed = versionWrite.changed() || projectionWrite.changed();
        BuyerCatalogRefreshOutcome outcome = suspended
                ? BuyerCatalogRefreshOutcome.PAUSED
                : decision.publish()
                        ? (changed ? BuyerCatalogRefreshOutcome.UPDATED : BuyerCatalogRefreshOutcome.UNCHANGED)
                        : BuyerCatalogRefreshOutcome.DEFERRED;
        BuyerListingStatus publicationState = !suspended && decision.publish()
                ? BuyerListingStatus.PUBLISHED : status;
        return new Outcome(new BuyerSelectionCatalogRefreshResult(outcome, publicationState,
                projectionWrite.availability(), decision.resultReason(), now),
                suspended ? null : decision.recalculationBuyerProductId());
    }

    private void requireAutomaticIdentity(BuyerListingDO listing) {
        if (listing.getBuyerId() == null || listing.getPlatformSkuId() == null || listing.getBuyerProductId() == null) {
            throw new IllegalStateException("Automatic listing is missing its identity links");
        }
    }

    /**
     * 已指向商业版本必须存在且归属本LST：缺失或跨归属为损坏引用技术失败零写，不得把损坏指针写进新游标。
     * 见设计 §4.3「Task 3 实施接口与事务边界」。
     */
    private void requireCurrentVersionOwned(BuyerListingDO listing) {
        if (listing.getCurrentVersionId() == null) {
            return;
        }
        BuyerListingVersionDO pointed = versionMapper.selectById(listing.getCurrentVersionId());
        if (pointed == null || !Objects.equals(pointed.getListingId(), listing.getId())) {
            throw new IllegalStateException("Listing points to a missing or foreign commercial version");
        }
    }

    private ListingAvailability currentAvailability(Long listingId) {
        BuyerListingAvailabilityDO projection = availabilityMapper.selectByListingId(listingId);
        return projection == null || projection.getAvailability() == null
                ? ListingAvailability.UNKNOWN : ListingAvailability.fromCode(projection.getAvailability());
    }

    /** 决定表：BPD移除→商品无效→缺价格/依赖前移→资格→系统操作人；发布=价格READY+商品有效+聚合CONFIRMED+操作人。 */
    private Decision decide(Long buyerProductId, boolean productActive, boolean sellable,
                            BuyerPricingReadiness.Result price,
                            CurrentSupplyEligibilityReader.BuyerEligibility eligibility, Long operatorId) {
        if (!productActive) {
            return Decision.deferred(ListingAvailability.UNAVAILABLE, REASON_BUYER_PRODUCT_REMOVED,
                    BuyerSelectionCatalogRefreshResult.Reason.BUYER_PRODUCT_REMOVED, null, null);
        }
        if (!sellable) {
            return Decision.deferred(ListingAvailability.UNAVAILABLE,
                    BuyerSelectionCatalogRefreshResult.Reason.PRODUCT_NOT_SELLABLE.name(),
                    BuyerSelectionCatalogRefreshResult.Reason.PRODUCT_NOT_SELLABLE, null, eligibility);
        }
        if (price.state() == BuyerPricingReadiness.State.STALE) {
            return Decision.deferred(ListingAvailability.UNAVAILABLE,
                    BuyerSelectionCatalogRefreshResult.Reason.PRICING_DEPENDENCY_STALE.name(),
                    BuyerSelectionCatalogRefreshResult.Reason.PRICING_DEPENDENCY_STALE, buyerProductId, eligibility);
        }
        if (price.state() != BuyerPricingReadiness.State.READY) {
            return Decision.deferred(ListingAvailability.UNAVAILABLE,
                    BuyerSelectionCatalogRefreshResult.Reason.PRICING_NOT_READY.name(),
                    BuyerSelectionCatalogRefreshResult.Reason.PRICING_NOT_READY, null, eligibility);
        }
        if (eligibility.qualification() == SupplyQualification.UNKNOWN) {
            return Decision.deferred(ListingAvailability.UNKNOWN,
                    orDefault(joinReasons(eligibility),
                            BuyerSelectionCatalogRefreshResult.Reason.QUALIFICATION_UNKNOWN.name()),
                    BuyerSelectionCatalogRefreshResult.Reason.QUALIFICATION_UNKNOWN, null, eligibility);
        }
        if (eligibility.qualification() != SupplyQualification.CONFIRMED) {
            return Decision.deferred(ListingAvailability.UNAVAILABLE,
                    orDefault(joinReasons(eligibility),
                            BuyerSelectionCatalogRefreshResult.Reason.QUALIFICATION_UNAVAILABLE.name()),
                    BuyerSelectionCatalogRefreshResult.Reason.QUALIFICATION_UNAVAILABLE, null, eligibility);
        }
        boolean operatorReady = operatorId != null && operatorId > 0;
        if (!operatorReady) {
            return Decision.deferred(ListingAvailability.AVAILABLE, null,
                    BuyerSelectionCatalogRefreshResult.Reason.SYSTEM_OPERATOR_NOT_CONFIGURED, null, eligibility);
        }
        return Decision.publishable(ListingAvailability.AVAILABLE, eligibility);
    }

    private static String joinReasons(CurrentSupplyEligibilityReader.BuyerEligibility eligibility) {
        List<String> reasons = eligibility == null ? List.of() : eligibility.reasonCodes();
        return reasons.isEmpty() ? null : String.join(",", reasons);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** 可用状态在数据库中存空串（reason_code非空），对外比较时归一为空。 */
    private static String normalizeReason(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** 商业快照幂等：同来源沿用已冻结业务有效期，同摘要复用不插入；current永远取本次当前来源。 */
    private VersionWrite publishOrReuseVersion(BuyerListingDO listing, BuyerSkuPricingVersionDO source,
                                               String displayName, Long operatorId, LocalDateTime now) {
        List<BuyerListingVersionDO> existing = versionMapper.selectByListingAndSource(listing.getId(), source.getId());
        LocalDateTime frozenValidFrom = existing.isEmpty() ? now : existing.getFirst().getValidFrom();
        byte[] digest = commercialDigest(listing.getId(), source, displayName, frozenValidFrom);
        BuyerListingVersionDO reused = existing.stream()
                .filter(version -> Arrays.equals(version.getCommercialSnapshotDigest(), digest))
                .findFirst().orElse(null);
        Long targetVersionId;
        if (reused == null) {
            BuyerListingVersionDO latest = versionMapper.selectLatest(listing.getId());
            BuyerListingVersionDO next = BuyerListingVersionDO.builder()
                    .listingId(listing.getId())
                    .versionNo(latest == null ? 1 : latest.getVersionNo() + 1)
                    .sourceBuyerPricingVersionId(source.getId())
                    .commercialSnapshotDigest(digest)
                    .buyerSettlementUnitPrice(source.getDistributionPriceCents())
                    .currency(source.getCurrency())
                    .displayName(displayName)
                    .minQuantity(AUTO_LISTING_QUANTITY)
                    .maxQuantity(AUTO_LISTING_QUANTITY)
                    .validFrom(frozenValidFrom)
                    .publishReason(AUTO_PUBLISH_REASON)
                    .publishedBy(operatorId)
                    .publishedAt(now)
                    .createdAt(now)
                    .build();
            versionMapper.insert(next);
            targetVersionId = next.getId();
        } else {
            targetVersionId = reused.getId();
        }
        boolean changed = !Objects.equals(listing.getCurrentVersionId(), targetVersionId)
                || listing.getStatus() != BuyerListingStatus.PUBLISHED.getCode();
        if (changed) {
            int updated = listingMapper.update(null, new LambdaUpdateWrapper<BuyerListingDO>()
                    .eq(BuyerListingDO::getId, listing.getId())
                    .eq(BuyerListingDO::getVersion, listing.getVersion())
                    .set(BuyerListingDO::getCurrentVersionId, targetVersionId)
                    .set(BuyerListingDO::getStatus, BuyerListingStatus.PUBLISHED.getCode())
                    .set(BuyerListingDO::getUpdatedBy, operatorId)
                    .set(BuyerListingDO::getUpdatedAt, now)
                    .set(BuyerListingDO::getVersion, listing.getVersion() + 1));
            if (updated != 1) {
                throw new IllegalStateException("Listing publication lost optimistic lock");
            }
        }
        return new VersionWrite(changed, targetVersionId);
    }

    /** 投影CAS：状态/原因/basis变化才产生新游标，重复评估只更新评估时间与未来边界。 */
    private ProjectionWrite writeProjection(BuyerListingDO listing, BuyerListingAvailabilityDO projection,
                                            BuyerPricingReadiness.Result price, Decision decision,
                                            CurrentSupplyEligibilityReader.BuyerEligibility eligibility,
                                            LocalDateTime now) {
        Long pricingVersionId = price.version() == null ? null : price.version().getId();
        byte[] digest = BuyerAvailabilityBasis.digest(listing.getId(), pricingVersionId,
                decision.availability(), decision.rowReason(), eligibility);
        boolean basisChanged = !Arrays.equals(projection.getBasisDigest(), digest);
        boolean stateChanged = projection.getAvailability() == null
                || projection.getAvailability() != decision.availability().getCode()
                || !Objects.equals(normalizeReason(projection.getReasonCode()), decision.rowReason());
        long basisVersion = projection.getBasisVersion() == null ? 0L : projection.getBasisVersion();
        if (basisChanged) {
            basisVersion++;
        }
        LocalDateTime validUntil = eligibility == null ? null : eligibility.nextFactBoundary();
        // 显式set允许写入null（清空原因/期限），并按投影版本CAS；updateById会忽略null字段，不能用于清空。
        int updated = availabilityMapper.update(null, new LambdaUpdateWrapper<BuyerListingAvailabilityDO>()
                .eq(BuyerListingAvailabilityDO::getId, projection.getId())
                .eq(BuyerListingAvailabilityDO::getVersion, projection.getVersion())
                .set(BuyerListingAvailabilityDO::getAvailability, decision.availability().getCode())
                .set(BuyerListingAvailabilityDO::getReasonCode,
                        decision.rowReason() == null ? "" : decision.rowReason())
                .set(BuyerListingAvailabilityDO::getEvaluatedAt, now)
                .set(BuyerListingAvailabilityDO::getBasisVersion, basisVersion)
                .set(BuyerListingAvailabilityDO::getBasisDigest, digest)
                .set(BuyerListingAvailabilityDO::getBasisJson,
                        BuyerAvailabilityBasis.json(listing.getId(), pricingVersionId,
                                decision.availability(), decision.rowReason(), eligibility, objectMapper))
                .set(BuyerListingAvailabilityDO::getValidUntil, validUntil)
                .set(BuyerListingAvailabilityDO::getUpdatedAt, now)
                .set(BuyerListingAvailabilityDO::getVersion, projection.getVersion() + 1));
        if (updated != 1) {
            throw new IllegalStateException("Availability projection lost optimistic lock");
        }
        long newVersion = projection.getVersion() == null ? 0L : projection.getVersion() + 1;
        return new ProjectionWrite(decision.availability(), newVersion, basisChanged || stateChanged);
    }

    private byte[] payloadDigest(BuyerListingDO listing, ProjectionWrite write, VersionWrite versionWrite) {
        return BuyerAvailabilityBasis.sha256(String.join("|", listing.getListingNo(), CHANGE_TYPE_UPSERT,
                versionWrite.currentVersionId() == null ? "" : String.valueOf(versionWrite.currentVersionId()),
                String.valueOf(write.newVersion()), String.valueOf(write.availability())));
    }

    private static byte[] commercialDigest(Long listingId, BuyerSkuPricingVersionDO source,
                                           String displayName, LocalDateTime validFrom) {
        return BuyerAvailabilityBasis.sha256(String.join("|", String.valueOf(listingId), String.valueOf(source.getId()),
                String.valueOf(source.getDistributionPriceCents()), source.getCurrency(), displayName,
                validFrom.toString(), "", String.valueOf(AUTO_LISTING_QUANTITY),
                String.valueOf(AUTO_LISTING_QUANTITY)));
    }

    /** 不可变内部决定；publish=false时只评估安全投影，不写商业版本与生命周期。 */
    private record Decision(boolean publish, ListingAvailability availability, String rowReason,
                            BuyerSelectionCatalogRefreshResult.Reason resultReason,
                            Long recalculationBuyerProductId,
                            CurrentSupplyEligibilityReader.BuyerEligibility eligibility) {

        static Decision deferred(ListingAvailability availability, String rowReason,
                                 BuyerSelectionCatalogRefreshResult.Reason resultReason,
                                 Long recalculationBuyerProductId,
                                 CurrentSupplyEligibilityReader.BuyerEligibility eligibility) {
            return new Decision(false, availability, rowReason, resultReason, recalculationBuyerProductId, eligibility);
        }

        static Decision publishable(ListingAvailability availability,
                                    CurrentSupplyEligibilityReader.BuyerEligibility eligibility) {
            return new Decision(true, availability, null, null, null, eligibility);
        }
    }

    private record VersionWrite(boolean changed, Long currentVersionId) {
        static VersionWrite unchanged(Long currentVersionId) {
            return new VersionWrite(false, currentVersionId);
        }
    }

    private record ProjectionWrite(ListingAvailability availability, long newVersion, boolean changed) { }
}
