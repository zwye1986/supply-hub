package com.aifuli.module.supplychain.tradecore.catalog.application;

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
import com.aifuli.module.supplychain.tradecore.catalog.domain.CategoryVisibility;
import com.aifuli.module.supplychain.tradecore.catalog.domain.DeliveryType;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.catalog.domain.PurchaseLimitPeriod;
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceCalculator;
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule;
import com.aifuli.module.supplychain.tradecore.catalog.domain.UnshelveMode;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingDependencyChangedEvent;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import com.aifuli.module.supplychain.tradecore.profile.domain.ProductClassification;
import com.aifuli.module.supplychain.tradecore.profile.domain.SellableCategory;
import com.aifuli.framework.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BRAND_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SKU_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SPU_NOT_FOUND;
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
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_FULFILLMENT_TYPE_IMMUTABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_SUGGESTED_PRICE_RULE_INVALID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCatalogCommandService {

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
    private final BrandAdminService brandAdminService;
    private final CategoryCommandService categoryCommandService;
    private final ProductTypeValidationService productTypeValidationService;
    private final ProductSellabilityService sellabilityService;
    private final ProductDeletionValidator deletionValidator;
    private final FulfillmentTargetSchemaRegistry targetSchemaRegistry;
    private final PlatformSkuSchemaRegistry skuSchemaRegistry;

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
        PlatformSpuDO spu = requireSpu(command.spuCode());
        String skuCode = hasText(command.skuCode()) ? command.skuCode().trim()
                : businessNoGenerator.next(BusinessNoPrefixes.SKU);
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
        BuyerDO buyer = requireActiveBuyer(command.buyerCode());
        PlatformSkuDO sku = sellabilityService.requireSellableByCode(command.platformSkuCode()).sku();
        LocalDateTime now = now();
        BuyerListingDO listing = BuyerListingDO.builder()
                .listingNo(businessNoGenerator.next("LST_"))
                .buyerId(buyer.getId())
                .platformSkuId(sku.getId())
                .buyerSkuCode(command.buyerSkuCode())
                .status(BuyerListingStatus.DRAFT.getCode())
                .version(0L)
                .createdBy(command.operatorId())
                .createdAt(now)
                .updatedBy(command.operatorId())
                .updatedAt(now)
                .build();
        listingMapper.insert(listing);
        availabilityMapper.insert(BuyerListingAvailabilityDO.builder()
                .listingId(listing.getId())
                .availability(ListingAvailability.UNKNOWN.getCode())
                .reasonCode("NOT_EVALUATED")
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

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeSpuStatus(String code, CatalogEntityStatus target, Long operatorId) {
        applySpuStatus(code, target, UnshelveMode.MANUAL_UNSHELVE.getCode(), operatorId);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeSpuStatus(String code, CatalogEntityStatus target, String unshelveMode, Long operatorId) {
        applySpuStatus(code, target, unshelveMode, operatorId);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeSkuStatus(String code, CatalogEntityStatus target, Long operatorId) {
        applySkuStatus(code, target, operatorId);
    }

    /**
     * 批量变更平台商品（SPU/SKU）状态。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#4110-批量上下架接口put-supply-chainproductsbatch-status}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void batchChangeStatus(BatchStatusCommand command) {
        log.info("批量变更商品状态 spuCodes={} skuCodes={} targetStatus={} unshelveMode={} operatorId={}",
                command.spuCodes(), command.skuCodes(), command.targetStatus(), command.unshelveMode(), command.operatorId());
        CatalogEntityStatus target = CatalogEntityStatus.fromCode(command.targetStatus());
        if (target != CatalogEntityStatus.ACTIVE && target != CatalogEntityStatus.INACTIVE) {
            throw exception(PRODUCT_STATUS_INVALID);
        }
        if (command.spuCodes() != null) {
            for (String spuCode : command.spuCodes()) {
                if (hasText(spuCode)) {
                    applySpuStatus(spuCode.trim(), target, command.unshelveMode(), command.operatorId());
                }
            }
        }
        if (command.skuCodes() != null) {
            for (String skuCode : command.skuCodes()) {
                if (hasText(skuCode)) {
                    applySkuStatus(skuCode.trim(), target, command.operatorId());
                }
            }
        }
    }

    /**
     * 批量记录 SKU 税务元数据。该方法只修改 {@code tax_included/tax_rate}，不得调用价格解析器或修改价格列。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#411-sku-税务元数据批量记录put-supply-chainproductsskustax-metadata}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void batchUpdateSkuTaxMetadata(SkuTaxMetadataBatchCommand command) {
        List<String> skuCodes = normalizeSkuCodes(command.skuCodes());
        validateTaxMetadata(command.taxIncluded(), command.taxRate());
        log.info("批量记录SKU税务元数据 skuCount={} taxIncluded={} taxRate={} operatorId={}",
                skuCodes.size(), command.taxIncluded(), command.taxRate(), command.operatorId());
        LocalDateTime now = now();
        for (String skuCode : skuCodes) {
            PlatformSkuDO sku = requireSkuForUpdate(skuCode);
            sku.setTaxIncluded(command.taxIncluded());
            sku.setTaxRate(command.taxRate());
            sku.setUpdatedBy(command.operatorId());
            sku.setUpdatedAt(now);
            requireUpdated(skuMapper.updateById(sku));
        }
    }

    /**
     * SPU 状态迁移。归档分支必须与 {@code selectByCodeForUpdate} 行锁处于同一事务，
     * 否则 4 表引用校验通过后仍可能被并发写入引用，故仅允许由带 {@code @Transactional} 的入口调用。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#32-删除强校验规则4-表物理主键与关联路径检查}。
     */
    private void applySpuStatus(String code, CatalogEntityStatus target, Long operatorId) {
        applySpuStatus(code, target, null, operatorId);
    }

    private void applySpuStatus(String code, CatalogEntityStatus target, String unshelveMode, Long operatorId) {
        log.info("变更平台 SPU 状态 spuCode={} targetStatus={} unshelveMode={} operatorId={}", code, target, unshelveMode, operatorId);
        PlatformSpuDO entity = requireSpuForUpdate(code);
        checkTransition(entity.getStatus(), target);
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
    }

    /** SKU 状态迁移，事务边界约束同 {@link #applySpuStatus}。 */
    private void applySkuStatus(String code, CatalogEntityStatus target, Long operatorId) {
        log.info("变更平台 SKU 状态 skuCode={} targetStatus={} operatorId={}", code, target, operatorId);
        PlatformSkuDO entity = requireSkuForUpdate(code);
        checkTransition(entity.getStatus(), target);
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
    }

    /**
     * 原子创建 SPU 与至少一个 SKU。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#413-spusku-复合创建postsupply-chainproductsspus}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createProduct(SaveSpuCommand command) {
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
        for (SaveSkuLine line : command.skus()) {
            String skuCode = hasText(line.skuCode()) ? line.skuCode().trim()
                    : businessNoGenerator.next(BusinessNoPrefixes.SKU);
            if (!requestCodes.add(skuCode)) {
                throw exception(PRODUCT_SKU_CODE_DUPLICATE);
            }
            if (hasText(line.fulfillmentType()) && !line.fulfillmentType().trim().equals(spu.getFulfillmentType())) {
                throw exception(PRODUCT_SKU_FIELD_INVALID);
            }
            SaveSkuLine inheritedLine = line.withFulfillmentType(spu.getFulfillmentType());
            insertSku(spu, skuCode, inheritedLine, spuStatus, command.operatorId(), spu.getCreatedAt());
        }
        return spu.getSpuCode();
    }

    /**
     * SPU 校验与落库的唯一路径，由复合创建与存量兼容入口共享，确保动态商品类型准入、品牌与类目有效性、
     * 辅助类目、配送模式与限购区间在新旧接口上完全一致。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#45-存量主数据接口治理m1masterdataadmincontroller}（d9）。
     */
    private PlatformSpuDO insertSpu(SaveSpuCommand command) {
        String spuCode = hasText(command.spuCode()) ? command.spuCode().trim()
                : businessNoGenerator.next(BusinessNoPrefixes.SPU);
        CatalogEntityStatus spuStatus = writableStatus(command.status(), CatalogEntityStatus.ACTIVE);
        boolean activeRequired = spuStatus == CatalogEntityStatus.ACTIVE;
        BrandDO brand = resolveBrand(command.brandId(), command.brandCode(), activeRequired);
        ProductCategoryDO primary = requireCategoryById(command.primaryCategoryId(), activeRequired);
        List<Long> secondary = validateSecondaryCategories(command.secondaryCategoryIds(), activeRequired);
        String productType = productTypeValidationService.requireActiveForUpdate(command.productType()).getTypeCode();
        // 可售品类与商品分类是供货匹配封闭枚举，不是类目树编码；未知值回显允许列表。
        // 设计见 docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#413-spusku-复合创建postsupply-chainproductsspus
        String sellable = ClosedEnums.parseRequired(SellableCategory.class, command.sellableCategory()).name();
        String classification = ClosedEnums.parseRequired(ProductClassification.class,
                command.productClassification()).name();
        String deliveryType = DeliveryType.parseOrDefault(command.deliveryType()).code();
        String fulfillmentType = hasText(command.fulfillmentType())
                ? FulfillmentType.fromCode(command.fulfillmentType().trim()).code()
                : inferFulfillmentTypeFromSkus(command.skus());
        validateQuantity(command.minQuantity(), command.maxQuantity());
        LocalDateTime now = now();
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .spuCode(spuCode).brandId(brand.getId())
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
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void updateProduct(String spuCode, SaveSpuCommand command) {
        log.info("更新平台商品 spuCode={} skuCount={} operatorId={}", spuCode,
                command.skus() == null ? 0 : command.skus().size(), command.operatorId());
        if (command.skus() == null || command.skus().isEmpty()) {
            throw exception(PRODUCT_SKU_REQUIRED);
        }
        PlatformSpuDO spu = requireSpuForUpdate(spuCode);
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
        spu.setProductClassification(ClosedEnums.parseRequired(ProductClassification.class,
                command.productClassification()).name());
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

        List<PlatformSkuDO> existing = skuMapper.selectList(new LambdaQueryWrapper<PlatformSkuDO>()
                .eq(PlatformSkuDO::getSpuId, spu.getId())
                .ne(PlatformSkuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode()));
        Map<String, PlatformSkuDO> byCode = new HashMap<>();
        existing.forEach(sku -> byCode.put(sku.getSkuCode(), sku));
        Set<String> requested = new HashSet<>();
        Set<Long> priceDependencyChangedSkuIds = new LinkedHashSet<>();
        LocalDateTime now = now();
        for (SaveSkuLine line : command.skus()) {
            String code = hasText(line.skuCode()) ? line.skuCode().trim()
                    : businessNoGenerator.next(BusinessNoPrefixes.SKU);
            if (!requested.add(code)) throw exception(PRODUCT_SKU_CODE_DUPLICATE);
            PlatformSkuDO sku = byCode.get(code);
            CatalogEntityStatus skuStatus = writableStatus(line.status(), spuStatus);
            if (hasText(line.fulfillmentType()) && !line.fulfillmentType().trim().equals(spu.getFulfillmentType())) {
                throw exception(PRODUCT_SKU_FIELD_INVALID);
            }
            SaveSkuLine inheritedLine = line.withFulfillmentType(spu.getFulfillmentType());
            SchemaDefinition schema = validateSkuLine(inheritedLine, skuStatus == CatalogEntityStatus.ACTIVE);
            if (sku == null) {
                insertValidatedSku(spu, code, inheritedLine, skuStatus, schema, command.operatorId(), now);
            } else {
                Long previousMarketPrice = sku.getMarketPrice();
                Long previousSuggestedSalePrice = sku.getSuggestedSalePrice();
                applySku(sku, inheritedLine, skuStatus, schema, command.operatorId(), now);
                requireUpdated(skuMapper.updateById(sku));
                if (!java.util.Objects.equals(previousMarketPrice, sku.getMarketPrice())
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
                }
            }
        }
        // 基础价是公式依赖。主数据写入完成后，在同一事务中为所有已定价分销商/项目生成新不可变版本。
        // 设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态。
        priceDependencyChangedSkuIds.forEach(id -> eventPublisher.publishEvent(new PricingDependencyChangedEvent(id)));
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
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void deleteSpu(String spuCode, Long operatorId) {
        applySpuStatus(spuCode, CatalogEntityStatus.ARCHIVED, operatorId);
    }

    /** SKU 逻辑删除（归档），事务边界约束同 {@link #deleteSpu}。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void deleteSku(String skuCode, Long operatorId) {
        applySkuStatus(skuCode, CatalogEntityStatus.ARCHIVED, operatorId);
    }

    private void insertSku(PlatformSpuDO spu, String skuCode, SaveSkuLine line,
                           CatalogEntityStatus defaultStatus, Long operatorId, LocalDateTime now) {
        CatalogEntityStatus status = writableStatus(line.status(), defaultStatus);
        SchemaDefinition schema = validateSkuLine(line, status == CatalogEntityStatus.ACTIVE);
        insertValidatedSku(spu, skuCode, line, status, schema, operatorId, now);
    }

    private void insertValidatedSku(PlatformSpuDO spu, String skuCode, SaveSkuLine line,
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
                || (line.suggestedSalePrice() != null && line.suggestedSalePrice() < 0)) {
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
            if (hasPeriod || hasQuantity) {
                throw exception(PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID);
            }
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
            if (hasPeriod || hasQuantity) {
                throw exception(PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID);
            }
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
    private static SuggestedSalePriceCalculator.Calculation resolveSuggestedSalePrice(
            SaveSkuLine line, long marketPrice, PlatformSkuDO existing) {
        SuggestedSalePriceRule requestedRule = line.suggestedSalePriceRule();
        if (requestedRule == null) {
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
        SuggestedSalePriceCalculator.Calculation calculation = calculateSuggestedSalePrice(
                requestedRule, marketPrice, existing == null ? null : existing.getSuggestedSalePrice());
        if (line.suggestedSalePrice() != null
                && line.suggestedSalePrice() != calculation.suggestedSalePriceCents()) {
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

    private static void checkTransition(short currentCode, CatalogEntityStatus target) {
        CatalogEntityStatus current = CatalogEntityStatus.fromCode(currentCode);
        if (!current.canTransitionTo(target)) {
            throw exception(PRODUCT_STATUS_INVALID);
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
                              String targetSchemaCode, Integer targetSchemaVersion,
                              String resultSchemaCode, Integer resultSchemaVersion, Short status) {

        public SaveSkuLine withFulfillmentType(String newFulfillmentType) {
            return new SaveSkuLine(skuCode, skuName, mainImageUrl, detailImageUrls, description,
                    newFulfillmentType, faceValue, marketPrice, suggestedSalePrice, suggestedSalePriceRule,
                    taxIncluded, taxRate, minQuantity, maxQuantity,
                    userLimitEnabled, userLimitPeriod, userLimitQuantity, orderLimitEnabled,
                    phoneBindLimitEnabled, phoneBindLimitPeriod, phoneBindLimitQuantity,
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
                    null, null, null, null, status);
        }

        /** 商品中心复合接口携带销售建议价规则的精简构造器。 */
        public SaveSkuLine(String skuCode, String skuName, String fulfillmentType, Long faceValue,
                           Long marketPrice, Long suggestedSalePrice,
                           SuggestedSalePriceRule suggestedSalePriceRule,
                           Long minQuantity, Long maxQuantity, Short status) {
            this(skuCode, skuName, null, null, null, fulfillmentType, faceValue, marketPrice, suggestedSalePrice,
                    suggestedSalePriceRule, false, null, minQuantity, maxQuantity,
                    null, null, null, null, null, null, null,
                    null, null, null, null, status);
        }

        /** 商品中心复合接口携带税务展示元数据的构造器，Schema 仍由应用层注册表派生。 */
        public SaveSkuLine(String skuCode, String skuName, String fulfillmentType, Long faceValue,
                           Long marketPrice, Long suggestedSalePrice, Boolean taxIncluded, BigDecimal taxRate,
                           Long minQuantity, Long maxQuantity, Short status) {
            this(skuCode, skuName, null, null, null, fulfillmentType, faceValue, marketPrice, suggestedSalePrice,
                    null, taxIncluded, taxRate, minQuantity, maxQuantity, null, null, null, null, null, null, null,
                    null, null, null, null, status);
        }

        /** 商品中心复合接口携带 SKU 限购配置，内部 Schema 仍由应用层注册表派生。 */
        public SaveSkuLine(String skuCode, String skuName, String fulfillmentType, Long faceValue,
                           Long marketPrice, Long suggestedSalePrice, Boolean taxIncluded, BigDecimal taxRate,
                           Long minQuantity, Long maxQuantity, Boolean userLimitEnabled,
                           String userLimitPeriod, Long userLimitQuantity, Boolean orderLimitEnabled,
                           Short status) {
            this(skuCode, skuName, null, null, null, fulfillmentType, faceValue, marketPrice, suggestedSalePrice,
                    null, taxIncluded, taxRate, minQuantity, maxQuantity, userLimitEnabled, userLimitPeriod,
                    userLimitQuantity, orderLimitEnabled, null, null, null, null, null, null, null, status);
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
                    phoneBindLimitQuantity, null, null, null, null, status);
        }

        /** 保留已有显式 Schema 调用方的构造契约，税务元数据缺省为不含税且税率未配置。 */
        public SaveSkuLine(String skuCode, String skuName, String fulfillmentType, Long faceValue,
                           Long marketPrice, Long suggestedSalePrice, Long minQuantity, Long maxQuantity,
                           String targetSchemaCode, Integer targetSchemaVersion,
                           String resultSchemaCode, Integer resultSchemaVersion, Short status) {
            this(skuCode, skuName, null, null, null, fulfillmentType, faceValue, marketPrice, suggestedSalePrice,
                    null, false, null, minQuantity, maxQuantity, null, null, null, null, null, null, null,
                    targetSchemaCode, targetSchemaVersion, resultSchemaCode, resultSchemaVersion, status);
        }

        /** 保留税务字段引入前显式 Schema 的完整构造契约。 */
        public SaveSkuLine(String skuCode, String skuName, String fulfillmentType, Long faceValue,
                           Long marketPrice, Long suggestedSalePrice, Boolean taxIncluded, BigDecimal taxRate,
                           Long minQuantity, Long maxQuantity, String targetSchemaCode, Integer targetSchemaVersion,
                           String resultSchemaCode, Integer resultSchemaVersion, Short status) {
            this(skuCode, skuName, null, null, null, fulfillmentType, faceValue, marketPrice, suggestedSalePrice,
                    null, taxIncluded, taxRate, minQuantity, maxQuantity, null, null, null, null, null, null, null,
                    targetSchemaCode, targetSchemaVersion, resultSchemaCode, resultSchemaVersion, status);
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
                    null, null, null, null, status);
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
                    null, null, null, null, status);
        }
    }

    /** 批量上下架命令。 */
    public record BatchStatusCommand(
            List<String> spuCodes,
            List<String> skuCodes,
            Short targetStatus,
            String unshelveMode,
            Long operatorId) {
    }

    /** SKU 税务展示元数据批量记录命令。 */
    public record SkuTaxMetadataBatchCommand(List<String> skuCodes, Boolean taxIncluded,
                                              BigDecimal taxRate, Long operatorId) {
    }

}
