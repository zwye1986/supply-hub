package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BrandDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ProductCenterTab;
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule;
import com.aifuli.module.supplychain.tradecore.catalog.domain.UnshelveMode;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.EffectiveSupplyPrice;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SPU_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_CODE_REQUIRED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;

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
    private final ProductTypeMapper typeMapper;
    private final SupplierOfferMapper offerMapper;
    private final SupplierOfferVersionMapper offerVersionMapper;
    private final SupplierMapper supplierMapper;
    private final ProductTypeValidationService productTypeValidationService;
    private final ProductSellabilityService sellabilityService;
    private final BuyerMapper buyerMapper;
    private final BuyerProductMapper buyerProductMapper;
    private final SupplyChainDatabaseClock databaseClock;

    public PageResult<SpuListItem> page(SpuPageQuery query) {
        TabFilter tab = resolveTab(query.tabCode());
        String productType = hasText(query.productType()) ? query.productType().trim() : tab.productType();
        String fulfillmentType = tab.fulfillmentType();
        LambdaQueryWrapper<PlatformSpuDO> wrapper = new LambdaQueryWrapper<PlatformSpuDO>()
                .ne(PlatformSpuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                .eq(query.brandId() != null, PlatformSpuDO::getBrandId, query.brandId())
                .eq(query.categoryId() != null, PlatformSpuDO::getPrimaryCategoryId, query.categoryId())
                .eq(hasText(query.sellableCategory()), PlatformSpuDO::getSellableCategory, query.sellableCategory())
                .eq(hasText(productType), PlatformSpuDO::getProductType, productType)
                .eq(hasText(query.productClassification()), PlatformSpuDO::getProductClassification,
                        query.productClassification())
                .eq(query.status() != null, PlatformSpuDO::getStatus, query.status())
                .eq(hasText(fulfillmentType), PlatformSpuDO::getFulfillmentType, fulfillmentType)
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
                .map(sku -> new SkuDetail(sku.getSkuCode(), sku.getSkuName(), sku.getMainImageUrl(),
                        sku.getDetailImageUrls(), sku.getDescription(), sku.getFulfillmentType(),
                        sku.getFaceValue(), sku.getMarketPrice(), sku.getSuggestedSalePrice(),
                        toSuggestedSalePriceRuleView(sku.getSuggestedSalePriceRuleJson()),
                        sku.getTaxIncluded(), sku.getTaxRate(), sku.getMinQuantity(),
                        sku.getMaxQuantity(), sku.getUserLimitEnabled(), sku.getUserLimitPeriod(),
                        sku.getUserLimitQuantity(), sku.getOrderLimitEnabled(),
                        sku.getPhoneBindLimitEnabled(), sku.getPhoneBindLimitPeriod(),
                        sku.getPhoneBindLimitQuantity(),
                        sku.getSkuInventory(),
                        sku.getTargetSchemaCode(), sku.getTargetSchemaVersion(),
                        sku.getResultSchemaCode(), sku.getResultSchemaVersion(), sku.getStatus(),
                        sellabilityService.isSellable(sku, spu)))
                .toList();
        SkuDetail firstSku = skus.isEmpty() ? null : skus.getFirst();
        return new SpuDetail(spu.getSpuCode(), spu.getSpuName(), spu.getSubtitle(), spu.getBrandId(),
                brand == null ? null : brand.getBrandCode(),
                brand == null ? null : brand.getBrandName(), spu.getPrimaryCategoryId(),
                category == null ? null : category.getCategoryName(), spu.getSecondaryCategoryIds(),
                spu.getSellableCategory(), spu.getProductType(), spu.getProductClassification(), spu.getCoverUrl(),
                spu.getSliderImageUrls(), spu.getUseInstruction(), spu.getDescription(), spu.getDeliveryType(),
                spu.getFulfillmentType(), resolveFulfillmentTypeDisplay(spu.getFulfillmentType()),
                spu.getMinQuantity(), spu.getMaxQuantity(), spu.getStatus(),
                firstSku == null ? null : firstSku.userLimitEnabled(),
                firstSku == null ? null : firstSku.userLimitPeriod(),
                firstSku == null ? null : firstSku.userLimitQuantity(),
                firstSku == null ? null : firstSku.orderLimitEnabled(),
                firstSku == null ? null : firstSku.phoneBindLimitEnabled(),
                firstSku == null ? null : firstSku.phoneBindLimitPeriod(),
                firstSku == null ? null : firstSku.phoneBindLimitQuantity(),
                skus, spu.getCreatedAt(), spu.getUpdatedAt());
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
            String brandName = spu.getBrandId() == null ? null : brandNames.get(spu.getBrandId());
            String categoryName = spu.getPrimaryCategoryId() == null ? null : categoryNames.get(spu.getPrimaryCategoryId());
            return new SpuListItem(spu.getSpuCode(), spu.getSpuName(),
                    brandName, categoryName,
                    spu.getSellableCategory(), spu.getProductType(), spu.getProductClassification(),
                    spu.getCoverUrl(), spu.getStatus(), skus.size(),
                    prices.isEmpty() ? null : prices.getFirst(),
                    prices.isEmpty() ? null : prices.getLast(), spu.getCreatedAt());
        }).toList();
    }

    private Map<Long, String> brandNames(List<Long> brandIds) {
        List<Long> ids = brandIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return brandMapper.selectList(new LambdaQueryWrapper<BrandDO>().in(BrandDO::getId, ids)).stream()
                .collect(Collectors.toMap(BrandDO::getId, BrandDO::getBrandName));
    }

    private Map<Long, String> categoryNames(List<Long> categoryIds) {
        List<Long> ids = categoryIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Collections.emptyMap();
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

    /**
     * 规范化管理端商品类型筛选：兼容单个编码、重复键多值与逗号拼接，trim、丢空白并去重。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d39）。
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

    /**
     * 规范化管理端主分类筛选：兼容单个主键、重复键多值与逗号拼接，trim、丢空白、解析 Long 并去重。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d40）。
     */
    private static List<Long> normalizeCategoryIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            }
            for (String part : item.split(",")) {
                String token = part.trim();
                if (token.isEmpty()) {
                    continue;
                }
                try {
                    ids.add(Long.valueOf(token));
                } catch (NumberFormatException ignored) {
                    // 筛选参数不因非法片段 400；无法解析的值丢弃，与空白片段相同。
                }
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 将选中主分类展开为自身 ∪ 未归档子孙，供管理端类目树勾选父节点时命中叶子商品。
     * 一次加载未归档类目后内存 BFS，访问集合防环；未选分类时不查库。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d42）。
     */
    private List<Long> expandCategoryIdsWithDescendants(List<Long> selectedIds) {
        if (selectedIds.isEmpty()) {
            return selectedIds;
        }
        List<ProductCategoryDO> liveCategories = categoryMapper.selectList(new LambdaQueryWrapper<ProductCategoryDO>()
                .select(ProductCategoryDO::getId, ProductCategoryDO::getParentId)
                .ne(ProductCategoryDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode()));
        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        for (ProductCategoryDO row : liveCategories) {
            if (row.getId() == null || row.getParentId() == null) {
                continue;
            }
            childrenByParent.computeIfAbsent(row.getParentId(), ignored -> new ArrayList<>()).add(row.getId());
        }
        LinkedHashSet<Long> expanded = new LinkedHashSet<>(selectedIds);
        ArrayDeque<Long> pending = new ArrayDeque<>(selectedIds);
        while (!pending.isEmpty()) {
            Long current = pending.removeFirst();
            List<Long> children = childrenByParent.get(current);
            if (children == null) {
                continue;
            }
            for (Long childId : children) {
                if (expanded.add(childId)) {
                    pending.addLast(childId);
                }
            }
        }
        return List.copyOf(expanded);
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
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(description = "SPU管理状态：0草稿，10启用，20停用；字典值以字符串返回", type = "string") Short status,
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
            @Schema(description = "平台品牌永久业务编码") String brandCode,
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
            @Schema(description = "履约类型：DIRECT_RECHARGE直充，VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益") String fulfillmentType,
            @Schema(description = "履约方式中文展示名称") String fulfillmentTypeDisplay,
            @Schema(description = "SPU默认单次最低起购量") Long minQuantity,
            @Schema(description = "SPU默认单次最高限购量") Long maxQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(description = "SPU管理状态：0草稿，10启用，20停用；字典值以字符串返回", type = "string") Short status,
            @Schema(description = "是否启用每人限购：1启用，0停用") Boolean userLimitEnabled,
            @Schema(description = "每人限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天") String userLimitPeriod,
            @Schema(description = "每人每周期最多购买数量") Long userLimitQuantity,
            @Schema(description = "是否启用每订单限购：1启用，0停用") Boolean orderLimitEnabled,
            @Schema(description = "是否启用绑定手机号限购：1启用，0停用") Boolean phoneBindLimitEnabled,
            @Schema(description = "绑定手机号限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天") String phoneBindLimitPeriod,
            @Schema(description = "同一履约手机号每周期最多购买数量") Long phoneBindLimitQuantity,
            @Schema(description = "该SPU下未归档SKU详情") List<SkuDetail> skus,
            @Schema(description = "SPU创建时间，Asia/Shanghai") LocalDateTime createdAt,
            @Schema(description = "SPU最后更新时间，Asia/Shanghai") LocalDateTime updatedAt) {
    }

    /** 平台SKU详情。 */
    public record SkuDetail(
            @Schema(description = "平台SKU永久业务编码") String skuCode,
            @Schema(description = "平台SKU标准名称") String skuName,
            @Schema(description = "SKU主图地址") String mainImageUrl,
            @Schema(description = "SKU详情图地址列表(JSON数组)") List<String> detailImageUrls,
            @Schema(description = "SKU商品描述") String description,
            @Schema(description = "履约方式封闭枚举名") String fulfillmentType,
            @Schema(description = "商品标准面值，单位CNY分") Long faceValue,
            @Schema(description = "市场划线价或吊牌价，单位CNY分") Long marketPrice,
            @Schema(description = "建议零售价或销售建议价，单位CNY分") Long suggestedSalePrice,
            @Schema(description = "SKU销售建议价生成规则", allOf = SuggestedSalePriceRuleView.class)
            SuggestedSalePriceRuleView suggestedSalePriceRule,
            @Schema(description = "是否含税：1含税，0不含税；仅记录展示，不参与价格计算；标准商品结算以分销商/项目通用定价为准") Boolean taxIncluded,
            @Schema(description = "税率，如0.0600表示6%；仅记录展示，不参与价格计算；标准商品结算以分销商/项目通用定价为准") BigDecimal taxRate,
            @Schema(description = "SKU单次最小起购数量") Long minQuantity,
            @Schema(description = "SKU单次最大限购数量") Long maxQuantity,
            @Schema(description = "是否启用每人限购：1启用，0停用") Boolean userLimitEnabled,
            @Schema(description = "每人限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天") String userLimitPeriod,
            @Schema(description = "每人每周期最多购买数量") Long userLimitQuantity,
            @Schema(description = "是否启用每订单限购：1启用，0停用") Boolean orderLimitEnabled,
            @Schema(description = "是否启用绑定手机号限购：1启用，0停用") Boolean phoneBindLimitEnabled,
            @Schema(description = "绑定手机号限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天") String phoneBindLimitPeriod,
            @Schema(description = "同一履约手机号每周期最多购买数量") Long phoneBindLimitQuantity,
            @Schema(description = "SKU库存数量，管理端创建或编辑商品时设置") Long skuInventory,
            @Schema(description = "履约目标封闭Schema编码") String targetSchemaCode,
            @Schema(description = "履约目标Schema版本") Integer targetSchemaVersion,
            @Schema(description = "履约结果封闭Schema编码") String resultSchemaCode,
            @Schema(description = "履约结果Schema版本") Integer resultSchemaVersion,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(description = "SKU管理状态：0草稿，10启用，20停用；字典值以字符串返回", type = "string") Short status,
            @Schema(description = "SPU与SKU均启用时为true") boolean effectiveSellable) {
        /**
         * 管理端表单用 picUrl 绑定 SKU 主图；必须与 mainImageUrl 同值回写，否则编辑保存会清空已入库图片。
         * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d35）。
         */
        @JsonProperty("picUrl")
        @Schema(description = "SKU主图地址")
        public String picUrl() {
            return mainImageUrl;
        }

        /**
         * 管理端表单用 detailPics 绑定 SKU 详情图；必须与 detailImageUrls 同值回写。
         * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d35）。
         */
        @JsonProperty("detailPics")
        @Schema(description = "SKU详情图地址列表(JSON数组)")
        public List<String> detailPics() {
            return detailImageUrls;
        }
    }

    /**
     * 管理端商品列表聚合查询。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#419-管理端商品列表聚合查询get-supply-chainproductsmanagement-page}（d39、d40、d42）。
     */
    public PageResult<ProductManagementSpuItem> managementPage(ProductManagementPageQuery query) {
        TabFilter tab = resolveTab(query.tabCode());
        List<String> productTypes = normalizeProductTypes(query.productType());
        if (productTypes.isEmpty() && hasText(tab.productType())) {
            productTypes = List.of(tab.productType());
        }
        List<Long> selectedCategoryIds = normalizeCategoryIds(query.categoryId());
        List<Long> filterCategoryIds = expandCategoryIdsWithDescendants(selectedCategoryIds);
        String fulfillmentType = hasText(query.fulfillmentType()) ? query.fulfillmentType().trim() : tab.fulfillmentType();

        Set<Long> candidateSpuIds = null;
        String keyword = hasText(query.keyword()) ? query.keyword().trim() : null;
        KeywordSkuMatch keywordMatch = keywordSkuMatch(keyword);
        Set<Long> keywordMatchedSpuIds = keywordMatch.spuIds();
        Set<Long> excludedSkuIds = new HashSet<>(excludedActiveBuyerSkuIds(query.buyerCode()));
        boolean excludeAddedSupplierOffers = Boolean.TRUE.equals(query.excludeAddedSupplierOffers());
        if (excludeAddedSupplierOffers) {
            excludedSkuIds.addAll(liveSupplierOfferSkuIds(query.supplierCode()));
        }

        // 普通商品管理列表展示全部未归档 SKU；分销商选品与显式供应商筛选继续要求正常供应商的未退役 Offer。
        // 新增供应商品选品允许无供应商 SKU，以便建立首条合作关系。
        // 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线（d47、d48）
        boolean requireLiveSupplierOffer = !excludeAddedSupplierOffers
                && (hasText(query.buyerCode()) || hasText(query.supplierCode()));
        List<Long> activeSupplierIds = Optional.ofNullable(supplierMapper.selectActiveIds()).orElse(List.of());
        Set<Long> activeSupplierLiveSkuIds = requireLiveSupplierOffer
                ? activeSupplierLiveSkuIds(activeSupplierIds) : Set.of();
        if (requireLiveSupplierOffer) {
            if (activeSupplierLiveSkuIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
            List<PlatformSkuDO> suppliedSkus = skuMapper.selectBatchIds(activeSupplierLiveSkuIds);
            Set<Long> suppliedSpuIds = suppliedSkus.stream()
                    .map(PlatformSkuDO::getSpuId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            candidateSpuIds = intersect(candidateSpuIds, suppliedSpuIds);
            if (candidateSpuIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
        }

        // 1. SKU 商品状态 / 兑换方式 / 市场价区间预过滤。
        // 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#419-管理端商品列表聚合查询get-supply-chainproductsmanagement-page
        if (query.status() != null || fulfillmentType != null
                || query.marketPriceMin() != null || query.marketPriceMax() != null) {
            LambdaQueryWrapper<PlatformSkuDO> skuWrapper = managementSkuFilter(query, fulfillmentType);
            List<PlatformSkuDO> matchedSkus = skuMapper.selectList(skuWrapper);
            Set<Long> skuSpuIds = matchedSkus.stream().map(PlatformSkuDO::getSpuId).filter(Objects::nonNull).collect(Collectors.toSet());
            candidateSpuIds = intersect(candidateSpuIds, skuSpuIds);
            if (candidateSpuIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
        }

        // 2. 供应商 / 供货价区间预过滤。排除已添加供应商品时，supplierCode 不再表示「只看该供应商已供商品」。
        // 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线（d46）
        boolean supplierInclusion = hasText(query.supplierCode()) && !excludeAddedSupplierOffers;
        if (supplierInclusion || query.supplyPriceMin() != null || query.supplyPriceMax() != null) {
            if (activeSupplierIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
            Long supplierId = null;
            if (supplierInclusion) {
                SupplierDO supplier = supplierMapper.selectOne(SupplierDO::getSupplierCode, query.supplierCode().trim());
                if (supplier == null || supplier.getStatus() == null
                        || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()) {
                    return new PageResult<>(List.of(), 0L);
                }
                supplierId = supplier.getId();
            }
            LambdaQueryWrapper<SupplierOfferDO> offerWrapper = new LambdaQueryWrapper<SupplierOfferDO>()
                    .ne(SupplierOfferDO::getStatus, (short) 90)
                    .eq(supplierId != null, SupplierOfferDO::getSupplierId, supplierId)
                    .in(supplierId == null, SupplierOfferDO::getSupplierId, activeSupplierIds);
            List<SupplierOfferDO> liveOffers = offerMapper.selectList(offerWrapper);
            if (liveOffers.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }

            if (query.supplyPriceMin() != null || query.supplyPriceMax() != null) {
                liveOffers = offersInsideEffectiveSupplyRange(liveOffers, query.supplyPriceMin(), query.supplyPriceMax());
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

        if (!excludedSkuIds.isEmpty()) {
            LambdaQueryWrapper<PlatformSkuDO> availableSkuWrapper = managementSkuFilter(query, fulfillmentType)
                    .notIn(PlatformSkuDO::getId, excludedSkuIds);
            List<PlatformSkuDO> availableSkus = skuMapper.selectList(availableSkuWrapper);
            Set<Long> availableSpuIds = availableSkus.stream()
                    .map(PlatformSkuDO::getSpuId).filter(Objects::nonNull).collect(Collectors.toSet());
            candidateSpuIds = intersect(candidateSpuIds, availableSpuIds);
            if (candidateSpuIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
        }

        // 3. SPU 主查询
        LambdaQueryWrapper<PlatformSpuDO> spuWrapper = new LambdaQueryWrapper<PlatformSpuDO>()
                .ne(PlatformSpuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                .eq(query.brandId() != null, PlatformSpuDO::getBrandId, query.brandId())
                .in(!filterCategoryIds.isEmpty(), PlatformSpuDO::getPrimaryCategoryId, filterCategoryIds)
                .in(!productTypes.isEmpty(), PlatformSpuDO::getProductType, productTypes)
                .eq(hasText(query.unshelveMode()), PlatformSpuDO::getUnshelveMode, query.unshelveMode())
                .ge(query.updateTimeStart() != null, PlatformSpuDO::getUpdatedAt, query.updateTimeStart())
                .le(query.updateTimeEnd() != null, PlatformSpuDO::getUpdatedAt, query.updateTimeEnd())
                .in(candidateSpuIds != null, PlatformSpuDO::getId, candidateSpuIds)
                .and(keyword != null, w -> {
                    w.like(PlatformSpuDO::getSpuCode, keyword)
                            .or().like(PlatformSpuDO::getSpuName, keyword)
                            .or().like(PlatformSpuDO::getSubtitle, keyword);
                    if (!keywordMatchedSpuIds.isEmpty()) {
                        w.or().in(PlatformSpuDO::getId, keywordMatchedSpuIds);
                    }
                })
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
        // 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#419-管理端商品列表聚合查询get-supply-chainproductsmanagement-page
        List<Long> brandIds = spus.stream().map(PlatformSpuDO::getBrandId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> brandNameMap = brandIds.isEmpty() ? Collections.emptyMap()
                : brandMapper.selectBatchIds(brandIds).stream().collect(Collectors.toMap(BrandDO::getId, BrandDO::getBrandName, (a, b) -> a));

        List<Long> categoryIds = spus.stream().map(PlatformSpuDO::getPrimaryCategoryId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> categoryNameMap = categoryIds.isEmpty() ? Collections.emptyMap()
                : categoryMapper.selectBatchIds(categoryIds).stream().collect(Collectors.toMap(ProductCategoryDO::getId, ProductCategoryDO::getCategoryName, (a, b) -> a));

        List<String> types = spus.stream().map(PlatformSpuDO::getProductType).filter(Objects::nonNull).distinct().toList();
        Map<String, String> productTypeNameMap = types.isEmpty() ? Collections.emptyMap()
                : typeMapper.selectList(new LambdaQueryWrapper<ProductTypeDO>().in(ProductTypeDO::getTypeCode, types))
                .stream().collect(Collectors.toMap(ProductTypeDO::getTypeCode, ProductTypeDO::getTypeName, (a, b) -> a));

        List<PlatformSkuDO> skus = skuMapper.selectList(managementSkuFilter(query, fulfillmentType)
                .in(PlatformSkuDO::getSpuId, spuIds)
                .orderByAsc(PlatformSkuDO::getId));
        Map<Long, List<PlatformSkuDO>> spuSkuMap = skus.stream().collect(Collectors.groupingBy(PlatformSkuDO::getSpuId));

        List<Long> skuIds = skus.stream().map(PlatformSkuDO::getId).filter(Objects::nonNull).toList();
        Map<Long, List<SupplierOfferDO>> skuOfferMap = Collections.emptyMap();
        Map<Long, SupplierOfferVersionDO> versionMap = Collections.emptyMap();
        Map<Long, SupplierDO> supplierMap = Collections.emptyMap();
        if (!skuIds.isEmpty()) {
            List<SupplierOfferDO> offers = offerMapper.selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                    .in(SupplierOfferDO::getPlatformSkuId, skuIds)
                    .ne(SupplierOfferDO::getStatus, (short) 90));
            List<Long> supplierIds = offers.stream().map(SupplierOfferDO::getSupplierId).filter(Objects::nonNull).distinct().toList();
            if (!supplierIds.isEmpty()) {
                supplierMap = supplierMapper.selectBatchIds(supplierIds).stream()
                        .filter(supplier -> supplier.getStatus() != null
                                && supplier.getStatus() == SupplierStatus.ACTIVE.getCode())
                        .collect(Collectors.toMap(SupplierDO::getId, s -> s, (a, b) -> a));
            }

            Map<Long, SupplierDO> activeSupplierMap = supplierMap;
            List<SupplierOfferDO> activeSupplierOffers = offers.stream()
                    .filter(offer -> offer.getSupplierId() != null
                            && activeSupplierMap.containsKey(offer.getSupplierId()))
                    .toList();
            skuOfferMap = activeSupplierOffers.stream()
                    .collect(Collectors.groupingBy(SupplierOfferDO::getPlatformSkuId));

            List<Long> versionIds = activeSupplierOffers.stream()
                    .map(SupplierOfferDO::getCurrentVersionId)
                    .filter(Objects::nonNull).distinct().toList();
            if (!versionIds.isEmpty()) {
                versionMap = offerVersionMapper.selectBatchIds(versionIds).stream()
                        .collect(Collectors.toMap(SupplierOfferVersionDO::getId, v -> v, (a, b) -> a));
            }
        }

        // 5. 组装结果
        LocalDateTime supplyPriceAt = databaseClock.now();
        List<ProductManagementSpuItem> items = new ArrayList<>(spus.size());
        for (PlatformSpuDO spu : spus) {
            List<PlatformSkuDO> spuSkus = spuSkuMap.getOrDefault(spu.getId(), List.of());
            boolean spuKeywordHit = keyword == null || spuTextMatches(spu, keyword);
            List<ProductManagementSkuItem> skuItems = new ArrayList<>(spuSkus.size());

            for (PlatformSkuDO sku : spuSkus) {
                if (requireLiveSupplierOffer && (sku.getId() == null
                        || !activeSupplierLiveSkuIds.contains(sku.getId()))) {
                    continue;
                }
                if (sku.getId() != null && excludedSkuIds.contains(sku.getId())) {
                    continue;
                }
                if (keyword != null && !spuKeywordHit && !keywordMatch.skuIds().contains(sku.getId())) {
                    continue;
                }
                List<SupplierOfferDO> skuOffers = skuOfferMap.getOrDefault(sku.getId(), List.of());
                Long minSupplyPrice = null;
                List<SupplierSimpleInfo> suppliers = new ArrayList<>();
                for (SupplierOfferDO offer : skuOffers) {
                    SupplierDO s = offer.getSupplierId() == null ? null : supplierMap.get(offer.getSupplierId());
                    if (s != null) {
                        suppliers.add(new SupplierSimpleInfo(s.getSupplierCode(), s.getSupplierName()));
                    }
                    SupplierOfferVersionDO version = offer.getCurrentVersionId() == null
                            ? null : versionMap.get(offer.getCurrentVersionId());
                    Long price = EffectiveSupplyPrice.of(offer, s, version, sku.getSuggestedSalePrice(),
                            supplyPriceAt);
                    if (price != null && (minSupplyPrice == null || price < minSupplyPrice)) {
                        minSupplyPrice = price;
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
                        toSuggestedSalePriceRuleView(sku.getSuggestedSalePriceRuleJson()),
                        minSupplyPrice,
                        supplyPriceExclusive,
                        taxIncluded,
                        taxRate,
                        taxRateDisplay,
                        taxAmount,
                        suppliers,
                        sku.getSkuInventory() == null ? 0L : sku.getSkuInventory(),
                        sku.getFulfillmentType(),
                        fulfillmentDisplay,
                        sku.getStatus()
                ));
            }
            if (skuItems.isEmpty()) {
                continue;
            }

            String unshelveDisplay = UnshelveMode.fromCode(spu.getUnshelveMode()) != null
                    ? UnshelveMode.fromCode(spu.getUnshelveMode()).getChineseName() : null;

            // 空值安全回显字典名称，避免不可变 Map 敌对 Null Key 引起 NullPointerException
            // 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#419-管理端商品列表聚合查询get-supply-chainproductsmanagement-page
            String productTypeName = spu.getProductType() == null ? null
                    : productTypeNameMap.getOrDefault(spu.getProductType(), spu.getProductType());
            String categoryName = spu.getPrimaryCategoryId() == null ? null
                    : categoryNameMap.get(spu.getPrimaryCategoryId());
            String brandName = spu.getBrandId() == null ? null
                    : brandNameMap.get(spu.getBrandId());

            items.add(new ProductManagementSpuItem(
                    spu.getSpuCode(),
                    spu.getSpuName(),
                    spu.getCoverUrl(),
                    spu.getProductType(),
                    productTypeName,
                    categoryName,
                    brandName,
                    spu.getStatus(),
                    spu.getUnshelveMode(),
                    unshelveDisplay,
                    spu.getUpdatedAt(),
                    skuItems
            ));
        }

        log.info("查询管理端平台商品聚合分页 tabCode={} productTypes={} categoryIds={} expandedCategoryIds={} pageNo={} pageSize={} resultCount={}",
                query.tabCode(), productTypes, selectedCategoryIds, filterCategoryIds, param.getPageNo(), param.getPageSize(), items.size());
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

    /**
     * 商品列表中的商品状态、兑换方式和市场价都归属于 SKU；候选 SPU 与返回明细必须复用同一组条件，
     * 避免命中一个 SKU 后把不符合筛选条件的兄弟 SKU 一并返回。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#419-管理端商品列表聚合查询get-supply-chainproductsmanagement-page}。
     */
    private static LambdaQueryWrapper<PlatformSkuDO> managementSkuFilter(
            ProductManagementPageQuery query, String fulfillmentType) {
        return new LambdaQueryWrapper<PlatformSkuDO>()
                .ne(PlatformSkuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                .eq(query.status() != null, PlatformSkuDO::getStatus, query.status())
                .eq(fulfillmentType != null, PlatformSkuDO::getFulfillmentType, fulfillmentType)
                .ge(query.marketPriceMin() != null, PlatformSkuDO::getMarketPrice, query.marketPriceMin())
                .le(query.marketPriceMax() != null, PlatformSkuDO::getMarketPrice, query.marketPriceMax());
    }

    private static Set<Long> intersect(Set<Long> existing, Set<Long> candidate) {
        if (existing == null) {
            return new HashSet<>(candidate);
        }
        existing.retainAll(candidate);
        return existing;
    }

    /**
     * 名称或编码包含即命中。SPU 文本与 SKU 文本同一口径，供新增分销商品按「名称/编号」搜索。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#419-管理端商品列表聚合查询get-supply-chainproductsmanagement-page}。
     */
    private KeywordSkuMatch keywordSkuMatch(String keyword) {
        if (keyword == null) {
            return new KeywordSkuMatch(Set.of(), Set.of());
        }
        List<PlatformSkuDO> keywordSkus = skuMapper.selectList(new LambdaQueryWrapper<PlatformSkuDO>()
                .ne(PlatformSkuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                .and(w -> w.like(PlatformSkuDO::getSkuName, keyword)
                        .or().like(PlatformSkuDO::getSkuCode, keyword)));
        Set<Long> skuIds = new HashSet<>();
        Set<Long> spuIds = new HashSet<>();
        for (PlatformSkuDO sku : keywordSkus) {
            if (sku.getId() != null) {
                skuIds.add(sku.getId());
            }
            if (sku.getSpuId() != null) {
                spuIds.add(sku.getSpuId());
            }
        }
        return new KeywordSkuMatch(skuIds, spuIds);
    }

    /**
     * 新增供应商品选品要去掉该供应商未退役 Offer。已退役行不排除，供货配置三码不参与。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d46）。
     */
    private Set<Long> liveSupplierOfferSkuIds(String supplierCode) {
        if (!hasText(supplierCode)) {
            throw exception(SUPPLIER_CODE_REQUIRED);
        }
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode.trim());
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        List<SupplierOfferDO> offers = offerMapper.selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplier.getId())
                .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode()));
        Set<Long> skuIds = new HashSet<>();
        for (SupplierOfferDO offer : offers) {
            if (offer.getPlatformSkuId() == null || offer.getStatus() == null
                    || offer.getStatus() == OfferStatus.RETIRED.getCode()) {
                continue;
            }
            skuIds.add(offer.getPlatformSkuId());
        }
        return skuIds;
    }

    /**
     * 分销商必须存在。已有效关联的平台 SKU 从新增分销商品候选中排除，已移除行不排除。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#419-管理端商品列表聚合查询get-supply-chainproductsmanagement-page}。
     */
    private Set<Long> excludedActiveBuyerSkuIds(String buyerCode) {
        if (!hasText(buyerCode)) {
            return Set.of();
        }
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode.trim());
        if (buyer == null) {
            throw exception(BUYER_PROFILE_NOT_FOUND);
        }
        Set<Long> skuIds = new HashSet<>();
        List<BuyerProductDO> products = buyerProductMapper.selectList(new LambdaQueryWrapper<BuyerProductDO>()
                .eq(BuyerProductDO::getBuyerId, buyer.getId())
                .eq(BuyerProductDO::getStatus, BuyerProductStatus.ACTIVE.getCode()));
        for (BuyerProductDO product : products) {
            if (product.getPlatformSkuId() != null) {
                skuIds.add(product.getPlatformSkuId());
            }
        }
        return skuIds;
    }

    /**
     * 正常供应商未退役合作商品 SKU 集合。仅分销商选品与显式供应商筛选用它同时约束分页候选 SPU
     * 和返回 SKU 明细；普通商品管理列表不按合作关系剔除 SKU。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d47、d48）。
     */
    private Set<Long> activeSupplierLiveSkuIds(List<Long> activeSupplierIds) {
        if (activeSupplierIds.isEmpty()) {
            return Set.of();
        }
        List<Long> skuIds = offerMapper.selectLiveSkuIdsBySupplierIds(activeSupplierIds);
        return skuIds == null ? Set.of() : new HashSet<>(skuIds);
    }

    /**
     * 供货价区间与列表展示使用同一最低有效供货价。没有有效报价的 SKU 不落入区间。
     * 设计见 {@code docs/superpowers/specs/2026-09-22-effective-supply-price-identity-fallback-design.md}。
     */
    private List<SupplierOfferDO> offersInsideEffectiveSupplyRange(List<SupplierOfferDO> offers,
                                                                   Long supplyPriceMin, Long supplyPriceMax) {
        List<Long> versionIds = offers.stream()
                .map(SupplierOfferDO::getCurrentVersionId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, SupplierOfferVersionDO> versions = versionIds.isEmpty() ? Map.of()
                : offerVersionMapper.selectBatchIds(versionIds).stream()
                .collect(Collectors.toMap(SupplierOfferVersionDO::getId, version -> version, (left, right) -> left));
        List<Long> supplierIds = offers.stream().map(SupplierOfferDO::getSupplierId).filter(Objects::nonNull).distinct().toList();
        Map<Long, SupplierDO> suppliers = supplierIds.isEmpty() ? Map.of()
                : supplierMapper.selectBatchIds(supplierIds).stream()
                .collect(Collectors.toMap(SupplierDO::getId, supplier -> supplier, (left, right) -> left));
        List<Long> skuIds = offers.stream().map(SupplierOfferDO::getPlatformSkuId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Long> suggestedBySku = new HashMap<>();
        if (!skuIds.isEmpty()) {
            for (PlatformSkuDO sku : skuMapper.selectBatchIds(skuIds)) {
                if (sku.getId() != null) {
                    suggestedBySku.put(sku.getId(), sku.getSuggestedSalePrice());
                }
            }
        }
        LocalDateTime now = databaseClock.now();
        List<SupplierOfferDO> matched = new ArrayList<>();
        for (SupplierOfferDO offer : offers) {
            SupplierOfferVersionDO version = offer.getCurrentVersionId() == null
                    ? null : versions.get(offer.getCurrentVersionId());
            SupplierDO supplier = offer.getSupplierId() == null ? null : suppliers.get(offer.getSupplierId());
            Long suggested = offer.getPlatformSkuId() == null ? null : suggestedBySku.get(offer.getPlatformSkuId());
            Long price = EffectiveSupplyPrice.of(offer, supplier, version, suggested, now);
            if (price == null) {
                continue;
            }
            if ((supplyPriceMin == null || price >= supplyPriceMin)
                    && (supplyPriceMax == null || price <= supplyPriceMax)) {
                matched.add(offer);
            }
        }
        return matched;
    }

    private static boolean spuTextMatches(PlatformSpuDO spu, String keyword) {
        return contains(spu.getSpuCode(), keyword) || contains(spu.getSpuName(), keyword)
                || contains(spu.getSubtitle(), keyword);
    }

    private static boolean contains(String value, String keyword) {
        return value != null && keyword != null && value.contains(keyword);
    }

    private record KeywordSkuMatch(Set<Long> skuIds, Set<Long> spuIds) {
    }

    /** 管理端商品列表多维检索入参。 */
    public record ProductManagementPageQuery(
            String tabCode,
            String keyword,
            List<String> productType,
            List<String> categoryId,
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
            Integer pageSize,
            String buyerCode,
            Boolean excludeAddedSupplierOffers) {
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
            @Schema(description = "SKU销售建议价生成规则", allOf = SuggestedSalePriceRuleView.class)
            SuggestedSalePriceRuleView suggestedSalePriceRule,
            @Schema(description = "当前最低有效供货价，单位CNY分。供应商须启用，Offer须已发布；"
                    + "当前版本有效则用版本单价，否则回退身份列，多条取最低；没有有效报价为空，0是有效价格")
            Long supplyPrice,
            @Schema(description = "不含税供货价，单位CNY分") Long supplyPriceExclusive,
            @Schema(description = "是否含税：1含税，0不含税；仅记录展示，不参与价格计算；标准商品结算以分销商/项目通用定价为准") Boolean taxIncluded,
            @Schema(description = "税率，如0.0600表示6%；仅记录展示，不参与价格计算；标准商品结算以分销商/项目通用定价为准") BigDecimal taxRate,
            @Schema(description = "税率展示文本，如6%、不含税、含税（税率未配置）") String taxRateDisplay,
            @Schema(description = "税额，单位CNY分") Long taxAmount,
            @Schema(description = "正常状态供应商列表") List<SupplierSimpleInfo> suppliers,
            @Schema(description = "总库存；本期取值SKU库存数量，管理端创建或编辑商品时设置") Long totalInventory,
            @Schema(description = "履约方式封闭枚举名") String fulfillmentType,
            @Schema(description = "履约方式中文展示名称") String fulfillmentTypeDisplay,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(description = "SKU管理状态：0草稿，10启用，20停用；字典值以字符串返回", type = "string") Short status) {
        /**
         * 管理端编辑表单与详情使用 skuInventory；列表既有列仍读 totalInventory。
         * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d38）。
         */
        @JsonProperty("skuInventory")
        @Schema(description = "SKU库存数量，管理端创建或编辑商品时设置")
        public Long skuInventory() {
            return totalInventory;
        }
    }

    /** SKU销售建议价规则管理端投影；字段语义与 suggested_sale_price_rule_json DDL COMMENT 对齐。 */
    @Schema(description = "SKU销售建议价生成规则")
    public record SuggestedSalePriceRuleView(
            @Schema(description = "建议价模式（字典supply_chain_suggested_sale_price_mode）：EQUAL_MARKET_PRICE、CUSTOM、FIXED") String mode,
            @Schema(description = "CUSTOM基数：MARKET_PRICE、SUGGESTED_SALE_PRICE") String base,
            @Schema(description = "CUSTOM百分比") BigDecimal percentage,
            @Schema(description = "CUSTOM运算符（字典supply_chain_suggested_sale_price_operator）：ADD加、SUBTRACT减、MULTIPLY乘、DIVIDE除") String operator,
            @Schema(description = "CUSTOM运算数；加减单位元，乘除为系数") BigDecimal operand,
            @Schema(description = "FIXED固定建议价，单位CNY分") Long fixedPriceCents,
            @Schema(description = "以修改前建议价为基数时的服务端快照，单位CNY分") Long basePriceSnapshotCents) {
    }

    private static SuggestedSalePriceRuleView toSuggestedSalePriceRuleView(SuggestedSalePriceRule rule) {
        if (rule == null) {
            return null;
        }
        return new SuggestedSalePriceRuleView(
                rule.mode() == null ? null : rule.mode().name(),
                rule.base() == null ? null : rule.base().name(),
                rule.percentage(),
                rule.operator() == null ? null : rule.operator().name(),
                rule.operand(), rule.fixedPriceCents(), rule.basePriceSnapshotCents());
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
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            @Schema(description = "SPU管理状态：0草稿，10启用，20停用；字典值以字符串返回", type = "string") Short status,
            @Schema(description = "下架方式：MANUAL_UNSHELVE手动下架，SYSTEM_UNSHELVE系统下架") String unshelveMode,
            @Schema(description = "下架方式中文名称") String unshelveModeDisplay,
            @Schema(description = "SPU最后更新时间，Asia/Shanghai") LocalDateTime updatedAt,
            @Schema(description = "该SPU挂载的全部未归档SKU明细列表") List<ProductManagementSkuItem> skus) {
    }
}
