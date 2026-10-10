package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.util.monitor.TracerUtils;
import com.aifuli.module.supplychain.infrastructure.configuration.BuyerSelectionCatalogProperties;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerCatalogRefreshOutcome;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_CATALOG_REFRESH_FAILED;

/**
 * 管理保存提交后编排：无活动事务入口调用原选品／定价服务（各自独立事务确认提交），随后定位受影响
 * AUTO目录并逐条经刷新门面刷新（门面自身断言无活动事务）。保存失败原错误契约直接上抛不刷新；
 * 部分刷新失败降级为 FAILED 条目由后台扫描重试，不重跑保存 worker、不把已提交选品包装成整体失败。
 * 设计见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#5-管理保存成功与刷新失败契约。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BuyerSelectionCatalogAdminCoordinator {

    private final BuyerProductCommandService productCommandService;
    private final ProjectListingCommandService projectListingCommandService;
    private final GenericBatchPricingService pricingService;
    private final BuyerProductMapper buyerProductMapper;
    private final ProjectListingMapper projectListingMapper;
    private final BuyerListingMapper listingMapper;
    private final BuyerSelectionCatalogRefreshService refreshService;
    private final BuyerSelectionCatalogProperties properties;

    public BuyerSelectionCatalogSaveRespVO saveBuyerProducts(String buyerCode,
                                                             List<BuyerProductCommandService.ProductLine> lines,
                                                              Long operatorId) {
        requireNoTransaction();
        List<String> savedCodes = productCommandService.addProducts(buyerCode, lines, operatorId);
        return buildResponse(savedCodes, operatorId, this::listingForBuyerProductCode);
    }

    public BuyerSelectionCatalogSaveRespVO saveBuyerPricing(String buyerCode, List<String> productCodes,
                                                            GenericBatchPricingService.ProjectOverrides overrides,
                                                             Long operatorId) {
        requireNoTransaction();
        pricingService.saveBuyer(buyerCode, productCodes, overrides, operatorId);
        return buildResponse(productCodes, operatorId, this::listingForBuyerProductCode);
    }

    public BuyerSelectionCatalogSaveRespVO saveProjectProducts(String projectCode,
                                                               List<ProjectListingCommandService.ListingLine> lines,
                                                                Long operatorId) {
        requireNoTransaction();
        List<String> savedCodes = projectListingCommandService.addProducts(projectCode, lines, operatorId);
        return buildResponse(savedCodes, operatorId, this::listingForProjectListingCode);
    }

    public BuyerSelectionCatalogSaveRespVO saveProjectPricing(String projectCode, List<String> listingCodes,
                                                              GenericBatchPricingService.ProjectOverrides overrides,
                                                               Long operatorId) {
        requireNoTransaction();
        pricingService.saveProject(projectCode, listingCodes, overrides, operatorId);
        return buildResponse(listingCodes, operatorId, this::listingForProjectListingCode);
    }

    /** 保存代理必须自行提交；拒绝外层事务，不能将尚可回滚的保存结果宣称 COMMITTED。见设计 §3.1。 */
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("Catalog admin save requires no active transaction");
        }
    }

    private BuyerListingDO listingForBuyerProductCode(String buyerProductCode) {
        BuyerProductDO product = buyerProductMapper.selectByCode(buyerProductCode);
        return product == null ? null : listingMapper.selectByBuyerProductId(product.getId());
    }

    private BuyerListingDO listingForProjectListingCode(String listingCode) {
        ProjectListingDO listing = projectListingMapper.selectByCode(listingCode);
        return listing == null || listing.getBuyerProductId() == null
                ? null : listingMapper.selectByBuyerProductId(listing.getBuyerProductId());
    }

    /** 保存已提交后逐目录刷新：同LST去重；定位失败与刷新技术失败均为FAILED条目，不补默认状态。 */
    private BuyerSelectionCatalogSaveRespVO buildResponse(List<String> savedCodes, Long operatorId,
                                                          Function<String, BuyerListingDO> locate) {
        BuyerSelectionCatalogSaveRespVO response = new BuyerSelectionCatalogSaveRespVO();
        response.setSaveState("COMMITTED");
        // 真实已提交操作身份按去重集合返回，与逐目录刷新同一口径。
        response.setSavedCodes(List.copyOf(new LinkedHashSet<>(savedCodes)));
        response.setAutomaticRecoveryEnabled(properties.isSweepEnabled());
        List<BuyerSelectionCatalogSaveRespVO.CatalogRefreshResultVO> results = new ArrayList<>();
        Set<Long> refreshed = new LinkedHashSet<>();
        for (String savedCode : savedCodes) {
            BuyerListingDO listing = null;
            try {
                listing = locate.apply(savedCode);
            } catch (RuntimeException failure) {
                log.warn("[catalogSave][目录定位失败] savedCode={}, traceId={}", savedCode, traceId(), failure);
            }
            if (listing == null || !refreshed.add(listing.getId())) {
                if (listing == null) {
                    results.add(failedResult(savedCode, null));
                }
                continue;
            }
            results.add(refresh(listing, savedCode, operatorId));
        }
        response.setCatalogResults(results);
        return response;
    }

    private BuyerSelectionCatalogSaveRespVO.CatalogRefreshResultVO refresh(BuyerListingDO listing,
                                                                           String savedCode, Long operatorId) {
        var item = new BuyerSelectionCatalogSaveRespVO.CatalogRefreshResultVO();
        item.setSavedCode(savedCode);
        item.setListingNo(listing.getListingNo());
        item.setTraceId(traceId());
        try {
            BuyerSelectionCatalogRefreshResult result = refreshService.refresh(listing.getId(),
                    BuyerSelectionCatalogRefreshService.Trigger.ADMIN_SAVE, operatorId);
            item.setRefreshOutcome(result.refreshOutcome().name());
            item.setPublicationState(result.publicationState().name());
            item.setAvailabilityStatus(result.availabilityStatus().name());
            item.setReasonCode(result.reasonCode() == null ? null : result.reasonCode().name());
            item.setEvaluatedAt(result.evaluatedAt());
        } catch (RuntimeException failure) {
            // 保存已提交，刷新降级为FAILED条目并记录一次系统因果；后续后台扫描重试，不重跑保存worker。
            log.warn("[catalogSave][目录刷新失败已降级] savedCode={}, listingNo={}, traceId={}",
                    savedCode, listing.getListingNo(), item.getTraceId(), failure);
            item.setRefreshOutcome(BuyerCatalogRefreshOutcome.FAILED.name());
            item.setReasonCode(String.valueOf(BUYER_CATALOG_REFRESH_FAILED.getCode()));
        }
        return item;
    }

    private BuyerSelectionCatalogSaveRespVO.CatalogRefreshResultVO failedResult(String savedCode, String listingNo) {
        var item = new BuyerSelectionCatalogSaveRespVO.CatalogRefreshResultVO();
        item.setSavedCode(savedCode);
        item.setListingNo(listingNo);
        item.setTraceId(traceId());
        item.setRefreshOutcome(BuyerCatalogRefreshOutcome.FAILED.name());
        item.setReasonCode(String.valueOf(BUYER_CATALOG_REFRESH_FAILED.getCode()));
        return item;
    }

    private static String traceId() {
        String traceId = TracerUtils.getTraceId();
        return traceId == null || traceId.isBlank() ? "admin-" + UUID.randomUUID() : traceId;
    }
}
