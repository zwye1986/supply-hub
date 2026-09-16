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
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
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
                skuMapper, spuMapper, buyerPricingMapper, buyerVersionMapper, projectPricingMapper,
                projectVersionMapper, offerMapper, offerVersionMapper, supplierMapper, taxDictionary, clock,
                new ObjectMapper());
    }

    @Test
    void recalculatePropagatesDeserializationFailureInsteadOfMarkingCalculationFailed() {
        PlatformSkuDO sku = PlatformSkuDO.builder().id(8L).skuCode("MOVIE_100").marketPrice(10_000L)
                .suggestedSalePrice(10_000L).build();
        BuyerProductDO product = BuyerProductDO.builder().id(1L).buyerProductCode("BPD_1")
                .platformSkuId(8L).status(BuyerProductStatus.ACTIVE.getCode()).build();
        BuyerSkuPricingDO identity = BuyerSkuPricingDO.builder().id(20L).buyerProductId(1L)
                .currentVersionId(9L).pricingStatus(PricingStatus.READY.name()).version(0L).build();
        BuyerSkuPricingVersionDO current = BuyerSkuPricingVersionDO.builder().id(9L).pricingId(20L)
                .distributionRuleJson("{").salesRuleJson("{}").serviceFeeRuleJson("{}")
                .hideRuleJson("{}").taxMetadataJson("{}").build();
        when(skuMapper.selectById(8L)).thenReturn(sku);
        when(buyerProductMapper.selectActiveByPlatformSkuId(8L)).thenReturn(List.of(product));
        when(buyerPricingMapper.selectByBuyerProductIdForUpdate(1L)).thenReturn(identity);
        when(buyerVersionMapper.selectById(9L)).thenReturn(current);
        when(listingMapper.selectAvailableByBuyerProductId(1L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.recalculatePlatformSku(new PricingDependencyChangedEvent(8L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deserialization");

        ArgumentCaptor<BuyerSkuPricingDO> updated = ArgumentCaptor.forClass(BuyerSkuPricingDO.class);
        verify(buyerPricingMapper).updateById(updated.capture());
        assertThat(updated.getAllValues())
                .extracting(BuyerSkuPricingDO::getPricingStatus)
                .doesNotContain(PricingStatus.CALCULATION_FAILED.name());
    }

    @Test
    void saveBuyerDoesNotPublishWhenTaxDictionaryUnavailable() {
        when(buyerMapper.selectByBuyerCodeForUpdate("BYR_A")).thenReturn(BuyerDO.builder()
                .id(5L).buyerCode("BYR_A").status(BuyerStatus.ACTIVE.getCode()).build());
        when(taxDictionary.requireEnabled(any(), anyString()))
                .thenThrow(new IllegalStateException("tax rate dictionary is unavailable"));

        assertThatThrownBy(() -> service.saveBuyer("BYR_A", List.of("BPD_1"), config(), 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tax rate dictionary is unavailable");
        verify(buyerProductMapper, never()).selectByCodeForUpdate(any());
        verify(buyerPricingMapper, never()).insert(any(BuyerSkuPricingDO.class));
        verify(buyerVersionMapper, never()).insert(any(BuyerSkuPricingVersionDO.class));
    }

    private static PricingConfig config() {
        FormulaRule direct = new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null);
        return new PricingConfig(direct, direct,
                new ServiceFeeRule(new BigDecimal("1"), FixedOperator.MULTIPLY, BigDecimal.ZERO, true, null),
                new HideRule(null, null),
                new TaxMetadata(false, PricingTaxRateCodes.NON_TAXABLE, null));
    }
}
