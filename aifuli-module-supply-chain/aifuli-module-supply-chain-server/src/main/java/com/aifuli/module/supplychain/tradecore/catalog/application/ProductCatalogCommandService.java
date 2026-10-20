package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService;

import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuSchemaRegistry.SchemaDefinition;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BrandDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityType;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CategoryVisibility;
import com.aifuli.module.supplychain.tradecore.catalog.domain.DeliveryType;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.catalog.domain.PurchaseLimitPeriod;
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceCalculator;
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule;
import com.aifuli.module.supplychain.tradecore.catalog.domain.UnshelveMode;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingDependencyChangedEvent;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PlatformSkuPricingInitializationEvent;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import com.aifuli.module.supplychain.tradecore.profile.domain.SellableCategory;
import com.aifuli.framework.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuBatchLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BRAND_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SKU_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SPU_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_BATCH_INPUT_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CANNOT_DELETE_HAS_BUYER_LISTING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CANNOT_DELETE_HAS_BUYER_PRODUCT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CANNOT_DELETE_HAS_PROJECT_LISTING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CANNOT_DELETE_HAS_SUPPLIER_OFFER;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CONCURRENT_UPDATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CURRENCY_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_QUANTITY_RANGE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_SCHEMA_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_SKU_CODE_DUPLICATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_SKU_FIELD_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_SKU_REQUIRED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_TRANSITION_NOT_ALLOWED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_FULFILLMENT_TYPE_IMMUTABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_SUGGESTED_PRICE_RULE_INVALID;

@Slf4j
@Service
public class ProductCatalogCommandService {
    @jakarta.annotation.Resource
    private com.aifuli.module.supplychain.tradecore.catalog.pricing.SuggestedSalePriceResolver suggestedPriceResolver;

    private final SupplyChainDatabaseClock databaseClock;
    private final ApplicationEventPublisher eventPublisher;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String CURRENCY_CNY = "CNY";
    /** 与 {@code sc_platform_spu/sku.min_quantity} DDL DEFAULT 对齐的默认起购量。 */
    private static final long DEFAULT_MIN_QUANTITY = 1L;
    /** 与 {@code sc_platform_spu/sku.max_quantity} DDL DEFAULT 对齐的默认限购量。 */
    private static final long DEFAULT_MAX_QUANTITY = 9_999_999L;
    /** 与 {@code market_price/suggested_sale_price} DDL DEFAULT 对齐的默认价格（单位：分）。 */
    private static final long DEFAULT_PRICE_FEN = 0L;
    /** 单次批量更新 SKU 税务元数据的上限，避免超大事务。 */
    private static final int MAX_BATCH_SKU_COUNT = 200;
    /**
     * 单次批量上下架的 SPU 与 SKU 项数之和上限。
     * <p>旧模式按逐实体，同步模式按 SPU 组走 {@code REQUIRES_NEW} 独立事务，展开数据与写入总量必须有界；
     * 取值与 {@link #MAX_BATCH_SKU_COUNT}、{@code GenericBatchPricingService.MAX_BATCH} 及全仓批量约定一致。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4110-批量上下架接口put-supply-chainproductsbatch-status}。
     */
    private static final int MAX_BATCH_STATUS_COUNT = 200;
    /** 与 {@code sc_platform_sku.main_image_url/detail_image_urls} DDL 长度一致。 */
    private static final int MAX_SKU_IMAGE_URL_LENGTH = 512;
    /** 与 {@code sc_platform_sku.description} DDL 长度一致。 */
    private static final int MAX_SKU_DESCRIPTION_LENGTH = 200;
    /**
     * 禁止写入的回执 Schema 占位编码。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d11）。
     */
    private static final String PLACEHOLDER_SCHEMA_CODE = "DEFAULT";
    /**
     * {@link ProductDeletionValidator} 判定「仍被下游引用」时使用的错误码集合，
     * 用于在复合更新中区分引用冲突与并发修改等其它失败。
     */
    private static final Set<Integer> REFERENCE_ERROR_CODES = Set.of(
            PRODUCT_CANNOT_DELETE_HAS_SUPPLIER_OFFER.getCode(),
            PRODUCT_CANNOT_DELETE_HAS_BUYER_LISTING.getCode(),
            PRODUCT_CANNOT_DELETE_HAS_BUYER_PRODUCT.getCode(),
            PRODUCT_CANNOT_DELETE_HAS_PROJECT_LISTING.getCode());

    private final BrandMapper brandMapper;
    private final ProductCategoryMapper categoryMapper;
    private final PlatformSpuMapper spuMapper;
    private final PlatformSkuMapper skuMapper;
    private final PlatformSkuPricingMapper platformSkuPricingMapper;
    private final BuyerMapper buyerMapper;
    private final BuyerListingMapper listingMapper;
    private final BuyerListingAvailabilityMapper availabilityMapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final ScopedBusinessCodeService scopedBusinessCodeService;
    private final BrandAdminService brandAdminService;
    private final CategoryCommandService categoryCommandService;
    private final ProductTypeValidationService productTypeValidationService;
    private final ProductClassificationValidationService productClassificationValidationService;
    private final ProductSellabilityService sellabilityService;
    private final ProductDeletionValidator deletionValidator;
    private final FulfillmentTargetSchemaRegistry targetSchemaRegistry;
    private final PlatformSkuSchemaRegistry skuSchemaRegistry;

    /**
     * 批量状态事务模板：旧模式每实体、同步模式每 SPU 组一个真实事务。传播行为 {@code REQUIRES_NEW}、隔离级别 {@code READ_COMMITTED}。
     * <p>不变量：旧模式每实体独立；同步模式父商品与全部未归档规格原子提交，任一组失败仅回滚该组。
     * 必须由 {@code REQUIRES_NEW} 强制，不能依赖默认 {@code REQUIRED} —— 本方法已无方法级
     * {@code @Transactional}，当前唯一调用方 {@code ProductAdminController} 也非事务，故 {@code REQUIRED}
     * 今天「碰巧」等价；但一旦任何调用方包裹事务，各项将并入外层事务，且被捕获的 {@code ServiceException}
     * 会把外层标记为 rollback-only，提交时抛 {@code UnexpectedRollbackException} 连带丢掉已成功项。
     * <p>设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4110-批量上下架接口put-supply-chainproductsbatch-status}（D1）。
     */
    private final TransactionTemplate batchStatusTransactionTemplate;

    /**
     * 手写构造器而非 {@code @RequiredArgsConstructor}：根 {@code lombok.config} 未配置
     * {@code copyableAnnotations}，Lombok 不会把 {@code @Qualifier} 复制到生成的构造器参数上，
     * 而事务管理器必须按 bean 名显式限定，不能依赖「上下文里恰好只有一个」的隐式按类型匹配。
     * 与 {@code BusinessCodeLegacyBackfill}、{@code RechargeFrequencyAdminCommandService} 的既有做法一致。
     */
    public ProductCatalogCommandService(SupplyChainDatabaseClock databaseClock,
                                        ApplicationEventPublisher eventPublisher,
                                        BrandMapper brandMapper,
                                        ProductCategoryMapper categoryMapper,
                                        PlatformSpuMapper spuMapper,
                                        PlatformSkuMapper skuMapper,
                                        PlatformSkuPricingMapper platformSkuPricingMapper,
                                        BuyerMapper buyerMapper,
                                        BuyerListingMapper listingMapper,
                                        BuyerListingAvailabilityMapper availabilityMapper,
                                        BusinessNoGenerator businessNoGenerator,
                                        ScopedBusinessCodeService scopedBusinessCodeService,
                                        BrandAdminService brandAdminService,
                                        CategoryCommandService categoryCommandService,
                                        ProductTypeValidationService productTypeValidationService,
                                        ProductClassificationValidationService productClassificationValidationService,
                                        ProductSellabilityService sellabilityService,
                                        ProductDeletionValidator deletionValidator,
                                        FulfillmentTargetSchemaRegistry targetSchemaRegistry,
                                        PlatformSkuSchemaRegistry skuSchemaRegistry,
                                        @Qualifier(TRANSACTION_MANAGER) PlatformTransactionManager supplyChainTransactionManager) {
        this.databaseClock = databaseClock;
        this.eventPublisher = eventPublisher;
        this.brandMapper = brandMapper;
        this.categoryMapper = categoryMapper;
        this.spuMapper = spuMapper;
        this.skuMapper = skuMapper;
        this.platformSkuPricingMapper = platformSkuPricingMapper;
        this.buyerMapper = buyerMapper;
        this.listingMapper = listingMapper;
        this.availabilityMapper = availabilityMapper;
        this.businessNoGenerator = businessNoGenerator;
        this.scopedBusinessCodeService = scopedBusinessCodeService;
        this.brandAdminService = brandAdminService;
        this.categoryCommandService = categoryCommandService;
        this.productTypeValidationService = productTypeValidationService;
        this.productClassificationValidationService = productClassificationValidationService;
        this.sellabilityService = sellabilityService;
        this.deletionValidator = deletionValidator;
        this.targetSchemaRegistry = targetSchemaRegistry;
        this.skuSchemaRegistry = skuSchemaRegistry;
        TransactionTemplate template = new TransactionTemplate(supplyChainTransactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.batchStatusTransactionTemplate = template;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createBrand(CreateBrandCommand command) {
        return brandAdminService.create(new BrandAdminService.CreateCommand(command.brandCode(), command.brandName(),
                null, command.englishName(), command.logoUrl(), null, command.sort(),
                CatalogEntityStatus.DRAFT.getCode(), command.operatorId()));
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createCategory(CreateCategoryCommand command) {
        return categoryCommandService.create(new CategoryCommandService.CreateCommand(command.categoryCode(),
                command.categoryName(), null, null, command.parentCategoryCode(), command.sort(),
                CategoryVisibility.VISIBLE.getCode(), CatalogEntityStatus.DRAFT.getCode(), command.operatorId()));
    }

    /**
     * 存量兼容入口：按业务编码解析品牌与主类目后，复用 {@link #insertSpu} 的统一校验与落库路径。
     * 旧契约不携带 SKU 列表，因此只建 SPU 草稿；复合校验（动态商品类型、类目有效性、限购区间、配送模式）与新入口一致。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#45-存量主数据接口治理m1masterdataadmincontroller}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createSpu(CreateSpuCommand command) {
        log.info("创建平台 SPU 草稿（兼容入口） spuCode={} brandCode={} operatorId={}",
                command.spuCode(), command.brandCode(), command.operatorId());
        BrandDO brand = requireBrand(command.brandCode());
        Long primaryCategoryId = hasText(command.primaryCategoryCode())
                ? requireCategory(command.primaryCategoryCode()).getId() : null;
        String fulfillmentType = hasText(command.fulfillmentType())
                ? FulfillmentType.fromCode(command.fulfillmentType().trim()).code()
                : FulfillmentType.DIRECT_RECHARGE.code();
        SaveSpuCommand delegated = new SaveSpuCommand(command.spuCode(), command.spuName(), command.subtitle(),
                brand.getId(), primaryCategoryId, null, command.sellableCategory(), command.productType(),
                command.productClassification(), command.coverUrl(), null, command.useInstruction(), null,
                null, fulfillmentType, null, null, CatalogEntityStatus.DRAFT.getCode(), List.of(), command.operatorId());
        return insertSpu(delegated).getSpuCode();
    }

    /**
     * 存量兼容入口：复用 {@link #insertSku} 的统一校验与落库路径，使旧接口与复合创建共享
     * Schema 服务端派生、履约模式校验、限购区间校验与建议价默认值。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#45-存量主数据接口治理m1masterdataadmincontroller}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createSku(CreateSkuCommand command) {
        log.info("创建平台 SKU 草稿（兼容入口） skuCode={} spuCode={} operatorId={}",
                command.skuCode(), command.spuCode(), command.operatorId());
        if (!CURRENCY_CNY.equals(command.currency())) {
            throw exception(PRODUCT_CURRENCY_INVALID);
        }
        // 子成员新增与同步上架共同遵守先父后子的锁顺序，防止解析后漏掉并发新规格。
        // 设计见 docs/superpowers/specs/2026-10-11-platform-product-batch-shelf-sync-design.md#4-分组事务与并发。
        PlatformSpuDO spu = requireSpuForUpdate(command.spuCode());
        String skuCode = scopedBusinessCodeService.allocateChild(ScopedBusinessCodeService.Kind.SKU,
                spu.getId(), spu.getCodeScopeNo(), command.skuCode(), command.operatorId()).code();
        String fulfillmentType = spu.getFulfillmentType();
        if (hasText(command.fulfillmentType()) && !command.fulfillmentType().trim().equals(fulfillmentType)) {
            throw exception(PRODUCT_SKU_FIELD_INVALID);
        }
        SaveSkuLine line = new SaveSkuLine(skuCode, command.skuName(), fulfillmentType,
                command.faceValue(), null, null, null, null, CatalogEntityStatus.DRAFT.getCode());
        insertSku(spu, skuCode, line, CatalogEntityStatus.DRAFT, command.operatorId(), now());
        return skuCode;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createBuyerListing(CreateBuyerListingCommand command) {
        String normalizedCode = command.buyerSkuCode() == null ? null : command.buyerSkuCode().trim();
        log.info("创建手工受理目录 buyerCode={} skuCode={} operatorId={}", command.buyerCode(), command.platformSkuCode(), command.operatorId());
        if (normalizedCode == null || normalizedCode.isEmpty()
                || normalizedCode.codePointCount(0, normalizedCode.length()) > 64) {
            throw new IllegalArgumentException("采购商商品编码须为1至64字符");
        }
        // 新兼容编码保留BPD命名空间；存量编码及请求指纹不改写，见桥接设计§3.1。
        if (listingMapper.isReservedBuyerProductCode(normalizedCode, BusinessNoPrefixes.BPD)) {
            throw exception(com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_CATALOG_CODE_CONFLICT);
        }
        BuyerDO buyer = requireActiveBuyer(command.buyerCode());
        PlatformSkuDO sku = sellabilityService.requireSellableByCode(command.platformSkuCode()).sku();
        BuyerListingDO existing = listingMapper.selectByBuyerAndSku(buyer.getId(), sku.getId());
        if (existing != null) {
            throw exception(com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_CATALOG_CODE_CONFLICT);
        }
        LocalDateTime now = now();
        BuyerListingDO listing = BuyerListingDO.builder()
                .listingNo(businessNoGenerator.next(BusinessNoPrefixes.LST))
                .buyerId(buyer.getId())
                .platformSkuId(sku.getId())
                .buyerSkuCode(normalizedCode)
                .managementMode(com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingManagementMode.MANUAL.name())
                .status(BuyerListingStatus.DRAFT.getCode())
                .version(0L)
                .createdBy(command.operatorId())
                .createdAt(now)
                .updatedBy(command.operatorId())
                .updatedAt(now)
                .build();
        try {
            listingMapper.insert(listing);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            if (!BuyerAcceptanceCatalogIdentityWriter.isIdentityKeyViolation(duplicate)) { throw duplicate; }
            var conflict = exception(com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_CATALOG_CODE_CONFLICT);
            conflict.initCause(duplicate);
            throw conflict;
        }
        availabilityMapper.insert(BuyerListingAvailabilityDO.builder()
                .listingId(listing.getId())
                .availability(ListingAvailability.UNKNOWN.getCode())
                .reasonCode(BuyerAcceptanceCatalogIdentityWriter.NOT_EVALUATED)
                .evaluatedAt(now)
                .basisVersion(0L)
                .version(0L)
                .updatedAt(now)
                .build());
        return listing.getListingNo();
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeBrandStatus(String code, CatalogEntityStatus target, Long operatorId) {
        brandAdminService.changeStatus(code, target, operatorId);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeCategoryStatus(String code, CatalogEntityStatus target, Long operatorId) {
        categoryCommandService.changeStatus(code, target, operatorId);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void changeSpuStatus(String code, CatalogEntityStatus target, Long operatorId) {
        applySpuStatus(code, target, UnshelveMode.MANUAL_UNSHELVE.getCode(), operatorId);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void changeSpuStatus(String code, CatalogEntityStatus target, String unshelveMode, Long operatorId) {
        applySpuStatus(code, target, unshelveMode, operatorId);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void changeSkuStatus(String code, CatalogEntityStatus target, Long operatorId) {
        applySkuStatus(code, target, operatorId);
    }

    /**
     * 批量状态编排：旧模式逐实体独立事务；显式同步模式按去重 SPU 及全部未归档 SKU 组原子提交。
     * 请求级入参及有界展开校验在第一笔写入之前完成；业务拒绝写入回执，系统故障保留因果交 HTTP 终结边界。
     * 系统故障时此前已提交组仍然生效，调用方须刷新确认，不能把整体异常解释成全批零写入或自动重发。
     * 成功日志只能在 TransactionTemplate 返回（真实提交完成）后记录。
     * 设计见 {@code docs/superpowers/specs/2026-10-11-platform-product-batch-shelf-sync-design.md#4-分组事务与并发}。
     */
    public BatchStatusResult batchChangeStatus(BatchStatusCommand command) {
        log.info("[商品批量状态开始][接收批量命令] spuCodes={}, skuCodes={}, targetStatus={}, unshelveMode={}, operatorId={}",
                command.spuCodes(), command.skuCodes(), command.targetStatus(), command.unshelveMode(), command.operatorId());
        CatalogEntityStatus target = CatalogEntityStatus.fromCode(command.targetStatus());
        if (target != CatalogEntityStatus.ACTIVE && target != CatalogEntityStatus.INACTIVE) {
            throw exception(PRODUCT_STATUS_INVALID);
        }
        List<String> spuCodes = normalizeBatchStatusCodes(command.spuCodes());
        List<String> skuCodes = normalizeBatchStatusCodes(command.skuCodes());
        if (spuCodes.isEmpty() && skuCodes.isEmpty()) {
            throw exception(PRODUCT_BATCH_INPUT_INVALID);
        }
        if (Boolean.TRUE.equals(command.syncSkuOnShelf())) {
            if (target != CatalogEntityStatus.ACTIVE || spuCodes.isEmpty() || !skuCodes.isEmpty()) {
                throw exception(PRODUCT_BATCH_INPUT_INVALID);
            }
            List<String> distinctSpuCodes = new ArrayList<>(new LinkedHashSet<>(spuCodes));
            if (distinctSpuCodes.size() > MAX_BATCH_STATUS_COUNT) throw exception(PRODUCT_BATCH_INPUT_INVALID);
            return syncBatchOnShelf(distinctSpuCodes, command.operatorId());
        }
        if (spuCodes.size() + skuCodes.size() > MAX_BATCH_STATUS_COUNT) {
            throw exception(PRODUCT_BATCH_INPUT_INVALID);
        }
        List<String> succeeded = new ArrayList<>();
        List<BatchStatusFailedItem> failed = new ArrayList<>();
        // 系统故障原样交由 HTTP 终结边界记录因果；此处不重复打印堆栈或失败摘要。
        applyBatchStatus(spuCodes, CatalogEntityType.SPU, target, succeeded, failed,
                code -> applySpuStatus(code, target, command.unshelveMode(), command.operatorId()));
        applyBatchStatus(skuCodes, CatalogEntityType.SKU, target, succeeded, failed,
                code -> applySkuStatus(code, target, command.operatorId()));
        log.info("[商品批量状态完成][逐实体事务已提交] successCount={}, failureCount={}, targetStatus={}, operatorId={}",
                succeeded.size(), failed.size(), target, command.operatorId());
        return new BatchStatusResult(succeeded, failed);
    }

    /**
     * 同步上架以 SPU 组计数，全部范围先有界解析，再逐组提交真实 REQUIRES_NEW 事务。
     * 已上架父商品也必须处理所有未归档规格，业务失败整组回滚，系统异常保留因果上抛。
     * 设计见 {@code docs/superpowers/specs/2026-10-11-platform-product-batch-shelf-sync-design.md#4-分组事务与并发}。
     */
    private BatchStatusResult syncBatchOnShelf(List<String> spuCodes, Long operatorId) {
        List<ShelfGroupPlan> plans = new ArrayList<>();
        int remaining = MAX_BATCH_STATUS_COUNT - spuCodes.size();
        for (String code : spuCodes) {
            PlatformSpuDO spu = spuMapper.selectByCode(code);
            List<PlatformSkuDO> members = spu == null || spu.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()
                    ? List.of() : readShelfMembers(spu.getId(), remaining + 1);
            remaining -= members.size();
            if (remaining < 0) {
                throw exception(PRODUCT_BATCH_INPUT_INVALID);
            }
            plans.add(new ShelfGroupPlan(code, spu == null ? null : spu.getId(),
                    members.stream().map(row -> new ShelfMember(row.getId(), row.getSkuCode())).toList()));
        }
        List<String> succeeded = new ArrayList<>();
        List<BatchStatusFailedItem> failed = new ArrayList<>();
        List<BatchStatusGroup> groups = new ArrayList<>();
        for (ShelfGroupPlan plan : plans) {
            ShelfFailureLocator locator = new ShelfFailureLocator(CatalogEntityType.SPU, plan.spuCode());
            List<String> memberCodes = plan.members().stream().map(ShelfMember::skuCode).toList();
            try {
                batchStatusTransactionTemplate.executeWithoutResult(tx -> applyShelfGroup(plan, operatorId, locator));
                // 模板已完成提交，才把整组计入成功回执。
                succeeded.add(plan.spuCode());
                groups.add(new BatchStatusGroup(plan.spuCode(), memberCodes, true, null, null));
                log.info("[商品同步上架组提交][父商品与全部规格已上架] spuCode={}, skuCodes={}, skuCount={}, operatorId={}",
                        plan.spuCode(), memberCodes, memberCodes.size(), operatorId);
            } catch (ServiceException rejected) {
                failed.add(new BatchStatusFailedItem(CatalogEntityType.SPU, plan.spuCode(), rejected.getCode(),
                        rejected.getMessage(), locator.entityType, locator.code));
                groups.add(new BatchStatusGroup(plan.spuCode(), memberCodes, false, locator.entityType, locator.code));
            }
        }
        // 部分业务拒绝在同一次执行汇总，避免逐规格 INFO 和多层堆栈；所有成功均已提交。
        if (!failed.isEmpty()) {
            log.warn("[商品批量同步上架完成][部分商品组业务拒绝] successCount={}, failureCount={}, operatorId={}, failed={}",
                    succeeded.size(), failed.size(), operatorId, failed);
        } else {
            log.info("[商品批量同步上架完成][全部商品组已提交] successCount={}, operatorId={}", succeeded.size(), operatorId);
        }
        return new BatchStatusResult(succeeded, failed, true, groups);
    }

    /**
     * 有界只读全部未归档成员范围，不能先物化任意数量 SKU 再检查额度；行锁由统一预锁工具逐主键取得。
     * 设计见 {@code docs/superpowers/specs/2026-10-11-platform-product-batch-shelf-sync-design.md#3-接口方案比较与推荐契约}。
     */
    private List<PlatformSkuDO> readShelfMembers(Long spuId, int limit) {
        return skuMapper.selectList(new LambdaQueryWrapper<PlatformSkuDO>()
                .eq(PlatformSkuDO::getSpuId, spuId)
                .ne(PlatformSkuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                .orderByAsc(PlatformSkuDO::getId)
                .last("LIMIT " + limit));
    }

    /**
     * 按父商品→类型→子主键升序锁定并复核成员，所有状态机、类型及 Schema 校验通过后才写第一行。
     * 设计见 {@code docs/superpowers/specs/2026-10-11-platform-product-batch-shelf-sync-design.md#4-分组事务与并发}。
     */
    private void applyShelfGroup(ShelfGroupPlan plan, Long operatorId, ShelfFailureLocator locator) {
        PlatformSpuDO spu = requireSpuForUpdate(plan.spuCode());
        if (!java.util.Objects.equals(spu.getId(), plan.spuId())) {
            throw exception(PRODUCT_CONCURRENT_UPDATE);
        }
        List<PlatformSkuDO> members = readShelfMembers(spu.getId(), plan.members().size() + 1);
        List<ShelfMember> lockedScope = members.stream().map(row -> new ShelfMember(row.getId(), row.getSkuCode())).toList();
        if (!lockedScope.equals(plan.members())) {
            throw exception(PRODUCT_CONCURRENT_UPDATE);
        }
        if (members.isEmpty()) {
            throw exception(PRODUCT_SKU_REQUIRED);
        }
        // 与复合编辑保持类型锁先于SKU锁；跨父人工定价可能同时连接两个商品，不能形成Type→SKU / SKU→Type环。
        checkTransition(spu.getStatus(), CatalogEntityStatus.ACTIVE, spu.getSpuCode(), spu.getSpuName());
        productTypeValidationService.requireActiveForUpdate(spu.getProductType());
        Map<String, PlatformSkuDO> lockedMembers = PlatformSkuBatchLock.lockExistingRows(skuMapper, members);
        members = List.copyOf(lockedMembers.values());
        lockedScope = members.stream().filter(row -> row.getStatus() != CatalogEntityStatus.ARCHIVED.getCode())
                .map(row -> new ShelfMember(row.getId(), row.getSkuCode())).toList();
        if (!lockedScope.equals(plan.members())) throw exception(PRODUCT_CONCURRENT_UPDATE);
        for (PlatformSkuDO sku : members) {
            locator.locate(CatalogEntityType.SKU, sku.getSkuCode());
            checkTransition(sku.getStatus(), CatalogEntityStatus.ACTIVE, sku.getSkuCode(), sku.getSkuName());
            validateSchema(sku.getTargetSchemaCode(), sku.getTargetSchemaVersion(), sku.getResultSchemaCode(),
                    sku.getResultSchemaVersion(), true);
        }
        LocalDateTime timestamp = now();
        locator.locate(CatalogEntityType.SPU, spu.getSpuCode());
        // 仅在同步模式显式写NULL；默认NOT_NULL的updateById会漏掉下架方式清理。
        // 局部更新同时保留版本条件/版本递增，避免把DO全局策略改为ALWAYS影响其它部分更新。
        // 设计见 docs/superpowers/specs/2026-10-11-platform-product-batch-shelf-sync-design.md#41-复审修正显式-null-与相交多-sku-批次锁顺序。
        requireUpdated(spuMapper.update(null, new LambdaUpdateWrapper<PlatformSpuDO>()
                .eq(PlatformSpuDO::getId, spu.getId()).eq(PlatformSpuDO::getVersion, spu.getVersion())
                .set(PlatformSpuDO::getStatus, CatalogEntityStatus.ACTIVE.getCode())
                .set(PlatformSpuDO::getUnshelveMode, null)
                .set(PlatformSpuDO::getUpdatedBy, operatorId).set(PlatformSpuDO::getUpdatedAt, timestamp)
                .set(PlatformSpuDO::getVersion, spu.getVersion() + 1)));
        for (PlatformSkuDO sku : members) {
            locator.locate(CatalogEntityType.SKU, sku.getSkuCode());
            sku.setStatus(CatalogEntityStatus.ACTIVE.getCode());
            sku.setUpdatedBy(operatorId);
            sku.setUpdatedAt(timestamp);
            requireUpdated(skuMapper.updateById(sku));
        }
        refreshPriceDependencies(members.stream().map(PlatformSkuDO::getId).toList());
    }

    private record ShelfMember(Long id, String skuCode) { }
    private record ShelfGroupPlan(String spuCode, Long spuId, List<ShelfMember> members) { }
    /** 仅保存事务失败定位，不包装异常，保留原始业务错误与系统故障因果。 */
    private static final class ShelfFailureLocator {
        private CatalogEntityType entityType;
        private String code;
        private ShelfFailureLocator(CatalogEntityType entityType, String code) { locate(entityType, code); }
        private void locate(CatalogEntityType entityType, String code) { this.entityType = entityType; this.code = code; }
    }

    /**
     * 逐项在独立事务内执行状态迁移。已知业务拒绝（{@link ServiceException}）计入 {@code failed} 并继续下一项；
     * 其余运行时异常向上抛出，由 HTTP 终结边界统一记录因果。
     *
     * @param succeeded 出参：成功项编码就地追加，使调用方在中断时仍能报告已提交清单
     * @param failed    出参：失败明细就地追加
     * @param mutation  单项状态迁移，运行在 {@link #batchStatusTransactionTemplate} 提供的事务与行锁边界内
     */
    private void applyBatchStatus(List<String> codes, CatalogEntityType entityType, CatalogEntityStatus target,
                                  List<String> succeeded, List<BatchStatusFailedItem> failed,
                                  Consumer<String> mutation) {
        for (String code : codes) {
            try {
                batchStatusTransactionTemplate.executeWithoutResult(status -> mutation.accept(code));
                succeeded.add(code);
            } catch (ServiceException ex) {
                failed.add(new BatchStatusFailedItem(entityType, code, ex.getCode(), ex.getMessage()));
                log.warn("[商品批量状态拒绝][实体业务校验失败] entityType={}, businessCode={}, targetStatus={}, errorCode={}, message={}",
                        entityType.getCode(), code, target, ex.getCode(), ex.getMessage());
            }
        }
    }

    /**
     * 批量上下架编码归一化：逐项 trim，任一编码为空白即整体拒绝。
     * <p>不静默跳过空白项：与本类 {@link #normalizeSkuCodes} 的既有约定一致；
     * 跳过会让 {@code succeeded + failed} 少于入参条数，前端「成功 N 项、失败 M 项」计数失真。
     */
    private static List<String> normalizeBatchStatusCodes(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(codes.size());
        for (String code : codes) {
            if (!hasText(code)) {
                throw exception(PRODUCT_BATCH_INPUT_INVALID);
            }
            normalized.add(code.trim());
        }
        return normalized;
    }

    /**
     * 批量记录 SKU 税务元数据。该方法只修改 {@code tax_included/tax_rate}，不得调用价格解析器或修改价格列。
     * 先按主键升序预锁，再按原请求顺序更新，保持原错误顺序与整批原子性。
     * 锁顺序见 {@code docs/superpowers/specs/2026-10-11-platform-product-batch-shelf-sync-design.md#41-复审修正显式-null-与相交多-sku-批次锁顺序}。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#411-sku-税务元数据批量记录put-supply-chainproductsskustax-metadata}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void batchUpdateSkuTaxMetadata(SkuTaxMetadataBatchCommand command) {
        List<String> skuCodes = normalizeSkuCodes(command.skuCodes());
        validateTaxMetadata(command.taxIncluded(), command.taxRate());
        log.info("批量记录SKU税务元数据 skuCount={} taxIncluded={} taxRate={} operatorId={}",
                skuCodes.size(), command.taxIncluded(), command.taxRate(), command.operatorId());
        Map<String, PlatformSkuDO> lockedSkus = PlatformSkuBatchLock.lockByCodes(skuMapper, skuCodes);
        LocalDateTime now = now();
        for (String skuCode : skuCodes) {
            PlatformSkuDO sku = lockedSkus.get(skuCode);
            if (sku == null || sku.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()) {
                throw exception(PLATFORM_SKU_NOT_FOUND);
            }
            sku.setTaxIncluded(command.taxIncluded());
            sku.setTaxRate(command.taxRate());
            sku.setUpdatedBy(command.operatorId());
            sku.setUpdatedAt(now);
            requireUpdated(skuMapper.updateById(sku));
        }
    }

    /**
     * SPU 状态迁移。归档分支必须与 {@code selectByCodeForUpdate} 行锁处于同一事务，
     * 否则 4 表引用校验通过后仍可能被并发写入引用，故只允许由已开启事务的入口调用：
     * 单项入口 {@link #changeSpuStatus} 靠方法级 {@code @Transactional}；
     * 批量入口 {@link #batchChangeStatus} 靠 {@link #batchStatusTransactionTemplate} 编程式开启
     * {@code REQUIRES_NEW} 事务——本方法是 private，Spring 代理对自调用不生效，加注解并无作用。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#32-删除强校验规则4-表物理主键与关联路径检查}。
     */
    private void applySpuStatus(String code, CatalogEntityStatus target, Long operatorId) {
        applySpuStatus(code, target, null, operatorId);
    }

    private void applySpuStatus(String code, CatalogEntityStatus target, String unshelveMode, Long operatorId) {
        com.aifuli.module.supplychain.tradecore.supply.application.SupplierLifecycleTransactions.requireReadCommitted();
        log.info("变更平台 SPU 状态 spuCode={} targetStatus={} unshelveMode={} operatorId={}", code, target, unshelveMode, operatorId);
        PlatformSpuDO entity = requireSpuForUpdate(code);
        checkTransition(entity.getStatus(), target, entity.getSpuCode(), entity.getSpuName());
        if (target == CatalogEntityStatus.ACTIVE) {
            productTypeValidationService.requireActiveForUpdate(entity.getProductType());
            entity.setUnshelveMode(null);
        } else if (target == CatalogEntityStatus.INACTIVE) {
            entity.setUnshelveMode(hasText(unshelveMode) ? unshelveMode.trim() : UnshelveMode.MANUAL_UNSHELVE.getCode());
        } else if (target == CatalogEntityStatus.ARCHIVED) {
            deletionValidator.validateSpu(entity.getId());
        }
        entity.setStatus(target.getCode());
        entity.setUpdatedBy(operatorId);
        entity.setUpdatedAt(now());
        requireUpdated(spuMapper.updateById(entity));
        refreshPriceDependencies(skuMapper.selectList(new LambdaQueryWrapper<PlatformSkuDO>()
                .eq(PlatformSkuDO::getSpuId, entity.getId())).stream().map(PlatformSkuDO::getId).toList());
    }

    /** SKU 状态迁移，事务边界约束同 {@link #applySpuStatus}。 */
    private void applySkuStatus(String code, CatalogEntityStatus target, Long operatorId) {
        com.aifuli.module.supplychain.tradecore.supply.application.SupplierLifecycleTransactions.requireReadCommitted();
        log.info("变更平台 SKU 状态 skuCode={} targetStatus={} operatorId={}", code, target, operatorId);
        // 归档改变同步成员范围，必须先锁父行，再锁子行，防止与整商品同步上架死锁。
        // 设计见 docs/superpowers/specs/2026-10-11-platform-product-batch-shelf-sync-design.md#4-分组事务与并发。
        PlatformSkuDO snapshot = target == CatalogEntityStatus.ARCHIVED ? requireSku(code) : null;
        if (snapshot != null) {
            PlatformSpuDO parent = spuMapper.selectOneForUpdate(PlatformSpuDO::getId, snapshot.getSpuId());
            if (parent == null) throw exception(PLATFORM_SPU_NOT_FOUND);
        }
        PlatformSkuDO entity = requireSkuForUpdate(code);
        if (snapshot != null && !java.util.Objects.equals(snapshot.getSpuId(), entity.getSpuId())) {
            throw exception(PRODUCT_CONCURRENT_UPDATE);
        }
        checkTransition(entity.getStatus(), target, entity.getSkuCode(), entity.getSkuName());
        if (target == CatalogEntityStatus.ACTIVE) {
            validateSchema(entity.getTargetSchemaCode(), entity.getTargetSchemaVersion(), entity.getResultSchemaCode(),
                    entity.getResultSchemaVersion(), true);
        } else if (target == CatalogEntityStatus.ARCHIVED) {
            deletionValidator.validateSku(entity.getId());
        }
        entity.setStatus(target.getCode());
        entity.setUpdatedBy(operatorId);
        entity.setUpdatedAt(now());
        requireUpdated(skuMapper.updateById(entity));
        refreshPriceDependencies(List.of(entity.getId()));
    }

    /**
     * 原子创建 SPU 与至少一个 SKU。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#413-spusku-复合创建postsupply-chainproductsspus}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public String createProduct(SaveSpuCommand command) {
        // 默认定价同步初始化与复合保存共用RC，见docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#34-闭环四同步重算的锁顺序与并发事务规范。
        com.aifuli.module.supplychain.tradecore.supply.application.SupplierLifecycleTransactions.requireReadCommitted();
        log.info("创建平台商品 spuCode={} spuName={} sellableCategory={} productType={} productClassification={} "
                        + "skuCount={} operatorId={}",
                command.spuCode(), command.spuName(), command.sellableCategory(), command.productType(),
                command.productClassification(), command.skus() == null ? 0 : command.skus().size(),
                command.operatorId());
        if (command.skus() == null || command.skus().isEmpty()) {
            throw exception(PRODUCT_SKU_REQUIRED);
        }
        PlatformSpuDO spu = insertSpu(command);
        CatalogEntityStatus spuStatus = CatalogEntityStatus.fromCode(spu.getStatus());
        Set<String> requestCodes = new HashSet<>();
        List<String> defaultPricingInitializationSkuCodes = new ArrayList<>();
        for (SaveSkuLine line : command.skus()) {
            String skuCode = scopedBusinessCodeService.allocateChild(ScopedBusinessCodeService.Kind.SKU,
                    spu.getId(), spu.getCodeScopeNo(), line.skuCode(), command.operatorId()).code();
            if (!requestCodes.add(skuCode)) {
                throw exception(PRODUCT_SKU_CODE_DUPLICATE);
            }
            if (hasText(line.fulfillmentType()) && !line.fulfillmentType().trim().equals(spu.getFulfillmentType())) {
                throw exception(PRODUCT_SKU_FIELD_INVALID);
            }
            SaveSkuLine inheritedLine = line.withFulfillmentType(spu.getFulfillmentType());
            insertSku(spu, skuCode, inheritedLine, spuStatus, command.operatorId(), spu.getCreatedAt());
            defaultPricingInitializationSkuCodes.add(skuCode);
        }
        eventPublisher.publishEvent(new PlatformSkuPricingInitializationEvent(
                defaultPricingInitializationSkuCodes, command.operatorId()));
        return spu.getSpuCode();
    }

    /**
     * SPU 校验与落库的唯一路径，由复合创建与存量兼容入口共享，确保动态商品类型准入、品牌与类目有效性、
     * 辅助类目、配送模式与限购区间在新旧接口上完全一致。
     * {@code productClassification} 先认封闭枚举名，否则按类目树 {@code category_code} 解析并原样落库（d34）。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#45-存量主数据接口治理m1masterdataadmincontroller}（d9）、
     * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d34）。
     */
    private PlatformSpuDO insertSpu(SaveSpuCommand command) {
        ScopedBusinessCodeService.Allocation allocation = scopedBusinessCodeService.allocateRoot(
                ScopedBusinessCodeService.Kind.SPU, command.spuCode(), command.operatorId());
        String spuCode = allocation.code();
        CatalogEntityStatus spuStatus = writableStatus(command.status(), CatalogEntityStatus.ACTIVE);
        boolean activeRequired = spuStatus == CatalogEntityStatus.ACTIVE;
        BrandDO brand = resolveBrand(command.brandId(), command.brandCode(), activeRequired);
        ProductCategoryDO primary = requireCategoryById(command.primaryCategoryId(), activeRequired);
        List<Long> secondary = validateSecondaryCategories(command.secondaryCategoryIds(), activeRequired);
        String productType = productTypeValidationService.requireActiveForUpdate(command.productType()).getTypeCode();
        String sellable = ClosedEnums.parseRequired(SellableCategory.class, command.sellableCategory()).name();
        String classification = productClassificationValidationService.requireWritable(
                command.productClassification(), activeRequired, primary == null ? null : primary.getId());
        String deliveryType = DeliveryType.parseOrDefault(command.deliveryType()).code();
        String fulfillmentType = hasText(command.fulfillmentType())
                ? FulfillmentType.fromCode(command.fulfillmentType().trim()).code()
                : inferFulfillmentTypeFromSkus(command.skus());
        validateQuantity(command.minQuantity(), command.maxQuantity());
        LocalDateTime now = now();
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .spuCode(spuCode).codeScopeNo((short) allocation.number()).brandId(brand.getId())
                .primaryCategoryId(primary == null ? null : primary.getId())
                .sellableCategory(sellable).productType(productType).productClassification(classification)
                .secondaryCategoryIds(secondary).spuName(command.spuName().trim()).subtitle(command.subtitle())
                .coverUrl(command.coverUrl()).sliderImageUrls(command.sliderImageUrls())
                .useInstruction(command.useInstruction()).description(command.description())
                .status(spuStatus.getCode()).deliveryType(deliveryType).fulfillmentType(fulfillmentType)
                .minQuantity(defaultLong(command.minQuantity(), DEFAULT_MIN_QUANTITY))
                .maxQuantity(defaultLong(command.maxQuantity(), DEFAULT_MAX_QUANTITY))
                .version(0L).createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        spuMapper.insert(spu);
        return spu;
    }

    /** 全量更新 SPU，并按 SKU 编码执行新增、修改和安全停用。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void updateProduct(String spuCode, SaveSpuCommand command) {
        // 默认定价同步初始化与复合保存共用RC，见docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#34-闭环四同步重算的锁顺序与并发事务规范。
        com.aifuli.module.supplychain.tradecore.supply.application.SupplierLifecycleTransactions.requireReadCommitted();
        log.info("更新平台商品 spuCode={} skuCount={} operatorId={}", spuCode,
                command.skus() == null ? 0 : command.skus().size(), command.operatorId());
        if (command.skus() == null || command.skus().isEmpty()) {
            throw exception(PRODUCT_SKU_REQUIRED);
        }
        PlatformSpuDO spu = requireSpuForUpdate(spuCode);
        boolean spuStatusChanged = command.status() != null && command.status() != spu.getStatus();
        CatalogEntityStatus spuStatus = writableStatus(command.status(), CatalogEntityStatus.fromCode(spu.getStatus()));
        BrandDO brand = resolveBrand(command.brandId(), command.brandCode(), spuStatus == CatalogEntityStatus.ACTIVE);
        ProductCategoryDO primary = requireCategoryById(command.primaryCategoryId(), spuStatus == CatalogEntityStatus.ACTIVE);
        String productType = productTypeValidationService.requireActiveForUpdate(command.productType()).getTypeCode();
        validateQuantity(command.minQuantity(), command.maxQuantity());
        spu.setBrandId(brand.getId());
        spu.setPrimaryCategoryId(primary == null ? null : primary.getId());
        spu.setSecondaryCategoryIds(validateSecondaryCategories(command.secondaryCategoryIds(),
                spuStatus == CatalogEntityStatus.ACTIVE));
        spu.setSellableCategory(ClosedEnums.parseRequired(SellableCategory.class, command.sellableCategory()).name());
        spu.setProductType(productType);
        spu.setProductClassification(productClassificationValidationService.requireWritable(
                command.productClassification(), spuStatus == CatalogEntityStatus.ACTIVE,
                primary == null ? null : primary.getId()));
        spu.setSpuName(command.spuName().trim());
        spu.setSubtitle(command.subtitle());
        spu.setCoverUrl(command.coverUrl());
        spu.setSliderImageUrls(command.sliderImageUrls());
        spu.setUseInstruction(command.useInstruction());
        spu.setDescription(command.description());
        spu.setDeliveryType(DeliveryType.parseOrDefault(command.deliveryType()).code());
        String targetFulfillmentType = hasText(command.fulfillmentType())
                ? FulfillmentType.fromCode(command.fulfillmentType().trim()).code()
                : (hasText(spu.getFulfillmentType())
                        ? spu.getFulfillmentType()
                        : inferFulfillmentTypeFromSkus(command.skus()));
        if (spu.getFulfillmentType() != null && !spu.getFulfillmentType().equals(targetFulfillmentType)) {
            if (spu.getStatus() != CatalogEntityStatus.DRAFT.getCode()) {
                throw exception(PRODUCT_FULFILLMENT_TYPE_IMMUTABLE);
            }
            try {
                deletionValidator.validateSpu(spu.getId());
            } catch (Exception ex) {
                throw exception(PRODUCT_FULFILLMENT_TYPE_IMMUTABLE);
            }
            spu.setFulfillmentType(targetFulfillmentType);
        } else if (spu.getFulfillmentType() == null) {
            spu.setFulfillmentType(targetFulfillmentType);
        }
        spu.setMinQuantity(defaultLong(command.minQuantity(), DEFAULT_MIN_QUANTITY));
        spu.setMaxQuantity(defaultLong(command.maxQuantity(), DEFAULT_MAX_QUANTITY));
        spu.setStatus(spuStatus.getCode());
        spu.setUpdatedBy(command.operatorId());
        spu.setUpdatedAt(now());
        requireUpdated(spuMapper.updateById(spu));

        List<PlatformSkuDO> snapshots = skuMapper.selectList(new LambdaQueryWrapper<PlatformSkuDO>()
                .eq(PlatformSkuDO::getSpuId, spu.getId())
                .ne(PlatformSkuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode()));
        // 人工批量定价仅读取父商品，因此本入口也要在任何子更新前按主键升序预锁，不能等初始化监听器才排序。
        // 设计见 docs/superpowers/specs/2026-10-11-platform-product-batch-shelf-sync-design.md#41-复审修正显式-null-与相交多-sku-批次锁顺序。
        Map<String, PlatformSkuDO> lockedSkus = PlatformSkuBatchLock.lockExistingRows(skuMapper, snapshots);
        if (lockedSkus.size() != snapshots.size()) throw exception(PRODUCT_CONCURRENT_UPDATE);
        List<PlatformSkuDO> existing = List.copyOf(lockedSkus.values());
        Map<String, PlatformSkuDO> byCode = new HashMap<>();
        existing.forEach(sku -> byCode.put(sku.getSkuCode(), sku));
        Set<String> requested = new HashSet<>();
        Set<String> defaultPricingInitializationSkuCodes = new LinkedHashSet<>();
        Set<Long> defaultPricingInitializationSkuIds = new LinkedHashSet<>();
        Set<Long> priceDependencyChangedSkuIds = new LinkedHashSet<>();
        LocalDateTime now = now();
        for (SaveSkuLine line : command.skus()) {
            String code = line.skuCode() == null || line.skuCode().isEmpty()
                    ? scopedBusinessCodeService.allocateChild(ScopedBusinessCodeService.Kind.SKU,
                    spu.getId(), spu.getCodeScopeNo(), null, command.operatorId()).code()
                    : line.skuCode();
            if (!requested.add(code)) throw exception(PRODUCT_SKU_CODE_DUPLICATE);
            PlatformSkuDO sku = byCode.get(code);
            // 存量规格保持独立状态；只有新增规格的缺省状态继承父商品。
            // 设计见 docs/superpowers/specs/2026-10-11-platform-product-batch-shelf-sync-design.md#5-结果与页面交互。
            CatalogEntityStatus skuStatus = writableStatus(line.status(), sku == null ? spuStatus
                    : CatalogEntityStatus.fromCode(sku.getStatus()));
            if (hasText(line.fulfillmentType()) && !line.fulfillmentType().trim().equals(spu.getFulfillmentType())) {
                throw exception(PRODUCT_SKU_FIELD_INVALID);
            }
            SaveSkuLine inheritedLine = line.withFulfillmentType(spu.getFulfillmentType());
            SchemaDefinition schema = validateSkuLine(inheritedLine, skuStatus == CatalogEntityStatus.ACTIVE);
            if (sku == null) {
                if (line.skuCode() != null && !line.skuCode().isEmpty()) {
                    scopedBusinessCodeService.allocateChild(ScopedBusinessCodeService.Kind.SKU,
                            spu.getId(), spu.getCodeScopeNo(), code, command.operatorId());
                }
                PlatformSkuDO inserted = insertValidatedSku(spu, code, inheritedLine, skuStatus, schema, command.operatorId(), now);
                defaultPricingInitializationSkuCodes.add(code);
                defaultPricingInitializationSkuIds.add(inserted.getId());
            } else {
                Short previousSkuStatus = sku.getStatus();
                Long previousMarketPrice = sku.getMarketPrice();
                Long previousSuggestedSalePrice = sku.getSuggestedSalePrice();
                SuggestedSalePriceRule previousSuggestedRule = sku.getSuggestedSalePriceRuleJson();
                applySku(sku, inheritedLine, skuStatus, schema, command.operatorId(), now);
                requireUpdated(skuMapper.updateById(sku));
                PlatformSkuPricingDO pricing = platformSkuPricingMapper.selectById(sku.getId());
                if (pricing == null || pricing.getCurrentVersionId() == null) {
                    defaultPricingInitializationSkuCodes.add(code);
                    defaultPricingInitializationSkuIds.add(sku.getId());
                } else if (spuStatusChanged || !java.util.Objects.equals(previousSkuStatus, sku.getStatus())
                        || (sku.getSuggestedSalePriceRuleJson() != null && sku.getSuggestedSalePriceRuleJson().usesSupplyPrice())
                        || (previousSuggestedRule != null && previousSuggestedRule.usesSupplyPrice())
                        || !java.util.Objects.equals(previousMarketPrice, sku.getMarketPrice())
                        || !java.util.Objects.equals(previousSuggestedSalePrice, sku.getSuggestedSalePrice())) {
                    priceDependencyChangedSkuIds.add(sku.getId());
                }
            }
        }
        for (PlatformSkuDO sku : existing) {
            if (!requested.contains(sku.getSkuCode()) && sku.getStatus() != CatalogEntityStatus.INACTIVE.getCode()) {
                if (!hasExternalReference(spuCode, sku)) {
                    sku.setStatus(CatalogEntityStatus.INACTIVE.getCode());
                    sku.setUpdatedBy(command.operatorId());
                    sku.setUpdatedAt(now);
                    requireUpdated(skuMapper.updateById(sku));
                    priceDependencyChangedSkuIds.add(sku.getId());
                }
            }
            if (spuStatusChanged) priceDependencyChangedSkuIds.add(sku.getId());
        }
        priceDependencyChangedSkuIds.removeAll(defaultPricingInitializationSkuIds);
        // 初始化与重算的合并目标先统一锁定，避免高ID初始化后低ID重算与供给批次形成锁环。
        // 设计见docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#34-闭环四同步重算的锁顺序与并发事务规范。
        List<Long> allPricingSkuIds = new ArrayList<>(priceDependencyChangedSkuIds);
        allPricingSkuIds.addAll(defaultPricingInitializationSkuIds);
        com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingBatchLock.lockOrCreateByIds(
                platformSkuPricingMapper, allPricingSkuIds, now);
        // 复合保存把尚未发布平台定价的 SKU 初始化为系统默认规则；已有版本只在基础价变化时按原规则重算。
        // 两个集合互斥，确保一个 SKU 在单次保存中最多生成一个平台定价版本。
        // 设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态。
        if (!defaultPricingInitializationSkuCodes.isEmpty()) {
            eventPublisher.publishEvent(new PlatformSkuPricingInitializationEvent(
                    List.copyOf(defaultPricingInitializationSkuCodes), command.operatorId()));
        }
        priceDependencyChangedSkuIds.stream().sorted().forEach(id -> eventPublisher.publishEvent(new PricingDependencyChangedEvent(id)));
    }

    /**
     * 判断请求中未出现的存量 SKU 是否仍被下游引用。按 4.1.4 节语义，仍被引用的 SKU 保留原状态而不停用，
     * 因此只吸收 {@link ProductDeletionValidator} 的引用类错误码；并发修改等其它失败必须继续上抛，
     * 不得被当作「存在引用」而静默跳过。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#414-spusku-复合更新putsupply-chainproductsspusspucode}。
     */
    private boolean hasExternalReference(String spuCode, PlatformSkuDO sku) {
        try {
            deletionValidator.validateSku(sku.getId());
            return false;
        } catch (ServiceException referenced) {
            if (!REFERENCE_ERROR_CODES.contains(referenced.getCode())) {
                throw referenced;
            }
            log.warn("保留仍被下游引用的 SKU，跳过停用 spuCode={} skuCode={} errorCode={}",
                    spuCode, sku.getSkuCode(), referenced.getCode(), referenced);
            return true;
        }
    }

    /**
     * SPU 逻辑删除（归档）。必须自带事务：先前实现通过自调用 {@code changeSpuStatus} 绕过了 Spring 代理，
     * 导致行锁、4 表引用校验与归档写入分处不同事务。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#416-spu-逻辑删除-deletesupply-chainproductsspusspucode}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void deleteSpu(String spuCode, Long operatorId) {
        applySpuStatus(spuCode, CatalogEntityStatus.ARCHIVED, operatorId);
    }

    /** SKU 逻辑删除（归档），事务边界约束同 {@link #deleteSpu}。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void deleteSku(String skuCode, Long operatorId) {
        applySkuStatus(skuCode, CatalogEntityStatus.ARCHIVED, operatorId);
    }

    /**
     * 平台启停影响供给资格，须收齐全批根后才逐个同步刷新，不能持某个子层锁再锁后续根。
     * 设计见 docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#61-主动变化。
     */
    private void refreshPriceDependencies(java.util.Collection<Long> ids) {
        var targets = ids.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingBatchLock.lockOrCreateByIds(
                platformSkuPricingMapper, targets, now());
        targets.forEach(id -> eventPublisher.publishEvent(new PricingDependencyChangedEvent(id)));
    }

    private PlatformSkuDO insertSku(PlatformSpuDO spu, String skuCode, SaveSkuLine line,
                                    CatalogEntityStatus defaultStatus, Long operatorId, LocalDateTime now) {
        CatalogEntityStatus status = writableStatus(line.status(), defaultStatus);
        SchemaDefinition schema = validateSkuLine(line, status == CatalogEntityStatus.ACTIVE);
        return insertValidatedSku(spu, skuCode, line, status, schema, operatorId, now);
    }

    private PlatformSkuDO insertValidatedSku(PlatformSpuDO spu, String skuCode, SaveSkuLine line,
                                             CatalogEntityStatus status, SchemaDefinition schema,
                                             Long operatorId, LocalDateTime now) {
        long marketPrice = defaultLong(line.marketPrice(), DEFAULT_PRICE_FEN);
        SuggestedSalePriceCalculator.Calculation suggestedPrice = resolveSuggestedSalePrice(line, marketPrice, null);
        PlatformSkuDO sku = PlatformSkuDO.builder().skuCode(skuCode).spuId(spu.getId())
                .skuName(line.skuName().trim()).mainImageUrl(normalizeOptionalText(line.mainImageUrl()))
                .detailImageUrls(normalizeDetailImageUrls(line.detailImageUrls()))
                .description(normalizeOptionalText(line.description())).fulfillmentType(line.fulfillmentType())
                .faceValue(line.faceValue()).marketPrice(marketPrice)
                .suggestedSalePrice(suggestedPrice.suggestedSalePriceCents())
                .suggestedSalePriceRuleJson(suggestedPrice.normalizedRule())
                .taxIncluded(Boolean.TRUE.equals(line.taxIncluded())).taxRate(line.taxRate())
                .minQuantity(defaultLong(line.minQuantity(), DEFAULT_MIN_QUANTITY))
                .maxQuantity(defaultLong(line.maxQuantity(), DEFAULT_MAX_QUANTITY))
                .userLimitEnabled(false).userLimitPeriod(null).userLimitQuantity(null)
                .orderLimitEnabled(Boolean.TRUE.equals(line.orderLimitEnabled()))
                .phoneBindLimitEnabled(false).phoneBindLimitPeriod(null).phoneBindLimitQuantity(null)
                // SKU 资料库存：未传时落库 0。不是 InventorySource，本期履约不预占、不扣减。
                // 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线（d38）。
                .skuInventory(defaultLong(line.skuInventory(), 0L))
                .currency(CURRENCY_CNY).targetSchemaCode(schema.targetSchemaCode())
                .targetSchemaVersion(schema.targetSchemaVersion()).resultSchemaCode(schema.resultSchemaCode())
                .resultSchemaVersion(schema.resultSchemaVersion()).status(status.getCode()).version(0L)
                .createdBy(operatorId).createdAt(now).updatedBy(operatorId).updatedAt(now).build();
        applyUserLimit(sku, line, false);
        applyPhoneBindLimit(sku, line, false);
        skuMapper.insert(sku);
        // 每个新 SKU 必须显式拥有商品默认定价身份；没有有效版本时保持 UNPRICED，禁止用户端展示和下单。
        // 设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态。
        platformSkuPricingMapper.insert(PlatformSkuPricingDO.builder()
                .platformSkuId(sku.getId()).pricingStatus(PricingStatus.UNPRICED.name())
                .version(0L).createdAt(now).updatedAt(now).build());
        return sku;
    }

    private void applySku(PlatformSkuDO sku, SaveSkuLine line, CatalogEntityStatus status, SchemaDefinition schema,
                          Long operatorId, LocalDateTime now) {
        long marketPrice = defaultLong(line.marketPrice(), DEFAULT_PRICE_FEN);
        SuggestedSalePriceCalculator.Calculation suggestedPrice = resolveSuggestedSalePrice(line, marketPrice, sku);
        sku.setSkuName(line.skuName().trim());
        sku.setMainImageUrl(normalizeOptionalText(line.mainImageUrl()));
        sku.setDetailImageUrls(normalizeDetailImageUrls(line.detailImageUrls()));
        sku.setDescription(normalizeOptionalText(line.description()));
        sku.setFulfillmentType(line.fulfillmentType());
        sku.setFaceValue(line.faceValue());
        sku.setMarketPrice(marketPrice);
        sku.setSuggestedSalePrice(suggestedPrice.suggestedSalePriceCents());
        sku.setSuggestedSalePriceRuleJson(suggestedPrice.normalizedRule());
        // 兼容尚未携带新增税务字段的旧商品编辑请求：两字段均缺省时保留原值；显式传含税标识时全量替换税务元数据。
        if (line.taxIncluded() != null) {
            sku.setTaxIncluded(line.taxIncluded());
            sku.setTaxRate(line.taxRate());
        } else if (line.taxRate() != null) {
            sku.setTaxRate(line.taxRate());
        }
        // 旧调用方未传 skuInventory 时保留原值；显式 0 会覆盖。
        // 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线（d38）。
        if (line.skuInventory() != null) {
            sku.setSkuInventory(line.skuInventory());
        }
        sku.setMinQuantity(defaultLong(line.minQuantity(), DEFAULT_MIN_QUANTITY));
        sku.setMaxQuantity(defaultLong(line.maxQuantity(), DEFAULT_MAX_QUANTITY));
        applyUserLimit(sku, line, true);
        applyPhoneBindLimit(sku, line, true);
        if (line.orderLimitEnabled() != null) {
            sku.setOrderLimitEnabled(line.orderLimitEnabled());
        }
        sku.setCurrency(CURRENCY_CNY);
        sku.setTargetSchemaCode(schema.targetSchemaCode());
        sku.setTargetSchemaVersion(schema.targetSchemaVersion());
        sku.setResultSchemaCode(schema.resultSchemaCode());
        sku.setResultSchemaVersion(schema.resultSchemaVersion());
        sku.setStatus(status.getCode());
        sku.setUpdatedBy(operatorId);
        sku.setUpdatedAt(now);
    }

    private SchemaDefinition validateSkuLine(SaveSkuLine line, boolean active) {
        if (line == null || !hasText(line.skuName()) || !hasText(line.fulfillmentType())
                || line.faceValue() == null || line.faceValue() < 0
                || defaultLong(line.marketPrice(), DEFAULT_PRICE_FEN) < 0
                || (line.suggestedSalePrice() != null && line.suggestedSalePrice() < 0)
                || (line.skuInventory() != null && line.skuInventory() < 0)) {
            throw exception(PRODUCT_SKU_FIELD_INVALID);
        }
        validateSkuDisplayContent(line);
        validateTaxMetadata(Boolean.TRUE.equals(line.taxIncluded()), line.taxRate());
        FulfillmentType fulfillmentType = FulfillmentType.fromCode(line.fulfillmentType());
        validateQuantity(line.minQuantity(), line.maxQuantity());
        validatePurchaseLimitInput(line);
        boolean explicitSchemaPresent = hasText(line.targetSchemaCode()) || line.targetSchemaVersion() != null
                || hasText(line.resultSchemaCode()) || line.resultSchemaVersion() != null;
        if (!explicitSchemaPresent) {
            return skuSchemaRegistry.requireByFulfillmentType(fulfillmentType);
        }
        validateSchema(line.targetSchemaCode(), line.targetSchemaVersion(), line.resultSchemaCode(),
                line.resultSchemaVersion(), active);
        return new SchemaDefinition(blankToNull(line.targetSchemaCode()), line.targetSchemaVersion(),
                line.resultSchemaCode().trim(), line.resultSchemaVersion());
    }

    /**
     * 校验并规范化 SKU 每人限购配置。兼容编辑请求未携带四个新增字段时保留存量配置；显式关闭时
     * 清空周期与数量，避免停用配置继续形成歧义数据。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。
     */
    private static void applyUserLimit(PlatformSkuDO sku, SaveSkuLine line, boolean preserveWhenAbsent) {
        boolean noUserLimitFields = line.userLimitEnabled() == null
                && line.userLimitPeriod() == null && line.userLimitQuantity() == null;
        if (preserveWhenAbsent && noUserLimitFields) {
            return;
        }
        boolean enabled = Boolean.TRUE.equals(line.userLimitEnabled());
        sku.setUserLimitEnabled(enabled);
        if (!enabled) {
            sku.setUserLimitPeriod(null);
            sku.setUserLimitQuantity(null);
            return;
        }
        sku.setUserLimitPeriod(PurchaseLimitPeriod.fromCode(line.userLimitPeriod()).name());
        sku.setUserLimitQuantity(line.userLimitQuantity());
    }

    /**
     * 校验并规范化 SKU 绑定手机号限购。省略字段时保留存量；显式关闭时清空周期与数量；
     * 仅直充与账户绑定权益允许开启。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。
     */
    private static void applyPhoneBindLimit(PlatformSkuDO sku, SaveSkuLine line, boolean preserveWhenAbsent) {
        boolean noPhoneBindFields = line.phoneBindLimitEnabled() == null
                && line.phoneBindLimitPeriod() == null && line.phoneBindLimitQuantity() == null;
        if (preserveWhenAbsent && noPhoneBindFields) {
            if (Boolean.TRUE.equals(sku.getPhoneBindLimitEnabled())
                    && !FulfillmentType.fromCode(sku.getFulfillmentType()).supportsPhoneBindLimit()) {
                throw exception(PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID);
            }
            return;
        }
        boolean enabled = Boolean.TRUE.equals(line.phoneBindLimitEnabled());
        sku.setPhoneBindLimitEnabled(enabled);
        if (!enabled) {
            sku.setPhoneBindLimitPeriod(null);
            sku.setPhoneBindLimitQuantity(null);
            return;
        }
        if (!FulfillmentType.fromCode(sku.getFulfillmentType()).supportsPhoneBindLimit()) {
            throw exception(PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID);
        }
        sku.setPhoneBindLimitPeriod(PurchaseLimitPeriod.fromCode(line.phoneBindLimitPeriod()).name());
        sku.setPhoneBindLimitQuantity(line.phoneBindLimitQuantity());
    }

    private static void validatePurchaseLimitInput(SaveSkuLine line) {
        validateUserLimitInput(line);
        validatePhoneBindLimitInput(line);
    }

    private static void validateUserLimitInput(SaveSkuLine line) {
        boolean enabled = Boolean.TRUE.equals(line.userLimitEnabled());
        boolean hasPeriod = hasText(line.userLimitPeriod());
        boolean hasQuantity = line.userLimitQuantity() != null;
        if (line.userLimitEnabled() == null && !hasPeriod && !hasQuantity) {
            return;
        }
        if (!enabled) {
            // 开关关闭时忽略表单残留的周期/数量，由 applyUserLimit 规范化为 NULL。
            // 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线（d37）。
            return;
        }
        if (!hasPeriod || line.userLimitQuantity() == null || line.userLimitQuantity() <= 0) {
            throw exception(PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID);
        }
        try {
            PurchaseLimitPeriod.fromCode(line.userLimitPeriod());
        } catch (IllegalArgumentException invalidPeriod) {
            throw exception(PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID);
        }
    }

    private static void validatePhoneBindLimitInput(SaveSkuLine line) {
        boolean enabled = Boolean.TRUE.equals(line.phoneBindLimitEnabled());
        boolean hasPeriod = hasText(line.phoneBindLimitPeriod());
        boolean hasQuantity = line.phoneBindLimitQuantity() != null;
        if (line.phoneBindLimitEnabled() == null && !hasPeriod && !hasQuantity) {
            return;
        }
        if (!enabled) {
            // 开关关闭时忽略表单残留的周期/数量，由 applyPhoneBindLimit 规范化为 NULL。
            // 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线（d37）。
            return;
        }
        if (!hasPeriod || line.phoneBindLimitQuantity() == null || line.phoneBindLimitQuantity() <= 0
                || !FulfillmentType.fromCode(line.fulfillmentType()).supportsPhoneBindLimit()) {
            throw exception(PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID);
        }
        try {
            PurchaseLimitPeriod.fromCode(line.phoneBindLimitPeriod());
        } catch (IllegalArgumentException invalidPeriod) {
            throw exception(PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID);
        }
    }

    private void validateSchema(String targetCode, Integer targetVersion, String resultCode,
                                Integer resultVersion, boolean targetRequired) {
        boolean targetCodePresent = hasText(targetCode);
        boolean targetVersionPresent = targetVersion != null;
        if (targetCodePresent != targetVersionPresent || (targetRequired && !targetCodePresent)
                || !hasText(resultCode) || PLACEHOLDER_SCHEMA_CODE.equalsIgnoreCase(resultCode.trim())
                || resultVersion == null || resultVersion < 1) {
            throw exception(PRODUCT_SCHEMA_INVALID);
        }
        if (targetCodePresent) {
            try {
                targetSchemaRegistry.requireSupportedSchema(targetCode.trim(), targetVersion);
            } catch (FulfillmentTargetSchemaRegistry.InvalidTargetException invalid) {
                throw exception(PRODUCT_SCHEMA_INVALID);
            }
        }
    }

    /**
     * 品牌标识双轨解析与容错。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#413-spusku-复合创建post-supply-chainproductsspus}（d33）。
     *
     * <p>解析规则：
     * 1. 优先使用 {@code brandCode} 查询；
     * 2. 其次使用 {@code brandId} 主键查询；
     * 3. 若按 {@code brandId} 未查到，尝试将 {@code brandId} 作为业务编码容错查询（兼容前端将纯数字编码绑定至 ID）；
     * 4. 查不到或状态无效（已归档/非启用）则抛出 {@link com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants#BRAND_NOT_FOUND}。
     * </p>
     */
    private BrandDO resolveBrand(Long brandId, String brandCode, boolean activeRequired) {
        BrandDO brand = null;
        if (hasText(brandCode)) {
            brand = brandMapper.selectByCode(brandCode.trim());
        }
        if (brand == null && brandId != null) {
            brand = brandMapper.selectById(brandId);
            if (brand == null) {
                // 容错兜底：前端可能将全数字 brandCode（如 "1111"）填入 brandId
                brand = brandMapper.selectByCode(String.valueOf(brandId));
            }
        }
        if (brand == null || brand.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()
                || (activeRequired && brand.getStatus() != CatalogEntityStatus.ACTIVE.getCode())) {
            throw exception(BRAND_NOT_FOUND);
        }
        return brand;
    }

    private BrandDO requireBrandById(Long brandId, boolean activeRequired) {
        return resolveBrand(brandId, null, activeRequired);
    }

    private ProductCategoryDO requireCategoryById(Long categoryId, boolean activeRequired) {
        ProductCategoryDO category = categoryId == null ? null : categoryMapper.selectById(categoryId);
        if (category == null || category.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()
                || (activeRequired && category.getStatus() != CatalogEntityStatus.ACTIVE.getCode())) {
            throw exception(CATEGORY_NOT_FOUND);
        }
        return category;
    }

    private List<Long> validateSecondaryCategories(List<Long> categoryIds, boolean activeRequired) {
        if (categoryIds == null || categoryIds.isEmpty()) return List.of();
        List<Long> distinct = categoryIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        for (Long categoryId : distinct) requireCategoryById(categoryId, activeRequired);
        return distinct;
    }

    private static CatalogEntityStatus writableStatus(Short code, CatalogEntityStatus defaultStatus) {
        CatalogEntityStatus status = code == null ? defaultStatus : CatalogEntityStatus.fromCode(code);
        if (status == CatalogEntityStatus.ARCHIVED) throw exception(PRODUCT_STATUS_INVALID);
        return status;
    }

    private static void validateQuantity(Long minQuantity, Long maxQuantity) {
        long min = defaultLong(minQuantity, DEFAULT_MIN_QUANTITY);
        long max = defaultLong(maxQuantity, DEFAULT_MAX_QUANTITY);
        if (min < DEFAULT_MIN_QUANTITY || max < min) throw exception(PRODUCT_QUANTITY_RANGE_INVALID);
    }

    private static long defaultLong(Long value, long defaultValue) {
        return value == null ? defaultValue : value;
    }

    /**
     * 解析新旧两类请求并生成唯一可信的建议价结果。
     * 新请求提交规则时服务端重新计算；旧请求只提交变化后的金额时归一成 FIXED。编辑请求两者都省略，或旧
     * 管理端原样回传未变化市场价与建议价时保留存量规则，避免编辑其它字段把已保存规则重置或降级。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4112-sku-销售建议价规则写入收口}。
     */
    private SuggestedSalePriceCalculator.Calculation resolveSuggestedSalePrice(
            SaveSkuLine line, long marketPrice, PlatformSkuDO existing) {
        SuggestedSalePriceRule requestedRule = line.suggestedSalePriceRule();
        if (requestedRule == null) {
            if (existing != null && existing.getSuggestedSalePriceRuleJson() != null
                    && existing.getSuggestedSalePriceRuleJson().usesSupplyPrice() && line.suggestedSalePrice() != null) {
                // 旧页面金额不是改成FIXED的意图证明，避免并发供给变化被旧金额静默覆盖。
                // 设计见 docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#7-前端与接口。
                throw exception(PRODUCT_SUGGESTED_PRICE_RULE_INVALID);
            }
            if (line.suggestedSalePrice() != null) {
                // 未升级的管理端会在编辑其它字段时原样回传金额。市场价与建议价都未变化时保留已保存规则，
                // 避免一次无关编辑把 CUSTOM/EQUAL_MARKET_PRICE 静默降级成 FIXED。
                if (existing != null
                        && line.suggestedSalePrice().equals(existing.getSuggestedSalePrice())
                        && marketPrice == defaultLong(existing.getMarketPrice(), DEFAULT_PRICE_FEN)) {
                    return existingSuggestedSalePrice(existing);
                }
                requestedRule = SuggestedSalePriceRule.fixed(line.suggestedSalePrice());
            } else if (existing != null) {
                return existingSuggestedSalePrice(existing);
            } else {
                requestedRule = SuggestedSalePriceRule.fixed(DEFAULT_PRICE_FEN);
            }
        }
        // 公式先校验并保存；供应价的有效金额由根锁内独立状态提供，兼容列不能写NULL或客户端旧金额。
        // 设计见 docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#41-独立建议价计算状态。
        if (requestedRule.usesSupplyPrice()) {
            if (line.suggestedSalePrice() != null) throw exception(PRODUCT_SUGGESTED_PRICE_RULE_INVALID);
            var normalized = calculateSuggestedSalePrice(requestedRule, marketPrice, null).normalizedRule();
            return new SuggestedSalePriceCalculator.Calculation(existing == null ? DEFAULT_PRICE_FEN
                    : defaultLong(existing.getSuggestedSalePrice(), DEFAULT_PRICE_FEN), normalized);
        }
        Long previousPrice = existing == null ? null : existing.getSuggestedSalePrice();
        if (existing != null && existing.getSuggestedSalePriceRuleJson() != null
                && existing.getSuggestedSalePriceRuleJson().usesSupplyPrice()
                && requestedRule.base() == SuggestedSalePriceRule.Base.SUGGESTED_SALE_PRICE) {
            previousPrice = suggestedPriceResolver.resolve(existing, now()).amountCents();
        }
        SuggestedSalePriceCalculator.Calculation calculation = calculateSuggestedSalePrice(requestedRule, marketPrice, previousPrice);
        if (line.suggestedSalePrice() != null
                && !line.suggestedSalePrice().equals(calculation.suggestedSalePriceCents())) {
            throw exception(PRODUCT_SUGGESTED_PRICE_RULE_INVALID);
        }
        return calculation;
    }

    private static SuggestedSalePriceCalculator.Calculation existingSuggestedSalePrice(PlatformSkuDO existing) {
        long existingAmount = defaultLong(existing.getSuggestedSalePrice(), DEFAULT_PRICE_FEN);
        SuggestedSalePriceRule existingRule = existing.getSuggestedSalePriceRuleJson() != null
                ? existing.getSuggestedSalePriceRuleJson()
                : SuggestedSalePriceRule.fixed(existingAmount);
        return new SuggestedSalePriceCalculator.Calculation(existingAmount, existingRule);
    }

    private static SuggestedSalePriceCalculator.Calculation calculateSuggestedSalePrice(
            SuggestedSalePriceRule rule, Long marketPrice, Long previousSuggestedSalePrice) {
        try {
            return SuggestedSalePriceCalculator.calculate(rule,
                    defaultLong(marketPrice, DEFAULT_PRICE_FEN), previousSuggestedSalePrice);
        } catch (RuntimeException invalidRule) {
            throw exception(PRODUCT_SUGGESTED_PRICE_RULE_INVALID);
        }
    }

    private static void validateTaxMetadata(Boolean taxIncluded, BigDecimal taxRate) {
        if (taxIncluded == null || (taxRate != null
                && (taxRate.compareTo(BigDecimal.ZERO) < 0 || taxRate.compareTo(BigDecimal.ONE) > 0))) {
            throw exception(PRODUCT_SKU_FIELD_INVALID);
        }
    }

    private static List<String> normalizeSkuCodes(List<String> skuCodes) {
        if (skuCodes == null || skuCodes.isEmpty() || skuCodes.size() > MAX_BATCH_SKU_COUNT) {
            throw exception(PRODUCT_SKU_FIELD_INVALID);
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String skuCode : skuCodes) {
            if (!hasText(skuCode)) {
                throw exception(PRODUCT_SKU_FIELD_INVALID);
            }
            normalized.add(skuCode.trim());
        }
        return List.copyOf(normalized);
    }

    private static String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    /**
     * 校验 SKU 主图、详情图与描述，确保绕过 Controller 的兼容调用也不能写入超过 DDL 长度的数据。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#212-sku-展示资料034-changeset}。
     */
    private static void validateSkuDisplayContent(SaveSkuLine line) {
        if ((hasText(line.mainImageUrl()) && line.mainImageUrl().trim().length() > MAX_SKU_IMAGE_URL_LENGTH)
                || (hasText(line.description())
                && line.description().trim().length() > MAX_SKU_DESCRIPTION_LENGTH)) {
            throw exception(PRODUCT_SKU_FIELD_INVALID);
        }
        normalizeDetailImageUrls(line.detailImageUrls());
    }

    private static List<String> normalizeDetailImageUrls(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return null;
        }
        List<String> normalized = imageUrls.stream().map(url -> {
            if (!hasText(url) || url.trim().length() > MAX_SKU_IMAGE_URL_LENGTH) {
                throw exception(PRODUCT_SKU_FIELD_INVALID);
            }
            return url.trim();
        }).toList();
        return List.copyOf(normalized);
    }

    private static String normalizeOptionalText(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private BuyerDO requireActiveBuyer(String buyerCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode);
        if (buyer == null || buyer.getStatus() != BuyerStatus.ACTIVE.getCode()) {
            throw exception(BUYER_NOT_FOUND);
        }
        return buyer;
    }

    private BrandDO requireBrand(String code) {
        BrandDO entity = brandMapper.selectByCode(code);
        if (entity == null || entity.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()) {
            throw exception(BRAND_NOT_FOUND);
        }
        return entity;
    }

    private ProductCategoryDO requireCategory(String code) {
        ProductCategoryDO entity = categoryMapper.selectByCode(code);
        if (entity == null || entity.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()) {
            throw exception(CATEGORY_NOT_FOUND);
        }
        return entity;
    }

    private PlatformSpuDO requireSpu(String code) {
        PlatformSpuDO entity = spuMapper.selectByCode(code);
        if (entity == null || entity.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()) {
            throw exception(PLATFORM_SPU_NOT_FOUND);
        }
        return entity;
    }

    private PlatformSkuDO requireSku(String code) {
        PlatformSkuDO entity = skuMapper.selectByCode(code);
        if (entity == null || entity.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()) {
            throw exception(PLATFORM_SKU_NOT_FOUND);
        }
        return entity;
    }

    private PlatformSpuDO requireSpuForUpdate(String code) {
        PlatformSpuDO entity = spuMapper.selectByCodeForUpdate(code);
        if (entity == null || entity.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()) {
            throw exception(PLATFORM_SPU_NOT_FOUND);
        }
        return entity;
    }

    private PlatformSkuDO requireSkuForUpdate(String code) {
        PlatformSkuDO entity = skuMapper.selectByCodeForUpdate(code);
        if (entity == null || entity.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()) {
            throw exception(PLATFORM_SKU_NOT_FOUND);
        }
        return entity;
    }

    /**
     * 校验商品生命周期状态迁移。
     * <p><b>同状态幂等放行（方案 A）</b>：当实体当前状态与目标状态一致时直接放行，视为幂等成功，
     * 避免运营人员在批量操作已达目标状态的商品时大面积报错。
     * <p><b>非法流转精准业务拒绝</b>：当状态不合法时，抛出 {@code PRODUCT_STATUS_TRANSITION_NOT_ALLOWED}
     * 并动态携带商品名称、编码、当前状态与操作约束提示，告别模糊晦涩的底层技术报错。
     * <p>设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4110-批量上下架接口put-supply-chainproductsbatch-status}。
     */
    private static void checkTransition(short currentCode, CatalogEntityStatus target,
                                        String businessCode, String businessName) {
        CatalogEntityStatus current = CatalogEntityStatus.fromCode(currentCode);
        if (current == target) {
            return;
        }
        if (!current.canTransitionTo(target)) {
            String targetDesc = target.getChineseName();
            if (current == CatalogEntityStatus.DRAFT && target == CatalogEntityStatus.INACTIVE) {
                targetDesc = target.getChineseName() + "（草稿请先上架或归档）";
            } else if (current == CatalogEntityStatus.ARCHIVED) {
                targetDesc = target.getChineseName() + "（已归档商品不可变更状态）";
            }
            String nameDesc = hasText(businessName) ? businessName.trim() : (hasText(businessCode) ? businessCode.trim() : "-");
            String codeDesc = hasText(businessCode) ? businessCode.trim() : "-";
            throw exception(PRODUCT_STATUS_TRANSITION_NOT_ALLOWED, nameDesc, codeDesc, current.getChineseName(), targetDesc);
        }
    }

    private static void requireUpdated(int count) {
        if (count != 1) {
            throw exception(PRODUCT_CONCURRENT_UPDATE);
        }
    }

    private LocalDateTime now() {
        return databaseClock.now();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String inferFulfillmentTypeFromSkus(List<SaveSkuLine> skus) {
        if (skus != null) {
            for (SaveSkuLine sku : skus) {
                if (sku != null && hasText(sku.fulfillmentType())) {
                    return FulfillmentType.fromCode(sku.fulfillmentType().trim()).code();
                }
            }
        }
        return FulfillmentType.DIRECT_RECHARGE.code();
    }

    public record CreateBrandCommand(String brandCode, String brandName, String englishName,
                                     String logoUrl, Integer sort, Long operatorId) {
    }

    public record CreateCategoryCommand(String categoryCode, String categoryName,
                                        String parentCategoryCode, Integer sort, Long operatorId) {
    }

    public record CreateSpuCommand(String spuCode, String brandCode, String primaryCategoryCode,
                                   String sellableCategory, String productType, String productClassification,
                                   String spuName, String subtitle, String coverUrl,
                                   String useInstruction, String fulfillmentType, Long operatorId) {
        public CreateSpuCommand(String spuCode, String brandCode, String primaryCategoryCode,
                                String sellableCategory, String productType, String productClassification,
                                String spuName, String subtitle, String coverUrl,
                                String useInstruction, Long operatorId) {
            this(spuCode, brandCode, primaryCategoryCode, sellableCategory, productType, productClassification,
                    spuName, subtitle, coverUrl, useInstruction, null, operatorId);
        }
    }

    public record CreateSkuCommand(String skuCode, String spuCode, String skuName,
                                   String fulfillmentType, long faceValue, String currency,
                                   Long operatorId) {
    }

    public record CreateBuyerListingCommand(String buyerCode, String platformSkuCode,
                                            String buyerSkuCode, Long operatorId) {
    }

    public record SaveSpuCommand(String spuCode, String spuName, String subtitle, Long brandId, String brandCode,
                                 Long primaryCategoryId, List<Long> secondaryCategoryIds,
                                 String sellableCategory, String productType, String productClassification,
                                 String coverUrl, List<String> sliderImageUrls, String useInstruction,
                                 String description, String deliveryType, String fulfillmentType,
                                 Long minQuantity, Long maxQuantity,
                                 Short status, List<SaveSkuLine> skus, Long operatorId) {
        public SaveSpuCommand(String spuCode, String spuName, String subtitle, Long brandId,
                             Long primaryCategoryId, List<Long> secondaryCategoryIds,
                             String sellableCategory, String productType, String productClassification,
                             String coverUrl, List<String> sliderImageUrls, String useInstruction,
                             String description, String deliveryType, Long minQuantity, Long maxQuantity,
                             Short status, List<SaveSkuLine> skus, Long operatorId) {
            this(spuCode, spuName, subtitle, brandId, null, primaryCategoryId, secondaryCategoryIds,
                    sellableCategory, productType, productClassification, coverUrl, sliderImageUrls,
                    useInstruction, description, deliveryType,
                    (skus != null && !skus.isEmpty() && skus.get(0) != null ? skus.get(0).fulfillmentType() : null),
                    minQuantity, maxQuantity, status, skus, operatorId);
        }

        public SaveSpuCommand(String spuCode, String spuName, String subtitle, Long brandId,
                             Long primaryCategoryId, List<Long> secondaryCategoryIds,
                             String sellableCategory, String productType, String productClassification,
                             String coverUrl, List<String> sliderImageUrls, String useInstruction,
                             String description, String deliveryType, String fulfillmentType,
                             Long minQuantity, Long maxQuantity,
                             Short status, List<SaveSkuLine> skus, Long operatorId) {
            this(spuCode, spuName, subtitle, brandId, null, primaryCategoryId, secondaryCategoryIds,
                    sellableCategory, productType, productClassification, coverUrl, sliderImageUrls,
                    useInstruction, description, deliveryType, fulfillmentType,
                    minQuantity, maxQuantity, status, skus, operatorId);
        }
    }

    public record SaveSkuLine(String skuCode, String skuName, String mainImageUrl,
                              List<String> detailImageUrls, String description,
                              String fulfillmentType, Long faceValue,
                              Long marketPrice, Long suggestedSalePrice,
                              SuggestedSalePriceRule suggestedSalePriceRule,
                              Boolean taxIncluded, BigDecimal taxRate,
                              Long minQuantity, Long maxQuantity,
                              Boolean userLimitEnabled, String userLimitPeriod, Long userLimitQuantity,
                              Boolean orderLimitEnabled,
                              Boolean phoneBindLimitEnabled, String phoneBindLimitPeriod, Long phoneBindLimitQuantity,
                              Long skuInventory,
                              String targetSchemaCode, Integer targetSchemaVersion,
                              String resultSchemaCode, Integer resultSchemaVersion, Short status) {

        public SaveSkuLine withFulfillmentType(String newFulfillmentType) {
            return new SaveSkuLine(skuCode, skuName, mainImageUrl, detailImageUrls, description,
                    newFulfillmentType, faceValue, marketPrice, suggestedSalePrice, suggestedSalePriceRule,
                    taxIncluded, taxRate, minQuantity, maxQuantity,
                    userLimitEnabled, userLimitPeriod, userLimitQuantity, orderLimitEnabled,
                    phoneBindLimitEnabled, phoneBindLimitPeriod, phoneBindLimitQuantity,
                    skuInventory,
                    targetSchemaCode, targetSchemaVersion, resultSchemaCode, resultSchemaVersion, status);
        }
        /**
         * 商品中心复合接口使用的构造器：请求只含业务履约类型，Schema 由应用层注册表派生。
         * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d11）。
         */
        public SaveSkuLine(String skuCode, String skuName, String fulfillmentType, Long faceValue,
                           Long marketPrice, Long suggestedSalePrice, Long minQuantity, Long maxQuantity,
                           Short status) {
            this(skuCode, skuName, null, null, null, fulfillmentType, faceValue, marketPrice, suggestedSalePrice,
                    null, false, null, minQuantity, maxQuantity, null, null, null, null, null, null, null,
                    null, null, null, null, null, status);
        }

        /** 商品中心复合接口携带销售建议价规则的精简构造器。 */
        public SaveSkuLine(String skuCode, String skuName, String fulfillmentType, Long faceValue,
                           Long marketPrice, Long suggestedSalePrice,
                           SuggestedSalePriceRule suggestedSalePriceRule,
                           Long minQuantity, Long maxQuantity, Short status) {
            this(skuCode, skuName, null, null, null, fulfillmentType, faceValue, marketPrice, suggestedSalePrice,
                    suggestedSalePriceRule, false, null, minQuantity, maxQuantity,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, status);
        }

        /** 商品中心复合接口携带税务展示元数据的构造器，Schema 仍由应用层注册表派生。 */
        public SaveSkuLine(String skuCode, String skuName, String fulfillmentType, Long faceValue,
                           Long marketPrice, Long suggestedSalePrice, Boolean taxIncluded, BigDecimal taxRate,
                           Long minQuantity, Long maxQuantity, Short status) {
            this(skuCode, skuName, null, null, null, fulfillmentType, faceValue, marketPrice, suggestedSalePrice,
                    null, taxIncluded, taxRate, minQuantity, maxQuantity, null, null, null, null, null, null, null,
                    null, null, null, null, null, status);
        }

        /** 商品中心复合接口携带 SKU 限购配置，内部 Schema 仍由应用层注册表派生。 */
        public SaveSkuLine(String skuCode, String skuName, String fulfillmentType, Long faceValue,
                           Long marketPrice, Long suggestedSalePrice, Boolean taxIncluded, BigDecimal taxRate,
                           Long minQuantity, Long maxQuantity, Boolean userLimitEnabled,
                           String userLimitPeriod, Long userLimitQuantity, Boolean orderLimitEnabled,
                           Short status) {
            this(skuCode, skuName, null, null, null, fulfillmentType, faceValue, marketPrice, suggestedSalePrice,
                    null, taxIncluded, taxRate, minQuantity, maxQuantity, userLimitEnabled, userLimitPeriod,
                    userLimitQuantity, orderLimitEnabled, null, null, null, null, null, null, null, null, status);
        }

        /** 商品中心复合接口携带每人、每订单与绑定手机号限购配置，内部 Schema 仍由应用层注册表派生。 */
        public SaveSkuLine(String skuCode, String skuName, String fulfillmentType, Long faceValue,
                           Long marketPrice, Long suggestedSalePrice, Boolean taxIncluded, BigDecimal taxRate,
                           Long minQuantity, Long maxQuantity, Boolean userLimitEnabled,
                           String userLimitPeriod, Long userLimitQuantity, Boolean orderLimitEnabled,
                           Boolean phoneBindLimitEnabled, String phoneBindLimitPeriod, Long phoneBindLimitQuantity,
                           Short status) {
            this(skuCode, skuName, null, null, null, fulfillmentType, faceValue, marketPrice, suggestedSalePrice,
                    null, taxIncluded, taxRate, minQuantity, maxQuantity, userLimitEnabled, userLimitPeriod,
                    userLimitQuantity, orderLimitEnabled, phoneBindLimitEnabled, phoneBindLimitPeriod,
                    phoneBindLimitQuantity, null, null, null, null, null, status);
        }

        /** 保留已有显式 Schema 调用方的构造契约，税务元数据缺省为不含税且税率未配置。 */
        public SaveSkuLine(String skuCode, String skuName, String fulfillmentType, Long faceValue,
                           Long marketPrice, Long suggestedSalePrice, Long minQuantity, Long maxQuantity,
                           String targetSchemaCode, Integer targetSchemaVersion,
                           String resultSchemaCode, Integer resultSchemaVersion, Short status) {
            this(skuCode, skuName, null, null, null, fulfillmentType, faceValue, marketPrice, suggestedSalePrice,
                    null, false, null, minQuantity, maxQuantity, null, null, null, null, null, null, null,
                    null, targetSchemaCode, targetSchemaVersion, resultSchemaCode, resultSchemaVersion, status);
        }

        /** 保留税务字段引入前显式 Schema 的完整构造契约。 */
        public SaveSkuLine(String skuCode, String skuName, String fulfillmentType, Long faceValue,
                           Long marketPrice, Long suggestedSalePrice, Boolean taxIncluded, BigDecimal taxRate,
                           Long minQuantity, Long maxQuantity, String targetSchemaCode, Integer targetSchemaVersion,
                           String resultSchemaCode, Integer resultSchemaVersion, Short status) {
            this(skuCode, skuName, null, null, null, fulfillmentType, faceValue, marketPrice, suggestedSalePrice,
                    null, taxIncluded, taxRate, minQuantity, maxQuantity, null, null, null, null, null, null, null,
                    null, targetSchemaCode, targetSchemaVersion, resultSchemaCode, resultSchemaVersion, status);
        }

        /** 商品中心复合接口携带 SKU 展示资料与完整限购配置，内部 Schema 仍由应用层注册表派生。 */
        public SaveSkuLine(String skuCode, String skuName, String mainImageUrl,
                           List<String> detailImageUrls, String description,
                           String fulfillmentType, Long faceValue, Long marketPrice, Long suggestedSalePrice,
                           Boolean taxIncluded, BigDecimal taxRate, Long minQuantity, Long maxQuantity,
                           Boolean userLimitEnabled, String userLimitPeriod, Long userLimitQuantity,
                           Boolean orderLimitEnabled, Boolean phoneBindLimitEnabled,
                           String phoneBindLimitPeriod, Long phoneBindLimitQuantity, Short status) {
            this(skuCode, skuName, mainImageUrl, detailImageUrls, description, fulfillmentType, faceValue,
                    marketPrice, suggestedSalePrice, null, taxIncluded, taxRate, minQuantity, maxQuantity,
                    userLimitEnabled, userLimitPeriod, userLimitQuantity, orderLimitEnabled,
                    phoneBindLimitEnabled, phoneBindLimitPeriod, phoneBindLimitQuantity,
                    null, null, null, null, null, status);
        }

        /** 复合接口携带销售建议价规则、展示资料与完整限购配置，内部 Schema 仍由应用层注册表派生。 */
        public SaveSkuLine(String skuCode, String skuName, String mainImageUrl,
                           List<String> detailImageUrls, String description,
                           String fulfillmentType, Long faceValue, Long marketPrice, Long suggestedSalePrice,
                           SuggestedSalePriceRule suggestedSalePriceRule,
                           Boolean taxIncluded, BigDecimal taxRate, Long minQuantity, Long maxQuantity,
                           Boolean userLimitEnabled, String userLimitPeriod, Long userLimitQuantity,
                           Boolean orderLimitEnabled, Boolean phoneBindLimitEnabled,
                           String phoneBindLimitPeriod, Long phoneBindLimitQuantity, Short status) {
            this(skuCode, skuName, mainImageUrl, detailImageUrls, description, fulfillmentType, faceValue,
                    marketPrice, suggestedSalePrice, suggestedSalePriceRule, taxIncluded, taxRate,
                    minQuantity, maxQuantity, userLimitEnabled, userLimitPeriod, userLimitQuantity,
                    orderLimitEnabled, phoneBindLimitEnabled, phoneBindLimitPeriod, phoneBindLimitQuantity,
                    null, null, null, null, null, status);
        }

        /**
         * 复合接口携带销售建议价规则、展示资料、完整限购配置与 SKU 库存，内部 Schema 仍由应用层注册表派生。
         * 由 {@link ProductAdminController#toSkuLine} 调用。
         */
        public SaveSkuLine(String skuCode, String skuName, String mainImageUrl,
                           List<String> detailImageUrls, String description,
                           String fulfillmentType, Long faceValue, Long marketPrice, Long suggestedSalePrice,
                           SuggestedSalePriceRule suggestedSalePriceRule,
                           Boolean taxIncluded, BigDecimal taxRate, Long minQuantity, Long maxQuantity,
                           Boolean userLimitEnabled, String userLimitPeriod, Long userLimitQuantity,
                           Boolean orderLimitEnabled, Boolean phoneBindLimitEnabled,
                           String phoneBindLimitPeriod, Long phoneBindLimitQuantity,
                           Long skuInventory, Short status) {
            this(skuCode, skuName, mainImageUrl, detailImageUrls, description, fulfillmentType, faceValue,
                    marketPrice, suggestedSalePrice, suggestedSalePriceRule, taxIncluded, taxRate,
                    minQuantity, maxQuantity, userLimitEnabled, userLimitPeriod, userLimitQuantity,
                    orderLimitEnabled, phoneBindLimitEnabled, phoneBindLimitPeriod, phoneBindLimitQuantity,
                    skuInventory, null, null, null, null, status);
        }
    }

    /** 批量上下架命令。 */
    public record BatchStatusCommand(
            List<String> spuCodes,
            List<String> skuCodes,
            Short targetStatus,
            String unshelveMode,
            Long operatorId,
            Boolean syncSkuOnShelf) {
        public BatchStatusCommand(List<String> spuCodes, List<String> skuCodes, Short targetStatus,
                                  String unshelveMode, Long operatorId) {
            this(spuCodes, skuCodes, targetStatus, unshelveMode, operatorId, false);
        }
    }

    /**
     * 批量上下架结果。
     * <p>旧模式 succeeded 为逐实体混合编码；同步模式按去重 SPU 组计数，groups 保留完整后台成员范围。
     * failed 携带稳定错误及实际失败实体定位，任何同步失败组都没有局部提交。
     */
    public record BatchStatusResult(
            List<String> succeeded,
            List<BatchStatusFailedItem> failed,
            boolean syncSkuOnShelf,
            List<BatchStatusGroup> groups) {
        public BatchStatusResult(List<String> succeeded, List<BatchStatusFailedItem> failed) {
            this(succeeded, failed, false, List.of());
        }
    }

    /** 同步上架范围和整组结果；失败实体定位见设计 §5，SKU 编码必须属于本组范围。 */
    public record BatchStatusGroup(String spuCode, List<String> skuCodes, boolean succeeded,
                                   CatalogEntityType failedEntityType, String failedEntityCode) {
    }

    /**
     * 批量上下架失败明细项。
     *
     * @param entityType  实体类型，取值见 {@link CatalogEntityType}；对外契约取其 {@code code}
     * @param code         失败项业务编码
     * @param errorCode    稳定错误码，与 {@link com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants} 对齐
     * @param errorMessage  错误消息，与 {@link ServiceException#getMessage()} 一致
     */
    public record BatchStatusFailedItem(
            CatalogEntityType entityType,
            String code,
            Integer errorCode,
            String errorMessage,
            CatalogEntityType failedEntityType,
            String failedEntityCode) {
        public BatchStatusFailedItem(CatalogEntityType entityType, String code, Integer errorCode, String errorMessage) {
            this(entityType, code, errorCode, errorMessage, null, null);
        }
    }

    /** SKU 税务展示元数据批量记录命令。 */
    public record SkuTaxMetadataBatchCommand(List<String> skuCodes, Boolean taxIncluded,
                                              BigDecimal taxRate, Long operatorId) {
    }

}
