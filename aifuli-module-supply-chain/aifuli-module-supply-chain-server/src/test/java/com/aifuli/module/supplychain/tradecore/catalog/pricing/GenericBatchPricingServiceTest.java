package com.aifuli.module.supplychain.tradecore.catalog.pricing;

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
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingVersionMapper;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    private static PricingConfig config() {
        FormulaRule direct = new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null);
        return new PricingConfig(direct, direct,
                new ServiceFeeRule(new BigDecimal("1"), FixedOperator.MULTIPLY, BigDecimal.ZERO, true, null),
                new HideRule(null, null),
                new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, null));
    }
}
