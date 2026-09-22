package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingDependencyChangedEvent;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferFulfillmentScore;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferPriceMode;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplyMode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_OFFER_OUT_OF_SCOPE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_SCOPE_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_STATUS_CONFLICT;

/**
 * 合作商品：新增即上架，改价只更新身份列，不写报价版本；新增与改价须发布定价依赖变更事件。
 * 设计见 {@code docs/superpowers/specs/2026-09-22-effective-supply-price-identity-fallback-design.md}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierCooperativeProductService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final long DEFAULT_OFFER_MIN_QUANTITY = 1L;

    private final SupplierMapper supplierMapper;
    private final SupplierSupplyScopeMapper scopeMapper;
    private final SupplierOfferMapper offerMapper;
    /**
     * 注入仅为保证管理端改价可被测试断言「从不 insert」；本服务禁止写版本表。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#55-合作商品}。
     */
    private final SupplierOfferVersionMapper offerVersionMapper;
    private final PlatformSkuMapper skuMapper;
    private final PlatformSpuMapper spuMapper;
    private final PlatformSettlementPriceMapper priceMapper;
    private final PlatformSettlementPriceVersionMapper priceVersionMapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 批量新增合作商品，状态直接 {@link OfferStatus#PUBLISHED}。供货上限为空时继承命中的供货配置；
     * 最低起购量为空时固定为 1，不读取供货配置中的预留字段。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#54-供货配置}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public List<String> createPublished(String supplierCode, List<DraftLine> lines, Long operatorId) {
        log.info("新增合作商品 supplierCode={} skuCount={} operatorId={}",
                supplierCode, lines == null ? 0 : lines.size(), operatorId);
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("At least one SKU is required");
        }
        SupplierDO supplier = supplierMapper.selectByCodeForUpdate(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        List<SupplierSupplyScopeDO> scopes = scopeMapper.selectBySupplierId(supplier.getId());
        LocalDateTime now = databaseClock.now();
        List<String> offerNos = new ArrayList<>();
        for (DraftLine line : lines) {
            offerNos.add(insertPublished(supplier, scopes, line, operatorId, now));
        }
        return offerNos;
    }

    /**
     * 兼容 {@code batch-draft}：委托 {@link #createPublished}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public List<String> createDrafts(String supplierCode, List<DraftLine> lines, Long operatorId) {
        return createPublished(supplierCode, lines, operatorId);
    }

    /**
     * 仅允许 {@code PUBLISHED}↔{@code SUSPENDED}；{@code RETIRED} 走 {@link #retire}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeOfferStatus(String supplierCode, String offerNo, OfferStatus target, Long operatorId) {
        log.info("变更合作商品状态 supplierCode={} offerNo={} target={} operatorId={}",
                supplierCode, offerNo, target, operatorId);
        if (target != OfferStatus.PUBLISHED && target != OfferStatus.SUSPENDED) {
            throw exception(SUPPLIER_STATUS_CONFLICT);
        }
        SupplierOfferDO offer = requireLiveOffer(supplierCode, offerNo);
        OfferStatus current = OfferStatus.fromCode(offer.getStatus());
        if (current != OfferStatus.PUBLISHED && current != OfferStatus.SUSPENDED) {
            throw exception(SUPPLIER_STATUS_CONFLICT);
        }
        if (current == target) {
            return;
        }
        offer.setStatus(target.getCode());
        offer.setUpdatedBy(operatorId);
        offer.setUpdatedAt(databaseClock.now());
        offerMapper.updateById(offer);
        eventPublisher.publishEvent(new PricingDependencyChangedEvent(offer.getPlatformSkuId()));
    }

    /**
     * 改价与供货模式：校验与新增相同，只更新身份列，禁止 {@code offerVersionMapper.insert}。
     * 身份列单价变化后必须发布定价依赖事件，供列表与通用定价按身份列回退。
     * 设计见 {@code docs/superpowers/specs/2026-09-22-effective-supply-price-identity-fallback-design.md}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void updateCommercial(String supplierCode, String offerNo, DraftLine line, Long operatorId) {
        log.info("更新合作商品身份列 supplierCode={} offerNo={} platformSkuCode={} operatorId={}",
                supplierCode, offerNo, line == null ? null : line.platformSkuCode(), operatorId);
        if (offerVersionMapper == null) {
            throw new IllegalStateException("offerVersionMapper is required");
        }
        SupplierOfferDO offer = requireLiveOffer(supplierCode, offerNo);
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        List<SupplierSupplyScopeDO> scopes = scopeMapper.selectBySupplierId(supplier.getId());
        ResolvedOffer resolved = resolveLine(scopes, line);
        Long existing = offerMapper.selectCount(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplier.getId())
                .eq(SupplierOfferDO::getPlatformSkuId, resolved.sku().getId())
                .ne(SupplierOfferDO::getId, offer.getId())
                .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode()));
        if (existing != null && existing > 0) {
            throw exception(SUPPLIER_SCOPE_CONFLICT);
        }
        offer.setPlatformSkuId(resolved.sku().getId());
        offer.setSupplyScopeId(resolved.scope().getId());
        offer.setPriceMode(resolved.priceMode().name());
        offer.setDraftUnitPrice(resolved.priceMode() == OfferPriceMode.FIXED ? line.unitPriceCents() : null);
        offer.setDiscountBp(resolved.priceMode() == OfferPriceMode.DISCOUNT ? line.discountBp() : null);
        offer.setOfferSupplyMode(resolved.supplyMode().name());
        offer.setDraftMaxQuantity(resolved.maxQuantity());
        offer.setDraftMinQuantity(resolved.minQuantity());
        offer.setUpdatedBy(operatorId);
        offer.setUpdatedAt(databaseClock.now());
        offerMapper.updateById(offer);
        eventPublisher.publishEvent(new PricingDependencyChangedEvent(offer.getPlatformSkuId()));
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void retire(String supplierCode, String offerNo, Long operatorId) {
        log.info("移除合作商品 supplierCode={} offerNo={} operatorId={}", supplierCode, offerNo, operatorId);
        SupplierOfferDO offer = requireLiveOffer(supplierCode, offerNo);
        offer.setStatus(OfferStatus.RETIRED.getCode());
        offer.setUpdatedBy(operatorId);
        offer.setUpdatedAt(databaseClock.now());
        offerMapper.updateById(offer);
        // 已发布版本仍然保留，但 RETIRED 身份不再属于最低有效供货价候选，必须同步生成新价格版本。
        // 设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态。
        eventPublisher.publishEvent(new PricingDependencyChangedEvent(offer.getPlatformSkuId()));
    }

    /**
     * 分页查询未移除合作商品。默认 pageNo=1、pageSize=20，按 createdAt 倒序。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#55-合作商品}。
     */
    public PageResult<OfferListItem> page(String supplierCode, String sellableCategory, String productType,
                                          String productClassification, Integer pageNo, Integer pageSize) {
        log.info("分页查询合作商品 supplierCode={} sellableCategory={} productType={} productClassification={} "
                        + "pageNo={} pageSize={}",
                supplierCode, sellableCategory, productType, productClassification, pageNo, pageSize);
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        LambdaQueryWrapper<SupplierOfferDO> wrapper = new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplier.getId())
                .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode())
                .orderByDesc(SupplierOfferDO::getCreatedAt);
        if (sellableCategory != null || productType != null || productClassification != null) {
            Set<Long> scopeIds = matchingScopeIds(supplier.getId(), sellableCategory, productType,
                    productClassification);
            if (scopeIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
            wrapper.in(SupplierOfferDO::getSupplyScopeId, scopeIds);
        }
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(pageNo == null ? DEFAULT_PAGE_NO : pageNo);
        pageParam.setPageSize(pageSize == null ? DEFAULT_PAGE_SIZE : pageSize);
        PageResult<SupplierOfferDO> page = offerMapper.selectPage(pageParam, wrapper);
        List<OfferListItem> items = new ArrayList<>();
        for (SupplierOfferDO offer : page.getList()) {
            items.add(toListItem(offer));
        }
        return new PageResult<>(items, page.getTotal());
    }

    public List<SupplierOfferDO> listLive(String supplierCode) {
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        return offerMapper.selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplier.getId())
                .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode())
                .orderByDesc(SupplierOfferDO::getUpdatedAt));
    }

    private String insertPublished(SupplierDO supplier, List<SupplierSupplyScopeDO> scopes, DraftLine line,
                                   Long operatorId, LocalDateTime now) {
        ResolvedOffer resolved = resolveLine(scopes, line);
        Long existing = offerMapper.selectCount(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplier.getId())
                .eq(SupplierOfferDO::getPlatformSkuId, resolved.sku().getId())
                .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode()));
        if (existing != null && existing > 0) {
            throw exception(SUPPLIER_SCOPE_CONFLICT);
        }
        String offerNo = businessNoGenerator.next(BusinessNoPrefixes.OFR);
        SupplierOfferDO offer = SupplierOfferDO.builder()
                .offerNo(offerNo)
                .offerCode(resolved.sku().getSkuCode() + "_" + offerNo)
                .supplierId(supplier.getId())
                .platformSkuId(resolved.sku().getId())
                .supplyScopeId(resolved.scope().getId())
                .priceMode(resolved.priceMode().name())
                .draftUnitPrice(resolved.priceMode() == OfferPriceMode.FIXED ? line.unitPriceCents() : null)
                .discountBp(resolved.priceMode() == OfferPriceMode.DISCOUNT ? line.discountBp() : null)
                .offerSupplyMode(resolved.supplyMode().name())
                .draftMaxQuantity(resolved.maxQuantity())
                .draftMinQuantity(resolved.minQuantity())
                .stabilityScore(OfferFulfillmentScore.DEFAULT)
                .timelinessScore(OfferFulfillmentScore.DEFAULT)
                .status(OfferStatus.PUBLISHED.getCode())
                .version(0L)
                .createdBy(operatorId)
                .createdAt(now)
                .updatedBy(operatorId)
                .updatedAt(now)
                .build();
        offerMapper.insert(offer);
        eventPublisher.publishEvent(new PricingDependencyChangedEvent(resolved.sku().getId()));
        return offer.getOfferNo();
    }

    private ResolvedOffer resolveLine(List<SupplierSupplyScopeDO> scopes, DraftLine line) {
        PlatformSkuDO sku = skuMapper.selectByCode(line.platformSkuCode());
        if (sku == null || sku.getStatus() == null
                || sku.getStatus() != CatalogEntityStatus.ACTIVE.getCode()) {
            throw exception(SUPPLIER_OFFER_OUT_OF_SCOPE);
        }
        PlatformSpuDO spu = spuMapper.selectById(sku.getSpuId());
        if (spu == null || spu.getStatus() == null
                || spu.getStatus() != CatalogEntityStatus.ACTIVE.getCode()) {
            throw exception(SUPPLIER_OFFER_OUT_OF_SCOPE);
        }
        SupplierSupplyScopeDO scope = matchScope(scopes, spu);
        OfferPriceMode priceMode = OfferPriceMode.fromCode(line.priceMode());
        SupplyMode supplyMode = SupplyMode.fromCode(line.supplyMode());
        validatePrice(priceMode, line);
        Long maxQuantity = line.maxQuantity() != null ? line.maxQuantity() : scope.getMaxQuantity();
        Long minQuantity = line.minQuantity() != null ? line.minQuantity() : DEFAULT_OFFER_MIN_QUANTITY;
        if (minQuantity == null || maxQuantity == null
                || minQuantity < 1 || maxQuantity < minQuantity) {
            throw new IllegalArgumentException("Offer quantity is invalid");
        }
        return new ResolvedOffer(sku, scope, priceMode, supplyMode, maxQuantity, minQuantity);
    }

    private SupplierOfferDO requireLiveOffer(String supplierCode, String offerNo) {
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        SupplierOfferDO offer = offerMapper.selectByNoForUpdate(offerNo);
        if (offer == null || !offer.getSupplierId().equals(supplier.getId())
                || offer.getStatus() == OfferStatus.RETIRED.getCode()) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        return offer;
    }

    private Set<Long> matchingScopeIds(Long supplierId, String sellableCategory, String productType,
                                       String productClassification) {
        Set<Long> ids = new HashSet<>();
        for (SupplierSupplyScopeDO scope : scopeMapper.selectBySupplierId(supplierId)) {
            if (sellableCategory != null && !sellableCategory.equals(scope.getSellableCategory())) {
                continue;
            }
            if (productType != null && !productType.equals(scope.getProductType())) {
                continue;
            }
            if (productClassification != null && !productClassification.equals(scope.getProductClassification())) {
                continue;
            }
            ids.add(scope.getId());
        }
        return ids;
    }

    private OfferListItem toListItem(SupplierOfferDO offer) {
        PlatformSkuDO sku = skuMapper.selectById(offer.getPlatformSkuId());
        PlatformSpuDO spu = sku == null ? null : spuMapper.selectById(sku.getSpuId());
        SupplierSupplyScopeDO scope = offer.getSupplyScopeId() == null
                ? null : scopeMapper.selectById(offer.getSupplyScopeId());
        return new OfferListItem(
                offer.getOfferNo(),
                spu == null ? null : spu.getSpuCode(),
                spu == null ? null : spu.getSpuName(),
                sku == null ? null : sku.getSkuCode(),
                sku == null ? null : sku.getSkuName(),
                scope == null ? null : scope.getSellableCategory(),
                scope == null ? null : scope.getProductType(),
                scope == null ? null : scope.getProductClassification(),
                resolveSuggestPriceCents(offer.getPlatformSkuId()),
                offer.getPriceMode(),
                offer.getDraftUnitPrice(),
                offer.getDiscountBp(),
                offer.getOfferSupplyMode(),
                offer.getDraftMaxQuantity(),
                offer.getDraftMinQuantity(),
                OfferStatus.fromCode(offer.getStatus()).name(),
                offer.getStabilityScore(),
                offer.getTimelinessScore());
    }

    private Long resolveSuggestPriceCents(Long skuId) {
        if (skuId == null) {
            return null;
        }
        PlatformSettlementPriceDO identity = priceMapper.selectById(skuId);
        if (identity == null || identity.getCurrentVersionId() == null) {
            return null;
        }
        PlatformSettlementPriceVersionDO version = priceVersionMapper.selectById(identity.getCurrentVersionId());
        if (version == null) {
            return null;
        }
        return version.getUnitPriceCents();
    }

    /**
     * SPU 三码须全部非空且与某条供货配置三码整体相等才命中；0 条或多条命中都判失败。
     * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#42-平台spu}。
     */
    private SupplierSupplyScopeDO matchScope(List<SupplierSupplyScopeDO> scopes, PlatformSpuDO spu) {
        if (spu.getSellableCategory() == null || spu.getProductType() == null
                || spu.getProductClassification() == null) {
            throw exception(SUPPLIER_OFFER_OUT_OF_SCOPE);
        }
        SupplierSupplyScopeDO matched = null;
        for (SupplierSupplyScopeDO scope : scopes) {
            boolean sameTriple = spu.getSellableCategory().equals(scope.getSellableCategory())
                    && spu.getProductType().equals(scope.getProductType())
                    && spu.getProductClassification().equals(scope.getProductClassification());
            if (sameTriple) {
                if (matched != null) {
                    throw exception(SUPPLIER_OFFER_OUT_OF_SCOPE);
                }
                matched = scope;
            }
        }
        if (matched == null) {
            throw exception(SUPPLIER_OFFER_OUT_OF_SCOPE);
        }
        return matched;
    }

    private static void validatePrice(OfferPriceMode mode, DraftLine line) {
        switch (mode) {
            case FIXED -> {
                if (line.unitPriceCents() == null || line.unitPriceCents() <= 0) {
                    throw new IllegalArgumentException("FIXED price must be > 0");
                }
            }
            case DISCOUNT -> {
                if (line.suggestedPriceCents() == null || line.suggestedPriceCents() <= 0) {
                    throw new IllegalArgumentException("DISCOUNT requires suggested price > 0");
                }
                if (line.discountBp() == null || line.discountBp() < 1 || line.discountBp() > 10_000) {
                    throw new IllegalArgumentException("Discount basis points must be 1-10000");
                }
            }
            case REALTIME -> {
                if (line.unitPriceCents() != null) {
                    throw new IllegalArgumentException("REALTIME must not set a fixed price");
                }
            }
        }
    }

    private record ResolvedOffer(PlatformSkuDO sku, SupplierSupplyScopeDO scope, OfferPriceMode priceMode,
                                 SupplyMode supplyMode, Long maxQuantity, Long minQuantity) {
    }

    public record DraftLine(String platformSkuCode, String priceMode, Long unitPriceCents,
                             Integer discountBp, Long suggestedPriceCents, String supplyMode,
                             Long maxQuantity, Long minQuantity) {
    }

    /**
     * 合作商品列表行。金额单位 CNY 分；status 为枚举名。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#55-合作商品}。
     */
    public record OfferListItem(
            @Schema(description = "供应商报价稳定业务编号") String offerNo,
            @Schema(description = "平台SPU永久业务编码") String platformSpuCode,
            @Schema(description = "平台SPU标准名称") String platformSpuName,
            @Schema(description = "平台SKU永久业务编码") String platformSkuCode,
            @Schema(description = "平台SKU标准名称") String platformSkuName,
            @Schema(description = "可售品类封闭枚举名") String sellableCategory,
            @Schema(description = "动态商品类型编码") String productType,
            @Schema(description = "商品分类封闭枚举名") String productClassification,
            @Schema(description = "L1 B端结算单价，CNY分，允许0") Long suggestPriceCents,
            @Schema(description = "供应价模式（字典supply_chain_offer_price_mode）：FIXED固定值，REALTIME实时更新，DISCOUNT按供货折扣") String priceMode,
            @Schema(description = "草稿供应价，CNY分；REALTIME为空") Long unitPriceCents,
            @Schema(description = "折扣基点，10000=100%") Integer discountBp,
            @Schema(description = "该Offer供货模式") String offerSupplyMode,
            @Schema(description = "草稿供货上限") Long maxQuantity,
            @Schema(description = "草稿最低起购量") Long minQuantity,
            @Schema(description = "生命周期：PUBLISHED已发布，SUSPENDED暂停") String status,
            @Schema(description = OfferFulfillmentScore.STABILITY_COMMENT) Integer stabilityScore,
            @Schema(description = OfferFulfillmentScore.TIMELINESS_COMMENT) Integer timelinessScore) {
    }
}
