package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferEligibilityService;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 当前展示金额与历史参考分开；设计见2026-10-19建议价供应价设计§4、§7。 */
class ProductCatalogQueryServiceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(longs = {0L, 5000L})
    void detailDoesNotExposeCompatibilityAmountForPendingSupplyRule(Long supplyPrice) {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), PlatformSkuDO.class);
        var spus = mock(PlatformSpuMapper.class);
        var skus = mock(PlatformSkuMapper.class);
        var eligibility = mock(SupplierOfferEligibilityService.class);
        var clock = mock(SupplyChainDatabaseClock.class);
        var now = LocalDateTime.of(2026, 10, 19, 12, 0);
        when(clock.now()).thenReturn(now);
        when(eligibility.resolveMinimumSupplyPrices(any(), any())).thenReturn(supplyPrice == null ? Map.of() : Map.of(2L, supplyPrice));
        when(spus.selectByCode("SPU")).thenReturn(PlatformSpuDO.builder().id(1L).spuCode("SPU")
                .status((short) 10).build());
        when(spus.selectPage(any(com.aifuli.framework.common.pojo.PageParam.class), any())).thenReturn(
                new com.aifuli.framework.common.pojo.PageResult<>(List.of(PlatformSpuDO.builder()
                        .id(1L).spuCode("SPU").status((short) 10).build()), 1L));
        when(skus.selectList(any())).thenReturn(List.of(PlatformSkuDO.builder().id(2L).spuId(1L)
                .skuCode("SKU").marketPrice(10000L).suggestedSalePrice(99999L)
                .suggestedSalePriceRuleJson(supplyRule()).build()));
        var service = new ProductCatalogQueryService(spus, skus, mock(BrandMapper.class),
                mock(ProductCategoryMapper.class), mock(ProductTypeMapper.class), mock(SupplierOfferMapper.class),
                mock(SupplierOfferVersionMapper.class), mock(SupplierMapper.class),
                mock(ProductTypeValidationService.class), mock(ProductSellabilityService.class),
                mock(BuyerMapper.class), mock(BuyerProductMapper.class), clock, eligibility,
                mock(SupplierSupplyScopeMapper.class), mock(ProductCategoryCoverageService.class));

        var states = mock(SkuSuggestedPriceStateMapper.class);
        when(states.selectList(any())).thenReturn(List.of(SkuSuggestedPriceStateDO.builder()
                .platformSkuId(2L).lastValidPriceCents(7777L).lastValidCalculatedAt(now.minusDays(1)).build()));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "suggestedSalePriceResolver",
                new com.aifuli.module.supplychain.tradecore.catalog.pricing.SuggestedSalePriceResolver(eligibility, states));
        var row = service.detail("SPU").skus().getFirst();

        assertThat(row.suggestedSalePrice()).isEqualTo(supplyPrice == null ? null : supplyPrice * 11 / 10 + 100);
        assertThat(row.suggestedSalePriceRule().basePriceSnapshotCents()).isEqualTo(supplyPrice);
        assertThat(row.suggestedSalePriceStatus()).isEqualTo(supplyPrice == null ? "DEPENDENCY_MISSING" : "READY");
        assertThat(row.supplyPrice()).isEqualTo(supplyPrice);
        assertThat(row.lastValidSuggestedSalePrice()).isEqualTo(7777L);
        assertThat(row.lastValidSuggestedSalePriceCalculatedAt()).isEqualTo(now.minusDays(1));
        verify(clock, times(1)).now();
        verify(states, times(1)).selectList(any());
        var management = service.managementPage(new ProductCatalogQueryService.ProductManagementPageQuery(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, 1, 20, null, null)).getList().getFirst().skus().getFirst();
        assertThat(management.suggestedSalePrice()).isEqualTo(row.suggestedSalePrice());
        assertThat(management.suggestedSalePriceStatus()).isEqualTo(row.suggestedSalePriceStatus());
        assertThat(management.suggestedSalePriceRule().basePriceSnapshotCents()).isEqualTo(supplyPrice);
        assertThat(management.lastValidSuggestedSalePrice()).isEqualTo(7777L);
        verify(clock, times(2)).now();
        verify(states, times(2)).selectList(any());
        verifyNoMoreInteractions(states);
    }

    static SuggestedSalePriceRule supplyRule() {
        return new SuggestedSalePriceRule(SuggestedSalePriceRule.Mode.CUSTOM, SuggestedSalePriceRule.Base.SUPPLY_PRICE,
                new BigDecimal("110"), SuggestedSalePriceRule.Operator.ADD, BigDecimal.ONE, null, 88888L);
    }
}
