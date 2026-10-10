package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.domain.*;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 单 LST 自动商业发布与投影刷新；锁序固定 LST→BPD→BuyerPricing，供给通过独立代理读取。
 * MANUAL、人工暂停、终态不自动管理；版本、CAS 指针、投影及可拉取游标原子提交，不写 Outbox。
 * 设计见 {@code docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#4-单目录刷新与幂等发布}。
 */
@Service
@RequiredArgsConstructor
public class BuyerSelectionCatalogLifecycle {
    private static final long MAX_EVALUATION_SECONDS = 30L;
    private static final String CURRENCY = "CNY";
    private static final String UPSERT = "UPSERT";
    private final SupplyChainDatabaseClock clock;
    private final BuyerListingMapper listings;
    private final BuyerListingVersionMapper versions;
    private final BuyerListingAvailabilityMapper availability;
    private final BuyerCatalogChangeMapper changes;
    private final BuyerProductMapper products;
    private final BuyerSkuPricingMapper pricing;
    private final BuyerSkuPricingVersionMapper priceVersions;
    private final PlatformSkuMapper skus;
    private final CurrentSupplyEligibilityReader supply;
    private final ObjectMapper json;

    /**
     * 最终HTTP的AUTO来源守卫：原quote→PLT→BPD前缀后再次核BPD并追加BuyerPricing当前锁。
     * 商业损坏为系统失败，正常来源变化/非READY为可重试失效；不刷新、不首冻、不写目录。
     * 定价版本当前读防止已建立RR视图遮蔽新提交版本，锁随外层受理提交释放。
     * 见 docs/superpowers/specs/2026-10-09-catalog-business-minimum-design.md#91-2026-10-10-评审修复约束。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY)
    public void requireCurrentAutoAdmission(BuyerListingDO listing, BuyerListingVersionDO version) {
        if (!BuyerListingManagementMode.AUTO_SELECTION.name().equals(listing.getManagementMode())) return;
        validateCommercial(listing, version);
        BuyerProductDO product = products.selectByIdForUpdate(listing.getBuyerProductId());
        requireBinding(listing, product);
        BuyerSkuPricingDO identity = pricing.selectByBuyerProductIdForUpdate(product.getId());
        BuyerSkuPricingVersionDO source = source(identity, product, true);
        BuyerSkuPricingVersionDO frozenSource = source != null && Objects.equals(source.getId(), version.getSourceBuyerPricingVersionId())
                ? source : priceVersions.selectByIdForUpdate(version.getSourceBuyerPricingVersionId());
        if (frozenSource == null || identity == null || !Objects.equals(frozenSource.getPricingId(), identity.getId())
                || frozenSource.getVersionNo() == null || frozenSource.getVersionNo() < 1
                || !PricingStatus.READY.name().equals(frozenSource.getPricingStatus())
                || !CURRENCY.equals(frozenSource.getCurrency())
                || !Objects.equals(frozenSource.getDistributionPriceCents(), version.getBuyerSettlementUnitPrice())) {
            throw new IllegalStateException("Automatic commercial frozen source is missing or contradictory");
        }
        if (source == null || !PricingStatus.READY.name().equals(identity.getPricingStatus())
                || !PricingStatus.READY.name().equals(source.getPricingStatus())
                || !Objects.equals(source.getId(), version.getSourceBuyerPricingVersionId())) {
            throw new com.aifuli.module.supplychain.tradecore.catalog.pricing.ProductNotSaleableException(
                    "Automatic commercial source changed or is no longer READY");
        }
        if (!Objects.equals(source.getDistributionPriceCents(), version.getBuyerSettlementUnitPrice())) {
            throw new IllegalStateException("Automatic commercial amount contradicts pricing source");
        }
    }

    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public RefreshResult refreshListing(String listingNo, Long operatorId) {
        BuyerListingDO listing = listings.selectByListingNoForUpdate(listingNo);
        if (listing == null) {
            throw new IllegalArgumentException("Acceptance listing does not exist");
        }
        BuyerListingStatus state = BuyerListingStatus.fromCode(listing.getStatus());
        BuyerListingManagementMode mode = BuyerListingManagementMode.valueOf(listing.getManagementMode());
        if (mode == BuyerListingManagementMode.MANUAL) {
            return unmanaged(listing, CatalogRefreshReason.MANUAL);
        }
        if (state == BuyerListingStatus.SUSPENDED || state == BuyerListingStatus.RETIRED) {
            return unmanaged(listing, state == BuyerListingStatus.SUSPENDED
                    ? CatalogRefreshReason.SUSPENDED : CatalogRefreshReason.RETIRED);
        }
        BuyerProductDO product = products.selectByIdForUpdate(listing.getBuyerProductId());
        requireBinding(listing, product);
        BuyerSkuPricingDO identity = pricing.selectByBuyerProductIdForUpdate(product.getId());
        BuyerSkuPricingVersionDO source = source(identity, product);
        BuyerListingVersionDO current = current(listing);
        LocalDateTime now = clock.now();
        CurrentSupplyEligibility eligibility;
        if (!Objects.equals(product.getStatus(), BuyerProductStatus.ACTIVE.getCode())) {
            eligibility = new CurrentSupplyEligibility(ListingAvailability.UNAVAILABLE,
                    CatalogRefreshReason.BUYER_PRODUCT_REMOVED.name(), now, null);
        } else if (source == null || !PricingStatus.READY.name().equals(identity.getPricingStatus())
                || !PricingStatus.READY.name().equals(source.getPricingStatus())) {
            eligibility = new CurrentSupplyEligibility(ListingAvailability.UNKNOWN,
                    CatalogRefreshReason.DEFERRED_PRICE.name(), now, null);
        } else {
            eligibility = supply.evaluateBuyerSelection(listing.getBuyerId(), product.getId(), listing.getPlatformSkuId());
        }
        LocalDateTime evaluatedAt = eligibility.evaluatedAt();
        LocalDateTime validUntil = evaluatedAt.plusSeconds(MAX_EVALUATION_SECONDS);
        LocalDateTime nextDay = evaluatedAt.toLocalDate().plusDays(1).atStartOfDay();
        if (nextDay.isBefore(validUntil)) validUntil = nextDay;
        if (eligibility.nextBoundaryAt() != null && eligibility.nextBoundaryAt().isBefore(validUntil)) {
            validUntil = eligibility.nextBoundaryAt();
        }
        boolean commercialChanged = false;
        if (eligibility.confirmed()) {
            PlatformSkuDO sku = skus.selectById(listing.getPlatformSkuId());
            if (sku == null || sku.getSkuName() == null) {
                throw new IllegalStateException("Automatic commercial SKU source is missing");
            }
            // 只有同来源同内容才沿用已冻结有效期；新商业内容不能倒签旧版本的起点。
            LocalDateTime from = current == null ? evaluatedAt : current.getValidFrom();
            LocalDateTime to = current == null ? null : current.getValidTo();
            byte[] digest = commercialDigest(source, sku.getSkuName(), from, to);
            if (current == null || !Arrays.equals(current.getCommercialSnapshotDigest(), digest)) {
                from = evaluatedAt;
                to = null;
                digest = commercialDigest(source, sku.getSkuName(), from, to);
                Long auditOperator = operatorId == null ? source.getPublishedBy() : operatorId;
                if (auditOperator == null) {
                    throw new IllegalStateException("Automatic publication audit source publishedBy is missing");
                }
                BuyerListingVersionDO latest = versions.selectLatest(listing.getId());
                current = BuyerListingVersionDO.builder().listingId(listing.getId())
                        .versionNo(latest == null ? 1 : latest.getVersionNo() + 1)
                        .sourceBuyerPricingVersionId(source.getId()).commercialSnapshotDigest(digest)
                        .buyerSettlementUnitPrice(source.getDistributionPriceCents()).currency(CURRENCY)
                        .displayName(sku.getSkuName()).minQuantity(1L).maxQuantity(1L).validFrom(from).validTo(to)
                        .publishReason(CatalogRefreshReason.CONFIRMED.name()).publishedBy(auditOperator)
                        .publishedAt(evaluatedAt).createdAt(evaluatedAt).build();
                versions.insert(current);
                if (listings.update(null, new LambdaUpdateWrapper<BuyerListingDO>()
                        .eq(BuyerListingDO::getId, listing.getId()).eq(BuyerListingDO::getVersion, listing.getVersion())
                        .set(BuyerListingDO::getCurrentVersionId, current.getId())
                        .set(BuyerListingDO::getStatus, BuyerListingStatus.PUBLISHED.getCode())
                        .set(BuyerListingDO::getVersion, listing.getVersion() + 1)
                        .set(BuyerListingDO::getUpdatedBy, auditOperator).set(BuyerListingDO::getUpdatedAt, evaluatedAt)) != 1) {
                    throw new IllegalStateException("Automatic listing publication CAS conflict");
                }
                listing.setCurrentVersionId(current.getId());
                listing.setStatus(BuyerListingStatus.PUBLISHED.getCode());
                commercialChanged = true;
            }
        }
        BuyerListingAvailabilityDO projection = availability.selectByListingId(listing.getId());
        if (projection == null) throw new IllegalStateException("Automatic listing availability identity is missing");
        String basis = basisJson(source, eligibility);
        byte[] basisDigest = digest(basis);
        boolean basisChanged = !Arrays.equals(projection.getBasisDigest(), basisDigest)
                || !Objects.equals(projection.getAvailability(), eligibility.availability().getCode())
                || !Objects.equals(projection.getReasonCode(), eligibility.reason());
        long nextProjectionVersion = basisChanged ? projection.getVersion() + 1 : projection.getVersion();
        if (availability.update(null, new LambdaUpdateWrapper<BuyerListingAvailabilityDO>()
                .eq(BuyerListingAvailabilityDO::getId, projection.getId())
                .eq(BuyerListingAvailabilityDO::getVersion, projection.getVersion())
                .set(BuyerListingAvailabilityDO::getAvailability, eligibility.availability().getCode())
                .set(BuyerListingAvailabilityDO::getReasonCode, eligibility.reason())
                .set(BuyerListingAvailabilityDO::getBasisDigest, basisDigest)
                .set(BuyerListingAvailabilityDO::getBasisJson, basis)
                .set(BuyerListingAvailabilityDO::getBasisVersion, source == null ? 0L : source.getId())
                .set(BuyerListingAvailabilityDO::getEvaluatedAt, evaluatedAt)
                .set(BuyerListingAvailabilityDO::getValidUntil, validUntil)
                .set(BuyerListingAvailabilityDO::getVersion, nextProjectionVersion)
                .set(BuyerListingAvailabilityDO::getUpdatedAt, evaluatedAt)) != 1) {
            throw new IllegalStateException("Automatic availability CAS conflict");
        }
        if (commercialChanged || basisChanged) {
            changes.insert(BuyerCatalogChangeDO.builder().buyerId(listing.getBuyerId()).listingId(listing.getId())
                    .listingVersionId(current == null ? null : current.getId()).availabilityVersion(nextProjectionVersion)
                    .changeType(UPSERT).occurredAt(evaluatedAt).payloadDigest(basisDigest).build());
        }
        return new RefreshResult(listing.getListingNo(), BuyerListingStatus.fromCode(listing.getStatus()).name(),
                eligibility.availability().name(), eligibility.reason(), source == null ? null : source.getId(),
                evaluatedAt, validUntil);
    }

    private BuyerSkuPricingVersionDO source(BuyerSkuPricingDO identity, BuyerProductDO product) {
        return source(identity, product, false);
    }

    private BuyerSkuPricingVersionDO source(BuyerSkuPricingDO identity, BuyerProductDO product, boolean currentRead) {
        if (identity == null) return null;
        if (!Objects.equals(identity.getBuyerProductId(), product.getId())) {
            throw new IllegalStateException("Automatic pricing identity ownership is invalid");
        }
        PricingStatus.valueOf(identity.getPricingStatus());
        if (identity.getCurrentVersionId() == null) {
            if (PricingStatus.READY.name().equals(identity.getPricingStatus()))
                throw new IllegalStateException("READY pricing has no current version");
            return null;
        }
        BuyerSkuPricingVersionDO version = currentRead ? priceVersions.selectByIdForUpdate(identity.getCurrentVersionId())
                : priceVersions.selectById(identity.getCurrentVersionId());
        if (version == null || !Objects.equals(version.getPricingId(), identity.getId())
                || version.getVersionNo() == null || version.getVersionNo() < 1
                || !CURRENCY.equals(version.getCurrency())
                || version.getDistributionPriceCents() != null && version.getDistributionPriceCents() < 0
                || !Objects.equals(identity.getPricingStatus(), version.getPricingStatus())) {
            throw new IllegalStateException("Automatic pricing current source is missing or contradictory");
        }
        PricingStatus.valueOf(version.getPricingStatus());
        if (PricingStatus.READY.name().equals(version.getPricingStatus()) && version.getDistributionPriceCents() == null) {
            throw new IllegalStateException("READY pricing distribution amount is missing");
        }
        return version;
    }

    private static void requireBinding(BuyerListingDO listing, BuyerProductDO product) {
        if (product == null || !Objects.equals(product.getId(), listing.getBuyerProductId())
                || !Objects.equals(product.getBuyerId(), listing.getBuyerId())
                || !Objects.equals(product.getPlatformSkuId(), listing.getPlatformSkuId())
                || !Objects.equals(product.getBuyerProductCode(), listing.getBuyerSkuCode())) {
            throw new IllegalStateException("Automatic listing immutable binding is invalid");
        }
    }

    private BuyerListingVersionDO current(BuyerListingDO listing) {
        if (listing.getCurrentVersionId() == null) {
            if (BuyerListingStatus.fromCode(listing.getStatus()) == BuyerListingStatus.PUBLISHED) {
                throw new IllegalStateException("Published automatic listing has no current version");
            }
            return null;
        }
        BuyerListingVersionDO version = versions.selectById(listing.getCurrentVersionId());
        validateCommercial(listing, version);
        return version;
    }

    private void validateCommercial(BuyerListingDO listing, BuyerListingVersionDO version) {
        if (version == null || !Objects.equals(version.getListingId(), listing.getId())
                || !Objects.equals(version.getId(), listing.getCurrentVersionId())
                || version.getSourceBuyerPricingVersionId() == null || version.getCommercialSnapshotDigest() == null
                || version.getVersionNo() == null || version.getVersionNo() < 1
                || version.getBuyerSettlementUnitPrice() == null || version.getBuyerSettlementUnitPrice() < 0
                || !CURRENCY.equals(version.getCurrency()) || version.getDisplayName() == null
                || !Long.valueOf(1L).equals(version.getMinQuantity()) || !Long.valueOf(1L).equals(version.getMaxQuantity())
                || version.getValidFrom() == null
                || version.getValidTo() != null && !version.getValidTo().isAfter(version.getValidFrom())) {
            throw new IllegalStateException("Automatic listing commercial current source is missing or mismatched");
        }
        var frozenSource = BuyerSkuPricingVersionDO.builder().id(version.getSourceBuyerPricingVersionId())
                .distributionPriceCents(version.getBuyerSettlementUnitPrice()).build();
        if (!Arrays.equals(version.getCommercialSnapshotDigest(), commercialDigest(frozenSource,
                version.getDisplayName(), version.getValidFrom(), version.getValidTo()))) {
            throw new IllegalStateException("Automatic listing commercial digest contradicts frozen content");
        }
    }

    private byte[] commercialDigest(BuyerSkuPricingVersionDO source, String name, LocalDateTime from, LocalDateTime to) {
        Map<String, Object> value = new TreeMap<>();
        value.put("sourceId", source.getId()); value.put("price", source.getDistributionPriceCents());
        value.put("currency", CURRENCY); value.put("name", name); value.put("quantity", 1L);
        value.put("validFrom", from); value.put("validTo", to);
        return digest(serialize(value));
    }

    private String basisJson(BuyerSkuPricingVersionDO source, CurrentSupplyEligibility eligibility) {
        Map<String, Object> basis = new TreeMap<>();
        basis.put("sourceBuyerPricingVersionId", source == null ? null : source.getId());
        basis.put("reason", eligibility.reason()); basis.put("availability", eligibility.availability().name());
        if (eligibility.routingSnapshot() != null) {
            var route = eligibility.routingSnapshot();
            basis.put("policyVersionId", route.policyVersionId()); basis.put("ruleId", route.ruleId());
            if (route.decision().selected()) {
                var selected = route.decision().selectedCandidate();
                basis.put("offerVersionId", selected.offerVersionId());
                basis.put("connectorVersionId", selected.connectorVersionId());
                basis.put("inventorySourceId", selected.inventorySourceId());
            }
        }
        return serialize(basis);
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Catalog digest serialization failed", failure); }
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
    }

    private static RefreshResult unmanaged(BuyerListingDO listing, CatalogRefreshReason reason) {
        return new RefreshResult(listing.getListingNo(), BuyerListingStatus.fromCode(listing.getStatus()).name(),
                ListingAvailability.UNKNOWN.name(), reason.name(), null, null, null);
    }

    /** 同步刷新真实结果，不表示每个项目都获准下单。字段沿060 DDL语义，客户端不得回写。 */
    public record RefreshResult(String acceptanceListingNo, String publicationState, String availabilityStatus,
                                String reason, Long sourceBuyerPricingVersionId,
                                LocalDateTime lastEvaluatedAt, LocalDateTime nextEvaluateAt) { }
}
