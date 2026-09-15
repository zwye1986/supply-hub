package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BrandDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ProductCenterTab;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SPU_NOT_FOUND;

/**
 * 商品中心 SPU 聚合查询。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#41-平台商品管理productadmincontroller}。
 */
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.UnshelveMode;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCatalogQueryService {

    private final PlatformSpuMapper spuMapper;
    private final PlatformSkuMapper skuMapper;
    private final BrandMapper brandMapper;
    private final ProductCategoryMapper categoryMapper;
    private final ProductTypeMapper typeMapper;
    private final SupplierOfferMapper offerMapper;
    private final SupplierOfferVersionMapper offerVersionMapper;
    private final SupplierMapper supplierMapper;
    private final ProductTypeValidationService productTypeValidationService;
    private final ProductSellabilityService sellabilityService;

    public PageResult<SpuListItem> page(SpuPageQuery query) {
        TabFilter tab = resolveTab(query.tabCode());
        String productType = hasText(query.productType()) ? query.productType().trim() : tab.productType();
        String fulfillmentType = tab.fulfillmentType();
        Set<Long> fulfillmentSpuIds = fulfillmentType == null ? Set.of() : skuMapper.selectList(
                        new LambdaQueryWrapper<PlatformSkuDO>()
                                .eq(PlatformSkuDO::getFulfillmentType, fulfillmentType)
                                .ne(PlatformSkuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode()))
                .stream().map(PlatformSkuDO::getSpuId).collect(Collectors.toSet());
        if (fulfillmentType != null && fulfillmentSpuIds.isEmpty()) return new PageResult<>(List.of(), 0L);
        LambdaQueryWrapper<PlatformSpuDO> wrapper = new LambdaQueryWrapper<PlatformSpuDO>()
                .ne(PlatformSpuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                .eq(query.brandId() != null, PlatformSpuDO::getBrandId, query.brandId())
                .eq(query.categoryId() != null, PlatformSpuDO::getPrimaryCategoryId, query.categoryId())
                .eq(hasText(query.sellableCategory()), PlatformSpuDO::getSellableCategory, query.sellableCategory())
                .eq(hasText(productType), PlatformSpuDO::getProductType, productType)
                .eq(hasText(query.productClassification()), PlatformSpuDO::getProductClassification,
                        query.productClassification())
                .eq(query.status() != null, PlatformSpuDO::getStatus, query.status())
                .in(fulfillmentType != null, PlatformSpuDO::getId, fulfillmentSpuIds)
                .and(hasText(query.keyword()), w -> w.like(PlatformSpuDO::getSpuCode, query.keyword())
                        .or().like(PlatformSpuDO::getSpuName, query.keyword())
                        .or().like(PlatformSpuDO::getSubtitle, query.keyword()))
                .orderByDesc(PlatformSpuDO::getCreatedAt);
        PageParam param = new PageParam();
        param.setPageNo(query.pageNo() == null ? 1 : query.pageNo());
        param.setPageSize(query.pageSize() == null ? 20 : query.pageSize());
        PageResult<PlatformSpuDO> page = spuMapper.selectPage(param, wrapper);
        List<SpuListItem> items = toListItems(page.getList(), fulfillmentType);
        log.info("查询平台SPU分页 tabCode={} productType={} pageNo={} pageSize={} resultCount={}",
                query.tabCode(), productType, param.getPageNo(), param.getPageSize(), items.size());
        return new PageResult<>(items, page.getTotal());
    }

    public SpuDetail detail(String spuCode) {
        PlatformSpuDO spu = spuMapper.selectByCode(spuCode);
        if (spu == null || spu.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()) {
            throw exception(PLATFORM_SPU_NOT_FOUND);
        }
        BrandDO brand = brandMapper.selectById(spu.getBrandId());
        ProductCategoryDO category = spu.getPrimaryCategoryId() == null ? null
                : categoryMapper.selectById(spu.getPrimaryCategoryId());
        List<SkuDetail> skus = skus(List.of(spu.getId()), null).stream()
                .map(sku -> new SkuDetail(sku.getSkuCode(), sku.getSkuName(), sku.getFulfillmentType(),
                        sku.getFaceValue(), sku.getMarketPrice(), sku.getSuggestedSalePrice(),
                        sku.getTaxIncluded(), sku.getTaxRate(), sku.getMinQuantity(),
                        sku.getMaxQuantity(), sku.getUserLimitEnabled(), sku.getUserLimitPeriod(),
                        sku.getUserLimitQuantity(), sku.getOrderLimitEnabled(),
                        sku.getTargetSchemaCode(), sku.getTargetSchemaVersion(),
                        sku.getResultSchemaCode(), sku.getResultSchemaVersion(), sku.getStatus(),
                        sellabilityService.isSellable(sku, spu)))
                .toList();
        return new SpuDetail(spu.getSpuCode(), spu.getSpuName(), spu.getSubtitle(), spu.getBrandId(),
                brand == null ? null : brand.getBrandName(), spu.getPrimaryCategoryId(),
                category == null ? null : category.getCategoryName(), spu.getSecondaryCategoryIds(),
                spu.getSellableCategory(), spu.getProductType(), spu.getProductClassification(), spu.getCoverUrl(),
                spu.getSliderImageUrls(), spu.getUseInstruction(), spu.getDescription(), spu.getDeliveryType(),
                spu.getMinQuantity(), spu.getMaxQuantity(), spu.getStatus(), skus,
                spu.getCreatedAt(), spu.getUpdatedAt());
    }

    /**
     * 按整页批量装配列表行：SKU、品牌、主类目各查一次并在内存关联，
     * 避免逐行 {@code selectById} 造成的 N+1（每页 20 行原先会产生 60 次查询）。
     */
    private List<SpuListItem> toListItems(List<PlatformSpuDO> spus, String fulfillmentType) {
        if (spus.isEmpty()) {
            return List.of();
        }
        Map<Long, List<PlatformSkuDO>> skusBySpuId = skus(spus.stream().map(PlatformSpuDO::getId).toList(),
                fulfillmentType).stream().collect(Collectors.groupingBy(PlatformSkuDO::getSpuId));
        Map<Long, String> brandNames = brandNames(spus.stream().map(PlatformSpuDO::getBrandId).toList());
        Map<Long, String> categoryNames = categoryNames(spus.stream()
                .map(PlatformSpuDO::getPrimaryCategoryId).toList());
        return spus.stream().map(spu -> {
            List<PlatformSkuDO> skus = skusBySpuId.getOrDefault(spu.getId(), List.of());
            List<Long> prices = skus.stream().filter(sku -> sellabilityService.isSellable(sku, spu))
                    .map(PlatformSkuDO::getSuggestedSalePrice).filter(Objects::nonNull)
                    .sorted(Comparator.naturalOrder()).toList();
            return new SpuListItem(spu.getSpuCode(), spu.getSpuName(),
                    brandNames.get(spu.getBrandId()), categoryNames.get(spu.getPrimaryCategoryId()),
                    spu.getSellableCategory(), spu.getProductType(), spu.getProductClassification(),
                    spu.getCoverUrl(), spu.getStatus(), skus.size(),
                    prices.isEmpty() ? null : prices.getFirst(),
                    prices.isEmpty() ? null : prices.getLast(), spu.getCreatedAt());
        }).toList();
    }

    private Map<Long, String> brandNames(List<Long> brandIds) {
        List<Long> ids = brandIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return brandMapper.selectList(new LambdaQueryWrapper<BrandDO>().in(BrandDO::getId, ids)).stream()
                .collect(Collectors.toMap(BrandDO::getId, BrandDO::getBrandName));
    }

    private Map<Long, String> categoryNames(List<Long> categoryIds) {
        List<Long> ids = categoryIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectList(new LambdaQueryWrapper<ProductCategoryDO>()
                        .in(ProductCategoryDO::getId, ids)).stream()
                .collect(Collectors.toMap(ProductCategoryDO::getId, ProductCategoryDO::getCategoryName));
    }

    private List<PlatformSkuDO> skus(List<Long> spuIds, String fulfillmentType) {
        return skuMapper.selectList(new LambdaQueryWrapper<PlatformSkuDO>()
                .in(PlatformSkuDO::getSpuId, spuIds)
                .ne(PlatformSkuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                .eq(hasText(fulfillmentType), PlatformSkuDO::getFulfillmentType, fulfillmentType)
                .orderByAsc(PlatformSkuDO::getId));
    }

    /**
     * 解析列表 Tab：固定复合 Tab 由 {@link ProductCenterTab} 收敛，其余 tabCode 一律按动态商品类型编码处理。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#34-列表-tab-复合解构映射与动态来源}。
     */
    private TabFilter resolveTab(String tabCode) {
        return ProductCenterTab.fromCode(tabCode)
                .map(tab -> new TabFilter(tab.getProductType(), tab.fulfillmentTypeCode()))
                .orElseGet(() -> new TabFilter(
                        productTypeValidationService.requireExisting(tabCode).getTypeCode(), null));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record TabFilter(String productType, String fulfillmentType) {
    }

    public record SpuPageQuery(String tabCode, String keyword, Long brandId, Long categoryId,
                               String sellableCategory, String productType, String productClassification,
                               Short status, Integer pageNo, Integer pageSize) {
    }

    /** 平台商品分页行；字段语义与商品中心 DDL COMMENT 及 §4.1.1 契约一致。 */
    public record SpuListItem(
            @Schema(description = "平台SPU永久业务编码") String spuCode,
            @Schema(description = "平台SPU标准名称") String spuName,
            @Schema(description = "品牌名称") String brandName,
            @Schema(description = "主分类名称") String categoryName,
            @Schema(description = "可售品类封闭枚举名") String sellableCategory,
            @Schema(description = "动态商品类型编码") String productType,
            @Schema(description = "商品分类封闭枚举名") String productClassification,
            @Schema(description = "商品封面图地址") String coverUrl,
            @Schema(description = "SPU管理状态：0草稿，10启用，20停用") Short status,
            @Schema(description = "该SPU下未归档SKU数量") int skuCount,
            @Schema(description = "有效可售SKU中的最低销售建议价，单位CNY分") Long minSuggestedPrice,
            @Schema(description = "有效可售SKU中的最高销售建议价，单位CNY分") Long maxSuggestedPrice,
            @Schema(description = "SPU创建时间，Asia/Shanghai") LocalDateTime createdAt) {
    }

    /** 平台商品详情。 */
    public record SpuDetail(
            @Schema(description = "平台SPU永久业务编码") String spuCode,
            @Schema(description = "平台SPU标准名称") String spuName,
            @Schema(description = "平台SPU副标题") String subtitle,
            @Schema(description = "品牌内部主键") Long brandId,
            @Schema(description = "品牌名称") String brandName,
            @Schema(description = "主分类内部主键") Long primaryCategoryId,
            @Schema(description = "主分类名称") String primaryCategoryName,
            @Schema(description = "辅助或扩展分类内部主键列表") List<Long> secondaryCategoryIds,
            @Schema(description = "可售品类封闭枚举名") String sellableCategory,
            @Schema(description = "动态商品类型编码") String productType,
            @Schema(description = "商品分类封闭枚举名") String productClassification,
            @Schema(description = "商品封面图地址") String coverUrl,
            @Schema(description = "商品轮播图地址列表") List<String> sliderImageUrls,
            @Schema(description = "商品使用说明") String useInstruction,
            @Schema(description = "商品富文本详情与图文介绍") String description,
            @Schema(description = "配送履约模式：VIRTUAL虚拟发货，EXPRESS物流快递，PICKUP自提核销") String deliveryType,
            @Schema(description = "SPU默认单次最低起购量") Long minQuantity,
            @Schema(description = "SPU默认单次最高限购量") Long maxQuantity,
            @Schema(description = "SPU管理状态：0草稿，10启用，20停用") Short status,
            @Schema(description = "该SPU下未归档SKU详情") List<SkuDetail> skus,
            @Schema(description = "SPU创建时间，Asia/Shanghai") LocalDateTime createdAt,
            @Schema(description = "SPU最后更新时间，Asia/Shanghai") LocalDateTime updatedAt) {
    }

    /** 平台SKU详情。 */
    public record SkuDetail(
            @Schema(description = "平台SKU永久业务编码") String skuCode,
            @Schema(description = "平台SKU标准名称") String skuName,
            @Schema(description = "履约方式封闭枚举名") String fulfillmentType,
            @Schema(description = "商品标准面值，单位CNY分") Long faceValue,
            @Schema(description = "市场划线价或吊牌价，单位CNY分") Long marketPrice,
            @Schema(description = "建议零售价或销售建议价，单位CNY分") Long suggestedSalePrice,
            @Schema(description = "是否含税：1含税，0不含税；仅记录展示，不参与价格计算") Boolean taxIncluded,
            @Schema(description = "税率，如0.0600表示6%；仅记录展示，不参与价格计算") BigDecimal taxRate,
            @Schema(description = "SKU单次最小起购数量") Long minQuantity,
            @Schema(description = "SKU单次最大限购数量") Long maxQuantity,
            @Schema(description = "是否启用每人限购：1启用，0停用") Boolean userLimitEnabled,
            @Schema(description = "每人限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天") String userLimitPeriod,
            @Schema(description = "每人每周期最多购买数量") Long userLimitQuantity,
            @Schema(description = "是否启用每订单限购：1启用，0停用") Boolean orderLimitEnabled,
            @Schema(description = "履约目标封闭Schema编码") String targetSchemaCode,
            @Schema(description = "履约目标Schema版本") Integer targetSchemaVersion,
            @Schema(description = "履约结果封闭Schema编码") String resultSchemaCode,
            @Schema(description = "履约结果Schema版本") Integer resultSchemaVersion,
            @Schema(description = "SKU管理状态：0草稿，10启用，20停用") Short status,
            @Schema(description = "SPU与SKU均启用时为true") boolean effectiveSellable) {
    }

    /**
     * 管理端商品列表聚合查询。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#419-管理端商品列表聚合查询get-supply-chainproductsmanagement-page}。
     */
    public PageResult<ProductManagementSpuItem> managementPage(ProductManagementPageQuery query) {
        TabFilter tab = resolveTab(query.tabCode());
        String productType = hasText(query.productType()) ? query.productType().trim() : tab.productType();
        String fulfillmentType = hasText(query.fulfillmentType()) ? query.fulfillmentType().trim() : tab.fulfillmentType();

        Set<Long> candidateSpuIds = null;

        // 1. 履约方式 / 市场价区间预过滤
        if (fulfillmentType != null || query.marketPriceMin() != null || query.marketPriceMax() != null) {
            LambdaQueryWrapper<PlatformSkuDO> skuWrapper = new LambdaQueryWrapper<PlatformSkuDO>()
                    .ne(PlatformSkuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                    .eq(fulfillmentType != null, PlatformSkuDO::getFulfillmentType, fulfillmentType)
                    .ge(query.marketPriceMin() != null, PlatformSkuDO::getMarketPrice, query.marketPriceMin())
                    .le(query.marketPriceMax() != null, PlatformSkuDO::getMarketPrice, query.marketPriceMax());
            List<PlatformSkuDO> matchedSkus = skuMapper.selectList(skuWrapper);
            Set<Long> skuSpuIds = matchedSkus.stream().map(PlatformSkuDO::getSpuId).filter(Objects::nonNull).collect(Collectors.toSet());
            candidateSpuIds = intersect(candidateSpuIds, skuSpuIds);
            if (candidateSpuIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
        }

        // 2. 供应商 / 供货价区间预过滤
        if (hasText(query.supplierCode()) || query.supplyPriceMin() != null || query.supplyPriceMax() != null) {
            Long supplierId = null;
            if (hasText(query.supplierCode())) {
                SupplierDO supplier = supplierMapper.selectOne(SupplierDO::getSupplierCode, query.supplierCode().trim());
                if (supplier == null) {
                    return new PageResult<>(List.of(), 0L);
                }
                supplierId = supplier.getId();
            }
            LambdaQueryWrapper<SupplierOfferDO> offerWrapper = new LambdaQueryWrapper<SupplierOfferDO>()
                    .ne(SupplierOfferDO::getStatus, (short) 90)
                    .eq(supplierId != null, SupplierOfferDO::getSupplierId, supplierId);
            List<SupplierOfferDO> liveOffers = offerMapper.selectList(offerWrapper);
            if (liveOffers.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }

            if (query.supplyPriceMin() != null || query.supplyPriceMax() != null) {
                List<Long> versionIds = liveOffers.stream()
                        .map(SupplierOfferDO::getCurrentVersionId)
                        .filter(Objects::nonNull).toList();
                if (versionIds.isEmpty()) {
                    return new PageResult<>(List.of(), 0L);
                }
                List<SupplierOfferVersionDO> matchedVersions = offerVersionMapper.selectBatchIds(versionIds).stream()
                        .filter(v -> v.getSupplierSettlementUnitPrice() != null)
                        .filter(v -> query.supplyPriceMin() == null || v.getSupplierSettlementUnitPrice() >= query.supplyPriceMin())
                        .filter(v -> query.supplyPriceMax() == null || v.getSupplierSettlementUnitPrice() <= query.supplyPriceMax())
                        .toList();
                Set<Long> matchedVersionIds = matchedVersions.stream().map(SupplierOfferVersionDO::getId).collect(Collectors.toSet());
                liveOffers = liveOffers.stream().filter(o -> matchedVersionIds.contains(o.getCurrentVersionId())).toList();
                if (liveOffers.isEmpty()) {
                    return new PageResult<>(List.of(), 0L);
                }
            }

            List<Long> matchedSkuIds = liveOffers.stream().map(SupplierOfferDO::getPlatformSkuId).filter(Objects::nonNull).toList();
            if (matchedSkuIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
            List<PlatformSkuDO> skusForOffers = skuMapper.selectBatchIds(matchedSkuIds);
            Set<Long> offerSpuIds = skusForOffers.stream().map(PlatformSkuDO::getSpuId).filter(Objects::nonNull).collect(Collectors.toSet());
            candidateSpuIds = intersect(candidateSpuIds, offerSpuIds);
            if (candidateSpuIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
        }

        // 3. SPU 主查询
        LambdaQueryWrapper<PlatformSpuDO> spuWrapper = new LambdaQueryWrapper<PlatformSpuDO>()
                .ne(PlatformSpuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                .eq(query.brandId() != null, PlatformSpuDO::getBrandId, query.brandId())
                .eq(query.categoryId() != null, PlatformSpuDO::getPrimaryCategoryId, query.categoryId())
                .eq(hasText(productType), PlatformSpuDO::getProductType, productType)
                .eq(query.status() != null, PlatformSpuDO::getStatus, query.status())
                .eq(hasText(query.unshelveMode()), PlatformSpuDO::getUnshelveMode, query.unshelveMode())
                .ge(query.updateTimeStart() != null, PlatformSpuDO::getUpdatedAt, query.updateTimeStart())
                .le(query.updateTimeEnd() != null, PlatformSpuDO::getUpdatedAt, query.updateTimeEnd())
                .in(candidateSpuIds != null, PlatformSpuDO::getId, candidateSpuIds)
                .and(hasText(query.keyword()), w -> w.like(PlatformSpuDO::getSpuCode, query.keyword().trim())
                        .or().like(PlatformSpuDO::getSpuName, query.keyword().trim())
                        .or().like(PlatformSpuDO::getSubtitle, query.keyword().trim()))
                .orderByDesc(PlatformSpuDO::getUpdatedAt)
                .orderByDesc(PlatformSpuDO::getId);

        PageParam param = new PageParam();
        param.setPageNo(query.pageNo() == null ? 1 : query.pageNo());
        param.setPageSize(query.pageSize() == null ? 20 : query.pageSize());
        PageResult<PlatformSpuDO> page = spuMapper.selectPage(param, spuWrapper);
        if (page.getList().isEmpty()) {
            return new PageResult<>(List.of(), page.getTotal());
        }

        List<PlatformSpuDO> spus = page.getList();
        List<Long> spuIds = spus.stream().map(PlatformSpuDO::getId).toList();

        // 4. 批量加载字典
        List<Long> brandIds = spus.stream().map(PlatformSpuDO::getBrandId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> brandNameMap = brandIds.isEmpty() ? Map.of()
                : brandMapper.selectBatchIds(brandIds).stream().collect(Collectors.toMap(BrandDO::getId, BrandDO::getBrandName, (a, b) -> a));

        List<Long> categoryIds = spus.stream().map(PlatformSpuDO::getPrimaryCategoryId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> categoryNameMap = categoryIds.isEmpty() ? Map.of()
                : categoryMapper.selectBatchIds(categoryIds).stream().collect(Collectors.toMap(ProductCategoryDO::getId, ProductCategoryDO::getCategoryName, (a, b) -> a));

        List<String> types = spus.stream().map(PlatformSpuDO::getProductType).filter(Objects::nonNull).distinct().toList();
        Map<String, String> productTypeNameMap = types.isEmpty() ? Map.of()
                : typeMapper.selectList(new LambdaQueryWrapper<ProductTypeDO>().in(ProductTypeDO::getTypeCode, types))
                .stream().collect(Collectors.toMap(ProductTypeDO::getTypeCode, ProductTypeDO::getTypeName, (a, b) -> a));

        List<PlatformSkuDO> skus = skuMapper.selectList(new LambdaQueryWrapper<PlatformSkuDO>()
                .in(PlatformSkuDO::getSpuId, spuIds)
                .ne(PlatformSkuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                .orderByAsc(PlatformSkuDO::getId));
        Map<Long, List<PlatformSkuDO>> spuSkuMap = skus.stream().collect(Collectors.groupingBy(PlatformSkuDO::getSpuId));

        List<Long> skuIds = skus.stream().map(PlatformSkuDO::getId).filter(Objects::nonNull).toList();
        Map<Long, List<SupplierOfferDO>> skuOfferMap = Map.of();
        Map<Long, SupplierOfferVersionDO> versionMap = Map.of();
        Map<Long, SupplierDO> supplierMap = Map.of();
        if (!skuIds.isEmpty()) {
            List<SupplierOfferDO> offers = offerMapper.selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                    .in(SupplierOfferDO::getPlatformSkuId, skuIds)
                    .ne(SupplierOfferDO::getStatus, (short) 90));
            skuOfferMap = offers.stream().collect(Collectors.groupingBy(SupplierOfferDO::getPlatformSkuId));

            List<Long> versionIds = offers.stream().map(SupplierOfferDO::getCurrentVersionId).filter(Objects::nonNull).distinct().toList();
            if (!versionIds.isEmpty()) {
                versionMap = offerVersionMapper.selectBatchIds(versionIds).stream()
                        .collect(Collectors.toMap(SupplierOfferVersionDO::getId, v -> v, (a, b) -> a));
            }

            List<Long> supplierIds = offers.stream().map(SupplierOfferDO::getSupplierId).filter(Objects::nonNull).distinct().toList();
            if (!supplierIds.isEmpty()) {
                supplierMap = supplierMapper.selectBatchIds(supplierIds).stream()
                        .collect(Collectors.toMap(SupplierDO::getId, s -> s, (a, b) -> a));
            }
        }

        // 5. 组装结果
        List<ProductManagementSpuItem> items = new ArrayList<>(spus.size());
        for (PlatformSpuDO spu : spus) {
            List<PlatformSkuDO> spuSkus = spuSkuMap.getOrDefault(spu.getId(), List.of());
            List<ProductManagementSkuItem> skuItems = new ArrayList<>(spuSkus.size());

            for (PlatformSkuDO sku : spuSkus) {
                List<SupplierOfferDO> skuOffers = skuOfferMap.getOrDefault(sku.getId(), List.of());
                Long minSupplyPrice = null;
                List<SupplierSimpleInfo> suppliers = new ArrayList<>();
                for (SupplierOfferDO offer : skuOffers) {
                    SupplierDO s = supplierMap.get(offer.getSupplierId());
                    if (s != null) {
                        suppliers.add(new SupplierSimpleInfo(s.getSupplierCode(), s.getSupplierName()));
                    }
                    if (offer.getCurrentVersionId() != null) {
                        SupplierOfferVersionDO v = versionMap.get(offer.getCurrentVersionId());
                        if (v != null && v.getSupplierSettlementUnitPrice() != null) {
                            if (minSupplyPrice == null || v.getSupplierSettlementUnitPrice() < minSupplyPrice) {
                                minSupplyPrice = v.getSupplierSettlementUnitPrice();
                            }
                        }
                    }
                }

                Long supplyPriceExclusive = null;
                Long taxAmount = null;
                boolean taxIncluded = Boolean.TRUE.equals(sku.getTaxIncluded());
                String taxRateDisplay = taxIncluded ? "含税（税率未配置）" : "不含税";
                BigDecimal taxRate = sku.getTaxRate();
                if (taxIncluded && taxRate != null && taxRate.compareTo(BigDecimal.ZERO) > 0) {
                    taxRateDisplay = taxRate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
                    if (minSupplyPrice != null) {
                        supplyPriceExclusive = Math.round(minSupplyPrice / (1.0 + taxRate.doubleValue()));
                        taxAmount = minSupplyPrice - supplyPriceExclusive;
                    }
                } else if (!taxIncluded && minSupplyPrice != null) {
                    supplyPriceExclusive = minSupplyPrice;
                    taxAmount = 0L;
                }

                String fulfillmentDisplay = resolveFulfillmentTypeDisplay(sku.getFulfillmentType());

                skuItems.add(new ProductManagementSkuItem(
                        sku.getSkuCode(),
                        sku.getSkuName(),
                        sku.getMarketPrice(),
                        sku.getSuggestedSalePrice(),
                        minSupplyPrice,
                        supplyPriceExclusive,
                        taxIncluded,
                        taxRate,
                        taxRateDisplay,
                        taxAmount,
                        suppliers,
                        0L,
                        sku.getFulfillmentType(),
                        fulfillmentDisplay,
                        sku.getStatus()
                ));
            }

            String unshelveDisplay = UnshelveMode.fromCode(spu.getUnshelveMode()) != null
                    ? UnshelveMode.fromCode(spu.getUnshelveMode()).getChineseName() : null;

            items.add(new ProductManagementSpuItem(
                    spu.getSpuCode(),
                    spu.getSpuName(),
                    spu.getCoverUrl(),
                    spu.getProductType(),
                    productTypeNameMap.getOrDefault(spu.getProductType(), spu.getProductType()),
                    categoryNameMap.getOrDefault(spu.getPrimaryCategoryId(), null),
                    brandNameMap.getOrDefault(spu.getBrandId(), null),
                    spu.getStatus(),
                    spu.getUnshelveMode(),
                    unshelveDisplay,
                    spu.getUpdatedAt(),
                    skuItems
            ));
        }

        log.info("查询管理端平台商品聚合分页 tabCode={} pageNo={} pageSize={} resultCount={}",
                query.tabCode(), param.getPageNo(), param.getPageSize(), items.size());
        return new PageResult<>(items, page.getTotal());
    }

    private static String resolveFulfillmentTypeDisplay(String fulfillmentType) {
        if (fulfillmentType == null || fulfillmentType.isBlank()) {
            return "—";
        }
        return switch (fulfillmentType.trim().toUpperCase()) {
            case "CARD_CODE_SECRET" -> "卡号卡密";
            case "DIRECT_RECHARGE" -> "虚拟直充";
            case "COUPON_LINK" -> "卡券链接";
            case "MANUAL" -> "人工履约";
            case "EXPRESS" -> "物流配送";
            default -> fulfillmentType;
        };
    }

    private static Set<Long> intersect(Set<Long> existing, Set<Long> candidate) {
        if (existing == null) {
            return new HashSet<>(candidate);
        }
        existing.retainAll(candidate);
        return existing;
    }

    /** 管理端商品列表多维检索入参。 */
    public record ProductManagementPageQuery(
            String tabCode,
            String keyword,
            String productType,
            Long categoryId,
            Long brandId,
            Short status,
            String unshelveMode,
            String fulfillmentType,
            Long marketPriceMin,
            Long marketPriceMax,
            Long supplyPriceMin,
            Long supplyPriceMax,
            String supplierCode,
            LocalDateTime updateTimeStart,
            LocalDateTime updateTimeEnd,
            Integer pageNo,
            Integer pageSize) {
    }

    /** 供应商精简信息（用于支撑原型列表多供应商换行展示）。 */
    public record SupplierSimpleInfo(
            @Schema(description = "供应商编码") String supplierCode,
            @Schema(description = "供应商名称") String supplierName) {
    }

    /** 管理端商品列表 SKU 聚合行。 */
    public record ProductManagementSkuItem(
            @Schema(description = "平台SKU永久业务编码") String skuCode,
            @Schema(description = "SKU规格名称") String skuName,
            @Schema(description = "市场划线价或吊牌价，单位CNY分") Long marketPrice,
            @Schema(description = "建议零售价或销售建议价，单位CNY分") Long suggestedSalePrice,
            @Schema(description = "供货价或有效结算价，单位CNY分") Long supplyPrice,
            @Schema(description = "不含税供货价，单位CNY分") Long supplyPriceExclusive,
            @Schema(description = "是否含税：1含税，0不含税；仅记录展示，不参与价格计算") Boolean taxIncluded,
            @Schema(description = "税率，如0.0600表示6%；仅记录展示，不参与价格计算") BigDecimal taxRate,
            @Schema(description = "税率展示文本，如6%、不含税、含税（税率未配置）") String taxRateDisplay,
            @Schema(description = "税额，单位CNY分") Long taxAmount,
            @Schema(description = "供应商列表") List<SupplierSimpleInfo> suppliers,
            @Schema(description = "总库存") Long totalInventory,
            @Schema(description = "履约方式封闭枚举名") String fulfillmentType,
            @Schema(description = "履约方式中文展示名称") String fulfillmentTypeDisplay,
            @Schema(description = "SKU管理状态：0草稿，10启用，20停用") Short status) {
    }

    /** 管理端商品列表 SPU 聚合行。 */
    public record ProductManagementSpuItem(
            @Schema(description = "平台SPU永久业务编码") String spuCode,
            @Schema(description = "平台SPU标准名称") String spuName,
            @Schema(description = "商品封面图地址") String coverUrl,
            @Schema(description = "动态商品类型编码") String productType,
            @Schema(description = "商品类型中文名称") String productTypeName,
            @Schema(description = "主分类中文名称") String primaryCategoryName,
            @Schema(description = "品牌中文名称") String brandName,
            @Schema(description = "SPU管理状态：0草稿，10启用，20停用") Short status,
            @Schema(description = "下架方式：MANUAL_UNSHELVE手动下架，SYSTEM_UNSHELVE系统下架") String unshelveMode,
            @Schema(description = "下架方式中文名称") String unshelveModeDisplay,
            @Schema(description = "SPU最后更新时间，Asia/Shanghai") LocalDateTime updatedAt,
            @Schema(description = "该SPU挂载的全部未归档SKU明细列表") List<ProductManagementSkuItem> skus) {
    }
}
