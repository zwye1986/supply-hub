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
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCatalogQueryService {

    private final PlatformSpuMapper spuMapper;
    private final PlatformSkuMapper skuMapper;
    private final BrandMapper brandMapper;
    private final ProductCategoryMapper categoryMapper;
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
                        sku.getFaceValue(), sku.getMarketPrice(), sku.getSuggestedSalePrice(), sku.getMinQuantity(),
                        sku.getMaxQuantity(), sku.getTargetSchemaCode(), sku.getTargetSchemaVersion(),
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
            @Schema(description = "SKU单次最小起购数量") Long minQuantity,
            @Schema(description = "SKU单次最大限购数量") Long maxQuantity,
            @Schema(description = "履约目标封闭Schema编码") String targetSchemaCode,
            @Schema(description = "履约目标Schema版本") Integer targetSchemaVersion,
            @Schema(description = "履约结果封闭Schema编码") String resultSchemaCode,
            @Schema(description = "履约结果Schema版本") Integer resultSchemaVersion,
            @Schema(description = "SKU管理状态：0草稿，10启用，20停用") Short status,
            @Schema(description = "SPU与SKU均启用时为true") boolean effectiveSellable) {
    }
}
