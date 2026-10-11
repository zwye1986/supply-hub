package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCategoryCoverageService;
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
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.FulfillmentStatus;
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
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_OFFER_SUGGESTED_SALE_PRICE_INVALID;
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
     * 供货配置分类码与 SPU 分类码的归属判定（类目祖先链，归档不断链），树数据所有权仍在 catalog。
     * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#1-已确认决策}（d3）。
     */
    private final ProductCategoryCoverageService categoryCoverageService;

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
        ProductCategoryCoverageService.CoverageResolver coverage = categoryCoverageService.loadResolver();
        LocalDateTime now = databaseClock.now();
        List<String> offerNos = new ArrayList<>();
        for (DraftLine line : lines) {
            offerNos.add(insertPublished(supplier, scopes, line, operatorId, now, coverage));
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
        // f1：改价改供货模式同样强制外部商品编码非空（"校验与新增相同"），并同步身份列；
        // 编码变化后与当前版本冻结值不一致，列表投影判为 STALE，须重新上架即发布。
        requireExternalSkuCode(line.externalSkuCode());
        SupplierOfferDO offer = requireLiveOffer(supplierCode, offerNo);
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        List<SupplierSupplyScopeDO> scopes = scopeMapper.selectBySupplierId(supplier.getId());
        ResolvedOffer resolved = resolveLine(scopes, line, categoryCoverageService.loadResolver());
        Long existing = offerMapper.selectCount(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplier.getId())
                .eq(SupplierOfferDO::getPlatformSkuId, resolved.sku().getId())
                .ne(SupplierOfferDO::getId, offer.getId())
                .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode()));
        if (existing != null && existing > 0) {
            throw exception(SUPPLIER_SCOPE_CONFLICT);
        }
        offer.setPlatformSkuId(resolved.sku().getId());
        offer.setExternalSkuCode(line.externalSkuCode());
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
                                   Long operatorId, LocalDateTime now,
                                   ProductCategoryCoverageService.CoverageResolver coverage) {
        // f1：供应商外部商品编码选品必填，冻结进身份行后再由上架即发布继承进版本行，执行侧据此作为供应商 productId 发出。
        // 前端另以 @NotBlank 兜底；此处服务层强制，避免编程式调用（createDrafts/集成）绕过。
        // 设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#54-f1-external_sku_code变更集-053。
        requireExternalSkuCode(line.externalSkuCode());
        ResolvedOffer resolved = resolveLine(scopes, line, coverage);
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
                .externalSkuCode(line.externalSkuCode())
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

    /**
     * f1：供应商外部商品编码必填校验，空白即拒绝。新增与改价共用（"校验与新增相同"）。
     * 设计见 {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#54-f1-external_sku_code变更集-053}。
     */
    private static void requireExternalSkuCode(String externalSkuCode) {
        if (externalSkuCode == null || externalSkuCode.isBlank()) {
            throw new IllegalArgumentException("externalSkuCode is required");
        }
    }

    private ResolvedOffer resolveLine(List<SupplierSupplyScopeDO> scopes, DraftLine line,
                                      ProductCategoryCoverageService.CoverageResolver coverage) {
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
        SupplierSupplyScopeDO scope = matchScope(scopes, spu, coverage);
        OfferPriceMode priceMode = OfferPriceMode.fromCode(line.priceMode());
        SupplyMode supplyMode = SupplyMode.fromCode(line.supplyMode());
        validatePrice(priceMode, line, sku.getSuggestedSalePrice());
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

    /**
     * i-b 履约就绪度三态纯函数（不落标志列，后端计算）。设计见
     * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#67-listoffers-投影扩展m1-沿用--i-b}。
     *
     * <p>{@code current_version_id==null}（或版本行缺失）→{@link FulfillmentStatus#NOT_CONFIGURED}；
     * 否则 diff 身份列（external_sku_code/draft_unit_price/draft_min_quantity/draft_max_quantity）与当前版本
     * 冻结字段（external_sku_code/supplier_settlement_unit_price/min_quantity/max_quantity），任一不一致→
     * {@link FulfillmentStatus#STALE}，全一致→{@link FulfillmentStatus#READY}。抽为静态纯函数便于 {@code *Test}
     * 覆盖各分支，且不依赖 Spring/MyBatis。</p>
     */
    public static FulfillmentStatus computeFulfillmentStatus(SupplierOfferDO offer,
                                                             SupplierOfferVersionDO currentVersion) {
        if (offer.getCurrentVersionId() == null || currentVersion == null) {
            return FulfillmentStatus.NOT_CONFIGURED;
        }
        boolean drift = !java.util.Objects.equals(offer.getExternalSkuCode(), currentVersion.getExternalSkuCode())
                || !java.util.Objects.equals(offer.getDraftUnitPrice(), currentVersion.getSupplierSettlementUnitPrice())
                || !java.util.Objects.equals(offer.getDraftMinQuantity(), currentVersion.getMinQuantity())
                || !java.util.Objects.equals(offer.getDraftMaxQuantity(), currentVersion.getMaxQuantity());
        return drift ? FulfillmentStatus.STALE : FulfillmentStatus.READY;
    }

    private OfferListItem toListItem(SupplierOfferDO offer) {
        PlatformSkuDO sku = skuMapper.selectById(offer.getPlatformSkuId());
        PlatformSpuDO spu = sku == null ? null : spuMapper.selectById(sku.getSpuId());
        SupplierSupplyScopeDO scope = offer.getSupplyScopeId() == null
                ? null : scopeMapper.selectById(offer.getSupplyScopeId());
        SupplierOfferVersionDO currentVersion = offer.getCurrentVersionId() == null
                ? null : offerVersionMapper.selectById(offer.getCurrentVersionId());
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
                sku == null ? null : sku.getSuggestedSalePrice(),
                offer.getPriceMode(),
                offer.getDraftUnitPrice(),
                offer.getDiscountBp(),
                offer.getOfferSupplyMode(),
                offer.getDraftMaxQuantity(),
                offer.getDraftMinQuantity(),
                OfferStatus.fromCode(offer.getStatus()).name(),
                offer.getStabilityScore(),
                offer.getTimelinessScore(),
                offer.getExternalSkuCode(),
                offer.getCurrentVersionId(),
                offer.getVersion(),
                computeFulfillmentStatus(offer, currentVersion).code());
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
     * 归属命中（2026-09-23 修订，取代三码全等）：SPU 三码须全部非空；{@code sellableCategory}、
     * {@code productType} 仍字符串全等；{@code productClassification} 由
     * {@link ProductCategoryCoverageService.CoverageResolver#covers} 判定——两边都是类目码时，
     * 配置码是 SPU 码祖先（或相等）即命中，枚举名仍只认全等。多条命中取类目层级最深（最具体）的一条；
     * 树性质下同层不可能双命中，若因脏数据出现同层多命中仍判失败。0 条命中抛
     * {@code SUPPLIER_OFFER_OUT_OF_SCOPE}。
     * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#1-已确认决策}（d3）。
     */
    private SupplierSupplyScopeDO matchScope(List<SupplierSupplyScopeDO> scopes, PlatformSpuDO spu,
                                             ProductCategoryCoverageService.CoverageResolver coverage) {
        if (spu.getSellableCategory() == null || spu.getProductType() == null
                || spu.getProductClassification() == null) {
            throw exception(SUPPLIER_OFFER_OUT_OF_SCOPE);
        }
        SupplierSupplyScopeDO matched = null;
        int matchedDepth = -1;
        boolean tieAtDeepest = false;
        for (SupplierSupplyScopeDO scope : scopes) {
            if (!spu.getSellableCategory().equals(scope.getSellableCategory())
                    || !spu.getProductType().equals(scope.getProductType())
                    || !coverage.covers(scope.getProductClassification(), spu.getProductClassification())) {
                continue;
            }
            int depth = coverage.depth(scope.getProductClassification());
            if (depth > matchedDepth) {
                matched = scope;
                matchedDepth = depth;
                tieAtDeepest = false;
            } else if (depth == matchedDepth) {
                // 树性质 + 三元组唯一下同层不可能双命中；出现即数据异常，保持失败语义
                tieAtDeepest = true;
            }
        }
        if (matched == null || tieAtDeepest) {
            throw exception(SUPPLIER_OFFER_OUT_OF_SCOPE);
        }
        return matched;
    }

    /**
     * 折扣准入读取事务内已加载SKU的权威销售建议价；历史客户端提示价可缺省或不一致，不参与准入。
     * 创建和改价复用本校验，不改FIXED/REALTIME或履约版本冻结价。
     * 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-sale-price-alignment-design.md#4-服务端折扣准入。
     */
    private static void validatePrice(OfferPriceMode mode, DraftLine line, Long suggestedSalePrice) {
        switch (mode) {
            case FIXED -> {
                if (line.unitPriceCents() == null || line.unitPriceCents() <= 0) {
                    throw new IllegalArgumentException("FIXED price must be > 0");
                }
            }
            case DISCOUNT -> {
                if (suggestedSalePrice == null || suggestedSalePrice <= 0) {
                    throw exception(SUPPLIER_OFFER_SUGGESTED_SALE_PRICE_INVALID);
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

    /**
     * 商业条款输入；suggestedPriceCents仅为历史兼容提示字段，不作为服务端价格来源。
     * 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-sale-price-alignment-design.md#4-服务端折扣准入。
     */
    public record DraftLine(String platformSkuCode, String priceMode, Long unitPriceCents,
                             Integer discountBp, Long suggestedPriceCents, String supplyMode,
                             Long maxQuantity, Long minQuantity, String externalSkuCode) {
    }

    /**
     * 合作商品列表行。金额单位 CNY 分；status 为枚举名。销售建议价取SKU，旧suggestPriceCents保留L1原义。
     * 价格口径见 docs/superpowers/specs/2026-10-11-supplier-offer-sale-price-alignment-design.md#3-后端响应与兼容契约。
     * 设计见 {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#67-listoffers-投影扩展m1-沿用--i-b}。
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
            @Schema(description = "建议零售价/销售建议价，单位为分") Long suggestedSalePriceCents,
            @Schema(description = "供应价模式（字典supply_chain_offer_price_mode）：FIXED固定值，REALTIME实时更新，DISCOUNT按供货折扣") String priceMode,
            @Schema(description = "草稿供应价，CNY分；REALTIME为空") Long unitPriceCents,
            @Schema(description = "折扣基点，10000=100%") Integer discountBp,
            @Schema(description = "该Offer供货模式") String offerSupplyMode,
            @Schema(description = "草稿供货上限") Long maxQuantity,
            @Schema(description = "草稿最低起购量") Long minQuantity,
            @Schema(description = "生命周期：PUBLISHED已发布，SUSPENDED暂停") String status,
            @Schema(description = OfferFulfillmentScore.STABILITY_COMMENT) Integer stabilityScore,
            @Schema(description = OfferFulfillmentScore.TIMELINESS_COMMENT) Integer timelinessScore,
            @Schema(description = "供应商侧商品编码（f1 身份行 external_sku_code），上架时冻结进版本行") String externalSkuCode,
            @Schema(description = "当前已发布履约版本ID；为空表示尚未配置履约") Long currentVersionId,
            @Schema(description = "Offer 聚合乐观锁版本号，上架须回传作为 expectedOfferVersion") Long offerVersion,
            @Schema(description = "i-b 履约就绪度三态：NOT_CONFIGURED未配置，STALE过期，READY就绪") String fulfillmentStatus) {
    }
}
