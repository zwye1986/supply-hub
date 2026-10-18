package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingPriceSource.PriceBasis;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 项目列表必须分别回显 C 端销售建议价、当前 B 端分销价及不变的冻结结算价。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
 */
class ProjectListingQueryServiceTest {
    /** 名称取项目关联分销商，不按组织等级或旧 L1/L2 编码改成其他主体；多行共享一次名称读取。 */
    @ParameterizedTest
    @ValueSource(shorts = {1, 2, 3})
    void inheritedRowsUseAssociatedBuyerNameOncePerPage(short level) {
        SourceFixture fixture = new SourceFixture(List.of(
                sourceListing(2L, 4L), sourceListing(5L, 6L)));
        when(fixture.buyers.selectById(8L)).thenReturn(BuyerDO.builder()
                .id(8L).buyerName("关联分销商").distributionLevel(level).parentBuyerId(99L).build());
        fixture.freeze(4L, PriceSource.L1);
        fixture.freeze(6L, PriceSource.L2);

        var page = fixture.service().page("PRJ_1", 1, 20);

        assertThat(page.getTotal()).isEqualTo(2L);
        for (var row : page.getList()) {
            assertThat(fixture.json.valueToTree(row).path("priceSourceName").asText()).isEqualTo("关联分销商");
            assertThat(row.projectPriceCovered()).isFalse();
            assertThat(row.finalPriceCents()).isEqualTo(9_800L);
        }
        assertThat(page.getList().getFirst().priceSource()).isEqualTo("L1");
        assertThat(page.getList().getLast().priceSource()).isEqualTo("L2");
        verify(fixture.buyers, times(1)).selectById(8L);
    }

    /** 项目显式规则决定来源，0、与旧价相同、依赖缺失的空金额都不能改变主体。 */
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, 9_800L})
    void genericDistributionOverrideUsesProjectNameRegardlessOfAmount(Long amount) throws Exception {
        SourceFixture fixture = new SourceFixture(List.of(sourceListing(2L, 4L)));
        fixture.freeze(4L, PriceSource.L2);
        fixture.pricing(new ProjectOverrides(false,
                new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null),
                true, null, true, null, true, null, true, null), amount);

        var row = fixture.service().page("PRJ_1", 1, 20).getList().getFirst();

        assertThat(fixture.json.valueToTree(row).path("priceSourceName").asText()).isEqualTo("福利项目");
        assertThat(row.priceSource()).isEqualTo("L3");
        assertThat(row.projectPriceCovered()).isTrue();
        assertThat(row.finalPriceCents()).isEqualTo(amount);
    }

    /** 历史 L3 仍是项目专属价，不能因没有新通用定价版本而标记为分销商。 */
    @Test
    void legacyL3UsesProjectName() {
        SourceFixture fixture = new SourceFixture(List.of(sourceListing(2L, 4L)));
        fixture.freeze(4L, PriceSource.L3);

        var row = fixture.service().page("PRJ_1", 1, 20).getList().getFirst();

        assertThat(fixture.json.valueToTree(row).path("priceSourceName").asText()).isEqualTo("福利项目");
        assertThat(row.projectPriceCovered()).isTrue();
        assertThat(row.projectPricingConfig()).isNull();
    }

    /** 税务单独覆盖不改变分销价来源。 */
    @Test
    void taxOnlyOverrideStillUsesAssociatedBuyerName() throws Exception {
        SourceFixture fixture = new SourceFixture(List.of(sourceListing(2L, 4L)));
        fixture.freeze(4L, PriceSource.L2);
        fixture.pricing(new ProjectOverrides(true, null, true, null, true, null, true, null,
                false, new TaxMetadata(true, "RATE_6", "6%")), 9_800L);

        var row = fixture.service().page("PRJ_1", 1, 20).getList().getFirst();

        assertThat(fixture.json.valueToTree(row).path("priceSourceName").asText()).isEqualTo("关联分销商");
        assertThat(row.projectPriceCovered()).isFalse();
        assertThat(row.priceSource()).isEqualTo("L2");
    }

    /** 缺失分销商档案只影响来源名称，不伪造项目或平台名称，不改变原价格。 */
    @Test
    void missingBuyerLeavesSourceNameNull() {
        SourceFixture fixture = new SourceFixture(List.of(sourceListing(2L, 4L)));
        fixture.freeze(4L, PriceSource.L2);
        when(fixture.buyers.selectById(8L)).thenReturn(null);

        var row = fixture.service().page("PRJ_1", 1, 20).getList().getFirst();

        assertThat(fixture.json.valueToTree(row).has("priceSourceName")).isTrue();
        assertThat(fixture.json.valueToTree(row).path("priceSourceName").isNull()).isTrue();
        assertThat(row.finalPriceCents()).isEqualTo(9_800L);
    }

    @Test
    void missingProjectNameDoesNotFallBackToBuyerName() {
        SourceFixture fixture = new SourceFixture(List.of(sourceListing(2L, 4L)));
        when(fixture.projects.selectByProjectCode("PRJ_1"))
                .thenReturn(BuyerProjectDO.builder().id(1L).buyerId(8L).build());
        fixture.freeze(4L, PriceSource.L3);

        var row = fixture.service().page("PRJ_1", 1, 20).getList().getFirst();

        assertThat(fixture.json.valueToTree(row).has("priceSourceName")).isTrue();
        assertThat(fixture.json.valueToTree(row).path("priceSourceName").isNull()).isTrue();
    }

    @Test
    void emptyPageDoesNotLoadBuyerName() {
        SourceFixture fixture = new SourceFixture(List.of());

        var page = fixture.service().page("PRJ_1", 1, 20);

        assertThat(page.getList()).isEmpty();
        assertThat(page.getTotal()).isZero();
        verifyNoInteractions(fixture.buyers);
    }

    private static ProjectListingDO sourceListing(Long id, Long frozenVersionId) {
        return ProjectListingDO.builder().id(id).projectId(1L).listingCode("PLT_" + id)
                .currentVersionId(frozenVersionId).build();
    }

    /** 仅隔离数据库依赖，真实执行分页装配、原始覆盖解析和 JSON 序列化。 */
    private static class SourceFixture {
        private final BuyerProjectMapper projects = mock(BuyerProjectMapper.class);
        private final BuyerMapper buyers = mock(BuyerMapper.class);
        private final ProjectListingMapper listings = mock(ProjectListingMapper.class);
        private final ProjectListingVersionMapper frozenVersions = mock(ProjectListingVersionMapper.class);
        private final ProjectSkuPricingMapper pricing = mock(ProjectSkuPricingMapper.class);
        private final ProjectSkuPricingVersionMapper pricingVersions = mock(ProjectSkuPricingVersionMapper.class);
        private final ObjectMapper json = new ObjectMapper();

        private SourceFixture(List<ProjectListingDO> rows) {
            when(projects.selectByProjectCode("PRJ_1")).thenReturn(BuyerProjectDO.builder()
                    .id(1L).buyerId(8L).projectName("福利项目").build());
            when(buyers.selectById(8L)).thenReturn(BuyerDO.builder().id(8L).buyerName("关联分销商").build());
            when(listings.selectPage(any(PageParam.class), any()))
                    .thenReturn(new PageResult<>(rows, (long) rows.size()));
        }

        private void freeze(Long versionId, PriceSource source) {
            when(frozenVersions.selectById(versionId)).thenReturn(ProjectListingVersionDO.builder()
                    .id(versionId).buyerSettlementUnitPrice(9_800L).settlementPriceLevel(source.name()).build());
        }

        private void pricing(ProjectOverrides overrides, Long amount) throws Exception {
            when(pricing.selectList(any())).thenReturn(List.of(ProjectSkuPricingDO.builder()
                    .id(10L).listingId(2L).currentVersionId(12L).build()));
            when(pricingVersions.selectList(any())).thenReturn(List.of(ProjectSkuPricingVersionDO.builder()
                    .id(12L).pricingId(10L).overrideJson(json.writeValueAsString(overrides))
                    .distributionPriceCents(amount).build()));
        }

        private ProjectListingQueryService service() {
            return new ProjectListingQueryService(projects, buyers, listings, frozenVersions, pricing, pricingVersions,
                    mock(BuyerProductMapper.class), mock(PlatformSkuMapper.class), mock(PlatformSpuMapper.class),
                    mock(ProductTypeMapper.class), mock(ProductCategoryMapper.class),
                    mock(ProjectListingPriceSource.class), json);
        }
    }

    @Test
    void genericBuyerPriceDoesNotChangeFrozenProjectPriceOrLegacyField() {
        BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
        ProjectListingMapper listingMapper = mock(ProjectListingMapper.class);
        ProjectListingVersionMapper versionMapper = mock(ProjectListingVersionMapper.class);
        ProjectSkuPricingMapper projectPricingMapper = mock(ProjectSkuPricingMapper.class);
        ProjectSkuPricingVersionMapper projectPricingVersionMapper = mock(ProjectSkuPricingVersionMapper.class);
        BuyerProductMapper productMapper = mock(BuyerProductMapper.class);
        PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
        PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
        ProductTypeMapper productTypeMapper = mock(ProductTypeMapper.class);
        ProductCategoryMapper categoryMapper = mock(ProductCategoryMapper.class);
        ProjectListingPriceSource priceSource = mock(ProjectListingPriceSource.class);
        ProjectListingQueryService service = new ProjectListingQueryService(projectMapper, mock(BuyerMapper.class), listingMapper,
                versionMapper, projectPricingMapper, projectPricingVersionMapper,
                productMapper, skuMapper, spuMapper, productTypeMapper, categoryMapper,
                priceSource, new ObjectMapper());
        when(projectMapper.selectByProjectCode("PRJ_1")).thenReturn(BuyerProjectDO.builder().id(1L).build());
        ProjectListingDO listing = ProjectListingDO.builder().id(2L).listingCode("PLT_1")
                .buyerProductId(3L).currentVersionId(4L).build();
        when(listingMapper.selectPage(any(PageParam.class), any())).thenReturn(
                new com.aifuli.framework.common.pojo.PageResult<>(java.util.List.of(listing), 1L));
        when(projectPricingMapper.selectList(any())).thenReturn(List.of());
        when(productMapper.selectById(3L)).thenReturn(BuyerProductDO.builder().id(3L).platformSkuId(5L).build());
        when(skuMapper.selectById(5L)).thenReturn(PlatformSkuDO.builder().id(5L).skuCode("SKU_1")
                .spuId(6L).marketPrice(49_000L).suggestedSalePrice(7_800L).build());
        when(spuMapper.selectBatchIds(any())).thenReturn(List.of(PlatformSpuDO.builder().id(6L)
                .spuCode("SPU_1").spuName("会员卡").productType("equity_product")
                .productClassification("VIRTUAL_CARD").primaryCategoryId(7L).build()));
        when(productTypeMapper.selectList(any())).thenReturn(List.of(ProductTypeDO.builder()
                .typeCode("equity_product").typeName("虚拟权益商品").build()));
        when(categoryMapper.selectBatchIds(any())).thenReturn(List.of(ProductCategoryDO.builder()
                .id(7L).categoryName("会员权益").build()));
        when(priceSource.resolve(any())).thenReturn(new PriceBasis(9_000L, 9_800L, PriceSource.L2, true));
        when(priceSource.legacyPlatformPrice(5L)).thenReturn(null);
        when(versionMapper.selectById(4L)).thenReturn(ProjectListingVersionDO.builder()
                .buyerSettlementUnitPrice(8_800L).settlementPriceLevel(PriceSource.L1.name()).build());

        ProjectListingQueryService.ProjectListingListItem row = service.page("PRJ_1", 1, 10)
                .getList().getFirst();

        assertThat(row.suggestPriceCents()).isNull();
        assertThat(row.suggestedSalePriceCents()).isEqualTo(7_800L);
        assertThat(new ObjectMapper().valueToTree(row).path("marketPriceCents").longValue()).isEqualTo(49_000L);
        assertThat(row.spuCode()).isEqualTo("SPU_1");
        assertThat(row.spuName()).isEqualTo("会员卡");
        assertThat(row.productType()).isEqualTo("equity_product");
        assertThat(row.productTypeName()).isEqualTo("虚拟权益商品");
        assertThat(row.productClassification()).isEqualTo("VIRTUAL_CARD");
        assertThat(row.categoryName()).isEqualTo("会员权益");
        assertThat(row.platformBasePriceCents()).isEqualTo(9_000L);
        assertThat(row.distPriceCents()).isEqualTo(9_800L);
        assertThat(row.finalPriceCents()).isEqualTo(8_800L);
        assertThat(row.projectPricingConfig()).isNull();
    }

    /** 市场价 0 分必须保留；缺失类型及主类目时展示字段为空，不影响 SKU 行返回。 */
    @Test
    void zeroMarketPriceAndMissingDisplayMetadataRemainReadable() {
        BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
        ProjectListingMapper listingMapper = mock(ProjectListingMapper.class);
        BuyerProductMapper productMapper = mock(BuyerProductMapper.class);
        PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
        PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
        ProjectListingQueryService service = new ProjectListingQueryService(projectMapper, mock(BuyerMapper.class), listingMapper,
                mock(ProjectListingVersionMapper.class), mock(ProjectSkuPricingMapper.class),
                mock(ProjectSkuPricingVersionMapper.class), productMapper, skuMapper, spuMapper,
                mock(ProductTypeMapper.class), mock(ProductCategoryMapper.class),
                mock(ProjectListingPriceSource.class), new ObjectMapper());
        when(projectMapper.selectByProjectCode("PRJ_1")).thenReturn(BuyerProjectDO.builder().id(1L).build());
        when(listingMapper.selectPage(any(PageParam.class), any())).thenReturn(
                new com.aifuli.framework.common.pojo.PageResult<>(List.of(
                        ProjectListingDO.builder().id(2L).buyerProductId(3L).build()), 1L));
        when(productMapper.selectById(3L)).thenReturn(BuyerProductDO.builder().platformSkuId(5L).build());
        when(skuMapper.selectById(5L)).thenReturn(PlatformSkuDO.builder().spuId(6L).marketPrice(0L).build());
        when(spuMapper.selectBatchIds(any())).thenReturn(List.of(PlatformSpuDO.builder()
                .id(6L).spuCode("SPU_1").spuName("会员卡").build()));

        var row = service.page("PRJ_1", 1, 20).getList().getFirst();

        assertThat(row.marketPriceCents()).isZero();
        assertThat(row.spuName()).isEqualTo("会员卡");
        assertThat(row.productTypeName()).isNull();
        assertThat(row.categoryName()).isNull();
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
        ProjectListingQueryService service = new ProjectListingQueryService(projectMapper, mock(BuyerMapper.class), listingMapper,
                listingVersionMapper, projectPricingMapper, projectPricingVersionMapper,
                mock(BuyerProductMapper.class), mock(PlatformSkuMapper.class),
                mock(PlatformSpuMapper.class), mock(ProductTypeMapper.class), mock(ProductCategoryMapper.class),
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
        ProjectListingQueryService service = new ProjectListingQueryService(projectMapper, mock(BuyerMapper.class), listingMapper,
                listingVersionMapper, projectPricingMapper, projectPricingVersionMapper,
                mock(BuyerProductMapper.class), mock(PlatformSkuMapper.class),
                mock(PlatformSpuMapper.class), mock(ProductTypeMapper.class), mock(ProductCategoryMapper.class),
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
        ProjectListingQueryService service = new ProjectListingQueryService(projectMapper, mock(BuyerMapper.class), listingMapper,
                mock(ProjectListingVersionMapper.class), projectPricingMapper, projectPricingVersionMapper,
                mock(BuyerProductMapper.class), mock(PlatformSkuMapper.class),
                mock(PlatformSpuMapper.class), mock(ProductTypeMapper.class), mock(ProductCategoryMapper.class),
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
