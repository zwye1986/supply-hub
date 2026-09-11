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

    public record SpuListItem(String spuCode, String spuName, String brandName, String categoryName,
                              String sellableCategory, String productType, String productClassification,
                              String coverUrl, Short status, int skuCount, Long minSuggestedPrice,
                              Long maxSuggestedPrice, LocalDateTime createdAt) {
    }

    public record SpuDetail(String spuCode, String spuName, String subtitle, Long brandId, String brandName,
                            Long primaryCategoryId, String primaryCategoryName, List<Long> secondaryCategoryIds,
                            String sellableCategory, String productType, String productClassification,
                            String coverUrl, List<String> sliderImageUrls, String useInstruction, String description,
                            String deliveryType, Long minQuantity, Long maxQuantity, Short status,
                            List<SkuDetail> skus, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record SkuDetail(String skuCode, String skuName, String fulfillmentType, Long faceValue,
                            Long marketPrice, Long suggestedSalePrice, Long minQuantity, Long maxQuantity,
                            String targetSchemaCode, Integer targetSchemaVersion, String resultSchemaCode,
                            Integer resultSchemaVersion, Short status, boolean effectiveSellable) {
    }
}
