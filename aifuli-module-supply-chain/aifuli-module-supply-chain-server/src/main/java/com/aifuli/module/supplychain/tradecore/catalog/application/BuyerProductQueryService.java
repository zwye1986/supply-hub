package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
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
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.profile.domain.L2AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.L3AdjustMode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SETTLEMENT_PRICE_MISSING;

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

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final String PRICING_RULE_EQUAL_L1 = "等于L1";
    private static final String PRICING_RULE_CUSTOM = "自定义公式";
    private static final String PRICING_RULE_INHERIT_L1 = "继承L1";

    private final BuyerMapper buyerMapper;
    private final BuyerProductMapper productMapper;
    private final PlatformSkuMapper skuMapper;
    private final PlatformSpuMapper spuMapper;
    private final PlatformSettlementPriceMapper l1Mapper;
    private final PlatformSettlementPriceVersionMapper l1VersionMapper;
    private final BuyerProductL2OverrideMapper overrideMapper;
    private final BuyerProductL2OverrideVersionMapper overrideVersionMapper;
    private final ProjectListingMapper listingMapper;
    private final ProjectListingVersionMapper listingVersionMapper;
    private final BuyerProjectMapper projectMapper;
    private final SettlementPriceResolver settlementPriceResolver;

    /**
     * 分页查询未移除分销商品。按更新时间倒序。
     * {@code productType} 可多选：重复键或逗号拼接，命中任一类型。省略、空白或拆分后无有效编码时不筛选；无命中返回空页。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
     */
    public PageResult<BuyerProductListItem> page(String buyerCode, List<String> productType,
                                                  Integer pageNo, Integer pageSize) {
        List<String> productTypes = normalizeProductTypes(productType);
        log.info("分页查询分销商品 buyerCode={} productTypes={} pageNo={} pageSize={}",
                buyerCode, productTypes, pageNo, pageSize);
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode);
        if (buyer == null) {
            throw exception(BUYER_PROFILE_NOT_FOUND);
        }
        LambdaQueryWrapper<BuyerProductDO> wrapper = new LambdaQueryWrapper<BuyerProductDO>()
                .eq(BuyerProductDO::getBuyerId, buyer.getId())
                .eq(BuyerProductDO::getStatus, BuyerProductStatus.ACTIVE.getCode())
                .orderByDesc(BuyerProductDO::getUpdatedAt);
        if (!productTypes.isEmpty()) {
            Set<Long> skuIds = matchingSkuIds(buyer.getId(), productTypes);
            if (skuIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
            wrapper.in(BuyerProductDO::getPlatformSkuId, skuIds);
        }
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(pageNo == null ? DEFAULT_PAGE_NO : pageNo);
        pageParam.setPageSize(pageSize == null ? DEFAULT_PAGE_SIZE : pageSize);
        PageResult<BuyerProductDO> page = productMapper.selectPage(pageParam, wrapper);
        List<BuyerProductListItem> items = new ArrayList<>();
        for (BuyerProductDO product : page.getList()) {
            items.add(toListItem(product));
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

    private BuyerProductListItem toListItem(BuyerProductDO product) {
        PlatformSkuDO sku = product.getPlatformSkuId() == null ? null : skuMapper.selectById(product.getPlatformSkuId());
        PlatformSpuDO spu = sku == null || sku.getSpuId() == null ? null : spuMapper.selectById(sku.getSpuId());
        Long l1Cents = sku == null ? null : loadL1Cents(sku.getId());
        BuyerProductL2OverrideVersionDO overrideVersion = loadOverrideVersion(product.getId());
        Long overrideCents = overrideVersion == null ? null : overrideVersion.getResolvedUnitPriceCents();
        Long distPriceCents = overrideCents != null ? overrideCents : l1Cents;
        ProjectPriceView projectView = projectPriceView(product.getId());
        Long l3ForResolve = projectView.l3ForResolve();
        ResolvedPrice resolved = resolveQuietly(l3ForResolve, overrideCents, l1Cents);
        return new BuyerProductListItem(
                product.getBuyerProductCode(),
                sku == null ? null : sku.getSkuCode(),
                sku == null ? null : sku.getSkuName(),
                spu == null ? null : spu.getSpuCode(),
                spu == null ? null : spu.getSpuName(),
                spu == null ? null : spu.getSellableCategory(),
                spu == null ? null : spu.getProductType(),
                spu == null ? null : spu.getProductClassification(),
                l1Cents,
                pricingRule(overrideVersion),
                distPriceCents,
                projectView.projectPriceCents(),
                resolved == null ? null : resolved.unitPriceCents(),
                resolved == null ? null : resolved.source().name(),
                projectView.projectPriceCovered(),
                projectView.projectNames(),
                product.getDisplayThresholdPercent());
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

    private static String pricingRule(BuyerProductL2OverrideVersionDO overrideVersion) {
        if (overrideVersion != null && L2AdjustMode.EQUAL_L1.name().equals(overrideVersion.getAdjustMode())) {
            return PRICING_RULE_EQUAL_L1;
        }
        if (overrideVersion != null && L2AdjustMode.CUSTOM.name().equals(overrideVersion.getAdjustMode())) {
            return PRICING_RULE_CUSTOM;
        }
        return PRICING_RULE_INHERIT_L1;
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
            @Schema(description = "建议结算价，取当前L1，单位CNY分") Long suggestPriceCents,
            @Schema(description = "定价规则文案：继承L1 / 等于L1 / 自定义公式") String pricingRule,
            @Schema(description = "有效L2，单位CNY分；无手工覆盖时等于当前L1") Long distPriceCents,
            @Schema(description = "恰有一个未移除项目listing时的当前版本单价，单位CNY分") Long projectPriceCents,
            @Schema(description = "按L3>L2>L1解析的有效B端结算价，单位CNY分") Long finalPriceCents,
            @Schema(description = "价格来源层级：L1 / L2 / L3") String priceSource,
            @Schema(description = "是否被多个项目L3覆盖") boolean projectPriceCovered,
            @Schema(description = "覆盖该SKU的项目名称") List<String> projectNames,
            @Schema(description = "利润空间低于该百分比时C端隐藏，本批不消费") Integer displayThresholdPercent) {
    }
}
