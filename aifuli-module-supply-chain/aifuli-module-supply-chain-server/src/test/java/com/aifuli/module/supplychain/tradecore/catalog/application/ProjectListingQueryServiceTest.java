package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingPriceSource.PriceBasis;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FixedOperator;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FormulaRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.HideRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PriceBase;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PriceMode;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.ServiceFeeRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.TaxMetadata;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 项目列表必须分别回显 C 端销售建议价、当前 B 端分销价及不变的冻结结算价。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
 */
class ProjectListingQueryServiceTest {

    @Test
    void genericBuyerPriceDoesNotChangeFrozenProjectPriceOrLegacyField() {
        BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
        ProjectListingMapper listingMapper = mock(ProjectListingMapper.class);
        ProjectListingVersionMapper versionMapper = mock(ProjectListingVersionMapper.class);
        ProjectSkuPricingMapper projectPricingMapper = mock(ProjectSkuPricingMapper.class);
        ProjectSkuPricingVersionMapper projectPricingVersionMapper = mock(ProjectSkuPricingVersionMapper.class);
        BuyerProductMapper productMapper = mock(BuyerProductMapper.class);
        PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
        ProjectListingPriceSource priceSource = mock(ProjectListingPriceSource.class);
        ProjectListingQueryService service = new ProjectListingQueryService(projectMapper, listingMapper,
                versionMapper, projectPricingMapper, projectPricingVersionMapper,
                productMapper, skuMapper, priceSource, new ObjectMapper());
        when(projectMapper.selectByProjectCode("PRJ_1")).thenReturn(BuyerProjectDO.builder().id(1L).build());
        ProjectListingDO listing = ProjectListingDO.builder().id(2L).listingCode("PLT_1")
                .buyerProductId(3L).currentVersionId(4L).build();
        when(listingMapper.selectPage(any(PageParam.class), any())).thenReturn(
                new com.aifuli.framework.common.pojo.PageResult<>(java.util.List.of(listing), 1L));
        when(projectPricingMapper.selectList(any())).thenReturn(List.of());
        when(productMapper.selectById(3L)).thenReturn(BuyerProductDO.builder().id(3L).platformSkuId(5L).build());
        when(skuMapper.selectById(5L)).thenReturn(PlatformSkuDO.builder().id(5L).skuCode("SKU_1")
                .suggestedSalePrice(7_800L).build());
        when(priceSource.resolve(any())).thenReturn(new PriceBasis(9_000L, 9_800L, PriceSource.L2, true));
        when(priceSource.legacyPlatformPrice(5L)).thenReturn(null);
        when(versionMapper.selectById(4L)).thenReturn(ProjectListingVersionDO.builder()
                .buyerSettlementUnitPrice(8_800L).settlementPriceLevel(PriceSource.L1.name()).build());

        ProjectListingQueryService.ProjectListingListItem row = service.page("PRJ_1", 1, 10)
                .getList().getFirst();

        assertThat(row.suggestPriceCents()).isNull();
        assertThat(row.suggestedSalePriceCents()).isEqualTo(7_800L);
        assertThat(row.platformBasePriceCents()).isEqualTo(9_000L);
        assertThat(row.distPriceCents()).isEqualTo(9_800L);
        assertThat(row.finalPriceCents()).isEqualTo(8_800L);
        assertThat(row.projectPricingConfig()).isNull();
    }

    /** 当前综合定价规则与旧冻结价分开返回；全继承及各组覆盖均按保存版本原值回显。 */
    @Test
    void returnsCurrentProjectPricingOverridesForOnlyThePagedListings() throws Exception {
        BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
        ProjectListingMapper listingMapper = mock(ProjectListingMapper.class);
        ProjectListingVersionMapper listingVersionMapper = mock(ProjectListingVersionMapper.class);
        ProjectSkuPricingMapper projectPricingMapper = mock(ProjectSkuPricingMapper.class);
        ProjectSkuPricingVersionMapper projectPricingVersionMapper = mock(ProjectSkuPricingVersionMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ProjectListingQueryService service = new ProjectListingQueryService(projectMapper, listingMapper,
                listingVersionMapper, projectPricingMapper, projectPricingVersionMapper,
                mock(BuyerProductMapper.class), mock(PlatformSkuMapper.class),
                mock(ProjectListingPriceSource.class), objectMapper);
        when(projectMapper.selectByProjectCode("PRJ_1")).thenReturn(BuyerProjectDO.builder().id(1L).build());
        ProjectListingDO configured = ProjectListingDO.builder().id(2L).listingCode("PLT_1").build();
        ProjectListingDO unpriced = ProjectListingDO.builder().id(3L).listingCode("PLT_2").build();
        when(listingMapper.selectPage(any(PageParam.class), any())).thenReturn(
                new com.aifuli.framework.common.pojo.PageResult<>(List.of(configured, unpriced), 2L));
        when(projectPricingMapper.selectList(any())).thenReturn(List.of(
                ProjectSkuPricingDO.builder().id(10L).listingId(2L).currentVersionId(12L).build(),
                ProjectSkuPricingDO.builder().id(11L).listingId(3L).build()));
        ProjectOverrides saved = new ProjectOverrides(false,
                new FormulaRule(PriceMode.CUSTOM, PriceBase.MARKET_PRICE,
                        new BigDecimal("85"), FixedOperator.SUBTRACT, new BigDecimal("1.50")),
                true, new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null),
                false, new ServiceFeeRule(new BigDecimal("2"), FixedOperator.ADD,
                        new BigDecimal("1.00"), false, "服务费"),
                false, new HideRule(new BigDecimal("12.50"), null),
                false, new TaxMetadata(true, "SIX_PERCENT", "6%"));
        when(projectPricingVersionMapper.selectList(any())).thenReturn(List.of(
                ProjectSkuPricingVersionDO.builder().id(12L).pricingId(10L)
                        .overrideJson(objectMapper.writeValueAsString(saved)).build()));

        var rows = service.page("PRJ_1", 1, 20).getList();

        assertThat(rows).hasSize(2);
        JsonNode actualConfig = objectMapper.valueToTree(rows.getFirst().projectPricingConfig());
        JsonNode savedConfig = objectMapper.valueToTree(saved);
        assertThat(actualConfig).isEqualTo(savedConfig);
        assertThat(rows.getLast().projectPricingConfig()).isNull();
        assertThat(objectMapper.valueToTree(rows.getFirst()).path("projectPricingConfig")
                .path("distributionRule").path("percentage").decimalValue())
                .isEqualByComparingTo("85");
    }

    /** 项目通用分销价覆盖后列表展示当前项目版本金额，旧 L2 冻结价不能遮盖它。 */
    @Test
    void returnsGenericProjectDistributionAsExclusivePrice() throws Exception {
        BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
        ProjectListingMapper listingMapper = mock(ProjectListingMapper.class);
        ProjectListingVersionMapper listingVersionMapper = mock(ProjectListingVersionMapper.class);
        ProjectSkuPricingMapper projectPricingMapper = mock(ProjectSkuPricingMapper.class);
        ProjectSkuPricingVersionMapper projectPricingVersionMapper = mock(ProjectSkuPricingVersionMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ProjectListingQueryService service = new ProjectListingQueryService(projectMapper, listingMapper,
                listingVersionMapper, projectPricingMapper, projectPricingVersionMapper,
                mock(BuyerProductMapper.class), mock(PlatformSkuMapper.class),
                mock(ProjectListingPriceSource.class), objectMapper);
        when(projectMapper.selectByProjectCode("PRJ_1")).thenReturn(BuyerProjectDO.builder().id(1L).build());
        when(listingMapper.selectPage(any(PageParam.class), any())).thenReturn(
                new com.aifuli.framework.common.pojo.PageResult<>(List.of(
                        ProjectListingDO.builder().id(2L).listingCode("PLT_1").currentVersionId(4L).build()), 1L));
        when(listingVersionMapper.selectById(4L)).thenReturn(ProjectListingVersionDO.builder()
                .buyerSettlementUnitPrice(9_800L).settlementPriceLevel(PriceSource.L2.name()).build());
        when(projectPricingMapper.selectList(any())).thenReturn(List.of(
                ProjectSkuPricingDO.builder().id(10L).listingId(2L).currentVersionId(12L).build()));
        ProjectOverrides saved = new ProjectOverrides(false,
                new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null),
                true, null, true, null, true, null, true, null);
        when(projectPricingVersionMapper.selectList(any())).thenReturn(List.of(
                ProjectSkuPricingVersionDO.builder().id(12L).pricingId(10L)
                        .distributionPriceCents(9_900L)
                        .overrideJson(objectMapper.writeValueAsString(saved)).build()));

        var row = service.page("PRJ_1", 1, 20).getList().getFirst();

        assertThat(row.projectPriceCents()).isEqualTo(9_900L);
        assertThat(row.finalPriceCents()).isEqualTo(9_900L);
        assertThat(row.priceSource()).isEqualTo(PriceSource.L3.name());
        assertThat(row.projectPriceCovered()).isTrue();
    }

    @Test
    void rejectsCurrentVersionFromAnotherPricingIdentity() {
        BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
        ProjectListingMapper listingMapper = mock(ProjectListingMapper.class);
        ProjectSkuPricingMapper projectPricingMapper = mock(ProjectSkuPricingMapper.class);
        ProjectSkuPricingVersionMapper projectPricingVersionMapper = mock(ProjectSkuPricingVersionMapper.class);
        ProjectListingQueryService service = new ProjectListingQueryService(projectMapper, listingMapper,
                mock(ProjectListingVersionMapper.class), projectPricingMapper, projectPricingVersionMapper,
                mock(BuyerProductMapper.class), mock(PlatformSkuMapper.class),
                mock(ProjectListingPriceSource.class), new ObjectMapper());
        when(projectMapper.selectByProjectCode("PRJ_1")).thenReturn(BuyerProjectDO.builder().id(1L).build());
        when(listingMapper.selectPage(any(PageParam.class), any())).thenReturn(
                new com.aifuli.framework.common.pojo.PageResult<>(
                        List.of(ProjectListingDO.builder().id(2L).listingCode("PLT_1").build()), 1L));
        when(projectPricingMapper.selectList(any())).thenReturn(List.of(
                ProjectSkuPricingDO.builder().id(10L).listingId(2L).currentVersionId(12L).build()));
        when(projectPricingVersionMapper.selectList(any())).thenReturn(List.of(
                ProjectSkuPricingVersionDO.builder().id(12L).pricingId(99L).build()));

        assertThatThrownBy(() -> service.page("PRJ_1", 1, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("listingId=2");
    }
}
