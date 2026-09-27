package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentTargetSchemaRegistry;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BrandDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingAvailabilityMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingDependencyChangedEvent;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PlatformSkuPricingInitializationEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.aifuli.module.supplychain.tradecore.profile.domain.ProductClassification;
import com.aifuli.module.supplychain.tradecore.profile.domain.SellableCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CANNOT_DELETE_HAS_SUPPLIER_OFFER;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BRAND_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CONCURRENT_UPDATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_FULFILLMENT_TYPE_IMMUTABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_SKU_FIELD_INVALID;
import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * 平台商品写服务单测，聚焦复合更新中「省略 SKU」的停用降级语义。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#414-spusku-复合更新putsupply-chainproductsspusspucode}。
 */
class ProductCatalogCommandServiceTest {

    private static final String SPU_CODE = "SPU_1";

    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final BrandMapper brandMapper = mock(BrandMapper.class);
    private final ProductCategoryMapper categoryMapper = mock(ProductCategoryMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final PlatformSkuPricingMapper platformSkuPricingMapper = mock(PlatformSkuPricingMapper.class);
    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerListingMapper listingMapper = mock(BuyerListingMapper.class);
    private final BuyerListingAvailabilityMapper availabilityMapper = mock(BuyerListingAvailabilityMapper.class);
    private final BusinessNoGenerator generator = mock(BusinessNoGenerator.class);
    private final com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService scopedBusinessCodeService =
            mock(com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService.class);
    private final BrandAdminService brandAdminService = mock(BrandAdminService.class);
    private final CategoryCommandService categoryCommandService = mock(CategoryCommandService.class);
    private final ProductTypeValidationService productTypeValidationService =
            mock(ProductTypeValidationService.class);
    private final ProductClassificationValidationService productClassificationValidationService =
            new ProductClassificationValidationService(categoryMapper);
    private final ProductSellabilityService sellabilityService = mock(ProductSellabilityService.class);
    private final ProductDeletionValidator deletionValidator = mock(ProductDeletionValidator.class);
    private final FulfillmentTargetSchemaRegistry targetSchemaRegistry = mock(FulfillmentTargetSchemaRegistry.class);
    private final PlatformSkuSchemaRegistry skuSchemaRegistry = new PlatformSkuSchemaRegistry(targetSchemaRegistry);

    private final ProductCatalogCommandService service = new ProductCatalogCommandService(
            clock, eventPublisher, brandMapper, categoryMapper, spuMapper, skuMapper, platformSkuPricingMapper,
            buyerMapper, listingMapper,
            availabilityMapper, generator,
            scopedBusinessCodeService,
            brandAdminService, categoryCommandService,
            productTypeValidationService, productClassificationValidationService, sellabilityService, deletionValidator, targetSchemaRegistry,
            skuSchemaRegistry);

    @BeforeEach
    void setUpCodes() {
        when(scopedBusinessCodeService.allocateRoot(any(), any())).thenAnswer(inv -> {
            String requested = inv.getArgument(1);
            return new com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService.Allocation(
                    requested == null ? generator.next(BusinessNoPrefixes.SPU) : requested, 1);
        });
        when(scopedBusinessCodeService.allocateChild(any(), anyLong(), anyInt(), any())).thenAnswer(inv -> {
            String requested = inv.getArgument(3);
            return new com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService.Allocation(
                    requested == null ? generator.next(BusinessNoPrefixes.SKU) : requested, 1);
        });
        when(spuMapper.insert(any(PlatformSpuDO.class))).thenAnswer(inv -> {
            PlatformSpuDO row = inv.getArgument(0);
            row.setId(1L);
            return 1;
        });
    }

    /** 请求中省略、且仍被下游引用的 SKU 保留原状态，不被停用，且整体更新继续成功。 */
    @Test
    void omittedSkuStillReferencedIsKeptActive() {
        PlatformSkuDO omitted = givenUpdateWithOneOmittedSku();
        doThrow(exception(PRODUCT_CANNOT_DELETE_HAS_SUPPLIER_OFFER))
                .when(deletionValidator).validateSku(omitted.getId());

        service.updateProduct(SPU_CODE, saveCommand());

        assertThat(omitted.getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
    }

    /** 旧调用方只提交最终金额时，服务端必须补齐 FIXED 规则，保证编辑详情可以精确回显来源。 */
    @Test
    void legacySuggestedSalePriceIsNormalizedToFixedRule() {
        givenUpdateWithOneOmittedSku();
        ProductCatalogCommandService.SaveSkuLine line = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.CARD_CODE_SECRET.code(),
                100L, 10_000L, 7_777L, 1L, 10L, CatalogEntityStatus.DRAFT.getCode());

        service.updateProduct(SPU_CODE, saveCommand(line));

        ArgumentCaptor<PlatformSkuDO> captor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(sku -> "SKU_KEEP".equals(sku.getSkuCode()))
                .singleElement().satisfies(sku -> {
                    assertThat(sku.getSuggestedSalePrice()).isEqualTo(7_777L);
                    assertThat(sku.getSuggestedSalePriceRuleJson().mode())
                            .isEqualTo(SuggestedSalePriceRule.Mode.FIXED);
                    assertThat(sku.getSuggestedSalePriceRuleJson().fixedPriceCents()).isEqualTo(7_777L);
                });
        verify(eventPublisher).publishEvent(new PricingDependencyChangedEvent(10L));
    }

    /** 旧管理端原样回传未变化金额时保留既有规则，避免编辑其它字段造成规则静默丢失。 */
    @Test
    void unchangedLegacyAmountPreservesExistingSuggestedSalePriceRule() {
        givenUpdateWithOneOmittedSku();
        SuggestedSalePriceRule customRule = new SuggestedSalePriceRule(
                SuggestedSalePriceRule.Mode.CUSTOM, SuggestedSalePriceRule.Base.MARKET_PRICE,
                new BigDecimal("50"), SuggestedSalePriceRule.Operator.ADD,
                new BigDecimal("1.00"), null, null);
        PlatformSkuDO keep = PlatformSkuDO.builder().id(10L).spuId(1L).skuCode("SKU_KEEP")
                .fulfillmentType(FulfillmentType.CARD_CODE_SECRET.code())
                .marketPrice(10_000L).suggestedSalePrice(5_100L).suggestedSalePriceRuleJson(customRule)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(skuMapper.selectList(any())).thenReturn(List.of(keep));
        ProductCatalogCommandService.SaveSkuLine unchanged = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.CARD_CODE_SECRET.code(),
                100L, 10_000L, 5_100L, 1L, 10L, CatalogEntityStatus.DRAFT.getCode());

        service.updateProduct(SPU_CODE, saveCommand(unchanged));

        ArgumentCaptor<PlatformSkuDO> captor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(sku -> "SKU_KEEP".equals(sku.getSkuCode()))
                .singleElement().satisfies(sku -> {
                    assertThat(sku.getSuggestedSalePrice()).isEqualTo(5_100L);
                    assertThat(sku.getSuggestedSalePriceRuleJson()).isEqualTo(customRule);
                });
        verify(eventPublisher, never()).publishEvent(new PricingDependencyChangedEvent(10L));
    }

    /** 历史未定价 SKU 在复合更新时补发系统默认定价初始化，价格变化也不得再触发第二次依赖重算。 */
    @Test
    void updateInitializesUnpricedSkuWithoutPublishingDependencyRecalculation() {
        givenUpdateWithOneOmittedSku();
        when(platformSkuPricingMapper.selectById(10L)).thenReturn(PlatformSkuPricingDO.builder()
                .platformSkuId(10L).pricingStatus("UNPRICED").build());
        ProductCatalogCommandService.SaveSkuLine changed = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.CARD_CODE_SECRET.code(),
                100L, 10_000L, 7_777L, 1L, 10L, CatalogEntityStatus.DRAFT.getCode());

        service.updateProduct(SPU_CODE, saveCommand(changed));

        verify(eventPublisher).publishEvent(new PlatformSkuPricingInitializationEvent(List.of("SKU_KEEP"), 1L));
        verify(eventPublisher, never()).publishEvent(new PricingDependencyChangedEvent(10L));
    }

    /** 复合编辑的自定义规则由服务端计算，客户端不得用不一致的 suggestedSalePrice 覆盖结果。 */
    @Test
    void customRuleIsCalculatedByServerAndMismatchedClientResultIsRejected() {
        givenUpdateWithOneOmittedSku();
        SuggestedSalePriceRule rule = new SuggestedSalePriceRule(
                SuggestedSalePriceRule.Mode.CUSTOM, SuggestedSalePriceRule.Base.MARKET_PRICE,
                new BigDecimal("50"), SuggestedSalePriceRule.Operator.ADD,
                new BigDecimal("1.00"), null, null);
        ProductCatalogCommandService.SaveSkuLine valid = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.CARD_CODE_SECRET.code(),
                100L, 10_000L, null, rule, 1L, 10L, CatalogEntityStatus.DRAFT.getCode());

        service.updateProduct(SPU_CODE, saveCommand(valid));

        ArgumentCaptor<PlatformSkuDO> captor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(sku -> "SKU_KEEP".equals(sku.getSkuCode()))
                .singleElement().satisfies(sku -> {
                    assertThat(sku.getSuggestedSalePrice()).isEqualTo(5_100L);
                    assertThat(sku.getSuggestedSalePriceRuleJson()).isEqualTo(rule);
                });

        ProductCatalogCommandService.SaveSkuLine mismatch = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.CARD_CODE_SECRET.code(),
                100L, 10_000L, 5_099L, rule, 1L, 10L, CatalogEntityStatus.DRAFT.getCode());
        assertThatThrownBy(() -> service.updateProduct(SPU_CODE, saveCommand(mismatch)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants
                        .PRODUCT_SUGGESTED_PRICE_RULE_INVALID.getCode());
    }

    /** 省略且无引用的 SKU 被安全停用。 */
    @Test
    void omittedSkuWithoutReferenceIsDeactivated() {
        PlatformSkuDO omitted = givenUpdateWithOneOmittedSku();
        doNothing().when(deletionValidator).validateSku(omitted.getId());

        service.updateProduct(SPU_CODE, saveCommand());

        assertThat(omitted.getStatus()).isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
    }

    /**
     * 引用校验之外的失败（如并发修改）必须继续上抛，不得被当作「存在引用」而静默跳过——
     * 否则会掩盖真实的并发冲突。
     */
    @Test
    void nonReferenceFailureIsNotSwallowed() {
        PlatformSkuDO omitted = givenUpdateWithOneOmittedSku();
        doThrow(exception(PRODUCT_CONCURRENT_UPDATE)).when(deletionValidator).validateSku(omitted.getId());

        assertThatThrownBy(() -> service.updateProduct(SPU_CODE, saveCommand()))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_CONCURRENT_UPDATE.getCode());
    }

    /** 含税标识与税率只更新两个元数据字段，既有价格字段必须保持不变。 */
    @Test
    void batchTaxMetadataDoesNotChangeAnyPrice() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 14, 19, 0);
        when(clock.now()).thenReturn(now);
        PlatformSkuDO sku = PlatformSkuDO.builder().id(10L).skuCode("SKU_TAX")
                .marketPrice(2_000L).suggestedSalePrice(1_800L)
                .taxIncluded(false).taxRate(null).status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(skuMapper.selectByCodeForUpdate("SKU_TAX")).thenReturn(sku);
        when(skuMapper.updateById(any(PlatformSkuDO.class))).thenReturn(1);

        service.batchUpdateSkuTaxMetadata(new ProductCatalogCommandService.SkuTaxMetadataBatchCommand(
                List.of("SKU_TAX"), true, new BigDecimal("0.0600"), 7L));

        ArgumentCaptor<PlatformSkuDO> captor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper).updateById(captor.capture());
        assertThat(captor.getValue().getTaxIncluded()).isTrue();
        assertThat(captor.getValue().getTaxRate()).isEqualByComparingTo("0.0600");
        assertThat(captor.getValue().getMarketPrice()).isEqualTo(2_000L);
        assertThat(captor.getValue().getSuggestedSalePrice()).isEqualTo(1_800L);
    }

    /** SKU 主图、详情图与描述通过复合编辑统一规范化并保存。 */
    @Test
    void updatesSkuDisplayContent() {
        givenUpdateWithOneOmittedSku();
        ProductCatalogCommandService.SaveSkuLine line = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", " https://example.com/main.png ",
                List.of(" https://example.com/detail-1.png ", "https://example.com/detail-2.png"),
                " 商品描述 ", FulfillmentType.CARD_CODE_SECRET.code(), 100L, 0L, 0L,
                false, null, 1L, 10L, false, null, null, false,
                false, null, null, CatalogEntityStatus.DRAFT.getCode());

        service.updateProduct(SPU_CODE, saveCommand(line));

        ArgumentCaptor<PlatformSkuDO> captor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(sku -> "SKU_KEEP".equals(sku.getSkuCode()))
                .singleElement().satisfies(sku -> {
                    assertThat(sku.getMainImageUrl()).isEqualTo("https://example.com/main.png");
                    assertThat(sku.getDetailImageUrls()).containsExactly(
                            "https://example.com/detail-1.png", "https://example.com/detail-2.png");
                    assertThat(sku.getDescription()).isEqualTo("商品描述");
                });
    }

    /** 图片地址和描述长度约束必须在应用层拦截，不能依赖数据库截断。 */
    @Test
    void rejectsOversizedSkuDisplayContent() {
        givenUpdateWithOneOmittedSku();
        ProductCatalogCommandService.SaveSkuLine line = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", "x".repeat(513), null, null,
                FulfillmentType.CARD_CODE_SECRET.code(), 100L, 0L, 0L,
                false, null, 1L, 10L, false, null, null, false,
                false, null, null, CatalogEntityStatus.DRAFT.getCode());

        assertThatThrownBy(() -> service.updateProduct(SPU_CODE, saveCommand(line)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_SKU_FIELD_INVALID.getCode());
    }

    /** 原型商品分类是必填的供应链类目主键，应用层必须先于数据库非空约束明确拒绝。 */
    @Test
    void rejectsMissingPrimaryCategory() {
        givenUpdateWithOneOmittedSku();
        ProductCatalogCommandService.SaveSpuCommand valid = saveCommand();
        ProductCatalogCommandService.SaveSpuCommand missingCategory = new ProductCatalogCommandService.SaveSpuCommand(
                valid.spuCode(), valid.spuName(), valid.subtitle(), valid.brandId(), null,
                valid.secondaryCategoryIds(), valid.sellableCategory(), valid.productType(),
                valid.productClassification(), valid.coverUrl(), valid.sliderImageUrls(), valid.useInstruction(),
                valid.description(), valid.deliveryType(), valid.minQuantity(), valid.maxQuantity(), valid.status(),
                valid.skus(), valid.operatorId());

        assertThatThrownBy(() -> service.updateProduct(SPU_CODE, missingCategory))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(CATEGORY_NOT_FOUND.getCode());
    }

    /** 开启每人限购时，周期与正整数数量必须同时提供。 */
    @Test
    void rejectsIncompletePersonalPurchaseLimitConfiguration() {
        givenUpdateWithOneOmittedSku();
        ProductCatalogCommandService.SaveSkuLine invalid = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.CARD_CODE_SECRET.code(), 100L, 0L, 0L,
                false, null, 1L, 10L, true, null, 2L, true,
                CatalogEntityStatus.DRAFT.getCode());

        assertThatThrownBy(() -> service.updateProduct(SPU_CODE, saveCommand(invalid)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID.getCode());
    }

    /** 未知周期必须拒绝，避免把非法窗口写入 SKU。 */
    @Test
    void rejectsUnknownPersonalPurchaseLimitPeriod() {
        givenUpdateWithOneOmittedSku();
        ProductCatalogCommandService.SaveSkuLine invalid = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.CARD_CODE_SECRET.code(), 100L, 0L, 0L,
                false, null, 1L, 10L, true, "QUARTER", 2L, true,
                CatalogEntityStatus.DRAFT.getCode());

        assertThatThrownBy(() -> service.updateProduct(SPU_CODE, saveCommand(invalid)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID.getCode());
    }

    /** 关闭每人限购时，表单残留的周期和数量必须忽略并规范化为空，不得整单拒绝。 */
    @Test
    void closingPersonalPurchaseLimitIgnoresLeftoverPeriodOrQuantity() {
        givenUpdateWithOneOmittedSku();
        ProductCatalogCommandService.SaveSkuLine disabled = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.CARD_CODE_SECRET.code(), 100L, 0L, 0L,
                false, null, 1L, 10L, false, "DAY", 1L, false,
                CatalogEntityStatus.DRAFT.getCode());

        service.updateProduct(SPU_CODE, saveCommand(disabled));

        ArgumentCaptor<PlatformSkuDO> captor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(sku -> "SKU_KEEP".equals(sku.getSkuCode()))
                .singleElement().satisfies(sku -> {
                    assertThat(sku.getUserLimitEnabled()).isFalse();
                    assertThat(sku.getUserLimitPeriod()).isNull();
                    assertThat(sku.getUserLimitQuantity()).isNull();
                });
    }

    /** 兼容编辑请求未携带四个新增字段时，必须保留原 SKU 限购配置。 */
    @Test
    void omittedPurchaseLimitFieldsPreserveExistingSkuConfiguration() {
        givenUpdateWithOneOmittedSku();

        service.updateProduct(SPU_CODE, saveCommand());

        ArgumentCaptor<PlatformSkuDO> captor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(sku -> "SKU_KEEP".equals(sku.getSkuCode()))
                .singleElement().satisfies(sku -> {
                    assertThat(sku.getUserLimitEnabled()).isTrue();
                    assertThat(sku.getUserLimitPeriod()).isEqualTo("MONTH");
                    assertThat(sku.getUserLimitQuantity()).isEqualTo(2L);
                    assertThat(sku.getOrderLimitEnabled()).isTrue();
                });
    }

    /** 显式关闭每人限购时，服务端必须把周期和数量规范化为空。 */
    @Test
    void disablingPersonalPurchaseLimitClearsPeriodAndQuantity() {
        givenUpdateWithOneOmittedSku();
        ProductCatalogCommandService.SaveSkuLine disabled = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.CARD_CODE_SECRET.code(), 100L, 0L, 0L,
                false, null, 1L, 10L, false, null, null, false,
                CatalogEntityStatus.DRAFT.getCode());

        service.updateProduct(SPU_CODE, saveCommand(disabled));

        ArgumentCaptor<PlatformSkuDO> captor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(sku -> "SKU_KEEP".equals(sku.getSkuCode()))
                .singleElement().satisfies(sku -> {
                    assertThat(sku.getUserLimitEnabled()).isFalse();
                    assertThat(sku.getUserLimitPeriod()).isNull();
                    assertThat(sku.getUserLimitQuantity()).isNull();
                    assertThat(sku.getOrderLimitEnabled()).isFalse();
                });
    }

    /** 卡密 SKU 不得开启绑定手机号限购，对齐卡管卡密下单无手机号。 */
    @Test
    void rejectsPhoneBindLimitOnCardSecretSku() {
        givenUpdateWithOneOmittedSku();
        ProductCatalogCommandService.SaveSkuLine invalid = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.CARD_CODE_SECRET.code(), 100L, 0L, 0L,
                false, null, 1L, 10L, false, null, null, false, true, "DAY", 2L,
                CatalogEntityStatus.DRAFT.getCode());

        assertThatThrownBy(() -> service.updateProduct(SPU_CODE, saveCommand(invalid)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID.getCode());
    }

    /** 直充 SKU 可保存绑定手机号限购，周期复用每人限购五值。 */
    @Test
    void savesPhoneBindLimitOnDirectRechargeSku() {
        givenUpdateWithOneOmittedSku();
        PlatformSpuDO spu = PlatformSpuDO.builder().id(1L).spuCode(SPU_CODE)
                .fulfillmentType(FulfillmentType.DIRECT_RECHARGE.code())
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(spuMapper.selectByCodeForUpdate(SPU_CODE)).thenReturn(spu);

        ProductCatalogCommandService.SaveSkuLine recharge = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.DIRECT_RECHARGE.code(), 100L, 0L, 0L,
                false, null, 1L, 10L, false, null, null, false, true, "DAY", 2L,
                CatalogEntityStatus.DRAFT.getCode());

        service.updateProduct(SPU_CODE, saveCommand(recharge));

        ArgumentCaptor<PlatformSkuDO> captor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(sku -> "SKU_KEEP".equals(sku.getSkuCode()))
                .singleElement().satisfies(sku -> {
                    assertThat(sku.getFulfillmentType()).isEqualTo(FulfillmentType.DIRECT_RECHARGE.code());
                    assertThat(sku.getPhoneBindLimitEnabled()).isTrue();
                    assertThat(sku.getPhoneBindLimitPeriod()).isEqualTo("DAY");
                    assertThat(sku.getPhoneBindLimitQuantity()).isEqualTo(2L);
                });
    }

    /** 账户绑定权益 SKU 同样允许开启绑定手机号限购。 */
    @Test
    void savesPhoneBindLimitOnAccountBoundBenefitSku() {
        givenUpdateWithOneOmittedSku();
        PlatformSpuDO spu = PlatformSpuDO.builder().id(1L).spuCode(SPU_CODE)
                .fulfillmentType(FulfillmentType.ACCOUNT_BOUND_BENEFIT.code())
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(spuMapper.selectByCodeForUpdate(SPU_CODE)).thenReturn(spu);

        ProductCatalogCommandService.SaveSkuLine bound = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.ACCOUNT_BOUND_BENEFIT.code(), 100L, 0L, 0L,
                false, null, 1L, 10L, false, null, null, false, true, "MONTH", 1L,
                CatalogEntityStatus.DRAFT.getCode());

        service.updateProduct(SPU_CODE, saveCommand(bound));

        ArgumentCaptor<PlatformSkuDO> captor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(sku -> "SKU_KEEP".equals(sku.getSkuCode()))
                .singleElement().satisfies(sku -> {
                    assertThat(sku.getPhoneBindLimitEnabled()).isTrue();
                    assertThat(sku.getPhoneBindLimitPeriod()).isEqualTo("MONTH");
                    assertThat(sku.getPhoneBindLimitQuantity()).isEqualTo(1L);
                });
    }

    /** 兼容编辑请求未携带绑定手机号限购字段时，必须保留原 SKU 配置。 */
    @Test
    void omittedPhoneBindLimitFieldsPreserveExistingSkuConfiguration() {
        PlatformSkuDO omitted = givenUpdateWithOneOmittedSku();
        PlatformSpuDO spu = PlatformSpuDO.builder().id(1L).spuCode(SPU_CODE)
                .fulfillmentType(FulfillmentType.DIRECT_RECHARGE.code())
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(spuMapper.selectByCodeForUpdate(SPU_CODE)).thenReturn(spu);
        PlatformSkuDO keep = PlatformSkuDO.builder().id(10L).spuId(1L).skuCode("SKU_KEEP")
                .fulfillmentType(FulfillmentType.DIRECT_RECHARGE.code())
                .phoneBindLimitEnabled(true).phoneBindLimitPeriod("DAY").phoneBindLimitQuantity(2L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(skuMapper.selectList(any())).thenReturn(List.of(keep, omitted));
        ProductCatalogCommandService.SaveSkuLine keepLine = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.DIRECT_RECHARGE.code(), 100L, 0L, 0L, 1L, 10L,
                CatalogEntityStatus.DRAFT.getCode());

        service.updateProduct(SPU_CODE, saveCommand(keepLine));

        ArgumentCaptor<PlatformSkuDO> captor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(sku -> "SKU_KEEP".equals(sku.getSkuCode()))
                .singleElement().satisfies(sku -> {
                    assertThat(sku.getPhoneBindLimitEnabled()).isTrue();
                    assertThat(sku.getPhoneBindLimitPeriod()).isEqualTo("DAY");
                    assertThat(sku.getPhoneBindLimitQuantity()).isEqualTo(2L);
                });
    }

    /** 显式关闭绑定手机号限购时，服务端必须把周期和数量规范化为空。 */
    @Test
    void disablingPhoneBindLimitClearsPeriodAndQuantity() {
        PlatformSkuDO omitted = givenUpdateWithOneOmittedSku();
        PlatformSpuDO spu = PlatformSpuDO.builder().id(1L).spuCode(SPU_CODE)
                .fulfillmentType(FulfillmentType.DIRECT_RECHARGE.code())
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(spuMapper.selectByCodeForUpdate(SPU_CODE)).thenReturn(spu);
        PlatformSkuDO keep = PlatformSkuDO.builder().id(10L).spuId(1L).skuCode("SKU_KEEP")
                .fulfillmentType(FulfillmentType.DIRECT_RECHARGE.code())
                .phoneBindLimitEnabled(true).phoneBindLimitPeriod("DAY").phoneBindLimitQuantity(2L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(skuMapper.selectList(any())).thenReturn(List.of(keep, omitted));
        ProductCatalogCommandService.SaveSkuLine disabled = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.DIRECT_RECHARGE.code(), 100L, 0L, 0L,
                false, null, 1L, 10L, false, null, null, false, false, null, null,
                CatalogEntityStatus.DRAFT.getCode());

        service.updateProduct(SPU_CODE, saveCommand(disabled));

        ArgumentCaptor<PlatformSkuDO> captor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(sku -> "SKU_KEEP".equals(sku.getSkuCode()))
                .singleElement().satisfies(sku -> {
                    assertThat(sku.getPhoneBindLimitEnabled()).isFalse();
                    assertThat(sku.getPhoneBindLimitPeriod()).isNull();
                    assertThat(sku.getPhoneBindLimitQuantity()).isNull();
                });
    }

    /** 关闭绑定手机号限购时，表单残留的周期和数量必须忽略并规范化为空，不得整单拒绝。 */
    @Test
    void closingPhoneBindLimitIgnoresLeftoverPeriodOrQuantity() {
        PlatformSkuDO omitted = givenUpdateWithOneOmittedSku();
        PlatformSpuDO spu = PlatformSpuDO.builder().id(1L).spuCode(SPU_CODE)
                .fulfillmentType(FulfillmentType.DIRECT_RECHARGE.code())
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(spuMapper.selectByCodeForUpdate(SPU_CODE)).thenReturn(spu);
        PlatformSkuDO keep = PlatformSkuDO.builder().id(10L).spuId(1L).skuCode("SKU_KEEP")
                .fulfillmentType(FulfillmentType.DIRECT_RECHARGE.code())
                .phoneBindLimitEnabled(true).phoneBindLimitPeriod("DAY").phoneBindLimitQuantity(2L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(skuMapper.selectList(any())).thenReturn(List.of(keep, omitted));
        ProductCatalogCommandService.SaveSkuLine disabled = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.DIRECT_RECHARGE.code(), 100L, 0L, 0L,
                false, null, 1L, 10L, false, null, null, false, false, "DAY", 1L,
                CatalogEntityStatus.DRAFT.getCode());

        service.updateProduct(SPU_CODE, saveCommand(disabled));

        ArgumentCaptor<PlatformSkuDO> captor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).updateById(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(sku -> "SKU_KEEP".equals(sku.getSkuCode()))
                .singleElement().satisfies(sku -> {
                    assertThat(sku.getPhoneBindLimitEnabled()).isFalse();
                    assertThat(sku.getPhoneBindLimitPeriod()).isNull();
                    assertThat(sku.getPhoneBindLimitQuantity()).isNull();
                });
    }

    /** 履约类型在草稿态改为卡密且省略绑定手机号字段时，不得把直充上已开启的限购保留下来。 */
    @Test
    void changingToCardSecretWhilePreservingPhoneBindLimitIsRejected() {
        PlatformSkuDO omitted = givenUpdateWithOneOmittedSku();
        PlatformSpuDO spu = PlatformSpuDO.builder().id(1L).spuCode(SPU_CODE)
                .fulfillmentType(FulfillmentType.DIRECT_RECHARGE.code())
                .status(CatalogEntityStatus.DRAFT.getCode()).build();
        when(spuMapper.selectByCodeForUpdate(SPU_CODE)).thenReturn(spu);
        PlatformSkuDO keep = PlatformSkuDO.builder().id(10L).spuId(1L).skuCode("SKU_KEEP")
                .fulfillmentType(FulfillmentType.DIRECT_RECHARGE.code())
                .phoneBindLimitEnabled(true).phoneBindLimitPeriod("DAY").phoneBindLimitQuantity(2L)
                .status(CatalogEntityStatus.DRAFT.getCode()).build();
        when(skuMapper.selectList(any())).thenReturn(List.of(keep, omitted));

        ProductCatalogCommandService.SaveSkuLine keepLine = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.CARD_CODE_SECRET.code(), 100L, 0L, 0L, 1L, 10L,
                CatalogEntityStatus.DRAFT.getCode());
        ProductCatalogCommandService.SaveSpuCommand command = new ProductCatalogCommandService.SaveSpuCommand(
                SPU_CODE, "商品", null, 2L, 3L, null,
                SellableCategory.EQUITY.name(), "EQUITY_PRODUCT", ProductClassification.VIRTUAL_CARD.name(),
                null, null, null, null, null, FulfillmentType.CARD_CODE_SECRET.code(), 1L, 10L,
                CatalogEntityStatus.DRAFT.getCode(), List.of(keepLine), 1L);

        assertThatThrownBy(() -> service.updateProduct(SPU_CODE, command))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_PURCHASE_LIMIT_CONFIG_INVALID.getCode());
    }

    /** 启用态 SPU 禁止修改履约方式。 */
    @Test
    void changingFulfillmentTypeOnActiveSpuIsRejected() {
        givenUpdateWithOneOmittedSku();
        ProductCatalogCommandService.SaveSkuLine keepLine = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.DIRECT_RECHARGE.code(), 100L, 0L, 0L, 1L, 10L,
                CatalogEntityStatus.DRAFT.getCode());
        ProductCatalogCommandService.SaveSpuCommand command = new ProductCatalogCommandService.SaveSpuCommand(
                SPU_CODE, "商品", null, 2L, 3L, null,
                SellableCategory.EQUITY.name(), "EQUITY_PRODUCT", ProductClassification.VIRTUAL_CARD.name(),
                null, null, null, null, null, FulfillmentType.DIRECT_RECHARGE.code(), 1L, 10L,
                CatalogEntityStatus.ACTIVE.getCode(), List.of(keepLine), 1L);

        assertThatThrownBy(() -> service.updateProduct(SPU_CODE, command))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_FULFILLMENT_TYPE_IMMUTABLE.getCode());
    }

    /** SPU 与下属 SKU 履约方式不一致时拒绝保存。 */
    @Test
    void inconsistentSkuFulfillmentTypeWithSpuIsRejected() {
        givenUpdateWithOneOmittedSku();
        ProductCatalogCommandService.SaveSkuLine keepLine = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.DIRECT_RECHARGE.code(), 100L, 0L, 0L, 1L, 10L,
                CatalogEntityStatus.DRAFT.getCode());
        ProductCatalogCommandService.SaveSpuCommand command = new ProductCatalogCommandService.SaveSpuCommand(
                SPU_CODE, "商品", null, 2L, 3L, null,
                SellableCategory.EQUITY.name(), "EQUITY_PRODUCT", ProductClassification.VIRTUAL_CARD.name(),
                null, null, null, null, null, FulfillmentType.CARD_CODE_SECRET.code(), 1L, 10L,
                CatalogEntityStatus.ACTIVE.getCode(), List.of(keepLine), 1L);

        assertThatThrownBy(() -> service.updateProduct(SPU_CODE, command))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_SKU_FIELD_INVALID.getCode());
    }

    @Test
    void createProductWithBrandCodeSuccess() {
        BrandDO brand = BrandDO.builder().id(99L).brandCode("BRD_TEST").status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(brandMapper.selectByCode("BRD_TEST")).thenReturn(brand);
        when(categoryMapper.selectById(3L)).thenReturn(ProductCategoryDO.builder().id(3L).status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(productTypeValidationService.requireActiveForUpdate("EQUITY_PRODUCT"))
                .thenReturn(ProductTypeDO.builder().typeCode("EQUITY_PRODUCT").build());
        when(clock.now()).thenReturn(LocalDateTime.now());
        when(generator.next(any())).thenReturn("SPU_AUTO");

        ProductCatalogCommandService.SaveSkuLine line = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_1", "SKU名称", FulfillmentType.CARD_CODE_SECRET.code(), 100L, 100L, 100L, 1L, 10L,
                CatalogEntityStatus.ACTIVE.getCode());
        ProductCatalogCommandService.SaveSpuCommand command = new ProductCatalogCommandService.SaveSpuCommand(
                null, "测试SPU", null, null, "BRD_TEST", 3L, null,
                SellableCategory.EQUITY.name(), "EQUITY_PRODUCT", ProductClassification.VIRTUAL_CARD.name(),
                null, null, null, null, null, FulfillmentType.CARD_CODE_SECRET.code(), 1L, 10L,
                CatalogEntityStatus.ACTIVE.getCode(), List.of(line), 1L);

        String result = service.createProduct(command);
        assertThat(result).isEqualTo("SPU_AUTO");
        verify(brandMapper).selectByCode("BRD_TEST");
    }

    /**
     * 复合创建未传 skuInventory 时落库 0；显式提交时按原值写入。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d38）。
     */
    @Test
    void createProductPersistsSkuInventory() {
        stubCreateProductLookups("BRD_INV");
        when(generator.next(any())).thenReturn("SPU_INV");

        service.createProduct(createCommand("SKU_DEFAULT", null));
        ArgumentCaptor<PlatformSkuDO> defaultCaptor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper).insert(defaultCaptor.capture());
        assertThat(defaultCaptor.getValue().getSkuInventory()).isEqualTo(0L);

        stubCreateProductLookups("BRD_INV2");
        when(generator.next(any())).thenReturn("SPU_INV2");
        service.createProduct(createCommand("SKU_EXPLICIT", 1000L));
        ArgumentCaptor<PlatformSkuDO> explicitCaptor = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).insert(explicitCaptor.capture());
        assertThat(explicitCaptor.getAllValues()).filteredOn(sku -> "SKU_EXPLICIT".equals(sku.getSkuCode()))
                .singleElement().extracting(PlatformSkuDO::getSkuInventory).isEqualTo(1000L);
    }

    /**
     * 复合更新写入 skuInventory。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d38）。
     */
    @Test
    void updateProductWritesSkuInventory() {
        givenUpdateWithOneOmittedSku();

        service.updateProduct(SPU_CODE, saveCommand(skuLineWithInventory(500L)));
        ArgumentCaptor<PlatformSkuDO> written = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).updateById(written.capture());
        assertThat(written.getAllValues()).filteredOn(sku -> "SKU_KEEP".equals(sku.getSkuCode()))
                .singleElement().extracting(PlatformSkuDO::getSkuInventory).isEqualTo(500L);
    }

    /**
     * 复合更新省略 skuInventory 时保留原值。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d38）。
     */
    @Test
    void omittedSkuInventoryPreservesExistingValue() {
        givenUpdateWithOneOmittedSku();

        service.updateProduct(SPU_CODE, saveCommand());
        ArgumentCaptor<PlatformSkuDO> preserved = ArgumentCaptor.forClass(PlatformSkuDO.class);
        verify(skuMapper, atLeastOnce()).updateById(preserved.capture());
        assertThat(preserved.getAllValues()).filteredOn(sku -> "SKU_KEEP".equals(sku.getSkuCode()))
                .singleElement().extracting(PlatformSkuDO::getSkuInventory).isEqualTo(88L);
    }

    /**
     * 负库存拒绝整单保存。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d38）。
     */
    @Test
    void rejectsNegativeSkuInventory() {
        givenUpdateWithOneOmittedSku();

        assertThatThrownBy(() -> service.updateProduct(SPU_CODE, saveCommand(skuLineWithInventory(-1L))))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_SKU_FIELD_INVALID.getCode());
    }

    @Test
    void createProductWithNumericBrandCodeInBrandIdFallbackSuccess() {
        // 前端将纯数字 brandCode 如 "1111" 传到了 brandId 字段，selectById(1111L) 返回 null，兜底按 selectByCode("1111") 解析成功
        BrandDO brand = BrandDO.builder().id(88L).brandCode("1111").status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(brandMapper.selectById(1111L)).thenReturn(null);
        when(brandMapper.selectByCode("1111")).thenReturn(brand);
        when(categoryMapper.selectById(3L)).thenReturn(ProductCategoryDO.builder().id(3L).status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(productTypeValidationService.requireActiveForUpdate("EQUITY_PRODUCT"))
                .thenReturn(ProductTypeDO.builder().typeCode("EQUITY_PRODUCT").build());
        when(clock.now()).thenReturn(LocalDateTime.now());
        when(generator.next(any())).thenReturn("SPU_AUTO_2");

        ProductCatalogCommandService.SaveSkuLine line = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_2", "SKU名称2", FulfillmentType.CARD_CODE_SECRET.code(), 100L, 100L, 100L, 1L, 10L,
                CatalogEntityStatus.ACTIVE.getCode());
        ProductCatalogCommandService.SaveSpuCommand command = new ProductCatalogCommandService.SaveSpuCommand(
                null, "测试SPU2", null, 1111L, null, 3L, null,
                SellableCategory.EQUITY.name(), "EQUITY_PRODUCT", ProductClassification.VIRTUAL_CARD.name(),
                null, null, null, null, null, FulfillmentType.CARD_CODE_SECRET.code(), 1L, 10L,
                CatalogEntityStatus.ACTIVE.getCode(), List.of(line), 1L);

        String result = service.createProduct(command);
        assertThat(result).isEqualTo("SPU_AUTO_2");
        verify(brandMapper).selectById(1111L);
        verify(brandMapper).selectByCode("1111");
    }

    @Test
    void createProductWithUnknownBrandThrowsBrandNotFound() {
        when(brandMapper.selectById(9999L)).thenReturn(null);
        when(brandMapper.selectByCode("9999")).thenReturn(null);

        ProductCatalogCommandService.SaveSkuLine line = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_3", "SKU名称3", FulfillmentType.CARD_CODE_SECRET.code(), 100L, 100L, 100L, 1L, 10L,
                CatalogEntityStatus.ACTIVE.getCode());
        ProductCatalogCommandService.SaveSpuCommand command = new ProductCatalogCommandService.SaveSpuCommand(
                null, "测试SPU3", null, 9999L, null, 3L, null,
                SellableCategory.EQUITY.name(), "EQUITY_PRODUCT", ProductClassification.VIRTUAL_CARD.name(),
                null, null, null, null, null, FulfillmentType.CARD_CODE_SECRET.code(), 1L, 10L,
                CatalogEntityStatus.ACTIVE.getCode(), List.of(line), 1L);

        assertThatThrownBy(() -> service.createProduct(command))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BRAND_NOT_FOUND.getCode());
    }

    /**
     * 复现：前端把类目树编码写进 productClassification，应解析为类目编码并落库，而不是 ENUM_NOT_OPEN。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d34）。
     */
    @Test
    void createProductWithCategoryCodeAsProductClassificationSuccess() {
        BrandDO brand = BrandDO.builder().id(99L).brandCode("1111").status(CatalogEntityStatus.ACTIVE.getCode()).build();
        ProductCategoryDO category = ProductCategoryDO.builder().id(1L).categoryCode("E2E_L1_20260903")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(brandMapper.selectByCode("1111")).thenReturn(brand);
        when(categoryMapper.selectById(1L)).thenReturn(category);
        when(categoryMapper.selectByCode("E2E_L1_20260903")).thenReturn(category);
        when(productTypeValidationService.requireActiveForUpdate("EQUITY_PRODUCT"))
                .thenReturn(ProductTypeDO.builder().typeCode("EQUITY_PRODUCT").build());
        when(clock.now()).thenReturn(LocalDateTime.now());
        when(generator.next(any())).thenReturn("SPU_CAT", "SKU_CAT");

        ProductCatalogCommandService.SaveSkuLine line = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_1", "SKU名称", FulfillmentType.CARD_CODE_SECRET.code(), 100L, 100L, 100L, 1L, 10L,
                CatalogEntityStatus.DRAFT.getCode());
        ProductCatalogCommandService.SaveSpuCommand command = new ProductCatalogCommandService.SaveSpuCommand(
                null, " 001", null, null, "1111", 1L, null,
                SellableCategory.EQUITY.name(), "EQUITY_PRODUCT", "E2E_L1_20260903",
                null, null, null, null, null, FulfillmentType.CARD_CODE_SECRET.code(), 1L, 10L,
                CatalogEntityStatus.DRAFT.getCode(), List.of(line), 1L);

        String result = service.createProduct(command);
        assertThat(result).isEqualTo("SPU_CAT");
        ArgumentCaptor<PlatformSpuDO> captor = ArgumentCaptor.forClass(PlatformSpuDO.class);
        verify(spuMapper).insert(captor.capture());
        assertThat(captor.getValue().getProductClassification()).isEqualTo("E2E_L1_20260903");
        assertThat(captor.getValue().getPrimaryCategoryId()).isEqualTo(1L);
    }

    @Test
    void createProductWithMismatchedClassificationCategoryThrowsCategoryNotFound() {
        BrandDO brand = BrandDO.builder().id(99L).brandCode("1111").status(CatalogEntityStatus.ACTIVE.getCode()).build();
        ProductCategoryDO primary = ProductCategoryDO.builder().id(1L).categoryCode("E2E_L1_20260903")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        ProductCategoryDO other = ProductCategoryDO.builder().id(2L).categoryCode("E2E_L2_20260903")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(brandMapper.selectByCode("1111")).thenReturn(brand);
        when(categoryMapper.selectById(1L)).thenReturn(primary);
        when(categoryMapper.selectByCode("E2E_L2_20260903")).thenReturn(other);
        when(productTypeValidationService.requireActiveForUpdate("EQUITY_PRODUCT"))
                .thenReturn(ProductTypeDO.builder().typeCode("EQUITY_PRODUCT").build());

        ProductCatalogCommandService.SaveSkuLine line = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_1", "SKU名称", FulfillmentType.CARD_CODE_SECRET.code(), 100L, 100L, 100L, 1L, 10L,
                CatalogEntityStatus.DRAFT.getCode());
        ProductCatalogCommandService.SaveSpuCommand command = new ProductCatalogCommandService.SaveSpuCommand(
                null, " 001", null, null, "1111", 1L, null,
                SellableCategory.EQUITY.name(), "EQUITY_PRODUCT", "E2E_L2_20260903",
                null, null, null, null, null, FulfillmentType.CARD_CODE_SECRET.code(), 1L, 10L,
                CatalogEntityStatus.DRAFT.getCode(), List.of(line), 1L);

        assertThatThrownBy(() -> service.createProduct(command))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(CATEGORY_NOT_FOUND.getCode());
    }

    /** 构造：SPU 下挂 keep 与 omitted 两个启用 SKU，更新请求只保留 keep。 */
    private PlatformSkuDO givenUpdateWithOneOmittedSku() {
        LocalDateTime now = LocalDateTime.now();
        when(clock.now()).thenReturn(now);
        PlatformSpuDO spu = PlatformSpuDO.builder().id(1L).spuCode(SPU_CODE)
                .fulfillmentType(FulfillmentType.CARD_CODE_SECRET.code())
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(spuMapper.selectByCodeForUpdate(SPU_CODE)).thenReturn(spu);
        when(spuMapper.updateById(any(PlatformSpuDO.class))).thenReturn(1);
        when(brandMapper.selectById(anyLong())).thenReturn(BrandDO.builder().id(2L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(categoryMapper.selectById(anyLong())).thenReturn(ProductCategoryDO.builder().id(3L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(productTypeValidationService.requireActiveForUpdate(any()))
                .thenReturn(ProductTypeDO.builder().typeCode("EQUITY_PRODUCT").build());

        PlatformSkuDO keep = PlatformSkuDO.builder().id(10L).spuId(1L).skuCode("SKU_KEEP")
                .fulfillmentType(FulfillmentType.CARD_CODE_SECRET.code())
                .userLimitEnabled(true).userLimitPeriod("MONTH").userLimitQuantity(2L)
                .orderLimitEnabled(true)
                .skuInventory(88L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSkuDO omitted = PlatformSkuDO.builder().id(11L).spuId(1L).skuCode("SKU_OMITTED")
                .fulfillmentType(FulfillmentType.CARD_CODE_SECRET.code())
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(skuMapper.selectList(any())).thenReturn(List.of(keep, omitted));
        when(skuMapper.updateById(any(PlatformSkuDO.class))).thenReturn(1);
        when(platformSkuPricingMapper.selectById(10L)).thenReturn(PlatformSkuPricingDO.builder()
                .platformSkuId(10L).currentVersionId(100L).pricingStatus("READY").build());
        return omitted;
    }

    private ProductCatalogCommandService.SaveSpuCommand saveCommand() {
        ProductCatalogCommandService.SaveSkuLine keepLine = new ProductCatalogCommandService.SaveSkuLine(
                "SKU_KEEP", "保留SKU", FulfillmentType.CARD_CODE_SECRET.code(), 100L, 0L, 0L, 1L, 10L,
                CatalogEntityStatus.DRAFT.getCode());
        return saveCommand(keepLine);
    }

    private ProductCatalogCommandService.SaveSpuCommand saveCommand(
            ProductCatalogCommandService.SaveSkuLine keepLine) {
        String fulfillmentType = keepLine != null && keepLine.fulfillmentType() != null
                ? keepLine.fulfillmentType()
                : FulfillmentType.CARD_CODE_SECRET.code();
        return new ProductCatalogCommandService.SaveSpuCommand(SPU_CODE, "商品", null, 2L, 3L, null,
                SellableCategory.EQUITY.name(), "EQUITY_PRODUCT", ProductClassification.VIRTUAL_CARD.name(),
                null, null, null, null, null, fulfillmentType, 1L, 10L,
                CatalogEntityStatus.ACTIVE.getCode(), List.of(keepLine), 1L);
    }

    private void stubCreateProductLookups(String brandCode) {
        BrandDO brand = BrandDO.builder().id(99L).brandCode(brandCode)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(brandMapper.selectByCode(brandCode)).thenReturn(brand);
        when(categoryMapper.selectById(3L)).thenReturn(ProductCategoryDO.builder().id(3L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(productTypeValidationService.requireActiveForUpdate("EQUITY_PRODUCT"))
                .thenReturn(ProductTypeDO.builder().typeCode("EQUITY_PRODUCT").build());
        when(clock.now()).thenReturn(LocalDateTime.now());
    }

    private ProductCatalogCommandService.SaveSpuCommand createCommand(String skuCode, Long skuInventory) {
        return new ProductCatalogCommandService.SaveSpuCommand(
                null, "测试SPU", null, null, skuCode.startsWith("SKU_EXPLICIT") ? "BRD_INV2" : "BRD_INV", 3L, null,
                SellableCategory.EQUITY.name(), "EQUITY_PRODUCT", ProductClassification.VIRTUAL_CARD.name(),
                null, null, null, null, null, FulfillmentType.CARD_CODE_SECRET.code(), 1L, 10L,
                CatalogEntityStatus.ACTIVE.getCode(), List.of(skuLine(skuCode, skuInventory)), 1L);
    }

    private static ProductCatalogCommandService.SaveSkuLine skuLineWithInventory(Long skuInventory) {
        return skuLine("SKU_KEEP", skuInventory);
    }

    private static ProductCatalogCommandService.SaveSkuLine skuLine(String skuCode, Long skuInventory) {
        return new ProductCatalogCommandService.SaveSkuLine(
                skuCode, "SKU名称", null, null, null,
                FulfillmentType.CARD_CODE_SECRET.code(), 100L, 100L, 100L, null,
                false, null, 1L, 10L,
                null, null, null, null, null, null, null,
                skuInventory, CatalogEntityStatus.ACTIVE.getCode());
    }
}
