package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.integration.system.TaxRateDictionaryAdapter;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FixedOperator;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FormulaRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.HideRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PriceMode;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingConfig;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.ServiceFeeRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferPriceMode;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_DEFAULT_PRICING_MISSING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 依赖重算失败必须上抛，税率字典不可用时不得写出价格版本。
 * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态}。
 */
class GenericBatchPricingServiceTest {

    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerProductMapper buyerProductMapper = mock(BuyerProductMapper.class);
    private final BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
    private final ProjectListingMapper listingMapper = mock(ProjectListingMapper.class);
    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final PlatformSkuPricingMapper platformPricingMapper = mock(PlatformSkuPricingMapper.class);
    private final PlatformSkuPricingVersionMapper platformVersionMapper = mock(PlatformSkuPricingVersionMapper.class);
    private final BuyerSkuPricingMapper buyerPricingMapper = mock(BuyerSkuPricingMapper.class);
    private final BuyerSkuPricingVersionMapper buyerVersionMapper = mock(BuyerSkuPricingVersionMapper.class);
    private final ProjectSkuPricingMapper projectPricingMapper = mock(ProjectSkuPricingMapper.class);
    private final ProjectSkuPricingVersionMapper projectVersionMapper = mock(ProjectSkuPricingVersionMapper.class);
    private final SupplierOfferMapper offerMapper = mock(SupplierOfferMapper.class);
    private final SupplierOfferVersionMapper offerVersionMapper = mock(SupplierOfferVersionMapper.class);
    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private final TaxRateDictionaryAdapter taxDictionary = mock(TaxRateDictionaryAdapter.class);
    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private GenericBatchPricingService service;

    @BeforeEach
    void setUp() {
        when(clock.now()).thenReturn(LocalDateTime.of(2026, 9, 16, 10, 0));
        service = new GenericBatchPricingService(buyerMapper, buyerProductMapper, projectMapper, listingMapper,
                skuMapper, spuMapper, platformPricingMapper, platformVersionMapper,
                buyerPricingMapper, buyerVersionMapper, projectPricingMapper,
                projectVersionMapper, offerMapper, offerVersionMapper, supplierMapper, taxDictionary, clock,
                new ObjectMapper());
    }

    @Test
    void projectPricingDanglingCurrentVersionMustNotPublishReplacement() throws Exception {
        stubConfiguredProjectIdentity();
        assertThatThrownBy(() -> service.ensureCurrentProjectVersion("PRJ_A", "SKU_A"))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessageContaining("current version is missing or mismatched");
        verify(projectVersionMapper, never()).insert(any(com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingVersionDO.class));
        verify(projectPricingMapper, never()).updateById(any(com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingDO.class));
    }

    @Test
    void projectPricingCurrentVersionOwnedByAnotherIdentityMustNotBeReusedOrOverwritten() throws Exception {
        stubConfiguredProjectIdentity();
        when(projectVersionMapper.selectById(22L)).thenReturn(
                com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingVersionDO.builder()
                        .id(22L).pricingId(999L).buyerPricingVersionId(12L).marketPriceCents(10000L)
                        .suggestedSalePriceCents(10000L).pricingStatus(PricingStatus.READY.name())
                        .overrideJson(new ObjectMapper().writeValueAsString(GenericBatchPricingService.ProjectOverrides.allInherited())).build());
        assertThatThrownBy(() -> service.ensureCurrentProjectVersion("PRJ_A", "SKU_A"))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessageContaining("current version is missing or mismatched");
        verify(projectVersionMapper, never()).insert(any(com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingVersionDO.class));
        verify(projectPricingMapper, never()).updateById(any(com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingDO.class));
    }

    private void stubConfiguredProjectIdentity() throws Exception {
        when(projectMapper.selectByProjectCodeForUpdate("PRJ_A")).thenReturn(
                com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO.builder()
                        .id(2L).buyerId(1L).status(com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus.ACTIVE.getCode()).build());
        when(skuMapper.selectByCode("SKU_A")).thenReturn(PlatformSkuDO.builder()
                .id(4L).spuId(5L).marketPrice(10000L).suggestedSalePrice(10000L).status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(spuMapper.selectById(5L)).thenReturn(PlatformSpuDO.builder().id(5L).status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(buyerProductMapper.selectByBuyerAndSku(1L, 4L)).thenReturn(BuyerProductDO.builder()
                .id(6L).buyerId(1L).platformSkuId(4L).status(BuyerProductStatus.ACTIVE.getCode()).build());
        when(listingMapper.selectByProjectAndBuyerProduct(2L, 6L)).thenReturn(
                com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO.builder()
                        .id(7L).projectId(2L).buyerProductId(6L)
                        .status(com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus.AVAILABLE.getCode()).build());
        when(buyerPricingMapper.selectByBuyerProductId(6L)).thenReturn(
                com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO.builder()
                        .id(11L).buyerProductId(6L).currentVersionId(12L).build());
        when(buyerVersionMapper.selectById(12L)).thenReturn(
                com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingVersionDO.builder()
                        .id(12L).pricingId(11L).effectiveConfigJson(new ObjectMapper().writeValueAsString(config())).build());
        when(projectPricingMapper.selectByListingIdForUpdate(7L)).thenReturn(
                com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingDO.builder()
                        .id(21L).listingId(7L).currentVersionId(22L).pricingStatus(PricingStatus.READY.name()).build());
    }

    @Test
    void quoteDistinguishesUnconfiguredBuyerPricingFromDanglingVersion() {
        when(projectMapper.selectByProjectCodeForUpdate("PRJ_A")).thenReturn(
                com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO.builder()
                        .id(2L).buyerId(1L).status((short) 10).build());
        when(skuMapper.selectByCode("SKU_A")).thenReturn(PlatformSkuDO.builder()
                .id(4L).spuId(5L).status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(spuMapper.selectById(5L)).thenReturn(PlatformSpuDO.builder()
                .id(5L).status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(buyerProductMapper.selectByBuyerAndSku(1L, 4L)).thenReturn(BuyerProductDO.builder()
                .id(6L).buyerId(1L).platformSkuId(4L).status(BuyerProductStatus.ACTIVE.getCode()).build());
        when(listingMapper.selectByProjectAndBuyerProduct(2L, 6L)).thenReturn(
                com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO.builder()
                        .id(7L).status((short) 10).build());
        assertThatThrownBy(() -> service.ensureCurrentProjectVersion("PRJ_A", "SKU_A"))
                .satisfies(error -> assertThat(error.getClass().getSimpleName()).isEqualTo("ProjectPricingNotReadyException"));
        when(buyerPricingMapper.selectByBuyerProductId(6L)).thenReturn(
                com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO.builder()
                        .buyerProductId(6L).currentVersionId(999L).build());
        assertThatThrownBy(() -> service.ensureCurrentProjectVersion("PRJ_A", "SKU_A"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing or mismatched")
                .satisfies(error -> assertThat(error.getClass().getSimpleName()).isNotEqualTo("ProjectPricingNotReadyException"));
    }

    @Test
    void recalculatePropagatesDeserializationFailureInsteadOfMarkingCalculationFailed() {
        PlatformSkuDO sku = PlatformSkuDO.builder().id(8L).skuCode("MOVIE_100").marketPrice(10_000L)
                .suggestedSalePrice(10_000L).build();
        PlatformSkuPricingDO identity = PlatformSkuPricingDO.builder().platformSkuId(8L)
                .currentVersionId(9L).pricingStatus(PricingStatus.READY.name()).version(0L).build();
        PlatformSkuPricingVersionDO current = PlatformSkuPricingVersionDO.builder().id(9L).platformSkuId(8L)
                .effectiveConfigJson("{").build();
        when(skuMapper.selectById(8L)).thenReturn(sku);
        when(platformPricingMapper.selectBySkuIdForUpdate(8L)).thenReturn(identity);
        when(platformVersionMapper.selectById(9L)).thenReturn(current);

        assertThatThrownBy(() -> service.recalculatePlatformSku(new PricingDependencyChangedEvent(8L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deserialization");

        ArgumentCaptor<PlatformSkuPricingDO> updated = ArgumentCaptor.forClass(PlatformSkuPricingDO.class);
        verify(platformPricingMapper).updateById(updated.capture());
        assertThat(updated.getAllValues())
                .extracting(PlatformSkuPricingDO::getPricingStatus)
                .doesNotContain(PricingStatus.CALCULATION_FAILED.name());
    }

    @Test
    void savePlatformDoesNotPublishWhenTaxDictionaryUnavailable() {
        when(taxDictionary.requireEnabled(any(), anyString()))
                .thenThrow(new IllegalStateException("tax rate dictionary is unavailable"));

        assertThatThrownBy(() -> service.savePlatform(List.of("SKU_1"), config(), 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tax rate dictionary is unavailable");
        verify(skuMapper, never()).selectByCodeForUpdate(any());
        verify(platformPricingMapper, never()).insert(any(PlatformSkuPricingDO.class));
        verify(platformVersionMapper, never()).insert(any(PlatformSkuPricingVersionDO.class));
    }

    /** 人工入口的状态拒绝必须是可读业务错误；只读查询与首版自动初始化不复用此限制。 */
    @ParameterizedTest
    @EnumSource(value = CatalogEntityStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void rejectsNonActiveSkuWithoutPublishing(CatalogEntityStatus status) {
        assertManualPricingRejected(status, CatalogEntityStatus.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(value = CatalogEntityStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void rejectsNonActiveSpuWithoutPublishing(CatalogEntityStatus status) {
        assertManualPricingRejected(CatalogEntityStatus.ACTIVE, status);
    }

    private void assertManualPricingRejected(CatalogEntityStatus skuStatus, CatalogEntityStatus spuStatus) {
        PlatformSkuDO sku = PlatformSkuDO.builder().id(8L).skuCode("SKU_1").spuId(2L)
                .status(skuStatus.getCode()).build();
        when(skuMapper.selectByCode("SKU_1")).thenReturn(sku);
        when(skuMapper.selectByCodeForUpdate("SKU_1")).thenReturn(sku);
        when(skuMapper.selectByIdForUpdate(sku.getId())).thenReturn(sku);
        when(spuMapper.selectById(2L)).thenReturn(PlatformSpuDO.builder().id(2L)
                .status(spuStatus.getCode()).build());
        when(taxDictionary.requireEnabled(false, PricingTaxRateCodes.NON_TAXABLE))
                .thenReturn(config().taxMetadata());
        assertThatThrownBy(() -> service.previewPlatform(List.of("SKU_1"), config()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("SKU_1").hasMessageContaining("先上架");
        assertThatThrownBy(() -> service.savePlatform(List.of("SKU_1"), config(), 1L))
                .isInstanceOf(ServiceException.class).hasMessageContaining("SKU_1").hasMessageContaining("先上架");
        verifyNoInteractions(platformPricingMapper, platformVersionMapper, buyerPricingMapper,
                buyerVersionMapper, projectPricingMapper, projectVersionMapper);
    }

    @Test
    void missingSkuReturnsStableBusinessError() {
        when(taxDictionary.requireEnabled(false, PricingTaxRateCodes.NON_TAXABLE))
                .thenReturn(config().taxMetadata());
        assertThatThrownBy(() -> service.previewPlatform(List.of("MISSING"), config()))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SKU_NOT_FOUND.getCode()));
    }

    @Test
    void missingSpuReturnsStableBusinessError() {
        when(taxDictionary.requireEnabled(false, PricingTaxRateCodes.NON_TAXABLE))
                .thenReturn(config().taxMetadata());
        when(skuMapper.selectByCode("SKU_1")).thenReturn(PlatformSkuDO.builder().id(8L)
                .skuCode("SKU_1").spuId(2L).status(CatalogEntityStatus.ACTIVE.getCode()).build());
        assertThatThrownBy(() -> service.previewPlatform(List.of("SKU_1"), config()))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode())
                        .isEqualTo(com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SPU_NOT_FOUND.getCode()));
    }

    @Test
    void initializesUnpricedSkuWithNonTaxableSystemDefault() throws Exception {
        PlatformSkuDO sku = PlatformSkuDO.builder().id(8L).skuCode("SKU_NEW").spuId(2L)
                .marketPrice(10_000L).suggestedSalePrice(9_800L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSkuPricingDO identity = PlatformSkuPricingDO.builder().platformSkuId(8L)
                .pricingStatus(PricingStatus.UNPRICED.name()).version(0L).build();
        TaxMetadata normalizedTax = new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, "不征税");
        when(skuMapper.selectByCode("SKU_NEW")).thenReturn(sku);
        when(skuMapper.selectByIdForUpdate(sku.getId())).thenReturn(sku);
        when(platformPricingMapper.selectBySkuIdForUpdate(8L)).thenReturn(identity);
        when(taxDictionary.requireEnabled(false, PricingTaxRateCodes.NON_TAXABLE)).thenReturn(normalizedTax);
        when(offerMapper.selectPublishedBySku(8L)).thenReturn(List.of());
        when(buyerProductMapper.selectActiveByPlatformSkuId(8L)).thenReturn(List.of());
        when(platformVersionMapper.insert(any(PlatformSkuPricingVersionDO.class))).thenAnswer(invocation -> {
            invocation.<PlatformSkuPricingVersionDO>getArgument(0).setId(99L);
            return 1;
        });

        service.initializePlatformDefaults(new PlatformSkuPricingInitializationEvent(List.of("SKU_NEW"), 7L));

        ArgumentCaptor<PlatformSkuPricingVersionDO> version =
                ArgumentCaptor.forClass(PlatformSkuPricingVersionDO.class);
        verify(platformVersionMapper).insert(version.capture());
        PricingConfig config = new ObjectMapper().readValue(version.getValue().getEffectiveConfigJson(),
                PricingConfig.class);
        assertThat(config.distributionRule().mode()).isEqualTo(PriceMode.DIRECT_MARKET_PRICE);
        assertThat(config.salesRule().mode()).isEqualTo(PriceMode.DIRECT_MARKET_PRICE);
        assertThat(config.serviceFeeRule().percentage()).isEqualByComparingTo("100");
        assertThat(config.serviceFeeRule().operand()).isEqualByComparingTo("0");
        assertThat(config.serviceFeeRule().hideCopy()).isTrue();
        assertThat(config.hideRule().distributionThresholdPercent()).isEqualByComparingTo("0");
        assertThat(config.hideRule().salesThresholdPercent()).isEqualByComparingTo("0");
        assertThat(config.taxMetadata()).isEqualTo(normalizedTax);
        assertThat(version.getValue().getDistributionPriceCents()).isEqualTo(10_000L);
        assertThat(version.getValue().getSalesPriceCents()).isEqualTo(10_000L);
        assertThat(version.getValue().getPricingStatus()).isEqualTo(PricingStatus.DEPENDENCY_MISSING.name());
        assertThat(identity.getCurrentVersionId()).isEqualTo(99L);
        assertThat(identity.getPricingStatus()).isEqualTo(PricingStatus.DEPENDENCY_MISSING.name());
    }

    @Test
    void defaultInitializationNeverOverwritesExistingVersion() {
        PlatformSkuDO sku = PlatformSkuDO.builder().id(8L).skuCode("SKU_PRICED").build();
        PlatformSkuPricingDO identity = PlatformSkuPricingDO.builder().platformSkuId(8L)
                .currentVersionId(88L).pricingStatus(PricingStatus.READY.name()).build();
        when(skuMapper.selectByCode("SKU_PRICED")).thenReturn(sku);
        when(skuMapper.selectByIdForUpdate(sku.getId())).thenReturn(sku);
        when(platformPricingMapper.selectBySkuIdForUpdate(8L)).thenReturn(identity);

        service.initializePlatformDefaults(new PlatformSkuPricingInitializationEvent(List.of("SKU_PRICED"), 7L));

        verifyNoInteractions(taxDictionary);
        verify(platformVersionMapper, never()).insert(any(PlatformSkuPricingVersionDO.class));
        verify(platformPricingMapper, never()).updateById(any(PlatformSkuPricingDO.class));
    }

    @Test
    void getPlatformPricingReturnsCompleteCurrentVersionWithoutRecalculation() throws Exception {
        PlatformSkuDO sku = PlatformSkuDO.builder().id(8L).skuCode("SKU_1").build();
        PlatformSkuPricingDO identity = PlatformSkuPricingDO.builder().platformSkuId(8L)
                .currentVersionId(9L).pricingStatus(PricingStatus.READY.name()).build();
        PricingConfig config = config();
        LocalDateTime publishedAt = LocalDateTime.of(2026, 9, 16, 9, 30);
        PlatformSkuPricingVersionDO version = PlatformSkuPricingVersionDO.builder()
                .id(9L).platformSkuId(8L).versionNo(3)
                .effectiveConfigJson(new ObjectMapper().writeValueAsString(config))
                .marketPriceCents(10_000L).suggestedSalePriceCents(9_800L).supplyPriceCents(8_000L)
                .distributionPriceCents(10_000L).salesPriceCents(10_000L)
                .distributionMarginPercent(new BigDecimal("25.0000"))
                .effectiveHideThresholdPercent(new BigDecimal("10.00"))
                .pricingStatus(PricingStatus.READY.name()).currency("CNY")
                .publishedBy(100L).publishedAt(publishedAt).build();
        when(skuMapper.selectByCode("SKU_1")).thenReturn(sku);
        when(platformPricingMapper.selectById(8L)).thenReturn(identity);
        when(platformVersionMapper.selectById(9L)).thenReturn(version);

        GenericBatchPricingService.PlatformPricingDetail detail = service.getPlatformPricing("SKU_1");

        assertThat(detail.platformSkuCode()).isEqualTo("SKU_1");
        assertThat(detail.currentVersionId()).isEqualTo(9L);
        assertThat(detail.versionNo()).isEqualTo(3);
        assertThat(detail.pricingStatus()).isEqualTo(PricingStatus.READY.name());
        assertThat(detail.distributionRule()).isEqualTo(config.distributionRule());
        assertThat(detail.serviceFeeRule()).isEqualTo(config.serviceFeeRule());
        assertThat(detail.taxMetadata()).isEqualTo(config.taxMetadata());
        assertThat(detail.supplyPriceCents()).isEqualTo(8_000L);
        assertThat(detail.distributionMarginPercent()).isEqualByComparingTo("25.0000");
        assertThat(detail.publishedAt()).isEqualTo(publishedAt);
        verifyNoInteractions(taxDictionary);
    }

    @Test
    void getPlatformPricingReturnsUnpricedForSkuWithoutCurrentVersion() {
        PlatformSkuDO sku = PlatformSkuDO.builder().id(8L).skuCode("SKU_NEW").build();
        PlatformSkuPricingDO identity = PlatformSkuPricingDO.builder().platformSkuId(8L)
                .pricingStatus(PricingStatus.UNPRICED.name()).build();
        when(skuMapper.selectByCode("SKU_NEW")).thenReturn(sku);
        when(platformPricingMapper.selectById(8L)).thenReturn(identity);

        GenericBatchPricingService.PlatformPricingDetail detail = service.getPlatformPricing("SKU_NEW");

        assertThat(detail.platformSkuCode()).isEqualTo("SKU_NEW");
        assertThat(detail.pricingStatus()).isEqualTo(PricingStatus.UNPRICED.name());
        assertThat(detail.currentVersionId()).isNull();
        assertThat(detail.distributionRule()).isNull();
        assertThat(detail.marketPriceCents()).isNull();
        verify(platformVersionMapper, never()).selectById(any());
        verifyNoInteractions(taxDictionary);
    }

    @Test
    void saveBuyerRejectsMissingPlatformDefaultPricing() {
        when(buyerMapper.selectByBuyerCodeForUpdate("BYR_A")).thenReturn(
                BuyerDO.builder().id(5L).buyerCode("BYR_A").status(BuyerStatus.ACTIVE.getCode()).build());
        when(buyerProductMapper.selectByCodeForUpdate("BPD_1")).thenReturn(BuyerProductDO.builder()
                .id(77L).buyerProductCode("BPD_1").buyerId(5L).platformSkuId(11L)
                .status(BuyerProductStatus.ACTIVE.getCode()).build());
        when(skuMapper.selectById(11L)).thenReturn(PlatformSkuDO.builder().id(11L).skuCode("SKU_1").build());
        when(platformPricingMapper.selectById(11L)).thenReturn(null);

        assertThatThrownBy(() -> service.saveBuyer("BYR_A", List.of("BPD_1"),
                GenericBatchPricingService.ProjectOverrides.allInherited(), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PLATFORM_DEFAULT_PRICING_MISSING.getCode());
        verify(buyerPricingMapper, never()).insert(any(com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO.class));
        verify(buyerVersionMapper, never()).insert(any(com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingVersionDO.class));
    }

    @Test
    void previewUsesFixedIdentitySupplyPriceWhenOfferHasNoVersion() {
        PlatformSkuDO sku = PlatformSkuDO.builder().id(8L).skuCode("SKU_1").spuId(2L)
                .marketPrice(10_000L).suggestedSalePrice(9_800L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(skuMapper.selectByCode("SKU_1")).thenReturn(sku);
        when(spuMapper.selectById(2L)).thenReturn(PlatformSpuDO.builder().id(2L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(taxDictionary.requireEnabled(any(), anyString())).thenReturn(config().taxMetadata());
        when(offerMapper.selectPublishedBySku(8L)).thenReturn(List.of(
                SupplierOfferDO.builder().id(1L).platformSkuId(8L).supplierId(4L)
                        .status(OfferStatus.PUBLISHED.getCode())
                        .priceMode(OfferPriceMode.FIXED.name())
                        .draftUnitPrice(8_000L).build()));
        when(supplierMapper.selectById(4L)).thenReturn(
                SupplierDO.builder().id(4L).status(SupplierStatus.ACTIVE.getCode()).build());

        List<GenericBatchPricingService.PricingPreview> previews = service.previewPlatform(List.of("SKU_1"), config());

        assertThat(previews.getFirst().supplyPriceCents()).isEqualTo(8_000L);
        verify(offerVersionMapper, never()).selectById(any());
    }

    private static PricingConfig config() {
        FormulaRule direct = new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null);
        return new PricingConfig(direct, direct,
                new ServiceFeeRule(new BigDecimal("1"), FixedOperator.MULTIPLY, BigDecimal.ZERO, true, null),
                new HideRule(null, null),
                new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, null));
    }
}
