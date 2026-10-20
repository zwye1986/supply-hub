package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.pricing.SuggestedSalePriceResolver;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.SuggestedSalePriceResolver.Resolution;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Resource;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.framework.mybatis.core.util.MyBatisUtils;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.SettlementPriceResolver.ResolvedPrice;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingConfig;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PriceMode;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FormulaRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.ServiceFeeRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.HideRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.aifuli.module.supplychain.tradecore.profile.domain.L2AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.L3AdjustMode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.time.LocalDateTime;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SETTLEMENT_PRICE_MISSING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND;

/**
 * 分销商品管理端列表：L1 / 有效 L2 / 单项目 L3 展示，金额一律走 {@link SettlementPriceResolver}。
 * 列表展示 SPU 三码，不再 walk 类目树。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}、
 * {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#42-平台-spu}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerProductQueryService {

    @Resource
    private com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock databaseClock;

    @Resource
    private SuggestedSalePriceResolver suggestedSalePriceResolver;

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final String PRICING_RULE_EQUAL_L1 = "等于L1";
    private static final String PRICING_RULE_EQUAL_MARKET_PRICE = "等于市场价";
    private static final String PRICING_RULE_CUSTOM = "自定义公式";
    private static final String PRICING_RULE_INHERIT_L1 = "继承L1";
    private static final String PRICING_RULE_INHERIT_PLATFORM = "继承商品默认";

    private final BuyerMapper buyerMapper;
    private final BuyerProductMapper productMapper;
    private final PlatformSkuMapper skuMapper;
    private final PlatformSpuMapper spuMapper;
    private final ProductCategoryCoverageService categoryCoverageService;
    private final PlatformSettlementPriceMapper l1Mapper;
    private final PlatformSettlementPriceVersionMapper l1VersionMapper;
    private final BuyerProductL2OverrideMapper overrideMapper;
    private final BuyerProductL2OverrideVersionMapper overrideVersionMapper;
    private final BuyerSkuPricingMapper buyerPricingMapper;
    private final BuyerSkuPricingVersionMapper buyerPricingVersionMapper;
    private final PlatformSkuPricingMapper platformPricingMapper;
    private final PlatformSkuPricingVersionMapper platformPricingVersionMapper;
    private final ProjectListingMapper listingMapper;
    private final ProjectListingVersionMapper listingVersionMapper;
    private final BuyerProjectMapper projectMapper;
    private final SettlementPriceResolver settlementPriceResolver;
    private final ObjectMapper objectMapper;
    private final BuyerAcceptanceCatalogReadService acceptanceCatalog;

    /**
     * 分页查询未移除分销商品。按更新时间倒序。
     * {@code productType} 可多选：重复键或逗号拼接，命中任一类型。省略、空白或拆分后无有效编码时不筛选；无命中返回空页。
     * {@code projectCode} 只排除当前项目未移除的 listing，必须在分页前排除以保证 total 和后续页正确。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
     */
    public PageResult<BuyerProductListItem> page(String buyerCode, List<String> productType,
                                                  Integer pageNo, Integer pageSize) {
        return page(buyerCode, productType, null, null, null, pageNo, pageSize);
    }

    /**
     * 项目选品时先校验项目归属，再用项目内有效目录项的分销商品主键排除候选；
     * 不查询同一分销商的其他项目，也不排除已移除的目录项。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
     */
    public PageResult<BuyerProductListItem> page(String buyerCode, List<String> productType,
                                                  String projectCode, Integer pageNo, Integer pageSize) {
        return page(buyerCode, productType, projectCode, null, null, pageNo, pageSize);
    }

    /**
     * 项目新增商品弹窗的名称/编号与主类目（含后代）条件，连同类型和项目排除条件在数据库分页前求交集。
     * 无新增条件时继续使用既有分销商品查询路径，保持其他调用方的请求口径。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
     */
    public PageResult<BuyerProductListItem> page(String buyerCode, List<String> productType,
                                                  String projectCode, String keyword, String categoryCode,
                                                  Integer pageNo, Integer pageSize) {
        List<String> productTypes = normalizeProductTypes(productType);
        String searchKeyword = trimToNull(keyword);
        String searchCategoryCode = trimToNull(categoryCode);
        log.info("分页查询分销商品 buyerCode={} projectCode={} productTypes={} keyword={} categoryCode={} pageNo={} pageSize={}",
                buyerCode, projectCode, productTypes, searchKeyword, searchCategoryCode, pageNo, pageSize);
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode);
        if (buyer == null) {
            throw exception(BUYER_PROFILE_NOT_FOUND);
        }
        Long projectId = null;
        if (projectCode != null && !projectCode.isBlank()) {
            BuyerProjectDO project = projectMapper.selectByProjectCode(projectCode);
            if (project == null || !Objects.equals(project.getBuyerId(), buyer.getId())) {
                throw exception(PROJECT_NOT_FOUND);
            }
            projectId = project.getId();
        }
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(pageNo == null ? DEFAULT_PAGE_NO : pageNo);
        pageParam.setPageSize(pageSize == null ? DEFAULT_PAGE_SIZE : pageSize);
        if (searchKeyword != null || searchCategoryCode != null) {
            return pageForProjectSelection(buyer.getId(), projectId, productTypes,
                    searchKeyword, searchCategoryCode, pageParam);
        }
        LambdaQueryWrapper<BuyerProductDO> wrapper = new LambdaQueryWrapper<BuyerProductDO>()
                .eq(BuyerProductDO::getBuyerId, buyer.getId())
                .eq(BuyerProductDO::getStatus, BuyerProductStatus.ACTIVE.getCode())
                .orderByDesc(BuyerProductDO::getUpdatedAt);
        if (projectId != null) {
            Long selectedProjectId = projectId;
            List<Long> listedProductIds = listingMapper.selectList(new LambdaQueryWrapper<ProjectListingDO>()
                            .eq(ProjectListingDO::getProjectId, selectedProjectId)
                            .eq(ProjectListingDO::getStatus, ProjectListingStatus.AVAILABLE.getCode()))
                    .stream().map(ProjectListingDO::getBuyerProductId).filter(Objects::nonNull).distinct().toList();
            if (!listedProductIds.isEmpty()) {
                wrapper.notIn(BuyerProductDO::getId, listedProductIds);
            }
        }
        if (!productTypes.isEmpty()) {
            Set<Long> skuIds = matchingSkuIds(buyer.getId(), productTypes);
            if (skuIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
            wrapper.in(BuyerProductDO::getPlatformSkuId, skuIds);
        }
        PageResult<BuyerProductDO> page = productMapper.selectPage(pageParam, wrapper);
        return toListPage(page);
    }

    private PageResult<BuyerProductListItem> pageForProjectSelection(Long buyerId, Long projectId,
            List<String> productTypes, String keyword, String categoryCode, PageParam pageParam) {
        List<Long> categoryIds = categoryCode == null ? null
                : categoryCoverageService.loadResolver().descendantIdsOf(categoryCode);
        if (categoryIds != null && categoryIds.isEmpty()) {
            return new PageResult<>(List.of(), 0L);
        }
        String escapedKeyword = keyword == null ? null : keyword.replace("!", "!!")
                .replace("%", "!%").replace("_", "!_");
        Short activeStatus = BuyerProductStatus.ACTIVE.getCode();
        Short availableStatus = ProjectListingStatus.AVAILABLE.getCode();
        PageResult<BuyerProductDO> page;
        if (PageParam.PAGE_SIZE_NONE.equals(pageParam.getPageSize())) {
            List<BuyerProductDO> all = productMapper.selectAllForProjectSelection(buyerId, activeStatus,
                    productTypes, escapedKeyword, categoryIds, projectId, availableStatus);
            page = new PageResult<>(all, (long) all.size());
        } else {
            IPage<BuyerProductDO> filtered = productMapper.selectPageForProjectSelection(
                    MyBatisUtils.buildPage(pageParam), buyerId, activeStatus, productTypes,
                    escapedKeyword, categoryIds, projectId, availableStatus);
            page = new PageResult<>(filtered.getRecords(), filtered.getTotal());
        }
        return toListPage(page);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private PageResult<BuyerProductListItem> toListPage(PageResult<BuyerProductDO> page) {
        var skus = new java.util.HashMap<Long, PlatformSkuDO>();
        page.getList().stream().map(BuyerProductDO::getPlatformSkuId).filter(Objects::nonNull).distinct()
                .forEach(id -> { var sku = skuMapper.selectById(id); if (sku != null) skus.put(id, sku); });
        var dynamic = skus.values().stream().anyMatch(SuggestedSalePriceResolver::isDynamic);
        var prices = resolveSuggestedPrices(skus.values(), dynamic ? databaseClock.now() : null);
        List<BuyerProductListItem> items = new ArrayList<>();
        for (BuyerProductDO product : page.getList()) {
            items.add(toListItem(product, skus.get(product.getPlatformSkuId()), prices.get(product.getPlatformSkuId())));
        }
        return new PageResult<>(items, page.getTotal());
    }

    /**
     * 只在当前分销商未移除分销商品关联的平台 SKU 中，按 SPU {@code productType} 精确匹配任一入参。
     * 先收窄再分页，避免类型条件落在内存页上导致 total 失真。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
     */
    private Set<Long> matchingSkuIds(Long buyerId, List<String> productTypes) {
        List<BuyerProductDO> products = productMapper.selectList(new LambdaQueryWrapper<BuyerProductDO>()
                .eq(BuyerProductDO::getBuyerId, buyerId)
                .eq(BuyerProductDO::getStatus, BuyerProductStatus.ACTIVE.getCode()));
        Set<Long> skuIds = new HashSet<>();
        for (BuyerProductDO product : products) {
            if (product.getPlatformSkuId() != null) {
                skuIds.add(product.getPlatformSkuId());
            }
        }
        if (skuIds.isEmpty()) {
            return Set.of();
        }
        List<PlatformSkuDO> skus = skuMapper.selectList(new LambdaQueryWrapper<PlatformSkuDO>()
                .in(PlatformSkuDO::getId, skuIds));
        Set<Long> spuIds = new HashSet<>();
        for (PlatformSkuDO sku : skus) {
            if (sku.getSpuId() != null) {
                spuIds.add(sku.getSpuId());
            }
        }
        if (spuIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> matchedSpuIds = new HashSet<>();
        for (PlatformSpuDO spu : spuMapper.selectList(new LambdaQueryWrapper<PlatformSpuDO>()
                .in(PlatformSpuDO::getId, spuIds)
                .in(PlatformSpuDO::getProductType, productTypes))) {
            matchedSpuIds.add(spu.getId());
        }
        Set<Long> matchedSkuIds = new HashSet<>();
        for (PlatformSkuDO sku : skus) {
            if (sku.getSpuId() != null && matchedSpuIds.contains(sku.getSpuId())) {
                matchedSkuIds.add(sku.getId());
            }
        }
        return matchedSkuIds;
    }

    /**
     * 兼容单个编码、重复查询参数和逗号拼接，trim 后去重；空白项丢弃。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
     */
    private static List<String> normalizeProductTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            }
            for (String part : item.split(",")) {
                String code = part.trim();
                if (!code.isEmpty()) {
                    codes.add(code);
                }
            }
        }
        return List.copyOf(codes);
    }

    private BuyerProductListItem toListItem(BuyerProductDO product, PlatformSkuDO sku, Resolution suggestedPrice) {
        PlatformSpuDO spu = sku == null || sku.getSpuId() == null ? null : spuMapper.selectById(sku.getSpuId());
        Long l1Cents = sku == null ? null : loadL1Cents(sku.getId());
        BuyerProductL2OverrideVersionDO overrideVersion = loadOverrideVersion(product.getId());
        Long overrideCents = overrideVersion == null ? null : overrideVersion.getResolvedUnitPriceCents();
        BuyerPricingSnapshot buyerPricing = loadBuyerPricingSnapshot(product.getId());
        BuyerSkuPricingVersionDO buyerPricingVersion = buyerPricing.version();
        Long platformBasePriceCents = loadPlatformBasePriceCents(buyerPricingVersion, l1Cents);
        ProjectOverrides buyerPricingOverrides = loadBuyerPricingOverrides(buyerPricingVersion);
        Long legacyDistPriceCents = overrideCents != null ? overrideCents : l1Cents;
        boolean genericPriceComplete = buyerPricingVersion != null
                && platformBasePriceCents != null && buyerPricingVersion.getDistributionPriceCents() != null;
        Long distPriceCents = buyerPricingVersion == null
                ? legacyDistPriceCents : genericPriceComplete ? buyerPricingVersion.getDistributionPriceCents() : null;
        ProjectPriceView projectView = projectPriceView(product.getId());
        Long l3ForResolve = projectView.l3ForResolve();
        boolean genericDistributionInherited = buyerPricingVersion != null
                && (buyerPricingOverrides == null || buyerPricingOverrides.distributionInherited());
        Long l2ForResolve = buyerPricingVersion == null ? overrideCents
                : genericDistributionInherited ? null : distPriceCents;
        Long l1ForResolve = buyerPricingVersion == null ? l1Cents
                : genericDistributionInherited ? distPriceCents : null;
        ResolvedPrice resolved = resolveQuietly(l3ForResolve, l2ForResolve, l1ForResolve);
        TaxMetadata tax = loadEffectiveTax(buyerPricingVersion, sku == null ? null : sku.getId());
        var catalog = acceptanceCatalog.read(product);
        return new BuyerProductListItem(
                product.getBuyerProductCode(),
                sku == null ? null : sku.getSkuCode(),
                sku == null ? null : sku.getSkuName(),
                spu == null ? null : spu.getSpuCode(),
                spu == null ? null : spu.getSpuName(),
                spu == null ? null : spu.getSellableCategory(),
                spu == null ? null : spu.getProductType(),
                spu == null ? null : spu.getProductClassification(),
                sku == null ? null : sku.getMarketPrice(),
                l1Cents,
                suggestedPrice == null ? null : suggestedPrice.amountCents(),
                platformBasePriceCents,
                pricingRule(buyerPricingVersion, buyerPricingOverrides, overrideVersion),
                tax == null ? null : tax.taxIncluded(),
                tax == null ? null : tax.taxRateCode(),
                tax == null ? null : tax.taxRateLabel(),
                distPriceCents,
                projectView.projectPriceCents(),
                resolved == null ? null : resolved.unitPriceCents(),
                resolved == null ? null : resolved.source().name(),
                projectView.projectPriceCovered(),
                projectView.projectNames(),
                product.getDisplayThresholdPercent(),
                catalog.canonicalBuyerSkuCode(), catalog.acceptanceListingNo(), catalog.managementMode(),
                catalog.publicationState(), catalog.availabilityStatus(), catalog.catalogRefreshReason(),
                catalog.sourceBuyerPricingVersionId(), catalog.lastEvaluatedAt(), catalog.nextEvaluateAt(),
                buyerPricing.currentVersionId(), buyerPricing.pricingStatus(),
                buyerPricingOverrides == null ? null : BuyerPricingConfigView.from(buyerPricingOverrides), suggestedPrice);
    }

    /**
     * 列表税务必须与实际通用定价链同源：优先分销商有效版本，历史关联尚无下游版本时回退平台默认版本。
     * 税务仅用于展示，不在查询链路计算税额。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
     */
    private TaxMetadata loadEffectiveTax(BuyerSkuPricingVersionDO buyerPricingVersion, Long platformSkuId) {
        if (buyerPricingVersion != null) {
            if (buyerPricingVersion.getTaxMetadataJson() != null
                    && !buyerPricingVersion.getTaxMetadataJson().isBlank()) {
                return fromJson(buyerPricingVersion.getTaxMetadataJson(), TaxMetadata.class);
            }
            if (buyerPricingVersion.getEffectiveConfigJson() != null
                    && !buyerPricingVersion.getEffectiveConfigJson().isBlank()) {
                return fromJson(buyerPricingVersion.getEffectiveConfigJson(), PricingConfig.class).taxMetadata();
            }
        }
        if (platformSkuId == null) {
            return null;
        }
        PlatformSkuPricingDO platformPricing = platformPricingMapper.selectById(platformSkuId);
        if (platformPricing == null || platformPricing.getCurrentVersionId() == null) {
            return null;
        }
        PlatformSkuPricingVersionDO version = platformPricingVersionMapper.selectById(
                platformPricing.getCurrentVersionId());
        if (version == null || version.getEffectiveConfigJson() == null
                || version.getEffectiveConfigJson().isBlank()) {
            return null;
        }
        return fromJson(version.getEffectiveConfigJson(), PricingConfig.class).taxMetadata();
    }

    private <T> T fromJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("pricing config deserialization failed", ex);
        }
    }

    private ResolvedPrice resolveQuietly(Long l3Cents, Long overrideCents, Long l1Cents) {
        try {
            return settlementPriceResolver.resolve(l3Cents, overrideCents, l1Cents);
        } catch (ServiceException ex) {
            if (Objects.equals(ex.getCode(), PLATFORM_SETTLEMENT_PRICE_MISSING.getCode())) {
                return null;
            }
            throw ex;
        }
    }

    /**
     * 通用定价版本是现行事实来源；只有历史行尚无通用版本时才解释旧 L2 覆盖。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#6-管理端接口}。
     */
    private String pricingRule(BuyerSkuPricingVersionDO buyerPricingVersion,
                               ProjectOverrides buyerPricingOverrides,
                               BuyerProductL2OverrideVersionDO overrideVersion) {
        if (buyerPricingVersion != null) {
            if (buyerPricingOverrides == null || buyerPricingOverrides.distributionInherited()) {
                return PRICING_RULE_INHERIT_PLATFORM;
            }
            if (buyerPricingOverrides.distributionRule() != null
                    && buyerPricingOverrides.distributionRule().mode() == PriceMode.DIRECT_MARKET_PRICE) {
                return PRICING_RULE_EQUAL_MARKET_PRICE;
            }
            return PRICING_RULE_CUSTOM;
        }
        if (overrideVersion != null && L2AdjustMode.EQUAL_L1.name().equals(overrideVersion.getAdjustMode())) {
            return PRICING_RULE_EQUAL_L1;
        }
        if (overrideVersion != null && L2AdjustMode.CUSTOM.name().equals(overrideVersion.getAdjustMode())) {
            return PRICING_RULE_CUSTOM;
        }
        return PRICING_RULE_INHERIT_L1;
    }

    /**
     * 当前指针存在时校验身份和版本归属，禁止损坏引用降级为未定价；状态与指针只读一次身份。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#63-分销商品原始配置回显与无损编辑2026-10-14-修复}。
     */
    private BuyerPricingSnapshot loadBuyerPricingSnapshot(Long buyerProductId) {
        BuyerSkuPricingDO identity = buyerPricingMapper.selectByBuyerProductId(buyerProductId);
        if (identity == null || identity.getCurrentVersionId() == null) {
            return new BuyerPricingSnapshot(null, PricingStatus.UNPRICED.name(), null);
        }
        if (!Objects.equals(identity.getBuyerProductId(), buyerProductId)) {
            throw new IllegalStateException("buyer pricing identity ownership is mismatched");
        }
        BuyerSkuPricingVersionDO version = buyerPricingVersionMapper.selectById(identity.getCurrentVersionId());
        if (version == null || !Objects.equals(version.getId(), identity.getCurrentVersionId())
                || !Objects.equals(version.getPricingId(), identity.getId())) {
            throw new IllegalStateException("buyer pricing current version is missing or mismatched: "
                    + identity.getCurrentVersionId());
        }
        return new BuyerPricingSnapshot(identity.getCurrentVersionId(), identity.getPricingStatus(), version);
    }

    /**
     * 同一次身份读取固定当前指针与处理状态；重算中的身份不能误报旧版本 READY。
     * 版本对象保持不可变读取，原始配置仍来自该指针指定的版本，不改写版本状态。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#63-分销商品原始配置回显与无损编辑2026-10-14-修复}。
     */
    private record BuyerPricingSnapshot(Long currentVersionId, String pricingStatus, BuyerSkuPricingVersionDO version) {
    }

    /**
     * 项目旧 L3 模式的 B 端平台基价必须与分销商通用版本引用的平台版本一致。
     * 历史行仍回显旧 L1；通用版本引用缺失时保留空值，不用旧价掩盖错误。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
     */
    private Long loadPlatformBasePriceCents(BuyerSkuPricingVersionDO buyerVersion, Long legacyL1Cents) {
        if (buyerVersion == null) {
            return legacyL1Cents;
        }
        if (buyerVersion.getPlatformPricingVersionId() == null) {
            return null;
        }
        PlatformSkuPricingVersionDO platformVersion = platformPricingVersionMapper.selectById(
                buyerVersion.getPlatformPricingVersionId());
        return platformVersion == null ? null : platformVersion.getDistributionPriceCents();
    }

    /**
     * 编辑回显只投影当前版本原始 override_json，不能用有效配置或旧整数隐藏列补全。
     * 五个继承标记必须明确；覆盖组必须结构完整，隐藏组的双 null 是合法覆盖。
     * 非 READY 版本仍可读取；损坏配置必须失败，避免编辑保存把损坏事实改写为全继承。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#63-分销商品原始配置回显与无损编辑2026-10-14-修复}。
     */
    private ProjectOverrides loadBuyerPricingOverrides(BuyerSkuPricingVersionDO version) {
        if (version == null) {
            return null;
        }
        if (version.getOverrideJson() == null || version.getOverrideJson().isBlank()) {
            throw new IllegalStateException("buyer pricing original config is missing");
        }
        try {
            JsonNode config = objectMapper.readTree(version.getOverrideJson());
            if (config == null || !config.isObject()) {
                throw new IllegalStateException("buyer pricing original config must be an object");
            }
            requireOriginalGroup(config, "distributionInherited", "distributionRule");
            requireOriginalGroup(config, "salesInherited", "salesRule");
            requireOriginalGroup(config, "serviceFeeInherited", "serviceFeeRule");
            requireOriginalGroup(config, "hideInherited", "hideRule");
            requireOriginalGroup(config, "taxInherited", "taxMetadata");
            // 从原始文本解析 BigDecimal，不能经浮点 JsonNode 重建原始公式的数值。
            return objectMapper.readValue(version.getOverrideJson(), ProjectOverrides.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("buyer pricing original config is invalid", ex);
        }
    }

    private static void requireOriginalGroup(JsonNode config, String inheritedField, String ruleField) {
        JsonNode inherited = config.get(inheritedField);
        if (inherited == null || !inherited.isBoolean()) {
            throw new IllegalStateException("buyer pricing inherited flag is missing or invalid: " + inheritedField);
        }
        JsonNode rule = config.get(ruleField);
        if (rule != null && !rule.isNull()) {
            requireOriginalRuleTypes(rule, ruleField);
        }
        if (inherited.booleanValue()) {
            return;
        }
        if (rule == null || !rule.isObject()) {
            throw new IllegalStateException("buyer pricing override group is missing: " + ruleField);
        }
        switch (ruleField) {
            case "distributionRule", "salesRule" -> {
                requireOriginalFields(rule, "mode");
                if (PriceMode.CUSTOM.name().equals(rule.get("mode").asText())) {
                    requireOriginalFields(rule, "base", "percentage", "operator", "operand");
                }
            }
            case "serviceFeeRule" -> {
                requireOriginalFields(rule, "percentage", "operator", "operand", "hideCopy");
                if (!rule.get("hideCopy").isBoolean()) {
                    throw new IllegalStateException("buyer pricing service fee hideCopy is invalid");
                }
                if (!rule.get("hideCopy").booleanValue()) {
                    requireOriginalFields(rule, "copy");
                }
            }
            case "hideRule" -> { /* 双 null 覆盖合法；缺省阈值按 null 原样投影。 */ }
            case "taxMetadata" -> {
                requireOriginalFields(rule, "taxIncluded", "taxRateCode");
                if (!rule.get("taxIncluded").isBoolean() || rule.get("taxRateCode").asText().isBlank()) {
                    throw new IllegalStateException("buyer pricing tax metadata is incomplete");
                }
            }
            default -> throw new IllegalStateException("unknown buyer pricing override group");
        }
    }

    /** 原始规则字段禁止 JSON 字符串/数值/布尔之间的宽松转换，防止读取时改变保存事实。 */
    private static void requireOriginalRuleTypes(JsonNode rule, String ruleField) {
        if (!rule.isObject()) {
            throw new IllegalStateException("buyer pricing original rule must be an object: " + ruleField);
        }
        // ServiceFeeRule.hideCopy 为 primitive boolean；缺省/null 会伪造 false，继承组也必须保留明确原始值。
        if ("serviceFeeRule".equals(ruleField)) {
            JsonNode hideCopy = rule.get("hideCopy");
            if (hideCopy == null || !hideCopy.isBoolean()) {
                throw new IllegalStateException("buyer pricing service fee hideCopy is missing or invalid");
            }
        }
        for (String name : List.of("percentage", "operand", "distributionThresholdPercent", "salesThresholdPercent")) {
            JsonNode value = rule.get(name);
            if (value != null && !value.isNull() && !value.isNumber()) {
                throw new IllegalStateException("buyer pricing numeric field is invalid: " + name);
            }
        }
        for (String name : List.of("hideCopy", "taxIncluded")) {
            JsonNode value = rule.get(name);
            if (value != null && !value.isNull() && !value.isBoolean()) {
                throw new IllegalStateException("buyer pricing boolean field is invalid: " + name);
            }
        }
        for (String name : List.of("mode", "base", "operator", "copy", "taxRateCode", "taxRateLabel")) {
            JsonNode value = rule.get(name);
            if (value != null && !value.isNull() && !value.isTextual()) {
                throw new IllegalStateException("buyer pricing text field is invalid: " + name);
            }
        }
    }

    private static void requireOriginalFields(JsonNode rule, String... names) {
        for (String name : names) {
            if (!rule.has(name) || rule.get(name).isNull()) {
                throw new IllegalStateException("buyer pricing override field is missing: " + name);
            }
        }
    }

    /**
     * 恰好一个未移除 listing 时给出其当前版本单价；多个 L3 则单价留空并标 covered。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
     */
    private ProjectPriceView projectPriceView(Long buyerProductId) {
        List<ProjectListingDO> live = listingMapper.selectList(new LambdaQueryWrapper<ProjectListingDO>()
                .eq(ProjectListingDO::getBuyerProductId, buyerProductId)
                .eq(ProjectListingDO::getStatus, ProjectListingStatus.AVAILABLE.getCode()));
        List<CoveredListing> covered = new ArrayList<>();
        List<CoveredListing> allLive = new ArrayList<>();
        for (ProjectListingDO listing : live) {
            if (listing.getCurrentVersionId() == null) {
                continue;
            }
            ProjectListingVersionDO version = listingVersionMapper.selectById(listing.getCurrentVersionId());
            if (version == null) {
                continue;
            }
            BuyerProjectDO project = listing.getProjectId() == null ? null : projectMapper.selectById(listing.getProjectId());
            String projectName = project == null ? listing.getListingCode() : project.getProjectName();
            CoveredListing item = new CoveredListing(projectName, version.getBuyerSettlementUnitPrice(), isL3Override(version));
            allLive.add(item);
            if (item.hasL3()) {
                covered.add(item);
            }
        }
        if (allLive.size() == 1) {
            CoveredListing only = allLive.getFirst();
            return new ProjectPriceView(only.unitPriceCents(), only.hasL3() ? only.unitPriceCents() : null,
                    only.hasL3(), List.of(only.projectName()));
        }
        if (covered.size() > 1) {
            return new ProjectPriceView(null, null, true, covered.stream().map(CoveredListing::projectName).toList());
        }
        if (covered.size() == 1) {
            CoveredListing onlyL3 = covered.getFirst();
            return new ProjectPriceView(null, onlyL3.unitPriceCents(), true, List.of(onlyL3.projectName()));
        }
        return new ProjectPriceView(null, null, false, List.of());
    }

    private static boolean isL3Override(ProjectListingVersionDO version) {
        String mode = version.getL3AdjustMode();
        return mode != null && !mode.isBlank() && !L3AdjustMode.FOLLOW_L2.name().equals(mode);
    }

    private BuyerProductL2OverrideVersionDO loadOverrideVersion(Long buyerProductId) {
        BuyerProductL2OverrideDO identity = overrideMapper.selectByBuyerProductId(buyerProductId);
        if (identity == null || identity.getCurrentVersionId() == null) {
            return null;
        }
        return overrideVersionMapper.selectById(identity.getCurrentVersionId());
    }

    private Long loadL1Cents(Long skuId) {
        PlatformSettlementPriceDO identity = l1Mapper.selectById(skuId);
        if (identity == null || identity.getCurrentVersionId() == null) {
            return null;
        }
        PlatformSettlementPriceVersionDO version = l1VersionMapper.selectById(identity.getCurrentVersionId());
        return version == null ? null : version.getUnitPriceCents();
    }

    private record CoveredListing(String projectName, Long unitPriceCents, boolean hasL3) {
    }

    private record ProjectPriceView(Long projectPriceCents, Long l3ForResolve, boolean projectPriceCovered,
                                    List<String> projectNames) {
    }

    /**
     * 分销商品列表行。金额单位 CNY 分。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
     */
    public record BuyerProductListItem(
            @Schema(description = "分销商品稳定业务编码") String buyerProductCode,
            @Schema(description = "平台SKU编码") String platformSkuCode,
            @Schema(description = "SKU名称") String skuName,
            @Schema(description = "SPU编码") String spuCode,
            @Schema(description = "SPU名称") String spuName,
            @Schema(description = "可售品类封闭枚举名") String sellableCategory,
            @Schema(description = "动态商品类型编码") String productType,
            @Schema(description = "商品分类封闭枚举名") String productClassification,
            @Schema(description = "平台SKU市场划线价或吊牌价，单位CNY分") Long marketPriceCents,
            @Schema(description = "建议结算价，取当前L1，单位CNY分") Long suggestPriceCents,
            @Schema(description = "当前有效销售建议价，单位CNY分；供应价规则待计算或失败时为空", nullable = true) Long suggestedSalePriceCents,
            @Schema(description = "项目旧调价模式的B端平台基价，单位CNY分") Long platformBasePriceCents,
            @Schema(description = "定价规则文案；通用定价为继承商品默认 / 等于市场价 / 自定义公式，历史链兼容继承L1 / 等于L1") String pricingRule,
            @Schema(description = "有效通用定价是否含税；仅用于展示") Boolean taxIncluded,
            @Schema(description = "有效通用定价税率字典编码；仅用于展示") String taxRateCode,
            @Schema(description = "有效通用定价税率展示文本；仅用于展示") String taxRateDisplay,
            @Schema(description = "当前分销商SKU通用定价的有效分销价，单位CNY分；历史行无通用版本时回退旧L2/L1") Long distPriceCents,
            @Schema(description = "恰有一个未移除项目listing时的当前版本单价，单位CNY分") Long projectPriceCents,
            @Schema(description = "按L3>L2>L1解析的有效B端结算价，单位CNY分") Long finalPriceCents,
            @Schema(description = "价格来源层级：L1 / L2 / L3") String priceSource,
            @Schema(description = "是否被多个项目L3覆盖") boolean projectPriceCovered,
            @Schema(description = "覆盖该SKU的项目名称") List<String> projectNames,
            @Schema(description = "利润空间低于该百分比时C端隐藏，本批不消费") Integer displayThresholdPercent,
            @Schema(description = "分销商品稳定业务编码；项目下单 canonical 编码为 BPD") String canonicalBuyerSkuCode,
            @Schema(description = "采购商上架记录永久业务编号") String acceptanceListingNo,
            @Schema(description = "受理目录管理来源：MANUAL手工管理，AUTO_SELECTION页面选品自动管理") String managementMode,
            @Schema(description = "采购商商品生命周期：DRAFT/PUBLISHED/SUSPENDED/RETIRED") String publicationState,
            @Schema(description = "采购商商品供给可用性投影：AVAILABLE/UNAVAILABLE/UNKNOWN；不是项目实时授权") String availabilityStatus,
            @Schema(description = "供给可用性原因编码；过期或来源失配显示待重新评估原因") String catalogRefreshReason,
            @Schema(description = "自动商业快照来源的分销通用定价版本主键，逻辑引用sc_buyer_sku_pricing_version.id；手工版本为空") Long sourceBuyerPricingVersionId,
            @Schema(description = "供给可用性投影评估时间，Asia/Shanghai") LocalDateTime lastEvaluatedAt,
            @Schema(description = "供给可用性投影下次必须重建的时间，Asia/Shanghai；历史未评估为空") LocalDateTime nextEvaluateAt,
            @JsonInclude(JsonInclude.Include.ALWAYS) @Schema(description = "当前通用定价版本主键，空表示未定价；逻辑引用sc_buyer_sku_pricing_version.id") Long currentPricingVersionId,
            @Schema(description = "当前定价状态：UNPRICED未定价，READY可售，RECALCULATING重算中，DEPENDENCY_MISSING依赖缺失，CALCULATION_FAILED计算失败，MARGIN_HIDDEN利润隐藏；取身份处理状态，无当前指针时为UNPRICED") String pricingStatus,
            @JsonInclude(JsonInclude.Include.ALWAYS) @Schema(description = "当前版本保存的原始五组覆盖配置；无当前指针时为空") BuyerPricingConfigView buyerPricingConfig,
            @JsonIgnore @Schema(hidden = true) Resolution suggestedPriceResolution) {

        @JsonProperty(value = "suggestedSalePriceStatus", access = JsonProperty.Access.READ_ONLY)
        @Schema(description = "当前销售建议价状态：READY可用、DEPENDENCY_MISSING待计算、CALCULATION_FAILED计算失败")
        public String suggestedSalePriceStatus() {
            return suggestedPriceResolution == null ? null : suggestedPriceResolution.status().name();
        }

        @JsonProperty(value = "suggestedSalePriceUnavailableReason", access = JsonProperty.Access.READ_ONLY)
        @Schema(description = "当前销售建议价不可用原因编码；READY时为空")
        public String suggestedSalePriceUnavailableReason() {
            return suggestedPriceResolution == null ? null : suggestedPriceResolution.unavailableReason();
        }

        @JsonProperty(value = "lastValidSuggestedSalePrice", access = JsonProperty.Access.READ_ONLY)
        @Schema(description = "最近一次成功计算的销售建议价，仅供历史参考，单位CNY分")
        public Long lastValidSuggestedSalePrice() {
            return suggestedPriceResolution == null ? null : suggestedPriceResolution.lastValidPriceCents();
        }

        @JsonProperty(value = "lastValidSuggestedSalePriceCalculatedAt", access = JsonProperty.Access.READ_ONLY)
        @Schema(description = "最近一次成功计算销售建议价的数据库时间，毫秒时间戳，仅供历史参考", type = "integer", format = "int64")
        public LocalDateTime lastValidSuggestedSalePriceCalculatedAt() {
            return suggestedPriceResolution == null ? null : suggestedPriceResolution.lastValidCalculatedAt();
        }

    }
    /**
     * 分销商当前版本的原始五组覆盖视图，独立于持久化对象；规则和继承标记逐字段保留。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#63-分销商品原始配置回显与无损编辑2026-10-14-修复}。
     */
    @Schema(name = "BuyerPricingConfigView", description = "分销商当前版本保存的五组配置。每组 Inherited=true 表示继承商品默认配置、计算时忽略本组规则；false 表示使用本分销商覆盖规则。继承时规则对象通常为 null，但已保存的非空规则仍原样回显。此对象不是合并后的有效配置或旧 L3 冻结价。")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BuyerPricingConfigView(
            @Schema(description = "分销价是否继承商品默认配置；true 继承，false 使用 distributionRule") boolean distributionInherited,
            @Schema(description = "分销商分销价覆盖公式；distributionInherited=true 时通常为 null，若保存了规则仍原样回显但不参与计算")
            BuyerFormulaRuleView distributionRule,
            @Schema(description = "销售价是否继承商品默认配置；true 继承，false 使用 salesRule") boolean salesInherited,
            @Schema(description = "分销商销售价覆盖公式；salesInherited=true 时通常为 null，若保存了规则仍原样回显但不参与计算")
            BuyerFormulaRuleView salesRule,
            @Schema(description = "服务费是否继承商品默认配置；true 继承，false 使用 serviceFeeRule") boolean serviceFeeInherited,
            @Schema(description = "分销商服务费覆盖公式；serviceFeeInherited=true 时通常为 null，若保存了规则仍原样回显但不参与计算")
            BuyerServiceFeeRuleView serviceFeeRule,
            @Schema(description = "利润隐藏阈值是否继承商品默认配置；true 继承，false 使用 hideRule") boolean hideInherited,
            @Schema(description = "分销商利润隐藏阈值；hideInherited=true 时通常为 null，若保存了规则仍原样回显但不参与计算")
            BuyerHideRuleView hideRule,
            @Schema(description = "税务展示属性是否继承商品默认配置；true 继承，false 使用 taxMetadata") boolean taxInherited,
            @Schema(description = "分销商税务展示属性；taxInherited=true 时通常为 null，已保存的非空值原样回显但不参与计算")
            BuyerTaxMetadataView taxMetadata) {

        public static BuyerPricingConfigView from(ProjectOverrides source) {
            return new BuyerPricingConfigView(
                    source.distributionInherited(), BuyerFormulaRuleView.from(source.distributionRule()),
                    source.salesInherited(), BuyerFormulaRuleView.from(source.salesRule()),
                    source.serviceFeeInherited(), BuyerServiceFeeRuleView.from(source.serviceFeeRule()),
                    source.hideInherited(), BuyerHideRuleView.from(source.hideRule()),
                    source.taxInherited(), BuyerTaxMetadataView.from(source.taxMetadata()));
        }
    }

    /** 价格公式回显；设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#2-公式与精度}。 */
    @Schema(name = "BuyerFormulaRuleView", description = "分销商分销价或销售价覆盖公式；对应的 distributionInherited/salesInherited=true 时通常为 null，已保存的非空规则仍回显但不参与计算。CUSTOM 时按 (base × percentage / 100) operator operand 计算，最终金额四舍五入到分；DIRECT_MARKET_PRICE 时直接等于市场价，其余字段为空。")
    public record BuyerFormulaRuleView(
            @Schema(description = "调价方式：DIRECT_MARKET_PRICE 直接等于市场价；CUSTOM 自定义公式", example = "CUSTOM") String mode,
            @Schema(description = "CUSTOM 的价格基数：MARKET_PRICE 市场价、SUGGESTED_SALE_PRICE 销售建议价、SUPPLY_PRICE 最低有效供货价；直接等于市场价时为 null", example = "MARKET_PRICE") String base,
            @Schema(description = "CUSTOM 的基数百分比，大于 0、最多两位小数；100 表示基数原值，85 表示基数的 85%；直接等于市场价时为 null", example = "85") BigDecimal percentage,
            @Schema(description = "CUSTOM 的运算符：ADD 加、SUBTRACT 减、MULTIPLY 乘、DIVIDE 除；直接等于市场价时为 null", example = "SUBTRACT") String operator,
            @Schema(description = "CUSTOM 的运算值，非负；ADD/SUBTRACT 的单位为元、最多两位小数，MULTIPLY/DIVIDE 为无单位系数、最多四位小数，DIVIDE 不得为 0；直接等于市场价时为 null", example = "1.50") BigDecimal operand) {

        static BuyerFormulaRuleView from(FormulaRule source) {
            return source == null ? null : new BuyerFormulaRuleView(
                    source.mode() == null ? null : source.mode().name(),
                    source.base() == null ? null : source.base().name(), source.percentage(),
                    source.operator() == null ? null : source.operator().name(), source.operand());
        }
    }

    /** 服务费规则回显；设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#2-公式与精度}。 */
    @Schema(name = "BuyerServiceFeeRuleView", description = "分销商服务费覆盖规则；serviceFeeInherited=true 时通常为 null，已保存的非空规则仍回显但不参与计算。按 (商品小计 × percentage / 100) operator operand 计算，最终金额四舍五入到分。")
    public record BuyerServiceFeeRuleView(
            @Schema(description = "商品小计的百分比，大于 0、最多两位小数；2 表示商品小计的 2%", example = "2") BigDecimal percentage,
            @Schema(description = "服务费运算符：ADD 加、SUBTRACT 减、MULTIPLY 乘、DIVIDE 除", example = "ADD") String operator,
            @Schema(description = "服务费运算值，非负；加减单位为元、最多两位小数，乘除为无单位系数、最多四位小数，除数不得为 0", example = "1.00") BigDecimal operand,
            @Schema(description = "是否隐藏自定义服务费文案；不影响费用金额或收取", example = "false") boolean hideCopy,
            @Schema(description = "服务费自定义展示文案；hideCopy=false 时应提供，未配置时为空", example = "服务费") String copy) {

        static BuyerServiceFeeRuleView from(ServiceFeeRule source) {
            return source == null ? null : new BuyerServiceFeeRuleView(source.percentage(),
                    source.operator() == null ? null : source.operator().name(), source.operand(),
                    source.hideCopy(), source.copy());
        }
    }

    /** 利润隐藏规则回显；设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#2-公式与精度}。 */
    @Schema(name = "BuyerHideRuleView", description = "分销商利润隐藏阈值；hideInherited=true 时通常为 null，已保存的非空规则仍回显但不参与计算。分销区与销售区同时配置时取较高值；利润率严格低于该值时用户端隐藏商品。")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BuyerHideRuleView(
            @Schema(description = "分销区利润隐藏阈值，百分数 0 到 100、最多两位小数；未配置时为 null", example = "12.50")
            BigDecimal distributionThresholdPercent,
            @Schema(description = "销售区利润隐藏阈值，百分数 0 到 100、最多两位小数；未配置时为 null", example = "10")
            BigDecimal salesThresholdPercent) {

        static BuyerHideRuleView from(HideRule source) {
            return source == null ? null : new BuyerHideRuleView(
                    source.distributionThresholdPercent(), source.salesThresholdPercent());
        }
    }

    /** 税务展示属性回显；设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#4-税务展示属性}。 */
    @Schema(name = "BuyerTaxMetadataView", description = "分销商原始税务展示属性；taxInherited=true 时通常为 null，已保存的非空值仍原样回显；不参与价格计算。")
    public record BuyerTaxMetadataView(
            @Schema(description = "true 含税，false 不含税；不含税时 taxRateCode 必须为 NON_TAXABLE", example = "true") Boolean taxIncluded,
            @Schema(description = "system 动态字典 supply_chain_tax_rate 的启用编码；不含税时为 NON_TAXABLE", example = "RATE_6") String taxRateCode,
            @Schema(description = "保存时解析并冻结的税率名称；字典未提供名称时为 null", example = "6%") String taxRateLabel) {

        static BuyerTaxMetadataView from(TaxMetadata source) {
            return source == null ? null : new BuyerTaxMetadataView(
                    source.taxIncluded(), source.taxRateCode(), source.taxRateLabel());
        }
    }

    /**
     * 同页动态规则共用数据库时刻并批量解析；非动态规则保持原金额来源，动态规则绝不回退兼容列。
     * 设计见 docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#4-数据模型与唯一取值入口。
     */
    private java.util.Map<Long, Resolution> resolveSuggestedPrices(java.util.Collection<PlatformSkuDO> skus,
                                                                  LocalDateTime now) {
        var dynamic = skus.stream().filter(SuggestedSalePriceResolver::isDynamic).toList();
        var resolved = dynamic.isEmpty() ? java.util.Map.<Long, Resolution>of()
                : suggestedSalePriceResolver.resolve(dynamic, now);
        java.util.Map<Long, Resolution> prices = new java.util.HashMap<>(resolved);
        for (PlatformSkuDO sku : skus) {
            if (!SuggestedSalePriceResolver.isDynamic(sku)) {
                prices.put(sku.getId(), SuggestedSalePriceResolver.evaluate(sku, null, null, now));
            }
        }
        return prices;
    }
}
