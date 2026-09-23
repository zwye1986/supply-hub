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
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
        BuyerProductMapper productMapper = mock(BuyerProductMapper.class);
        PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
        ProjectListingPriceSource priceSource = mock(ProjectListingPriceSource.class);
        ProjectListingQueryService service = new ProjectListingQueryService(projectMapper, listingMapper,
                versionMapper, productMapper, skuMapper, priceSource);
        when(projectMapper.selectByProjectCode("PRJ_1")).thenReturn(BuyerProjectDO.builder().id(1L).build());
        ProjectListingDO listing = ProjectListingDO.builder().id(2L).listingCode("PLT_1")
                .buyerProductId(3L).currentVersionId(4L).build();
        when(listingMapper.selectPage(any(PageParam.class), any())).thenReturn(
                new com.aifuli.framework.common.pojo.PageResult<>(java.util.List.of(listing), 1L));
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
    }
}
