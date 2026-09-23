package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingPriceSource.PriceBasis;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND;

/**
 * 项目商品列表：冻结价为 finalPriceCents，现价 L2 为 distPriceCents，L3 覆盖用 projectPriceCovered。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectListingQueryService {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final BuyerProjectMapper projectMapper;
    private final ProjectListingMapper listingMapper;
    private final ProjectListingVersionMapper listingVersionMapper;
    private final BuyerProductMapper productMapper;
    private final PlatformSkuMapper skuMapper;
    private final ProjectListingPriceSource priceSource;

    /**
     * 分页未移除项目商品。按更新时间倒序。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
     */
    public PageResult<ProjectListingListItem> page(String projectCode, Integer pageNo, Integer pageSize) {
        log.info("分页查询项目商品 projectCode={} pageNo={} pageSize={}", projectCode, pageNo, pageSize);
        BuyerProjectDO project = projectMapper.selectByProjectCode(projectCode);
        if (project == null) {
            throw exception(PROJECT_NOT_FOUND);
        }
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(pageNo == null ? DEFAULT_PAGE_NO : pageNo);
        pageParam.setPageSize(pageSize == null ? DEFAULT_PAGE_SIZE : pageSize);
        PageResult<ProjectListingDO> page = listingMapper.selectPage(pageParam, new LambdaQueryWrapper<ProjectListingDO>()
                .eq(ProjectListingDO::getProjectId, project.getId())
                .eq(ProjectListingDO::getStatus, ProjectListingStatus.AVAILABLE.getCode())
                .orderByDesc(ProjectListingDO::getUpdatedAt));
        List<ProjectListingListItem> items = new ArrayList<>();
        for (ProjectListingDO listing : page.getList()) {
            items.add(toListItem(listing));
        }
        return new PageResult<>(items, page.getTotal());
    }

    private ProjectListingListItem toListItem(ProjectListingDO listing) {
        BuyerProductDO product = listing.getBuyerProductId() == null
                ? null : productMapper.selectById(listing.getBuyerProductId());
        PlatformSkuDO sku = product == null || product.getPlatformSkuId() == null
                ? null : skuMapper.selectById(product.getPlatformSkuId());
        PriceBasis basis = product == null ? null : priceSource.resolve(product);
        Long suggestPriceCents = sku == null ? null : priceSource.legacyPlatformPrice(sku.getId());
        Long platformBasePriceCents = basis == null ? null : basis.platformBaseCents();
        Long distPriceCents = basis == null ? null : basis.distributionCents();
        ProjectListingVersionDO frozen = listing.getCurrentVersionId() == null
                ? null : listingVersionMapper.selectById(listing.getCurrentVersionId());
        Long finalPriceCents = frozen == null ? null : frozen.getBuyerSettlementUnitPrice();
        boolean covered = frozen != null && PriceSource.L3.name().equals(frozen.getSettlementPriceLevel());
        return new ProjectListingListItem(
                listing.getListingCode(),
                sku == null ? null : sku.getSkuCode(),
                sku == null ? null : sku.getSkuName(),
                suggestPriceCents,
                sku == null ? null : sku.getSuggestedSalePrice(),
                platformBasePriceCents,
                distPriceCents,
                covered ? finalPriceCents : null,
                finalPriceCents,
                frozen == null ? null : frozen.getSettlementPriceLevel(),
                covered);
    }

    /**
     * 项目商品列表行。金额单位 CNY 分。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
     */
    public record ProjectListingListItem(
            @Schema(description = "项目商品稳定业务编码") String listingCode,
            @Schema(description = "平台SKU编码") String platformSkuCode,
            @Schema(description = "SKU名称") String skuName,
            @Schema(description = "建议结算价，取当前L1，单位CNY分") Long suggestPriceCents,
            @Schema(description = "平台SKU销售建议价，单位CNY分") Long suggestedSalePriceCents,
            @Schema(description = "项目旧调价模式的B端平台基价，单位CNY分") Long platformBasePriceCents,
            @Schema(description = "当前有效分销价，优先通用版本，单位CNY分") Long distPriceCents,
            @Schema(description = "当前版本为L3时的冻结单价，单位CNY分") Long projectPriceCents,
            @Schema(description = "当前冻结版本单价，单位CNY分") Long finalPriceCents,
            @Schema(description = "冻结版本结算层级：L1 / L2 / L3") String priceSource,
            @Schema(description = "当前版本 settlement_price_level 是否 L3") boolean projectPriceCovered) {
    }
}
