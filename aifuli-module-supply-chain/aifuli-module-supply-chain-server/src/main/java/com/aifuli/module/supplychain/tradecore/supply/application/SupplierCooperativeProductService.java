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
import org.springframework.transaction.annotation.Isolation;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Collection;
import java.util.TreeSet;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_OFFER_OUT_OF_SCOPE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_OFFER_SUGGESTED_SALE_PRICE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_SCOPE_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_STATUS_CONFLICT;

/**
 * 合作商品身份管理：新增默认下架，商业编辑保存待发布草稿；正式上架委派不可变履约版本发布。
 * 设计见 {@code docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#1-核心业务规则}。
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
    private final SupplierOfferPublicationService publicationService;
    private final SupplierOfferEligibilityService eligibilityService;

    /**
     * 批量新增合作商品，默认下架（{@link OfferStatus#SUSPENDED}）。供货上限为空时继承命中的供货配置；
     * 最低起购量为空时固定为 1，不读取供货配置中的预留字段。
     * 设计见 {@code docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#1-核心业务规则}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public List<String> createPublished(String supplierCode, List<DraftLine> lines, Long operatorId) {
        SupplierLifecycleTransactions.requireReadCommitted();
        log.info("[offerCreate][新增下架合作商品] supplierCode={}, skuCount={}, operatorId={}",
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
            SupplierOfferDO offer = insertPublished(supplier, scopes, line, operatorId, now, coverage);
            offerNos.add(offer.getOfferNo());
        }
        // 新增关系尚未生效，不生成正式定价版本；首次上架编排才同步重算。
        // 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#1-核心业务规则。
        return offerNos;
    }

    /**
     * 兼容 {@code batch-draft}：委托 {@link #createPublished}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public List<String> createDrafts(String supplierCode, List<DraftLine> lines, Long operatorId) {
        SupplierLifecycleTransactions.requireReadCommitted();
        return createPublished(supplierCode, lines, operatorId);
    }

    /**
     * 仅允许 {@code PUBLISHED}↔{@code SUSPENDED}；{@code RETIRED} 走 {@link #retire}。
     * 上架（PUBLISHED）强制委托正式发布编排；下架（SUSPENDED）更新状态并发布定价依赖事件。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void changeOfferStatus(String supplierCode, String offerNo, OfferStatus target, Long operatorId) {
        SupplierLifecycleTransactions.requireReadCommitted();
        log.info("[offerStatusChange][合作商品状态变更请求] supplierCode={}, offerNo={}, target={}, operatorId={}",
                supplierCode, offerNo, target, operatorId);
        if (target != OfferStatus.PUBLISHED && target != OfferStatus.SUSPENDED) {
            throw exception(SUPPLIER_STATUS_CONFLICT);
        }
        if (target == OfferStatus.PUBLISHED) {
            // PATCH 上架委派正式发布编排；outbox trace_id 非空，无链路上下文时按管理端同一约定生成。
            publicationService.listOnShelf(new SupplierOfferPublicationService.ListOnShelfCommand(
                    supplierCode, offerNo, null, operatorId, currentTraceId(), null, null));
            return;
        }
        SupplierDO supplier = supplierMapper.selectByCodeForUpdate(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
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
     * 身份列保存不触发正式定价；发布变更后才切换冻结成本，禁止篡改已有 Offer 的 platformSkuId。
     * 设计见 {@code docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#1-核心业务规则}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void updateCommercial(String supplierCode, String offerNo, DraftLine line, Long operatorId) {
        SupplierLifecycleTransactions.requireReadCommitted();
        log.info("[offerCommercialEdit][保存合作商品草稿] supplierCode={}, offerNo={}, platformSkuCode={}, operatorId={}",
                supplierCode, offerNo, line == null ? null : line.platformSkuCode(), operatorId);
        if (offerVersionMapper == null) {
            throw new IllegalStateException("offerVersionMapper is required");
        }
        requireExternalSkuCode(line.externalSkuCode());
        SupplierDO supplier = supplierMapper.selectByCodeForUpdate(supplierCode);
        if (supplier == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        SupplierOfferDO offer = requireLiveOffer(supplierCode, offerNo);
        List<SupplierSupplyScopeDO> scopes = scopeMapper.selectBySupplierId(supplier.getId());
        ResolvedOffer resolved = resolveLine(scopes, line, categoryCoverageService.loadResolver());
        if (!offer.getPlatformSkuId().equals(resolved.sku().getId())) {
            throw new IllegalArgumentException("Cannot change platform SKU of an existing offer");
        }
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
        // 商业字段仅保存待发布变更，旧冻结版本仍生效，不能触发正式定价重算。
        // 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#1-核心业务规则。
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void retire(String supplierCode, String offerNo, Long operatorId) {
        SupplierLifecycleTransactions.requireReadCommitted();
        log.info("[offerRetire][移除合作商品请求] supplierCode={}, offerNo={}, operatorId={}", supplierCode, offerNo, operatorId);
        if (supplierMapper.selectByCodeForUpdate(supplierCode) == null) {
            throw exception(SUPPLIER_NOT_FOUND);
        }
        SupplierOfferDO offer = requireLiveOffer(supplierCode, offerNo);
        retireOffers(List.of(offer), operatorId);
    }

    /**
     * 配置保存持有供应商锁后调用：只看旧配置归属，不能用新范围为原关系“续命”。
     * 有效旧引用优先；历史悬空引用按旧范围唯一重匹配。歧义涉及编辑行时拒绝，不任意选择或漏退役。
     * 所有目标先退役，再按SKU升序同步重算；本方法不另开事务，任何失败由保存入口整体回滚。
     * 设计见 docs/superpowers/specs/2026-10-20-supply-scope-change-retirement-design.md#3-原配置对应商品的判定。
     */
    void retireForScopeChanges(SupplierDO supplier, List<SupplierSupplyScopeDO> oldScopes,
                               Set<Long> changedScopeIds, Long operatorId) {
        if (changedScopeIds.isEmpty()) {
            return;
        }
        var coverage = categoryCoverageService.loadResolver();
        Map<Long, SupplierSupplyScopeDO> byId = new HashMap<>();
        oldScopes.forEach(scope -> byId.put(scope.getId(), scope));
        List<SupplierOfferDO> live = offerMapper.selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplier.getId())
                .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode()));
        Set<Long> danglingSkuIds = new HashSet<>();
        for (SupplierOfferDO offer : live) {
            if (!byId.containsKey(offer.getSupplyScopeId()) && offer.getPlatformSkuId() != null) {
                danglingSkuIds.add(offer.getPlatformSkuId());
            }
        }
        Map<Long, PlatformSkuDO> skus = new HashMap<>();
        if (!danglingSkuIds.isEmpty()) {
            skuMapper.selectBatchIds(danglingSkuIds).forEach(sku -> skus.put(sku.getId(), sku));
        }
        Set<Long> spuIds = new HashSet<>();
        skus.values().forEach(sku -> { if (sku.getSpuId() != null) spuIds.add(sku.getSpuId()); });
        Map<Long, PlatformSpuDO> spus = new HashMap<>();
        if (!spuIds.isEmpty()) {
            spuMapper.selectBatchIds(spuIds).forEach(spu -> spus.put(spu.getId(), spu));
        }
        List<SupplierOfferDO> retiring = new ArrayList<>();
        for (SupplierOfferDO offer : live) {
            Long originalId = offer.getSupplyScopeId();
            if (!byId.containsKey(originalId)) {
                PlatformSkuDO sku = skus.get(offer.getPlatformSkuId());
                PlatformSpuDO spu = sku == null ? null : spus.get(sku.getSpuId());
                var match = SupplierSupplyScopeMatcher.match(oldScopes, spu, coverage);
                if (match.conflict()) {
                    // 仅拒绝归属歧义可能影响本次编辑的商品，无关类型不阻断保存。
                    var changed = oldScopes.stream().filter(scope -> changedScopeIds.contains(scope.getId())).toList();
                    if (changed.stream().anyMatch(scope -> SupplierSupplyScopeMatcher.match(List.of(scope), spu, coverage).matched())) {
                        throw exception(SUPPLIER_SCOPE_CONFLICT);
                    }
                }
                originalId = match.scope() == null ? null : match.scope().getId();
            }
            if (changedScopeIds.contains(originalId)) {
                retiring.add(offer);
            }
        }
        retireOffers(retiring, operatorId);
    }

    /** 手动/配置变更共用移除语义；保留冻结历史，事件失败不得吞掉。 */
    private void retireOffers(Collection<SupplierOfferDO> offers, Long operatorId) {
        if (offers.isEmpty()) return;
        LocalDateTime now = databaseClock.now();
        Set<Long> skuIds = new TreeSet<>();
        for (SupplierOfferDO offer : offers) {
            offer.setStatus(OfferStatus.RETIRED.getCode());
            offer.setUpdatedBy(operatorId);
            offer.setUpdatedAt(now);
            offerMapper.updateById(offer);
            skuIds.add(offer.getPlatformSkuId());
        }
        skuIds.forEach(id -> eventPublisher.publishEvent(new PricingDependencyChangedEvent(id)));
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
        List<SupplierSupplyScopeDO> scopes = scopeMapper.selectBySupplierId(supplier.getId());
        ProductCategoryCoverageService.CoverageResolver coverage = categoryCoverageService.loadResolver();
        LambdaQueryWrapper<SupplierOfferDO> wrapper = new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplier.getId())
                .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode())
                .orderByDesc(SupplierOfferDO::getCreatedAt);
        if (sellableCategory != null || productType != null || productClassification != null) {
            Set<Long> skuIds = matchingConfiguredSkuIds(supplier.getId(), scopes, coverage,
                    sellableCategory, productType, productClassification);
            if (skuIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L);
            }
            wrapper.in(SupplierOfferDO::getPlatformSkuId, skuIds);
        }
        PageParam pageParam = new PageParam();
        pageParam.setPageNo(pageNo == null ? DEFAULT_PAGE_NO : pageNo);
        pageParam.setPageSize(pageSize == null ? DEFAULT_PAGE_SIZE : pageSize);
        PageResult<SupplierOfferDO> page = offerMapper.selectPage(pageParam, wrapper);
        List<OfferListItem> items = new ArrayList<>();
        for (SupplierOfferDO offer : page.getList()) {
            items.add(toListItem(offer, supplier, scopes, coverage));
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

    private SupplierOfferDO insertPublished(SupplierDO supplier, List<SupplierSupplyScopeDO> scopes, DraftLine line,
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
                .status(OfferStatus.SUSPENDED.getCode())
                .version(0L)
                .createdBy(operatorId)
                .createdAt(now)
                .updatedBy(operatorId)
                .updatedAt(now)
                .build();
        offerMapper.insert(offer);
        return offer;
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

    private static String currentTraceId() {
        String traceId = com.aifuli.framework.common.util.monitor.TracerUtils.getTraceId();
        return traceId == null || traceId.isBlank() ? "admin-" + java.util.UUID.randomUUID() : traceId;
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

    /**
     * 配置删除重建后，以已有Offer关联商品重新匹配当前范围，不依赖旧supply_scope_id。
     * 先用全部配置决定最具体唯一匹配，再应用显式检索；在数据库分页前筛SKU，保证total与翻页一致。
     * 不检查商品启用状态，已下架商品仍须可管理；本方法只读，不重新绑定或继承商业条款。
     * 设计见 docs/superpowers/specs/2026-10-20-supplier-supply-scope-matching-design.md#43-已配置合作商品列表重匹配2026-10-20追加用户批准最小读侧修复。
     */
    private Set<Long> matchingConfiguredSkuIds(Long supplierId, List<SupplierSupplyScopeDO> scopes,
                                              ProductCategoryCoverageService.CoverageResolver coverage,
                                              String sellableCategory, String productType, String productClassification) {
        if (scopes.isEmpty()) {
            return Set.of();
        }
        Set<Long> skuIds = new HashSet<>();
        for (SupplierOfferDO offer : offerMapper.selectList(new LambdaQueryWrapper<SupplierOfferDO>()
                .eq(SupplierOfferDO::getSupplierId, supplierId)
                .ne(SupplierOfferDO::getStatus, OfferStatus.RETIRED.getCode()))) {
            if (offer.getPlatformSkuId() != null) {
                skuIds.add(offer.getPlatformSkuId());
            }
        }
        if (skuIds.isEmpty()) {
            return Set.of();
        }
        List<PlatformSkuDO> skus = skuMapper.selectBatchIds(skuIds);
        Set<Long> spuIds = new HashSet<>();
        for (PlatformSkuDO sku : skus) {
            if (sku.getSpuId() != null) {
                spuIds.add(sku.getSpuId());
            }
        }
        if (spuIds.isEmpty()) {
            return Set.of();
        }
        Map<Long, PlatformSpuDO> spus = new HashMap<>();
        for (PlatformSpuDO spu : spuMapper.selectBatchIds(spuIds)) {
            spus.put(spu.getId(), spu);
        }
        Set<Long> matchedSkuIds = new HashSet<>();
        for (PlatformSkuDO sku : skus) {
            SupplierSupplyScopeDO scope = SupplierSupplyScopeMatcher.match(scopes, spus.get(sku.getSpuId()), coverage).scope();
            if (scope != null
                    && (sellableCategory == null || sellableCategory.equals(scope.getSellableCategory()))
                    && (productType == null || productType.equals(scope.getProductType()))
                    && (productClassification == null || productClassification.equals(scope.getProductClassification()))) {
                matchedSkuIds.add(sku.getId());
            }
        }
        return matchedSkuIds;
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

    /** 当前配置匹配仅用于字段投影；无筛选的旧关系不可因范围收窄或歧义被隐式删除。 */
    private OfferListItem toListItem(SupplierOfferDO offer, SupplierDO supplier,
                                     List<SupplierSupplyScopeDO> scopes,
                                     ProductCategoryCoverageService.CoverageResolver coverage) {
        PlatformSkuDO sku = skuMapper.selectById(offer.getPlatformSkuId());
        PlatformSpuDO spu = sku == null ? null : spuMapper.selectById(sku.getSpuId());
        SupplierSupplyScopeDO scope = SupplierSupplyScopeMatcher.match(scopes, spu, coverage).scope();
        if (scope == null && offer.getSupplyScopeId() != null) {
            scope = scopeMapper.selectById(offer.getSupplyScopeId());
        }
        SupplierOfferVersionDO currentVersion = offer.getCurrentVersionId() == null
                ? null : offerVersionMapper.selectById(offer.getCurrentVersionId());
        SupplierOfferEligibilityService.PublishEligibility eligibility =
                eligibilityService != null && supplier != null ? eligibilityService.checkPublishEligibility(offer, supplier, sku, spu)
                        : null;
        if (eligibility == null) {
            eligibility = SupplierOfferEligibilityService.PublishEligibility.allowed();
        }
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
                computeFulfillmentStatus(offer, currentVersion).code(),
                eligibility.canPublish(),
                eligibility.publishBlockedReason());
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
     * 与选品入口共用类型/分类归属匹配，不比较可售品类。最深层多配置命中明确返回配置冲突，
     * 避免任取配置导致供货上限与supply_scope_id漂移；无匹配仍返回范围外错误。
     * 设计见 {@code docs/superpowers/specs/2026-10-20-supplier-supply-scope-matching-design.md#2-唯一匹配算法}。
     */
    private SupplierSupplyScopeDO matchScope(List<SupplierSupplyScopeDO> scopes, PlatformSpuDO spu,
                                             ProductCategoryCoverageService.CoverageResolver coverage) {
        SupplierSupplyScopeMatcher.Match match = SupplierSupplyScopeMatcher.match(scopes, spu, coverage);
        if (match.conflict()) {
            throw exception(SUPPLIER_SCOPE_CONFLICT);
        }
        if (!match.matched()) {
            throw exception(SUPPLIER_OFFER_OUT_OF_SCOPE);
        }
        return match.scope();
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
            @Schema(description = "履约发布投影：NOT_CONFIGURED未发布版本，STALE待发布变更，READY已发布且无待发布变更") String fulfillmentStatus,
            @Schema(description = "是否可上架/发布变更") Boolean canPublish,
            @Schema(description = "不可上架/发布变更时的中文原因提示，可发布时为空") String publishBlockedReason) {
    }
}
