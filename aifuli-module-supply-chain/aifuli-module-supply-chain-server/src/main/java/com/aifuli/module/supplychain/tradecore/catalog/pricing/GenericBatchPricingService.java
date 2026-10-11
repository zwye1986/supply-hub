package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_DEFAULT_PRICING_MISSING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SKU_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SPU_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SKU_PRICING_NOT_ALLOWED;

/**
 * 商品默认、分销商与项目三层通用批量定价编排。写入前先完成整批试算，随后发布不可变版本并逐层切换当前指针。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#6-管理端接口}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenericBatchPricingService {
    private static final String TX = "supplyChainTransactionManager";
    /** 单次批量定价允许的最大 SKU 数，与设计不变量及请求 {@code @Size(max)} 一致。 */
    public static final int MAX_BATCH = 200;

    private final BuyerMapper buyerMapper;
    private final BuyerProductMapper buyerProductMapper;
    private final BuyerProjectMapper projectMapper;
    private final ProjectListingMapper listingMapper;
    private final PlatformSkuMapper skuMapper;
    private final PlatformSpuMapper spuMapper;
    private final PlatformSkuPricingMapper platformPricingMapper;
    private final PlatformSkuPricingVersionMapper platformVersionMapper;
    private final BuyerSkuPricingMapper buyerPricingMapper;
    private final BuyerSkuPricingVersionMapper buyerVersionMapper;
    private final ProjectSkuPricingMapper projectPricingMapper;
    private final ProjectSkuPricingVersionMapper projectVersionMapper;
    private final SupplierOfferMapper offerMapper;
    private final SupplierOfferVersionMapper offerVersionMapper;
    private final SupplierMapper supplierMapper;
    private final TaxRateDictionaryAdapter taxDictionary;
    private final SupplyChainDatabaseClock clock;
    private final ObjectMapper objectMapper;
    private final GenericPricingCalculator calculator = new GenericPricingCalculator();

    /**
     * 查询平台 SKU 当前默认定价完整快照。未定价是正常业务状态，返回身份状态而不是抛错；
     * 已发布版本严格按当前指针读取，不调用税率字典、不重新计算，保证编辑回显与当前生效版本一致。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#6-管理端接口}。
     */
    @Transactional(transactionManager = TX, readOnly = true)
    public PlatformPricingDetail getPlatformPricing(String skuCode) {
        log.info("查询商品默认定价 skuCode={}", skuCode);
        PlatformSkuDO sku = skuMapper.selectByCode(skuCode);
        if (sku == null) {
            throw exception(PLATFORM_SKU_NOT_FOUND);
        }
        PlatformSkuPricingDO identity = platformPricingMapper.selectById(sku.getId());
        if (identity == null || identity.getCurrentVersionId() == null) {
            String status = identity == null ? PricingStatus.UNPRICED.name() : identity.getPricingStatus();
            return PlatformPricingDetail.unpriced(sku.getSkuCode(), status,
                    identity == null ? null : identity.getUnavailableReason());
        }
        PlatformSkuPricingVersionDO version = platformVersionMapper.selectById(identity.getCurrentVersionId());
        if (version == null || !sku.getId().equals(version.getPlatformSkuId())) {
            throw new IllegalStateException("platform SKU pricing current version is missing or mismatched");
        }
        PricingConfig config = configFrom(version);
        return new PlatformPricingDetail(sku.getSkuCode(), version.getId(), version.getVersionNo(),
                identity.getPricingStatus(), identity.getUnavailableReason(),
                config.distributionRule(), config.salesRule(), config.serviceFeeRule(),
                config.hideRule(), config.taxMetadata(), version.getMarketPriceCents(),
                version.getSuggestedSalePriceCents(), version.getSupplyPriceCents(),
                version.getDistributionPriceCents(), version.getSalesPriceCents(),
                version.getDistributionMarginPercent(), version.getEffectiveHideThresholdPercent(),
                version.getCurrency(), version.getPublishedBy(), version.getPublishedAt());
    }

    @Transactional(transactionManager = TX, readOnly = true)
    public List<PricingPreview> previewPlatform(List<String> skuCodes, PricingConfig config) {
        List<String> codes = normalize(skuCodes);
        log.info("商品默认批量定价试算 skuCount={}", codes.size());
        TaxMetadata tax = taxDictionary.requireEnabled(config.taxMetadata().taxIncluded(),
                config.taxMetadata().taxRateCode());
        PricingConfig normalized = withTax(config, tax);
        List<PricingPreview> result = new ArrayList<>();
        LocalDateTime now = clock.now();
        for (String code : codes) {
            PlatformSkuDO sku = requireSku(code, false);
            PriceDependencies dependencies = dependencies(sku, now);
            result.add(toPreview(code, code, dependencies, calculator.calculate(normalized, dependencies)));
        }
        return result;
    }

    @Transactional(transactionManager = TX, rollbackFor = Exception.class)
    public void savePlatform(List<String> skuCodes, PricingConfig config, Long operatorId) {
        List<String> codes = normalize(skuCodes);
        log.info("商品默认批量定价保存 skuCount={} operatorId={}", codes.size(), operatorId);
        TaxMetadata tax = taxDictionary.requireEnabled(config.taxMetadata().taxIncluded(),
                config.taxMetadata().taxRateCode());
        PricingConfig normalized = withTax(config, tax);
        LocalDateTime now = clock.now();
        // 先统一取得子主键升序锁，再按原请求顺序校验/发布，避免与整商品同步上架形成反序锁环。
        // 设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#51-与整商品同步上架的多-sku-锁顺序2026-10-11-复审修正。
        Map<String, PlatformSkuDO> lockedSkus = PlatformSkuBatchLock.lockByCodes(skuMapper, codes);
        List<CalculatedPlatform> calculated = new ArrayList<>();
        for (String code : codes) {
            PlatformSkuDO sku = requireSku(code, lockedSkus.get(code));
            PriceDependencies dependencies = dependencies(sku, now);
            calculated.add(new CalculatedPlatform(sku, dependencies,
                    calculator.calculate(normalized, dependencies)));
        }
        for (CalculatedPlatform item : calculated) {
            PlatformSkuPricingVersionDO version = publishPlatform(item.sku(), normalized,
                    item.dependencies(), item.result(), operatorId, now);
            rebuildBuyers(item.sku(), version, operatorId, now);
        }
    }

    /**
     * 为复合保存产生的新 SKU 或历史 UNPRICED SKU 发布首个系统默认版本。监听器在商品事务内同步执行，
     * 并在锁定定价身份后再次判断当前指针，防止覆盖并发完成的人工定价。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态}。
     */
    @EventListener
    @Transactional(transactionManager = TX, rollbackFor = Exception.class)
    public void initializePlatformDefaults(PlatformSkuPricingInitializationEvent event) {
        List<String> skuCodes = event.platformSkuCodes().stream()
                .filter(code -> code != null && !code.isBlank()).map(String::trim).distinct().toList();
        if (skuCodes.isEmpty()) return;
        log.info("复合保存初始化平台默认定价 skuCount={} operatorId={}", skuCodes.size(), event.operatorId());
        Map<String, PlatformSkuDO> lockedSkus = PlatformSkuBatchLock.lockByCodes(skuMapper, skuCodes);
        List<PlatformSkuDO> unpricedSkus = new ArrayList<>();
        for (String skuCode : skuCodes) {
            // 复合保存允许创建草稿 SKU；默认定价是主数据完整性的一部分，不能沿用人工定价入口的“仅启用 SKU”限制。
            PlatformSkuDO sku = lockedSkus.get(skuCode);
            if (sku == null) {
                throw exception(PLATFORM_SKU_NOT_FOUND);
            }
            PlatformSkuPricingDO identity = platformPricingMapper.selectBySkuIdForUpdate(sku.getId());
            if (identity != null && identity.getCurrentVersionId() != null) {
                log.info("跳过已有版本的平台默认定价初始化 skuCode={} currentVersionId={}",
                        sku.getSkuCode(), identity.getCurrentVersionId());
            } else {
                unpricedSkus.add(sku);
            }
        }
        if (unpricedSkus.isEmpty()) return;
        PricingConfig defaults = PlatformDefaultPricingPolicy.create();
        TaxMetadata tax = taxDictionary.requireEnabled(defaults.taxMetadata().taxIncluded(),
                defaults.taxMetadata().taxRateCode());
        PricingConfig normalized = withTax(defaults, tax);
        LocalDateTime now = clock.now();
        for (PlatformSkuDO sku : unpricedSkus) {
            PriceDependencies dependencies = dependencies(sku, now);
            PricingResult result = calculator.calculate(normalized, dependencies);
            PlatformSkuPricingVersionDO version = publishPlatform(sku, normalized, dependencies, result,
                    event.operatorId(), now);
            rebuildBuyers(sku, version, event.operatorId(), now);
        }
    }

    @Transactional(transactionManager = TX, readOnly = true)
    public List<PricingPreview> previewBuyer(String buyerCode, List<String> productCodes, ProjectOverrides overrides) {
        List<String> codes = normalize(productCodes);
        log.info("通用批量定价试算 buyerCode={} skuCount={}", buyerCode, codes.size());
        BuyerDO buyer = requireBuyer(buyerCode, false);
        ProjectOverrides normalized = normalizeTax(overrides);
        List<PricingPreview> result = new ArrayList<>();
        for (String code : codes) {
            ProductContext item = requireProduct(buyer.getId(), code, false);
            PlatformConfigSnapshot parent = requirePlatformConfig(item.sku().getId());
            PricingConfig effective = ProjectPricingConfigMerger.merge(parent.config(), normalized);
            PriceDependencies dependencies = dependencies(item.sku(), clock.now());
            result.add(toPreview(code, item.sku().getSkuCode(), dependencies,
                    calculator.calculate(effective, dependencies)));
        }
        return result;
    }

    @Transactional(transactionManager = TX, rollbackFor = Exception.class)
    public void saveBuyer(String buyerCode, List<String> productCodes, ProjectOverrides overrides, Long operatorId) {
        List<String> codes = normalize(productCodes);
        log.info("通用批量定价保存 buyerCode={} skuCount={} operatorId={}", buyerCode, codes.size(), operatorId);
        BuyerDO buyer = requireBuyer(buyerCode, true);
        ProjectOverrides normalized = normalizeTax(overrides);
        LocalDateTime now = clock.now();
        List<CalculatedProduct> calculated = new ArrayList<>();
        for (String code : codes) {
            ProductContext item = requireProduct(buyer.getId(), code, true);
            PlatformConfigSnapshot parent = requirePlatformConfig(item.sku().getId());
            PricingConfig effective = ProjectPricingConfigMerger.merge(parent.config(), normalized);
            PriceDependencies dependencies = dependencies(item.sku(), now);
            calculated.add(new CalculatedProduct(item, parent.version(), normalized, effective,
                    dependencies, calculator.calculate(effective, dependencies)));
        }
        for (CalculatedProduct item : calculated) {
            BuyerSkuPricingVersionDO buyerVersion = publishBuyer(item.context().product(), item.platformVersion(),
                    item.overrides(), item.effective(),
                    item.dependencies(), item.result(), operatorId, now);
            rebuildInheritedProjects(item.context(), buyerVersion, operatorId, now);
        }
    }

    @Transactional(transactionManager = TX, readOnly = true)
    public List<PricingPreview> previewProject(String projectCode, List<String> listingCodes,
                                               ProjectOverrides overrides) {
        List<String> codes = normalize(listingCodes);
        log.info("项目通用批量定价试算 projectCode={} skuCount={}", projectCode, codes.size());
        BuyerProjectDO project = requireProject(projectCode, false);
        ProjectOverrides normalized = normalizeTax(overrides);
        List<PricingPreview> result = new ArrayList<>();
        for (String code : codes) {
            ListingContext context = requireListing(project, code, false);
            BuyerConfigSnapshot buyerConfig = requireBuyerConfig(context.product().getId());
            PricingConfig effective = ProjectPricingConfigMerger.merge(buyerConfig.config(), normalized);
            PriceDependencies dependencies = dependencies(context.sku(), clock.now());
            result.add(toPreview(code, context.sku().getSkuCode(), dependencies,
                    calculator.calculate(effective, dependencies)));
        }
        return result;
    }

    @Transactional(transactionManager = TX, rollbackFor = Exception.class)
    public void saveProject(String projectCode, List<String> listingCodes,
                            ProjectOverrides overrides, Long operatorId) {
        List<String> codes = normalize(listingCodes);
        log.info("项目通用批量定价保存 projectCode={} skuCount={} operatorId={}", projectCode, codes.size(), operatorId);
        BuyerProjectDO project = requireProject(projectCode, true);
        ProjectOverrides normalized = normalizeTax(overrides);
        LocalDateTime now = clock.now();
        List<CalculatedListing> calculated = new ArrayList<>();
        for (String code : codes) {
            ListingContext context = requireListing(project, code, true);
            BuyerConfigSnapshot buyerConfig = requireBuyerConfig(context.product().getId());
            PricingConfig effective = ProjectPricingConfigMerger.merge(buyerConfig.config(), normalized);
            PriceDependencies dependencies = dependencies(context.sku(), now);
            calculated.add(new CalculatedListing(context, buyerConfig.version(), effective, dependencies,
                    calculator.calculate(effective, dependencies)));
        }
        for (CalculatedListing item : calculated) {
            publishProject(item.context().listing(), item.buyerVersion(), normalized, item.effective(),
                    item.dependencies(), item.result(), operatorId, now);
        }
    }

    /**
     * 为尚未做项目覆盖的商品建立继承快照，并在供货有效期跨界后按需补偿重算。
     * 报价入口调用本方法，因此不会把过期供货价继续签发给新订单。
     */
    @Transactional(transactionManager = TX, rollbackFor = Exception.class)
    public CurrentProjectPricing ensureCurrentProjectVersion(String projectCode, String platformSkuCode) {
        BuyerProjectDO project = requireProject(projectCode, true);
        PlatformSkuDO sku = skuMapper.selectByCode(platformSkuCode);
        PlatformSpuDO spu = sku == null ? null : spuMapper.selectById(sku.getSpuId());
        if (sku == null || sku.getStatus() != CatalogEntityStatus.ACTIVE.getCode()
                || spu == null || spu.getStatus() != CatalogEntityStatus.ACTIVE.getCode()) {
            throw new ProductNotSaleableException("platform SKU is not currently saleable");
        }
        BuyerProductDO product = buyerProductMapper.selectByBuyerAndSku(project.getBuyerId(), sku.getId());
        if (product == null || product.getStatus() != BuyerProductStatus.ACTIVE.getCode()) {
            throw new ProductNotSaleableException("buyer SKU is not in catalog");
        }
        ProjectListingDO listing = listingMapper.selectByProjectAndBuyerProduct(project.getId(), product.getId());
        if (listing == null || listing.getStatus() != ProjectListingStatus.AVAILABLE.getCode()) {
            throw new ProductNotSaleableException("project SKU is not in catalog");
        }
        BuyerConfigSnapshot buyer = requireBuyerConfig(product.getId(), true);
        ProjectSkuPricingDO identity = projectPricingMapper.selectByListingIdForUpdate(listing.getId());
        ProjectSkuPricingVersionDO current = identity == null || identity.getCurrentVersionId() == null ? null
                : projectVersionMapper.selectById(identity.getCurrentVersionId());
        // 已发布的覆盖版本引用损坏必须停止，不能按“尚未配置”重建并覆盖原规则；HTTP/RPC共享该不变量。
        // 设计见 docs/superpowers/specs/2026-10-03-standard-project-direct-order-price-freeze-design.md#3-定价身份与当前准入。
        if (identity != null && identity.getCurrentVersionId() != null
                && (current == null || !Objects.equals(identity.getId(), current.getPricingId()))) {
            throw new IllegalStateException("project pricing current version is missing or mismatched");
        }
        ProjectOverrides overrides = current == null ? ProjectOverrides.allInherited()
                : fromJson(current.getOverrideJson(), ProjectOverrides.class);
        PricingConfig effective = ProjectPricingConfigMerger.merge(buyer.config(), overrides);
        LocalDateTime now = clock.now();
        PriceDependencies dependencies = dependencies(sku, now);
        PricingResult result = calculator.calculate(effective, dependencies);
        if (current != null && PricingStatus.READY.name().equals(identity.getPricingStatus())
                && Objects.equals(current.getBuyerPricingVersionId(), buyer.version().getId())
                && Objects.equals(current.getMarketPriceCents(), dependencies.marketPriceCents())
                && Objects.equals(current.getSuggestedSalePriceCents(), dependencies.suggestedSalePriceCents())
                && Objects.equals(current.getSupplyPriceCents(), dependencies.supplyPriceCents())) {
            return new CurrentProjectPricing(current, listing.getId(), sku.getId(), sku.getSkuCode());
        }
        publishProject(listing, buyer.version(), overrides, effective, dependencies, result, null, now);
        ProjectSkuPricingDO refreshed = projectPricingMapper.selectByListingId(listing.getId());
        return new CurrentProjectPricing(projectVersionMapper.selectById(refreshed.getCurrentVersionId()),
                listing.getId(), sku.getId(), sku.getSkuCode());
    }

    /**
     * 商品基础价或供应商品当前版本变化后的同步重算入口。监听失败必须向上传播并回滚上游主数据事务。
     */
    @EventListener
    @Transactional(transactionManager = TX, rollbackFor = Exception.class)
    public void recalculatePlatformSku(PricingDependencyChangedEvent event) {
        Long platformSkuId = event.platformSkuId();
        PlatformSkuDO sku = skuMapper.selectById(platformSkuId);
        if (sku == null) return;
        LocalDateTime now = clock.now();
        PlatformSkuPricingDO identity = platformPricingMapper.selectBySkuIdForUpdate(platformSkuId);
        if (identity == null || identity.getCurrentVersionId() == null) return;
        PlatformSkuPricingVersionDO current = platformVersionMapper.selectById(identity.getCurrentVersionId());
        if (current == null) return;
        identity.setPricingStatus(PricingStatus.RECALCULATING.name());
        identity.setUnavailableReason(PricingUnavailableReason.PRICE_DEPENDENCY_CHANGED.name());
        identity.setUpdatedAt(now);
        platformPricingMapper.updateById(identity);
        PricingConfig config = configFrom(current);
        PriceDependencies dependencies = dependencies(sku, now);
        PricingResult result = calculator.calculate(config, dependencies);
        PlatformSkuPricingVersionDO next = publishPlatform(sku, config, dependencies, result, null, now);
        rebuildBuyers(sku, next, null, now);
    }

    /**
     * 分销商品创建后按商品默认层同步生成继承版本。上游尚未定价时保留创建方写入的 UNPRICED 身份；
     * 监听失败向上传播并回滚目录关联，避免出现“目录已生效但继承版本缺失”的半完成状态。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态}。
     */
    @EventListener
    @Transactional(transactionManager = TX, rollbackFor = Exception.class)
    public void initializeBuyerProduct(BuyerProductPricingInitializationEvent event) {
        BuyerProductDO product = buyerProductMapper.selectById(event.buyerProductId());
        if (product == null || product.getStatus() != BuyerProductStatus.ACTIVE.getCode()) return;
        PlatformSkuDO sku = skuMapper.selectById(product.getPlatformSkuId());
        if (sku == null) return;
        PlatformSkuPricingDO platformIdentity = platformPricingMapper.selectById(sku.getId());
        if (platformIdentity == null || platformIdentity.getCurrentVersionId() == null) return;
        PlatformSkuPricingVersionDO platformVersion = platformVersionMapper.selectById(
                platformIdentity.getCurrentVersionId());
        if (platformVersion == null) return;
        BuyerSkuPricingDO buyerIdentity = buyerPricingMapper.selectByBuyerProductIdForUpdate(product.getId());
        BuyerSkuPricingVersionDO current = buyerIdentity == null || buyerIdentity.getCurrentVersionId() == null
                ? null : buyerVersionMapper.selectById(buyerIdentity.getCurrentVersionId());
        ProjectOverrides overrides = current == null ? ProjectOverrides.allInherited()
                : fromJson(current.getOverrideJson(), ProjectOverrides.class);
        PricingConfig effective = ProjectPricingConfigMerger.merge(configFrom(platformVersion), overrides);
        LocalDateTime now = clock.now();
        PriceDependencies dependencies = dependencies(sku, now);
        PricingResult result = calculator.calculate(effective, dependencies);
        BuyerSkuPricingVersionDO next = publishBuyer(product, platformVersion, overrides, effective,
                dependencies, result, event.operatorId(), now);
        rebuildInheritedProjects(new ProductContext(product, sku), next, event.operatorId(), now);
    }

    /**
     * 项目商品创建后按分销商层同步生成全继承版本。上游尚未定价时保留 UNPRICED；已存在项目覆盖的复活场景
     * 继续沿用原覆盖规则并生成新版本。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态}。
     */
    @EventListener
    @Transactional(transactionManager = TX, rollbackFor = Exception.class)
    public void initializeProjectListing(ProjectListingPricingInitializationEvent event) {
        ProjectListingDO listing = listingMapper.selectById(event.listingId());
        if (listing == null || listing.getStatus() != ProjectListingStatus.AVAILABLE.getCode()) return;
        BuyerProductDO product = buyerProductMapper.selectById(listing.getBuyerProductId());
        if (product == null || product.getStatus() != BuyerProductStatus.ACTIVE.getCode()) return;
        BuyerSkuPricingDO buyerIdentity = buyerPricingMapper.selectByBuyerProductId(product.getId());
        if (buyerIdentity == null || buyerIdentity.getCurrentVersionId() == null) return;
        BuyerSkuPricingVersionDO buyerVersion = buyerVersionMapper.selectById(buyerIdentity.getCurrentVersionId());
        if (buyerVersion == null) return;
        PlatformSkuDO sku = skuMapper.selectById(product.getPlatformSkuId());
        if (sku == null) return;
        ProjectSkuPricingDO projectIdentity = projectPricingMapper.selectByListingIdForUpdate(listing.getId());
        ProjectSkuPricingVersionDO current = projectIdentity == null || projectIdentity.getCurrentVersionId() == null
                ? null : projectVersionMapper.selectById(projectIdentity.getCurrentVersionId());
        ProjectOverrides overrides = current == null ? ProjectOverrides.allInherited()
                : fromJson(current.getOverrideJson(), ProjectOverrides.class);
        PricingConfig effective = ProjectPricingConfigMerger.merge(configFrom(buyerVersion), overrides);
        LocalDateTime now = clock.now();
        PriceDependencies dependencies = dependencies(sku, now);
        PricingResult result = calculator.calculate(effective, dependencies);
        publishProject(listing, buyerVersion, overrides, effective, dependencies, result, event.operatorId(), now);
    }

    private PlatformSkuPricingVersionDO publishPlatform(PlatformSkuDO sku, PricingConfig config,
                                                        PriceDependencies deps, PricingResult result,
                                                        Long operatorId, LocalDateTime now) {
        PlatformSkuPricingDO identity = platformPricingMapper.selectBySkuIdForUpdate(sku.getId());
        if (identity == null) {
            identity = PlatformSkuPricingDO.builder().platformSkuId(sku.getId())
                    .pricingStatus(PricingStatus.UNPRICED.name()).version(0L)
                    .createdAt(now).updatedAt(now).build();
            platformPricingMapper.insert(identity);
        }
        PlatformSkuPricingVersionDO latest = platformVersionMapper.selectLatest(sku.getId());
        int versionNo = latest == null ? 1 : latest.getVersionNo() + 1;
        PlatformSkuPricingVersionDO version = PlatformSkuPricingVersionDO.builder()
                .platformSkuId(sku.getId()).versionNo(versionNo).effectiveConfigJson(json(config))
                .marketPriceCents(deps.marketPriceCents()).suggestedSalePriceCents(deps.suggestedSalePriceCents())
                .supplyPriceCents(deps.supplyPriceCents()).distributionPriceCents(result.distributionPriceCents())
                .salesPriceCents(result.salesPriceCents()).distributionMarginPercent(result.distributionMarginPercent())
                .effectiveHideThresholdPercent(result.effectiveHideThresholdPercent())
                .pricingStatus(result.status().name()).unavailableReason(result.unavailableReason())
                .currency(Money.CURRENCY_CNY).publishedBy(operatorId).publishedAt(now).createdAt(now).build();
        platformVersionMapper.insert(version);
        identity.setCurrentVersionId(version.getId());
        identity.setPricingStatus(result.status().name());
        identity.setUnavailableReason(result.unavailableReason());
        identity.setUpdatedAt(now);
        platformPricingMapper.updateById(identity);
        return version;
    }

    private void rebuildBuyers(PlatformSkuDO sku, PlatformSkuPricingVersionDO platformVersion,
                               Long operatorId, LocalDateTime now) {
        PricingConfig parent = configFrom(platformVersion);
        for (BuyerProductDO product : buyerProductMapper.selectActiveByPlatformSkuId(sku.getId())) {
            BuyerSkuPricingDO identity = buyerPricingMapper.selectByBuyerProductIdForUpdate(product.getId());
            BuyerSkuPricingVersionDO current = identity == null || identity.getCurrentVersionId() == null ? null
                    : buyerVersionMapper.selectById(identity.getCurrentVersionId());
            ProjectOverrides overrides = current == null ? ProjectOverrides.allInherited()
                    : fromJson(current.getOverrideJson(), ProjectOverrides.class);
            PricingConfig effective = ProjectPricingConfigMerger.merge(parent, overrides);
            PriceDependencies dependencies = dependencies(sku, now);
            PricingResult result = calculator.calculate(effective, dependencies);
            BuyerSkuPricingVersionDO next = publishBuyer(product, platformVersion, overrides, effective,
                    dependencies, result, operatorId, now);
            rebuildInheritedProjects(new ProductContext(product, sku), next, operatorId, now);
        }
    }

    private BuyerSkuPricingVersionDO publishBuyer(BuyerProductDO product,
                                                  PlatformSkuPricingVersionDO platformVersion,
                                                  ProjectOverrides overrides, PricingConfig config,
                                                  PriceDependencies deps,
                                                  PricingResult result, Long operatorId, LocalDateTime now) {
        BuyerSkuPricingDO identity = buyerPricingMapper.selectByBuyerProductIdForUpdate(product.getId());
        if (identity == null) {
            identity = BuyerSkuPricingDO.builder().buyerProductId(product.getId())
                    .pricingStatus(PricingStatus.UNPRICED.name()).version(0L)
                    .createdAt(now).updatedAt(now).build();
            buyerPricingMapper.insert(identity);
        }
        BuyerSkuPricingVersionDO latest = buyerVersionMapper.selectLatest(identity.getId());
        int versionNo = latest == null ? 1 : latest.getVersionNo() + 1;
        BuyerSkuPricingVersionDO version = BuyerSkuPricingVersionDO.builder()
                .pricingId(identity.getId()).versionNo(versionNo)
                .platformPricingVersionId(platformVersion.getId())
                .overrideJson(json(overrides)).effectiveConfigJson(json(config))
                .distributionRuleJson(json(config.distributionRule())).salesRuleJson(json(config.salesRule()))
                .serviceFeeRuleJson(json(config.serviceFeeRule())).hideRuleJson(json(config.hideRule()))
                .taxMetadataJson(json(config.taxMetadata()))
                .marketPriceCents(deps.marketPriceCents()).suggestedSalePriceCents(deps.suggestedSalePriceCents())
                .supplyPriceCents(deps.supplyPriceCents()).distributionPriceCents(result.distributionPriceCents())
                .salesPriceCents(result.salesPriceCents()).distributionMarginPercent(result.distributionMarginPercent())
                .effectiveHideThresholdPercent(result.effectiveHideThresholdPercent())
                .pricingStatus(result.status().name()).unavailableReason(result.unavailableReason())
                .currency(Money.CURRENCY_CNY).publishedBy(operatorId).publishedAt(now).createdAt(now).build();
        buyerVersionMapper.insert(version);
        identity.setCurrentVersionId(version.getId());
        identity.setPricingStatus(result.status().name());
        identity.setUnavailableReason(result.unavailableReason());
        identity.setUpdatedAt(now);
        buyerPricingMapper.updateById(identity);
        return version;
    }

    private void rebuildInheritedProjects(ProductContext context, BuyerSkuPricingVersionDO buyerVersion,
                                          Long operatorId, LocalDateTime now) {
        for (ProjectListingDO listing : listingMapper.selectAvailableByBuyerProductId(context.product().getId())) {
            ProjectSkuPricingDO identity = projectPricingMapper.selectByListingId(listing.getId());
            ProjectSkuPricingVersionDO current = identity == null || identity.getCurrentVersionId() == null ? null
                    : projectVersionMapper.selectById(identity.getCurrentVersionId());
            ProjectOverrides overrides = current == null ? ProjectOverrides.allInherited()
                    : fromJson(current.getOverrideJson(), ProjectOverrides.class);
            PricingConfig buyerConfig = configFrom(buyerVersion);
            PricingConfig effective = ProjectPricingConfigMerger.merge(buyerConfig, overrides);
            PriceDependencies dependencies = dependencies(context.sku(), now);
            PricingResult result = calculator.calculate(effective, dependencies);
            publishProject(listing, buyerVersion, overrides, effective, dependencies, result, operatorId, now);
        }
    }

    private void publishProject(ProjectListingDO listing, BuyerSkuPricingVersionDO buyerVersion,
                                ProjectOverrides overrides, PricingConfig effective, PriceDependencies deps,
                                PricingResult result, Long operatorId, LocalDateTime now) {
        ProjectSkuPricingDO identity = projectPricingMapper.selectByListingIdForUpdate(listing.getId());
        if (identity == null) {
            identity = ProjectSkuPricingDO.builder().listingId(listing.getId())
                    .pricingStatus(PricingStatus.UNPRICED.name()).version(0L)
                    .createdAt(now).updatedAt(now).build();
            projectPricingMapper.insert(identity);
        }
        ProjectSkuPricingVersionDO latest = projectVersionMapper.selectLatest(identity.getId());
        int versionNo = latest == null ? 1 : latest.getVersionNo() + 1;
        ProjectSkuPricingVersionDO version = ProjectSkuPricingVersionDO.builder()
                .pricingId(identity.getId()).versionNo(versionNo).buyerPricingVersionId(buyerVersion.getId())
                .overrideJson(json(overrides)).effectiveConfigJson(json(effective))
                .marketPriceCents(deps.marketPriceCents()).suggestedSalePriceCents(deps.suggestedSalePriceCents())
                .supplyPriceCents(deps.supplyPriceCents()).distributionPriceCents(result.distributionPriceCents())
                .salesPriceCents(result.salesPriceCents()).distributionMarginPercent(result.distributionMarginPercent())
                .effectiveHideThresholdPercent(result.effectiveHideThresholdPercent())
                .pricingStatus(result.status().name()).unavailableReason(result.unavailableReason())
                .currency(Money.CURRENCY_CNY).publishedBy(operatorId).publishedAt(now).createdAt(now).build();
        projectVersionMapper.insert(version);
        identity.setCurrentVersionId(version.getId());
        identity.setPricingStatus(result.status().name());
        identity.setUnavailableReason(result.unavailableReason());
        identity.setUpdatedAt(now);
        projectPricingMapper.updateById(identity);
    }

    private ProjectOverrides normalizeTax(ProjectOverrides overrides) {
        if (!overrides.taxInherited()) {
            TaxMetadata tax = Objects.requireNonNull(overrides.taxMetadata(),
                    "downstream tax metadata is required when inherit=false");
            return overrides.withTax(taxDictionary.requireEnabled(tax.taxIncluded(), tax.taxRateCode()));
        }
        return overrides;
    }

    private BuyerConfigSnapshot requireBuyerConfig(Long buyerProductId) {
        return requireBuyerConfig(buyerProductId, false);
    }

    /**
     * 报价未配置属可恢复业务条件，已指向不存在的版本则属引用损坏；发布入口保持原异常契约。
     * 设计见 docs/superpowers/specs/2026-10-03-standard-project-direct-order-price-freeze-design.md#3-定价身份与当前准入。
     */
    private BuyerConfigSnapshot requireBuyerConfig(Long buyerProductId, boolean quoteEntry) {
        BuyerSkuPricingDO identity = buyerPricingMapper.selectByBuyerProductId(buyerProductId);
        if (quoteEntry && (identity == null || identity.getCurrentVersionId() == null)) {
            throw new ProjectPricingNotReadyException("buyer SKU is unpriced");
        }
        BuyerSkuPricingVersionDO version = identity == null || identity.getCurrentVersionId() == null ? null
                : buyerVersionMapper.selectById(identity.getCurrentVersionId());
        if (version == null) {
            throw new IllegalStateException(quoteEntry ? "buyer pricing current version is missing or mismatched" : "buyer SKU is unpriced");
        }
        if (quoteEntry && !Objects.equals(identity.getId(), version.getPricingId())) {
            throw new IllegalStateException("buyer pricing current version is missing or mismatched");
        }
        PricingConfig config = configFrom(version);
        return new BuyerConfigSnapshot(version, config);
    }

    private PricingConfig configFrom(BuyerSkuPricingVersionDO version) {
        if (version.getEffectiveConfigJson() != null) {
            return fromJson(version.getEffectiveConfigJson(), PricingConfig.class);
        }
        return new PricingConfig(fromJson(version.getDistributionRuleJson(), FormulaRule.class),
                fromJson(version.getSalesRuleJson(), FormulaRule.class),
                fromJson(version.getServiceFeeRuleJson(), ServiceFeeRule.class),
                fromJson(version.getHideRuleJson(), HideRule.class),
                fromJson(version.getTaxMetadataJson(), TaxMetadata.class));
    }

    private PlatformConfigSnapshot requirePlatformConfig(Long platformSkuId) {
        PlatformSkuPricingDO identity = platformPricingMapper.selectById(platformSkuId);
        PlatformSkuPricingVersionDO version = identity == null || identity.getCurrentVersionId() == null ? null
                : platformVersionMapper.selectById(identity.getCurrentVersionId());
        if (version == null) {
            throw exception(PLATFORM_DEFAULT_PRICING_MISSING);
        }
        return new PlatformConfigSnapshot(version, configFrom(version));
    }

    private PricingConfig configFrom(PlatformSkuPricingVersionDO version) {
        return fromJson(version.getEffectiveConfigJson(), PricingConfig.class);
    }

    private PriceDependencies dependencies(PlatformSkuDO sku, LocalDateTime now) {
        Long minimum = null;
        for (SupplierOfferDO offer : offerMapper.selectPublishedBySku(sku.getId())) {
            SupplierDO supplier = supplierMapper.selectById(offer.getSupplierId());
            SupplierOfferVersionDO version = offer.getCurrentVersionId() == null
                    ? null : offerVersionMapper.selectById(offer.getCurrentVersionId());
            Long price = EffectiveSupplyPrice.of(offer, supplier, version, sku.getSuggestedSalePrice(), now);
            if (price == null) {
                continue;
            }
            minimum = minimum == null ? price : Math.min(minimum, price);
        }
        return new PriceDependencies(sku.getMarketPrice(), sku.getSuggestedSalePrice(), minimum);
    }

    private BuyerDO requireBuyer(String code, boolean forUpdate) {
        BuyerDO buyer = forUpdate ? buyerMapper.selectByBuyerCodeForUpdate(code) : buyerMapper.selectByBuyerCode(code);
        if (buyer == null || buyer.getStatus() != BuyerStatus.ACTIVE.getCode()) {
            throw new IllegalArgumentException("active buyer not found");
        }
        return buyer;
    }

    /**
     * 人工默认定价的权威准入：SKU 与所属 SPU 必须同时启用；不存在与状态拒绝分别返回稳定业务错误。
     * 预览只读，保存锁定 SKU 后对整批完成检查再发布版本；本层继续上抛，不重复记录异常堆栈。
     * 此守卫不用于审计读取和复合保存首版自动初始化，不能把草稿的系统默认定价与人工定价混为一谈。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#6-管理端接口}。
     */
    private PlatformSkuDO requireSku(String code, boolean forUpdate) {
        return requireSku(code, forUpdate ? skuMapper.selectByCodeForUpdate(code) : skuMapper.selectByCode(code));
    }

    private PlatformSkuDO requireSku(String code, PlatformSkuDO sku) {
        if (sku == null) {
            throw exception(PLATFORM_SKU_NOT_FOUND);
        }
        PlatformSpuDO spu = spuMapper.selectById(sku.getSpuId());
        if (spu == null) {
            throw exception(PLATFORM_SPU_NOT_FOUND);
        }
        if (!Objects.equals(sku.getStatus(), CatalogEntityStatus.ACTIVE.getCode())
                || !Objects.equals(spu.getStatus(), CatalogEntityStatus.ACTIVE.getCode())) {
            throw exception(PLATFORM_SKU_PRICING_NOT_ALLOWED, code);
        }
        return sku;
    }

    private ProductContext requireProduct(Long buyerId, String code, boolean forUpdate) {
        BuyerProductDO product = forUpdate ? buyerProductMapper.selectByCodeForUpdate(code)
                : buyerProductMapper.selectByCode(code);
        if (product == null || !buyerId.equals(product.getBuyerId())
                || product.getStatus() != BuyerProductStatus.ACTIVE.getCode()) {
            throw new IllegalArgumentException("buyer product not found in current buyer");
        }
        PlatformSkuDO sku = skuMapper.selectById(product.getPlatformSkuId());
        if (sku == null) {
            throw new IllegalArgumentException("platform SKU not found");
        }
        return new ProductContext(product, sku);
    }

    private BuyerProjectDO requireProject(String code, boolean forUpdate) {
        BuyerProjectDO project = forUpdate ? projectMapper.selectByProjectCodeForUpdate(code)
                : projectMapper.selectByProjectCode(code);
        if (project == null) {
            throw new IllegalArgumentException("project not found");
        }
        return project;
    }

    private ListingContext requireListing(BuyerProjectDO project, String code, boolean forUpdate) {
        ProjectListingDO listing = forUpdate ? listingMapper.selectByCodeForUpdate(code) : listingMapper.selectByCode(code);
        if (listing == null || !project.getId().equals(listing.getProjectId())
                || listing.getStatus() != ProjectListingStatus.AVAILABLE.getCode()) {
            throw new IllegalArgumentException("project listing not found in current project");
        }
        BuyerProductDO product = buyerProductMapper.selectById(listing.getBuyerProductId());
        if (product == null || !project.getBuyerId().equals(product.getBuyerId())) {
            throw new IllegalArgumentException("project listing does not belong to project buyer");
        }
        PlatformSkuDO sku = skuMapper.selectById(product.getPlatformSkuId());
        if (sku == null) {
            throw new IllegalArgumentException("platform SKU not found");
        }
        return new ListingContext(listing, product, sku);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("pricing config serialization failed", ex);
        }
    }

    private <T> T fromJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("pricing config deserialization failed", ex);
        }
    }

    private static PricingConfig withTax(PricingConfig config, TaxMetadata tax) {
        return new PricingConfig(config.distributionRule(), config.salesRule(), config.serviceFeeRule(),
                config.hideRule(), tax);
    }

    private static PricingPreview toPreview(String scopeItemCode, String skuCode, PriceDependencies deps,
                                            PricingResult result) {
        return new PricingPreview(scopeItemCode, skuCode, deps.marketPriceCents(),
                deps.suggestedSalePriceCents(), deps.supplyPriceCents(), result.distributionPriceCents(),
                result.salesPriceCents(), result.distributionMarginPercent(),
                result.effectiveHideThresholdPercent(), result.status(), result.unavailableReason());
    }

    private static List<String> normalize(List<String> codes) {
        if (codes == null || codes.isEmpty() || codes.size() > MAX_BATCH) {
            throw new IllegalArgumentException("selected SKU count must be 1 to 200");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("selected SKU code must not be blank");
            }
            result.add(code.trim());
        }
        return List.copyOf(result);
    }

    public record ProjectOverrides(boolean distributionInherited, FormulaRule distributionRule,
                                   boolean salesInherited, FormulaRule salesRule,
                                   boolean serviceFeeInherited, ServiceFeeRule serviceFeeRule,
                                   boolean hideInherited, HideRule hideRule,
                                   boolean taxInherited, TaxMetadata taxMetadata) {
        ProjectOverrides withTax(TaxMetadata tax) {
            return new ProjectOverrides(distributionInherited, distributionRule, salesInherited, salesRule,
                    serviceFeeInherited, serviceFeeRule, hideInherited, hideRule, taxInherited, tax);
        }
        public static ProjectOverrides allInherited() {
            return new ProjectOverrides(true, null, true, null, true, null, true, null, true, null);
        }
    }

    public record PricingPreview(String scopeItemCode, String platformSkuCode,
                                 long marketPriceCents, long suggestedSalePriceCents, Long supplyPriceCents,
                                 Long distributionPriceCents, Long salesPriceCents,
                                 java.math.BigDecimal distributionMarginPercent,
                                 java.math.BigDecimal effectiveHideThresholdPercent,
                                 PricingStatus status, String unavailableReason) { }

    /**
     * 平台 SKU 当前默认定价详情。字段语义与 035 变更集的身份表和不可变版本表 COMMENT 一致。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#6-管理端接口}。
     */
    public record PlatformPricingDetail(
            @Schema(description = "平台SKU永久业务编码") String platformSkuCode,
            @Schema(description = "当前商品默认定价版本主键，空表示未定价") Long currentVersionId,
            @Schema(description = "定价版本号，从1递增；未定价为空") Integer versionNo,
            @Schema(description = "定价状态：UNPRICED、READY、RECALCULATING、DEPENDENCY_MISSING、CALCULATION_FAILED、MARGIN_HIDDEN")
            String pricingStatus,
            @Schema(description = "用户端不可售原因编码") String unavailableReason,
            @Schema(description = "默认分销价规则") FormulaRule distributionRule,
            @Schema(description = "默认销售价规则") FormulaRule salesRule,
            @Schema(description = "默认服务费规则与文案") ServiceFeeRule serviceFeeRule,
            @Schema(description = "默认分销区和销售区利润隐藏阈值") HideRule hideRule,
            @Schema(description = "默认税务展示属性；不参与价格计算") TaxMetadata taxMetadata,
            @Schema(description = "计算时市场价快照，单位CNY分") Long marketPriceCents,
            @Schema(description = "计算时销售建议价快照，单位CNY分") Long suggestedSalePriceCents,
            @Schema(description = "计算时最低有效供货价快照，单位CNY分") Long supplyPriceCents,
            @Schema(description = "计算后默认商品分销单价，单位CNY分") Long distributionPriceCents,
            @Schema(description = "计算后默认商品销售单价，单位CNY分") Long salesPriceCents,
            @Schema(description = "单品分销利润率百分数快照") java.math.BigDecimal distributionMarginPercent,
            @Schema(description = "实际生效的利润隐藏阈值百分数") java.math.BigDecimal effectiveHideThresholdPercent,
            @Schema(description = "币种，一期固定CNY") String currency,
            @Schema(description = "发布人用户ID；依赖重算可为空") Long publishedBy,
            @Schema(description = "发布时间，Asia/Shanghai") LocalDateTime publishedAt) {

        private static PlatformPricingDetail unpriced(String skuCode, String status, String unavailableReason) {
            return new PlatformPricingDetail(skuCode, null, null, status, unavailableReason,
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
        }
    }

    public record CurrentProjectPricing(ProjectSkuPricingVersionDO version, Long listingId,
                                        Long platformSkuId, String platformSkuCode) { }

    private record CalculatedPlatform(PlatformSkuDO sku, PriceDependencies dependencies, PricingResult result) { }
    private record ProductContext(BuyerProductDO product, PlatformSkuDO sku) { }
    private record ListingContext(ProjectListingDO listing, BuyerProductDO product, PlatformSkuDO sku) { }
    private record CalculatedProduct(ProductContext context, PlatformSkuPricingVersionDO platformVersion,
                                     ProjectOverrides overrides, PricingConfig effective,
                                     PriceDependencies dependencies, PricingResult result) { }
    private record CalculatedListing(ListingContext context, BuyerSkuPricingVersionDO buyerVersion,
                                     PricingConfig effective, PriceDependencies dependencies, PricingResult result) { }
    private record BuyerConfigSnapshot(BuyerSkuPricingVersionDO version, PricingConfig config) { }
    private record PlatformConfigSnapshot(PlatformSkuPricingVersionDO version, PricingConfig config) { }
}
