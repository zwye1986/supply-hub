package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerCategoryScopeDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerCategoryScopeMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerScopeKind;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.FormulaRule;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PriceMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 项目覆盖统计必须与项目商品列表的通用分销价覆盖口径一致。设计见 docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品。 */
class ProjectAdminQueryServiceTest {

    @Test
    void listAndDetailProjectIndependentSettlementIdentityAndLegacyFallback() {
        BuyerProjectMapper projects = mock(BuyerProjectMapper.class);
        BuyerMapper buyers = mock(BuyerMapper.class);
        var scope = mock(BuyerCategoryScopeMapper.class);
        var listings = mock(ProjectListingMapper.class);
        var service = new ProjectAdminQueryService(projects, buyers, scope, mock(ProductTypeMapper.class),
                listings, mock(ProjectListingVersionMapper.class), mock(ProjectSkuPricingMapper.class),
                mock(ProjectSkuPricingVersionMapper.class), new ObjectMapper());
        BuyerProjectDO independent = BuyerProjectDO.builder().id(1L).projectCode("PRJ_INDEPENDENT")
                .buyerId(2L).settlementBuyerId(3L).status(BuyerProjectStatus.ACTIVE.getCode()).build();
        BuyerProjectDO legacy = BuyerProjectDO.builder().id(2L).projectCode("PRJ_LEGACY")
                .buyerId(2L).status(BuyerProjectStatus.ACTIVE.getCode()).build();
        when(buyers.selectById(2L)).thenReturn(BuyerDO.builder().id(2L).buyerCode("OWNER").buyerName("关联方").build());
        when(buyers.selectById(3L)).thenReturn(BuyerDO.builder().id(3L).buyerCode("SETTLEMENT").buyerName("结算方").build());
        when(projects.selectPage(any(PageParam.class), any(Wrapper.class)))
                .thenReturn(new PageResult<>(List.of(independent, legacy), 2L));
        when(projects.selectByProjectCode("PRJ_INDEPENDENT")).thenReturn(independent);
        when(projects.selectByProjectCode("PRJ_LEGACY")).thenReturn(legacy);
        var rows = service.page(new ProjectAdminQueryService.ProjectPageQuery(
                null, null, List.of(), null, List.of(), null, null, 1, 12)).getList();
        assertThat(rows).extracting(row -> row.buyerCode()).containsExactly("OWNER", "OWNER");
        assertThat(rows).extracting(row -> row.settlementBuyerCode()).containsExactly("SETTLEMENT", "OWNER");
        assertThat(rows).extracting(row -> row.settlementBuyerName()).containsExactly("结算方", "关联方");
        assertThat(service.get("PRJ_INDEPENDENT").settlementBuyerCode()).isEqualTo("SETTLEMENT");
        assertThat(service.get("PRJ_LEGACY").settlementBuyerCode()).isEqualTo("OWNER");
    }

    @Test
    void countsGenericAndLegacyProjectPricesWithoutDoubleCounting() throws Exception {
        BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
        ProjectListingMapper listingMapper = mock(ProjectListingMapper.class);
        ProjectListingVersionMapper listingVersionMapper = mock(ProjectListingVersionMapper.class);
        ProjectSkuPricingMapper pricingMapper = mock(ProjectSkuPricingMapper.class);
        ProjectSkuPricingVersionMapper pricingVersionMapper = mock(ProjectSkuPricingVersionMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ProjectAdminQueryService service = new ProjectAdminQueryService(projectMapper, mock(BuyerMapper.class),
                mock(BuyerCategoryScopeMapper.class), mock(ProductTypeMapper.class),
                listingMapper, listingVersionMapper, pricingMapper, pricingVersionMapper, objectMapper);
        when(projectMapper.selectByProjectCode("PRJ_1")).thenReturn(BuyerProjectDO.builder()
                .id(1L).projectCode("PRJ_1").status(BuyerProjectStatus.ACTIVE.getCode()).build());
        when(listingMapper.selectList(any())).thenReturn(List.of(
                ProjectListingDO.builder().id(2L).projectId(1L).currentVersionId(4L).build(),
                ProjectListingDO.builder().id(3L).projectId(1L).currentVersionId(5L).build()));
        when(pricingMapper.selectList(any())).thenReturn(List.of(ProjectSkuPricingDO.builder()
                .id(10L).listingId(2L).currentVersionId(12L).build()));
        ProjectOverrides override = new ProjectOverrides(false,
                new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null),
                true, null, true, null, true, null, true, null);
        when(pricingVersionMapper.selectList(any())).thenReturn(List.of(ProjectSkuPricingVersionDO.builder()
                .id(12L).pricingId(10L).overrideJson(objectMapper.writeValueAsString(override)).build()));
        when(listingVersionMapper.selectList(any())).thenReturn(List.of(
                ProjectListingVersionDO.builder().id(4L).settlementPriceLevel(PriceSource.L3.name()).build(),
                ProjectListingVersionDO.builder().id(5L).settlementPriceLevel(PriceSource.L3.name()).build()));

        var detail = service.get("PRJ_1");

        assertThat(detail.skuCount()).isEqualTo(2);
        assertThat(detail.projectPriceSkuCount()).isEqualTo(2);
    }

    @Test
    void pageReturnsStatsForEachProjectIncludingEmptyProject() throws Exception {
        BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
        ProjectListingMapper listingMapper = mock(ProjectListingMapper.class);
        ProjectSkuPricingMapper pricingMapper = mock(ProjectSkuPricingMapper.class);
        ProjectSkuPricingVersionMapper pricingVersionMapper = mock(ProjectSkuPricingVersionMapper.class);
        ProjectListingVersionMapper listingVersionMapper = mock(ProjectListingVersionMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ProjectAdminQueryService service = new ProjectAdminQueryService(projectMapper, mock(BuyerMapper.class),
                mock(BuyerCategoryScopeMapper.class), mock(ProductTypeMapper.class),
                listingMapper, listingVersionMapper, pricingMapper, pricingVersionMapper, objectMapper);
        when(projectMapper.selectPage(any(PageParam.class), any(Wrapper.class))).thenReturn(new PageResult<>(List.of(
                BuyerProjectDO.builder().id(1L).status(BuyerProjectStatus.ACTIVE.getCode()).build(),
                BuyerProjectDO.builder().id(2L).status(BuyerProjectStatus.ACTIVE.getCode()).build(),
                BuyerProjectDO.builder().id(3L).status(BuyerProjectStatus.ACTIVE.getCode()).build()), 3L));
        when(listingMapper.selectList(any())).thenReturn(List.of(
                ProjectListingDO.builder().id(11L).projectId(1L).currentVersionId(21L).build(),
                ProjectListingDO.builder().id(12L).projectId(1L).currentVersionId(22L).build(),
                ProjectListingDO.builder().id(13L).projectId(2L).build()));
        when(pricingMapper.selectList(any())).thenReturn(List.of(ProjectSkuPricingDO.builder()
                .id(31L).listingId(11L).currentVersionId(41L).build()));
        ProjectOverrides override = new ProjectOverrides(false,
                new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null),
                true, null, true, null, true, null, true, null);
        when(pricingVersionMapper.selectList(any())).thenReturn(List.of(ProjectSkuPricingVersionDO.builder()
                .id(41L).pricingId(31L).overrideJson(objectMapper.writeValueAsString(override)).build()));
        when(listingVersionMapper.selectList(any())).thenReturn(List.of(
                ProjectListingVersionDO.builder().id(21L).settlementPriceLevel(PriceSource.L3.name()).build(),
                ProjectListingVersionDO.builder().id(22L).settlementPriceLevel(PriceSource.L2.name()).build()));

        var rows = service.page(new ProjectAdminQueryService.ProjectPageQuery(
                null, null, List.of(), null, List.of(), null, null, 1, 12)).getList();

        assertThat(rows).extracting(row -> row.skuCount()).containsExactly(2, 1, 0);
        assertThat(rows).extracting(row -> row.projectPriceSkuCount()).containsExactly(1, 0, 0);
    }

    @Test
    void projectContractsExposeIndependentSettlementBuyer() {
        ObjectMapper mapper = new ObjectMapper();
        for (Class<?> type : List.of(ProjectAdminQueryService.ProjectListItem.class,
                ProjectAdminQueryService.ProjectDetail.class)) {
            var properties = mapper.getSerializationConfig().introspect(mapper.constructType(type))
                    .findProperties().stream().map(p -> p.getName()).toList();
            assertThat(properties).contains("settlementBuyerCode", "settlementBuyerName");
        }
        var requestProperties = mapper.getDeserializationConfig().introspect(mapper.constructType(
                com.aifuli.module.supplychain.interfaceapi.admin.ProjectAdminController.ProjectSaveReqVO.class))
                .findProperties().stream().map(p -> p.getName()).toList();
        assertThat(requestProperties).contains("settlementBuyerCode");
    }

    /** 项目详情只取关联分销商的商品类型范围，保持与分销商详情相同的类型名称。 */
    @Test
    void detailProjectsBuyersConfiguredProductTypes() {
        BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
        BuyerMapper buyerMapper = mock(BuyerMapper.class);
        BuyerCategoryScopeMapper scopeMapper = mock(BuyerCategoryScopeMapper.class);
        ProductTypeMapper typeMapper = mock(ProductTypeMapper.class);
        ProjectListingMapper listingMapper = mock(ProjectListingMapper.class);
        ProjectAdminQueryService service = new ProjectAdminQueryService(projectMapper, buyerMapper,
                scopeMapper, typeMapper, listingMapper, mock(ProjectListingVersionMapper.class),
                mock(ProjectSkuPricingMapper.class), mock(ProjectSkuPricingVersionMapper.class), new ObjectMapper());
        when(projectMapper.selectByProjectCode("PRJ_1")).thenReturn(BuyerProjectDO.builder()
                .id(1L).buyerId(2L).status(BuyerProjectStatus.ACTIVE.getCode()).build());
        when(buyerMapper.selectById(2L)).thenReturn(BuyerDO.builder().id(2L).build());
        when(scopeMapper.selectByBuyerId(2L)).thenReturn(List.of(
                BuyerCategoryScopeDO.builder().scopeKind(BuyerScopeKind.SELLABLE.name()).scopeCode("MOVIE").build(),
                BuyerCategoryScopeDO.builder().scopeKind(BuyerScopeKind.PRODUCT_TYPE.name()).scopeCode("MOVIE").build(),
                BuyerCategoryScopeDO.builder().scopeKind(BuyerScopeKind.PRODUCT_TYPE.name()).scopeCode("EQUITY_PRODUCT").build()));
        when(typeMapper.selectList(any())).thenReturn(List.of(
                ProductTypeDO.builder().typeCode("EQUITY_PRODUCT").typeName("虚拟权益商品").build(),
                ProductTypeDO.builder().typeCode("MOVIE").typeName("电影票").build()));
        when(listingMapper.selectList(any())).thenReturn(List.of());

        var detail = service.get("PRJ_1");

        assertThat(detail.productTypes()).extracting(type -> type.id())
                .containsExactly("MOVIE", "EQUITY_PRODUCT");
        assertThat(detail.productTypes()).extracting(type -> type.name())
                .containsExactly("电影票", "虚拟权益商品");
    }
}
