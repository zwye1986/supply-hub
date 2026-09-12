package com.aifuli.module.supplychain.tradecore.catalog.application;

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
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;

/**
 * 管理端标品 SKU 池分页查询。金额单位均为 CNY 分；0 元 L1 视为已定价。
 * 三码直接来自 {@code sc_platform_spu}，不再走类目树祖先链；供应商过滤按三码整体相等命中供货配置。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#42-平台spu}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformSkuQueryService {

    private final PlatformSkuMapper skuMapper;
    private final PlatformSpuMapper spuMapper;
    private final PlatformSettlementPriceMapper priceMapper;
    private final PlatformSettlementPriceVersionMapper versionMapper;
    private final SupplierMapper supplierMapper;
    private final SupplierSupplyScopeMapper scopeMapper;
    private final SupplierOfferMapper offerMapper;
    private final BuyerMapper buyerMapper;
    private final BuyerProductMapper buyerProductMapper;
    private final BuyerProjectMapper buyerProjectMapper;
    private final ProjectListingMapper projectListingMapper;

    /**
     * 分页查询启用标品 SKU。供应商三码集合为空或候选 SPU 三码无一命中时返回空页；
     * 供应商/分销商/项目编码缺失抛业务错误。
     */
    public PageResult<SkuListItem> page(SkuPageQuery query) {
        log.info("查询标品SKU分页 keyword={} sellableCategory={} productType={} productClassification={} "
                        + "supplierCode={} buyerCode={} projectCode={} pageNo={} pageSize={}",
                query.keyword(), query.sellableCategory(), query.productType(), query.productClassification(),
                query.supplierCode(), query.buyerCode(), query.projectCode(),
                query.pageNo(), query.pageSize());

        Set<Long> includeSkuIds = null;
        Set<Long> excludeSkuIds = new HashSet<>();
        Set<String> supplierTripleKeys = null;
        if (hasText(query.supplierCode())) {
            SupplierDO supplier = supplierMapper.selectByCode(query.supplierCode());
            if (supplier == null) {
                throw exception(SUPPLIER_NOT_FOUND);
            }
            supplierTripleKeys = new HashSet<>();
            for (SupplierSupplyScopeDO scope : scopeMapper.selectBySupplierId(supplier.getId())) {
                supplierTripleKeys.add(tripleKey(scope.getSellableCategory(), scope.getProductType(),
                        scope.getProductClassification()));
            }
            if (supplierTripleKeys.isEmpty()) {
                return emptyPage();
            }
            List<SupplierOfferDO> offers = offerMapper.selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                    .eq(SupplierOfferDO::getSupplierId, supplier.getId())
                    .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode()));
            for (SupplierOfferDO offer : offers) {
                if (offer.getPlatformSkuId() != null) {
                    excludeSkuIds.add(offer.getPlatformSkuId());
                }
            }
        }
        if (hasText(query.buyerCode())) {
            excludeSkuIds.addAll(activeBuyerProductSkuIds(query.buyerCode()));
        }
        if (hasText(query.projectCode())) {
            ProjectSkuFilter projectFilter = resolveProjectSkuFilter(query.projectCode());
            if (projectFilter.includeSkuIds().isEmpty()) {
                return emptyPage();
            }
            includeSkuIds = projectFilter.includeSkuIds();
            excludeSkuIds.addAll(projectFilter.listedSkuIds());
        }

        LambdaQueryWrapper<PlatformSpuDO> spuWrapper = new LambdaQueryWrapper<PlatformSpuDO>()
                .eq(PlatformSpuDO::getStatus, CatalogEntityStatus.ACTIVE.getCode())
                .eq(hasText(query.sellableCategory()), PlatformSpuDO::getSellableCategory, query.sellableCategory())
                .eq(hasText(query.productType()), PlatformSpuDO::getProductType, query.productType())
                .eq(hasText(query.productClassification()), PlatformSpuDO::getProductClassification,
                        query.productClassification());
        List<PlatformSpuDO> spus = spuMapper.selectList(spuWrapper);
        if (supplierTripleKeys != null) {
            Set<String> allowedKeys = supplierTripleKeys;
            spus = spus.stream()
                    .filter(spu -> allowedKeys.contains(tripleKey(
                            spu.getSellableCategory(), spu.getProductType(), spu.getProductClassification())))
                    .toList();
        }
        if (spus.isEmpty()) {
            return emptyPage();
        }

        Map<Long, PlatformSpuDO> spuById = new HashMap<>();
        Set<Long> spuIds = new HashSet<>();
        Set<Long> keywordSpuIds = new HashSet<>();
        boolean keywordPresent = hasText(query.keyword());
        for (PlatformSpuDO spu : spus) {
            spuById.put(spu.getId(), spu);
            spuIds.add(spu.getId());
            if (keywordPresent && spu.getSpuName() != null && spu.getSpuName().contains(query.keyword())) {
                keywordSpuIds.add(spu.getId());
            }
        }

        LambdaQueryWrapper<PlatformSkuDO> skuWrapper = new LambdaQueryWrapper<PlatformSkuDO>()
                .eq(PlatformSkuDO::getStatus, CatalogEntityStatus.ACTIVE.getCode())
                .in(PlatformSkuDO::getSpuId, spuIds)
                .orderByDesc(PlatformSkuDO::getCreatedAt);
        if (keywordPresent) {
            skuWrapper.and(w -> {
                w.like(PlatformSkuDO::getSkuName, query.keyword());
                if (!keywordSpuIds.isEmpty()) {
                    w.or().in(PlatformSkuDO::getSpuId, keywordSpuIds);
                }
            });
        }
        if (includeSkuIds != null) {
            skuWrapper.in(PlatformSkuDO::getId, includeSkuIds);
        }
        if (!excludeSkuIds.isEmpty()) {
            skuWrapper.notIn(PlatformSkuDO::getId, excludeSkuIds);
        }

        PageParam pageParam = new PageParam();
        pageParam.setPageNo(query.pageNo() == null ? 1 : query.pageNo());
        pageParam.setPageSize(query.pageSize() == null ? 20 : query.pageSize());
        PageResult<PlatformSkuDO> page = skuMapper.selectPage(pageParam, skuWrapper);

        List<SkuListItem> items = new ArrayList<>();
        for (PlatformSkuDO sku : page.getList()) {
            items.add(toListItem(sku, spuById.get(sku.getSpuId())));
        }
        return new PageResult<>(items, page.getTotal());
    }

    /**
     * 分销商必须存在；已有效关联的平台 SKU 从「新增分销商品」池中排除。
     */
    private Set<Long> activeBuyerProductSkuIds(String buyerCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode);
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
     * 项目必须存在；SKU 须已是所属分销商的有效分销商品，且项目目录尚未可采购关联。
     */
    private ProjectSkuFilter resolveProjectSkuFilter(String projectCode) {
        BuyerProjectDO project = buyerProjectMapper.selectByProjectCode(projectCode);
        if (project == null) {
            throw exception(PROJECT_NOT_FOUND);
        }
        Set<Long> includeSkuIds = new HashSet<>();
        Map<Long, BuyerProductDO> productById = new HashMap<>();
        List<BuyerProductDO> products = buyerProductMapper.selectList(new LambdaQueryWrapper<BuyerProductDO>()
                .eq(BuyerProductDO::getBuyerId, project.getBuyerId())
                .eq(BuyerProductDO::getStatus, BuyerProductStatus.ACTIVE.getCode()));
        for (BuyerProductDO product : products) {
            productById.put(product.getId(), product);
            if (product.getPlatformSkuId() != null) {
                includeSkuIds.add(product.getPlatformSkuId());
            }
        }
        Set<Long> listedSkuIds = new HashSet<>();
        List<ProjectListingDO> listings = projectListingMapper.selectList(
                new LambdaQueryWrapper<ProjectListingDO>()
                        .eq(ProjectListingDO::getProjectId, project.getId())
                        .eq(ProjectListingDO::getStatus, ProjectListingStatus.AVAILABLE.getCode()));
        for (ProjectListingDO listing : listings) {
            BuyerProductDO product = productById.get(listing.getBuyerProductId());
            if (product != null && product.getPlatformSkuId() != null) {
                listedSkuIds.add(product.getPlatformSkuId());
            }
        }
        return new ProjectSkuFilter(includeSkuIds, listedSkuIds);
    }

    private SkuListItem toListItem(PlatformSkuDO sku, PlatformSpuDO spu) {
        return new SkuListItem(
                sku.getId(),
                sku.getSkuCode(),
                sku.getSkuName(),
                spu == null ? null : spu.getSpuCode(),
                spu == null ? null : spu.getSpuName(),
                spu == null ? null : spu.getSellableCategory(),
                spu == null ? null : spu.getProductType(),
                spu == null ? null : spu.getProductClassification(),
                resolveSuggestPriceCents(sku.getId()),
                sku.getSuggestedSalePrice());
    }

    /**
     * 建议结算价取当前 L1 {@code unit_price_cents}；无身份或无版本则为空。0 不是 missing。
     */
    private Long resolveSuggestPriceCents(Long skuId) {
        PlatformSettlementPriceDO identity = priceMapper.selectById(skuId);
        if (identity == null || identity.getCurrentVersionId() == null) {
            return null;
        }
        PlatformSettlementPriceVersionDO version = versionMapper.selectById(identity.getCurrentVersionId());
        if (version == null) {
            return null;
        }
        return version.getUnitPriceCents();
    }

    private static String tripleKey(String sellableCategory, String productType, String productClassification) {
        return sellableCategory + "|" + productType + "|" + productClassification;
    }

    private static PageResult<SkuListItem> emptyPage() {
        return new PageResult<>(List.of(), 0L);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ProjectSkuFilter(Set<Long> includeSkuIds, Set<Long> listedSkuIds) {
    }

    public record SkuPageQuery(String keyword, String sellableCategory, String productType,
                               String productClassification, String supplierCode, String buyerCode,
                               String projectCode, Integer pageNo, Integer pageSize) {
    }

    /**
     * 标品 SKU 列表行。金额注释与 DDL COMMENT 对齐：销售建议价为 C 端建议价，L1 为 B 端结算单价；
     * 三码直接来自 SPU，未打码的历史 SPU 三个字段均为 null。
     * 兼容字段 {@code marketPriceCents} 的值取 {@code sc_platform_sku.suggested_sale_price}，
     * 不得回退到履约面值或市场划线价。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线} d15。
     */
    public record SkuListItem(
            @Schema(description = "平台SKU内部主键") Long id,
            @Schema(description = "平台SKU永久业务编码") String skuCode,
            @Schema(description = "平台SKU标准名称") String skuName,
            @Schema(description = "平台SPU永久业务编码") String spuCode,
            @Schema(description = "平台SPU标准名称") String spuName,
            @Schema(description = "可售品类封闭枚举名") String sellableCategory,
            @Schema(description = "动态商品类型编码") String productType,
            @Schema(description = "商品分类封闭枚举名") String productClassification,
            @Schema(description = "L1 B端结算单价，CNY分，允许0") Long suggestPriceCents,
            @Schema(description = "兼容字段名；C端建议价，取自平台SKU销售建议价，单位CNY分") Long marketPriceCents) {
    }
}
