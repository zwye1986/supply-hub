package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogChangeBatchRespDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogChangeRespDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogChangesReqDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogItemRespDTO;
import com.aifuli.module.supplychain.api.catalog.dto.BuyerCatalogPageReqDTO;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerResolver;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerCatalogChangeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerCatalogChangeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.read.BuyerCatalogReadMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.readmodel.BuyerCatalogReadRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BuyerCatalogQueryService {

    private final SupplyChainDatabaseClock databaseClock;

    private final BuyerResolver buyerResolver;
    private final BuyerCatalogReadMapper readMapper;
    private final BuyerCatalogChangeMapper changeMapper;

    /** 列表、总数与输出共用只读 RR 视图和一次库时钟，避免刷新穿插产生自相矛盾的分页。 */
    @Transactional(transactionManager = "supplyChainTransactionManager", readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public PageResult<BuyerCatalogItemRespDTO> pageCatalog(BuyerCatalogPageReqDTO request) {
        LocalDateTime now = databaseClock.now();
        BuyerResolver.BuyerSnapshot buyer = resolve(request.getSourceSystem(), request.getExternalTenantId(), now);
        boolean availableOnly = Boolean.TRUE.equals(request.getAvailableOnly());
        String saleablePricingStatus = PricingStatus.READY.name();
        long offset = (long) (request.getPageNo() - 1) * request.getPageSize();
        List<BuyerCatalogReadRow> rows = readMapper.selectPage(buyer.id(), request.getBuyerSkuCode(),
                request.getFulfillmentType(), availableOnly, saleablePricingStatus, now, offset, request.getPageSize());
        long total = readMapper.countPage(buyer.id(), request.getBuyerSkuCode(),
                request.getFulfillmentType(), availableOnly, saleablePricingStatus, now);
        return new PageResult<>(rows.stream().map(row -> toItem(row, now)).toList(), total);
    }

    @Transactional(transactionManager = "supplyChainTransactionManager", readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public BuyerCatalogChangeBatchRespDTO getCatalogChanges(BuyerCatalogChangesReqDTO request) {
        LocalDateTime now = databaseClock.now();
        BuyerResolver.BuyerSnapshot buyer = resolve(request.getSourceSystem(), request.getExternalTenantId(), now);
        List<BuyerCatalogChangeDO> selected = changeMapper.selectAfterCursor(
                buyer.id(), request.getCursor(), request.getLimit() + 1);
        boolean hasMore = selected.size() > request.getLimit();
        List<BuyerCatalogChangeDO> page = hasMore
                ? selected.subList(0, request.getLimit()) : selected;
        List<BuyerCatalogChangeRespDTO> changes = new ArrayList<>(page.size());
        for (BuyerCatalogChangeDO change : page) {
            BuyerCatalogReadRow row = readMapper.selectByListingId(change.getListingId());
            BuyerCatalogChangeRespDTO response = new BuyerCatalogChangeRespDTO();
            response.setCursor(change.getId());
            response.setChangeType(change.getChangeType());
            response.setBuyerSkuCode(row == null ? null : row.getBuyerSkuCode());
            response.setItem(row == null ? null : toItem(row, now));
            response.setOccurredAt(databaseClock.toInstant(change.getOccurredAt()).atOffset(ZoneOffset.UTC));
            changes.add(response);
        }
        BuyerCatalogChangeBatchRespDTO result = new BuyerCatalogChangeBatchRespDTO();
        result.setChanges(changes);
        result.setNextCursor(page.isEmpty() ? request.getCursor() : page.get(page.size() - 1).getId());
        result.setHasMore(hasMore);
        return result;
    }

    private BuyerResolver.BuyerSnapshot resolve(String sourceSystem, String externalTenantId, LocalDateTime now) {
        return buyerResolver.resolve(sourceSystem, externalTenantId, now);
    }

    /**
     * AUTO 与管理读使用同一真实来源／期限规则；非 READY 返回 UNKNOWN 而不是确定不可用。
     * MANUAL 与无管理模式历史行保持旧 RPC 定价覆盖，不读取 AUTO basis。
     * 设计见 {@code docs/superpowers/specs/2026-10-12-catalog-source-freshness-correction-design.md#43-rpc-筛选与返回一致}。
     */
    private static String resolveAvailabilityStatus(BuyerCatalogReadRow row, LocalDateTime now) {
        ListingAvailability stored = row.getAvailability() == null
                ? ListingAvailability.UNKNOWN : ListingAvailability.fromCode(row.getAvailability());
        if (BuyerListingManagementMode.AUTO_SELECTION.name().equals(row.getManagementMode())) {
            return BuyerCatalogAutoReadPolicy.decide(new BuyerCatalogAutoReadPolicy.Input(
                    row.getListingId(), BuyerListingStatus.fromCode(row.getListingStatus()), row.getPricingStatus(),
                    row.getBuyerPricingCurrentVersionId(), row.getBasisJson(), row.getEvaluatedAt(), row.getValidUntil(),
                    true, row.getSourceBuyerPricingVersionId(), row.getCommercialValidFrom(), row.getCommercialValidTo(),
                    stored, row.getReasonCode()), now).availability().name();
        }
        if (row.getPricingStatus() != null && !PricingStatus.READY.name().equals(row.getPricingStatus())) {
            return ListingAvailability.UNAVAILABLE.name();
        }
        return stored.name();
    }

    private BuyerCatalogItemRespDTO toItem(BuyerCatalogReadRow row, LocalDateTime now) {
        BuyerCatalogItemRespDTO response = new BuyerCatalogItemRespDTO();
        response.setBuyerSkuCode(row.getBuyerSkuCode());
        response.setListingVersion(row.getListingVersion().longValue());
        response.setPlatformSkuCode(row.getPlatformSkuCode());
        response.setPlatformSpuCode(row.getPlatformSpuCode());
        response.setDisplayName(row.getDisplayName());
        response.setBrandCode(row.getBrandCode());
        response.setBrandName(row.getBrandName());
        response.setCategoryCode(row.getCategoryCode());
        response.setCategoryName(row.getCategoryName());
        response.setFulfillmentType(row.getFulfillmentType());
        response.setBuyerSettlementUnitPrice(row.getBuyerSettlementUnitPrice());
        response.setCurrency(row.getCurrency());
        response.setListingStatus(BuyerListingStatus.fromCode(row.getListingStatus()).name());
        response.setAvailabilityStatus(resolveAvailabilityStatus(row, now));
        response.setAvailabilityVersion(row.getAvailabilityVersion() == null ? 0L : row.getAvailabilityVersion());
        response.setUpdatedAt(databaseClock.toInstant(row.getUpdatedAt()).atOffset(ZoneOffset.UTC));
        return response;
    }
}
