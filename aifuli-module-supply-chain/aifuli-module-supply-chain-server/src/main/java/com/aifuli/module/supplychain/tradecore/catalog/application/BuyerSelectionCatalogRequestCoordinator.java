package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.routing.domain.RoutingInput;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.Map;
import java.util.Objects;

/**
 * 首冻前准备：仅需执行的有效新尝试在首冻前调用——AUTO 经刷新门面刷新目录（ORDER_PREPARE），
 * MANUAL 零写只核真实商业发布与有效性，不改共享投影；目录未发布／暂停／退役或供给明确不可用不 proceed，
 * 由 Facade 保存真实可重试拒绝且不创建新报价。缺价（PRICING_NOT_READY/PRICING_DEPENDENCY_STALE）不在此拒绝，
 * 沿原 STANDARD_PRICING_QUOTE_MISSING 与 PROCESSING 契约；刷新技术失败继续上抛，不伪装业务拒绝。
 * 设计见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#72-下单顺序。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BuyerSelectionCatalogRequestCoordinator {

    /** 明确供给不可用才在首冻前拒绝；缺价原因放行由原首冻契约处理。 */
    private static final Set<String> UNAVAILABLE_REJECT_REASONS = Set.of(
            BuyerSelectionCatalogRefreshResult.Reason.QUALIFICATION_UNAVAILABLE.name(),
            BuyerSelectionCatalogRefreshResult.Reason.BUYER_PRODUCT_REMOVED.name(),
            BuyerSelectionCatalogRefreshResult.Reason.PRODUCT_NOT_SELLABLE.name());

    private final BuyerSelectionCatalogRefreshService refreshService;
    private final BuyerListingMapper listingMapper;
    private final BuyerListingVersionMapper versionMapper;
    private final PlatformSkuMapper skuMapper;
    private final SupplyChainDatabaseClock databaseClock;
    private final CurrentSupplyEligibilityReader eligibilityReader;

    /** 首冻前准备结果：proceed=false 时必须保存可重试拒绝且不首冻；listingVersionId 供拒绝回执引用。 */
    public record Preparation(boolean proceed, Long listingVersionId,
                              com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode rejectionCode) {

        static Preparation proceed(Long listingVersionId) {
            return new Preparation(true, listingVersionId, null);
        }

        static Preparation reject(Long listingVersionId,
                                  com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode code) {
            return new Preparation(false, listingVersionId, code);
        }
    }

    public Preparation prepare(BuyerAcceptanceCatalogResolver.CatalogIdentity identity, Long projectId, long quantity) {
        if (identity == null || !identity.available()) {
            // 目录不可解析时沿原首冻/受理准入契约，不在此伪造目录结论。
            return Preparation.proceed(null);
        }
        BuyerListingDO listing = listingMapper.selectById(identity.listingId());
        if (listing == null) {
            return Preparation.proceed(null);
        }
        Long versionId = listing.getCurrentVersionId();
        if (BuyerListingManagementMode.MANUAL.name().equals(identity.managementMode())) {
            // MANUAL零共享写：商业版本真实有效后再检查本项目；绝不以买家聚合授权。见设计 §7.2。
            boolean published = listing.getStatus() == BuyerListingStatus.PUBLISHED.getCode()
                    && versionId != null;
            if (!published) {
                return Preparation.reject(versionId,
                        com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode.LISTING_VERSION_EXPIRED);
            }
            var version = versionMapper.selectById(versionId);
            if (version == null || !Objects.equals(version.getListingId(), listing.getId())) {
                throw new IllegalStateException("Manual catalog references a missing or foreign commercial version");
            }
            var now = databaseClock.now();
            if (version.getValidFrom().isAfter(now)
                    || (version.getValidTo() != null && !version.getValidTo().isAfter(now))) {
                return Preparation.reject(versionId,
                        com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode.LISTING_VERSION_EXPIRED);
            }
            return checkProject(identity, projectId, quantity, versionId);
        }
        BuyerSelectionCatalogRefreshResult result = refreshService.refresh(identity.listingId(),
                BuyerSelectionCatalogRefreshService.Trigger.ORDER_PREPARE, null);
        if (result.publicationState() != BuyerListingStatus.PUBLISHED) {
            log.info("[orderPrepare][目录未就绪] listingNo={}, publicationState={}, reason={}",
                    identity.listingNo(), result.publicationState(), result.reasonCode());
            return Preparation.reject(versionId,
                    com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode.LISTING_VERSION_EXPIRED);
        }
        if (result.availabilityStatus() == ListingAvailability.UNAVAILABLE
                && result.reasonCode() != null
                && UNAVAILABLE_REJECT_REASONS.contains(result.reasonCode().name())) {
            log.info("[orderPrepare][供给明确不可用] listingNo={}, reason={}",
                    identity.listingNo(), result.reasonCode());
            return Preparation.reject(versionId,
                    com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode.SKU_NOT_SALEABLE);
        }
        return checkProject(identity, projectId, quantity, listingMapper.selectById(listing.getId()).getCurrentVersionId());
    }

    /** 首冻授权取本项目新视图；UNKNOWN/UNAVAILABLE 均不能新建永久报价，技术故障继续上抛。见设计 §3.1/§7.2。 */
    private Preparation checkProject(BuyerAcceptanceCatalogResolver.CatalogIdentity identity,
                                     Long projectId, long quantity, Long versionId) {
        Objects.requireNonNull(projectId, "trusted projectId");
        var sku = skuMapper.selectById(identity.platformSkuId());
        if (sku == null) {
            return Preparation.reject(versionId,
                    com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode.SKU_NOT_SALEABLE);
        }
        var eligibility = eligibilityReader.readProject(new RoutingInput(identity.buyerId(), projectId,
                identity.platformSkuId(), sku.getFulfillmentType(), quantity, databaseClock.now(), Map.of()));
        return eligibility.confirmed() ? Preparation.proceed(versionId)
                : Preparation.reject(versionId,
                        com.aifuli.module.supplychain.border.fulfillment.domain.AcceptanceRejectionCode.NO_ROUTABLE_SUPPLIER);
    }
}
