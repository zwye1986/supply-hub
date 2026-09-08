package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BrandDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingAvailabilityDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import com.aifuli.module.supplychain.tradecore.profile.domain.ProductClassification;
import com.aifuli.module.supplychain.tradecore.profile.domain.ProductTypeTag;
import com.aifuli.module.supplychain.tradecore.profile.domain.SellableCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ProductCatalogCommandService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final BrandMapper brandMapper;
    private final ProductCategoryMapper categoryMapper;
    private final PlatformSpuMapper spuMapper;
    private final PlatformSkuMapper skuMapper;
    private final BuyerMapper buyerMapper;
    private final BuyerListingMapper listingMapper;
    private final BuyerListingAvailabilityMapper availabilityMapper;
    private final BusinessNoGenerator businessNoGenerator;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createBrand(CreateBrandCommand command) {
        LocalDateTime now = now();
        BrandDO brand = BrandDO.builder()
                .brandCode(command.brandCode())
                .brandName(command.brandName())
                .englishName(command.englishName())
                .logoUrl(command.logoUrl())
                .status(CatalogEntityStatus.DRAFT.getCode())
                .sort(command.sort())
                .version(0L)
                .createdBy(command.operatorId())
                .createdAt(now)
                .updatedBy(command.operatorId())
                .updatedAt(now)
                .build();
        brandMapper.insert(brand);
        return brand.getBrandCode();
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createCategory(CreateCategoryCommand command) {
        ProductCategoryDO parent = command.parentCategoryCode() == null ? null
                : requireCategory(command.parentCategoryCode());
        short level = parent == null ? (short) 1 : (short) (parent.getCategoryLevel() + 1);
        LocalDateTime now = now();
        ProductCategoryDO category = ProductCategoryDO.builder()
                .categoryCode(command.categoryCode())
                .categoryName(command.categoryName())
                .parentId(parent == null ? null : parent.getId())
                .categoryLevel(level)
                .sort(command.sort())
                .status(CatalogEntityStatus.DRAFT.getCode())
                .version(0L)
                .createdBy(command.operatorId())
                .createdAt(now)
                .updatedBy(command.operatorId())
                .updatedAt(now)
                .build();
        categoryMapper.insert(category);
        return category.getCategoryCode();
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createSpu(CreateSpuCommand command) {
        BrandDO brand = requireBrand(command.brandCode());
        Long primaryCategoryId = hasText(command.primaryCategoryCode())
                ? requireCategory(command.primaryCategoryCode()).getId() : null;
        String sellableCategory = ClosedEnums.parseRequired(SellableCategory.class, command.sellableCategory()).name();
        String productType = ClosedEnums.parseRequired(ProductTypeTag.class, command.productType()).name();
        String productClassification =
                ClosedEnums.parseRequired(ProductClassification.class, command.productClassification()).name();
        LocalDateTime now = now();
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .spuCode(command.spuCode())
                .brandId(brand.getId())
                .primaryCategoryId(primaryCategoryId)
                .sellableCategory(sellableCategory)
                .productType(productType)
                .productClassification(productClassification)
                .spuName(command.spuName())
                .subtitle(command.subtitle())
                .coverUrl(command.coverUrl())
                .useInstruction(command.useInstruction())
                .status(CatalogEntityStatus.DRAFT.getCode())
                .version(0L)
                .createdBy(command.operatorId())
                .createdAt(now)
                .updatedBy(command.operatorId())
                .updatedAt(now)
                .build();
        spuMapper.insert(spu);
        return spu.getSpuCode();
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createSku(CreateSkuCommand command) {
        if (!"CNY".equals(command.currency())) {
            throw new IllegalArgumentException("Platform SKU currency must be CNY");
        }
        if (command.faceValue() < 0 || command.resultSchemaVersion() < 1
                || (command.targetSchemaVersion() != null && command.targetSchemaVersion() < 1)) {
            throw new IllegalArgumentException("Platform SKU amount or Schema version is invalid");
        }
        PlatformSpuDO spu = requireSpu(command.spuCode());
        LocalDateTime now = now();
        PlatformSkuDO sku = PlatformSkuDO.builder()
                .skuCode(command.skuCode())
                .spuId(spu.getId())
                .skuName(command.skuName())
                .fulfillmentType(command.fulfillmentType())
                .faceValue(command.faceValue())
                .currency(command.currency())
                .targetSchemaCode(command.targetSchemaCode())
                .targetSchemaVersion(command.targetSchemaVersion())
                .resultSchemaCode(command.resultSchemaCode())
                .resultSchemaVersion(command.resultSchemaVersion())
                .status(CatalogEntityStatus.DRAFT.getCode())
                .version(0L)
                .createdBy(command.operatorId())
                .createdAt(now)
                .updatedBy(command.operatorId())
                .updatedAt(now)
                .build();
        skuMapper.insert(sku);
        return sku.getSkuCode();
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String createBuyerListing(CreateBuyerListingCommand command) {
        BuyerDO buyer = requireActiveBuyer(command.buyerCode());
        PlatformSkuDO sku = requireActiveSku(command.platformSkuCode());
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
        BrandDO entity = requireBrand(code);
        checkTransition(entity.getStatus(), target, "Brand");
        entity.setStatus(target.getCode());
        touch(entity, operatorId);
        requireUpdated(brandMapper.updateById(entity), "Brand");
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeCategoryStatus(String code, CatalogEntityStatus target, Long operatorId) {
        ProductCategoryDO entity = requireCategory(code);
        checkTransition(entity.getStatus(), target, "Category");
        entity.setStatus(target.getCode());
        entity.setUpdatedBy(operatorId);
        entity.setUpdatedAt(now());
        requireUpdated(categoryMapper.updateById(entity), "Category");
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeSpuStatus(String code, CatalogEntityStatus target, Long operatorId) {
        PlatformSpuDO entity = requireSpu(code);
        checkTransition(entity.getStatus(), target, "SPU");
        entity.setStatus(target.getCode());
        entity.setUpdatedBy(operatorId);
        entity.setUpdatedAt(now());
        requireUpdated(spuMapper.updateById(entity), "SPU");
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeSkuStatus(String code, CatalogEntityStatus target, Long operatorId) {
        PlatformSkuDO entity = requireSku(code);
        checkTransition(entity.getStatus(), target, "SKU");
        entity.setStatus(target.getCode());
        entity.setUpdatedBy(operatorId);
        entity.setUpdatedAt(now());
        requireUpdated(skuMapper.updateById(entity), "SKU");
    }

    private BuyerDO requireActiveBuyer(String buyerCode) {
        BuyerDO buyer = buyerMapper.selectByBuyerCode(buyerCode);
        if (buyer == null || buyer.getStatus() != BuyerStatus.ACTIVE.getCode()) {
            throw new IllegalArgumentException("Active buyer does not exist: " + buyerCode);
        }
        return buyer;
    }

    private BrandDO requireBrand(String code) {
        BrandDO entity = brandMapper.selectByCode(code);
        if (entity == null) {
            throw new IllegalArgumentException("Brand does not exist: " + code);
        }
        return entity;
    }

    private ProductCategoryDO requireCategory(String code) {
        ProductCategoryDO entity = categoryMapper.selectByCode(code);
        if (entity == null) {
            throw new IllegalArgumentException("Category does not exist: " + code);
        }
        return entity;
    }

    private PlatformSpuDO requireSpu(String code) {
        PlatformSpuDO entity = spuMapper.selectByCode(code);
        if (entity == null) {
            throw new IllegalArgumentException("SPU does not exist: " + code);
        }
        return entity;
    }

    private PlatformSkuDO requireSku(String code) {
        PlatformSkuDO entity = skuMapper.selectByCode(code);
        if (entity == null) {
            throw new IllegalArgumentException("SKU does not exist: " + code);
        }
        return entity;
    }

    private PlatformSkuDO requireActiveSku(String code) {
        PlatformSkuDO sku = requireSku(code);
        if (sku.getStatus() != CatalogEntityStatus.ACTIVE.getCode()) {
            throw new IllegalStateException("Platform SKU is not active: " + code);
        }
        return sku;
    }

    private static void checkTransition(short currentCode, CatalogEntityStatus target, String entityName) {
        CatalogEntityStatus current = CatalogEntityStatus.fromCode(currentCode);
        if (!current.canTransitionTo(target)) {
            throw new IllegalStateException(entityName + " status transition is not allowed: "
                    + current + " -> " + target);
        }
    }

    private static void requireUpdated(int count, String entityName) {
        if (count != 1) {
            throw new IllegalStateException(entityName + " update lost optimistic lock");
        }
    }

    private void touch(BrandDO entity, Long operatorId) {
        entity.setUpdatedBy(operatorId);
        entity.setUpdatedAt(now());
    }

    private LocalDateTime now() {
        return databaseClock.now();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
                                   String useInstruction, Long operatorId) {
    }

    public record CreateSkuCommand(String skuCode, String spuCode, String skuName,
                                   String fulfillmentType, long faceValue, String currency,
                                   String targetSchemaCode, Integer targetSchemaVersion,
                                   String resultSchemaCode, int resultSchemaVersion,
                                   Long operatorId) {
    }

    public record CreateBuyerListingCommand(String buyerCode, String platformSkuCode,
                                            String buyerSkuCode, Long operatorId) {
    }
}
