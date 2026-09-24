package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 项目覆盖统计必须与项目商品列表的通用分销价覆盖口径一致。设计见 docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品。 */
class ProjectAdminQueryServiceTest {

    @Test
    void countsGenericAndLegacyProjectPricesWithoutDoubleCounting() throws Exception {
        BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
        ProjectListingMapper listingMapper = mock(ProjectListingMapper.class);
        ProjectListingVersionMapper listingVersionMapper = mock(ProjectListingVersionMapper.class);
        ProjectSkuPricingMapper pricingMapper = mock(ProjectSkuPricingMapper.class);
        ProjectSkuPricingVersionMapper pricingVersionMapper = mock(ProjectSkuPricingVersionMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ProjectAdminQueryService service = new ProjectAdminQueryService(projectMapper, mock(BuyerMapper.class),
                listingMapper, listingVersionMapper, pricingMapper, pricingVersionMapper, objectMapper);
        when(projectMapper.selectByProjectCode("PRJ_1")).thenReturn(BuyerProjectDO.builder()
                .id(1L).projectCode("PRJ_1").status(BuyerProjectStatus.ACTIVE.getCode()).build());
        when(listingMapper.selectList(any())).thenReturn(List.of(
                ProjectListingDO.builder().id(2L).currentVersionId(4L).build(),
                ProjectListingDO.builder().id(3L).currentVersionId(5L).build()));
        when(pricingMapper.selectByListingId(2L)).thenReturn(ProjectSkuPricingDO.builder()
                .id(10L).currentVersionId(12L).build());
        ProjectOverrides override = new ProjectOverrides(false,
                new FormulaRule(PriceMode.DIRECT_MARKET_PRICE, null, null, null, null),
                true, null, true, null, true, null, true, null);
        when(pricingVersionMapper.selectById(12L)).thenReturn(ProjectSkuPricingVersionDO.builder()
                .pricingId(10L).overrideJson(objectMapper.writeValueAsString(override)).build());
        when(listingVersionMapper.selectById(5L)).thenReturn(ProjectListingVersionDO.builder()
                .settlementPriceLevel(PriceSource.L3.name()).build());

        var detail = service.get("PRJ_1");

        assertThat(detail.skuCount()).isEqualTo(2);
        assertThat(detail.projectPriceSkuCount()).isEqualTo(2);
    }
}
