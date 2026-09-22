package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 标品 SKU 池：无 L1 时建议结算价为空；projectCode 只露出所属分销商尚未挂项目目录的 SKU。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#44-标品-sku-分页}。
 */
class PlatformSkuQueryServiceTest {

    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final PlatformSettlementPriceMapper priceMapper = mock(PlatformSettlementPriceMapper.class);
    private final PlatformSettlementPriceVersionMapper versionMapper =
            mock(PlatformSettlementPriceVersionMapper.class);
    private final BuyerProductMapper buyerProductMapper = mock(BuyerProductMapper.class);
    private final BuyerProjectMapper buyerProjectMapper = mock(BuyerProjectMapper.class);
    private final ProjectListingMapper projectListingMapper = mock(ProjectListingMapper.class);
    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private final SupplierOfferMapper offerMapper = mock(SupplierOfferMapper.class);
    private final SupplierOfferVersionMapper offerVersionMapper = mock(SupplierOfferVersionMapper.class);
    private final CategoryQueryService categoryQueryService = mock(CategoryQueryService.class);
    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private PlatformSkuQueryService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformSkuDO.class);
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 22, 11, 0));
        service = new PlatformSkuQueryService(
                skuMapper, spuMapper, priceMapper, versionMapper,
                supplierMapper, mock(SupplierSupplyScopeMapper.class),
                offerMapper, offerVersionMapper, categoryQueryService, databaseClock,
                mock(BuyerMapper.class),
                buyerProductMapper, buyerProjectMapper, projectListingMapper);
    }

    @Test
    void filtersByKeywordAndProjectsSuggestedSalePriceInsteadOfFaceOrMarketPrice() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(8L).spuCode("MOVIE_TICKET").spuName("电影票")
                .sellableCategory("MOVIE").productType("MOVIE").productClassification("MOVIE_TICKET")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSkuDO sku = PlatformSkuDO.builder()
                .id(11L).skuCode("MOVIE_100").spuId(8L).skuName("电影票 100")
                .faceValue(10_000L).marketPrice(12_000L).suggestedSalePrice(11_000L)
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .createdAt(LocalDateTime.of(2026, 9, 2, 12, 0)).build();
        when(spuMapper.selectList(any())).thenReturn(List.of(spu));
        when(skuMapper.selectPage(any(PageParam.class), ArgumentMatchers.any()))
                .thenReturn(new PageResult<>(List.of(sku), 1L));
        when(priceMapper.selectById(11L)).thenReturn(null);

        PageResult<PlatformSkuQueryService.SkuListItem> page = service.page(
                new PlatformSkuQueryService.SkuPageQuery(
                        "电影票", null, null, null, null, null, null, 1, 20));

        assertThat(page.getList()).hasSize(1);
        PlatformSkuQueryService.SkuListItem row = page.getList().getFirst();
        assertThat(row.skuName()).isEqualTo("电影票 100");
        assertThat(row.suggestPriceCents()).isNull();
        assertThat(row.marketPriceCents()).isEqualTo(11_000L);
        assertThat(row.sellableCategory()).isEqualTo("MOVIE");
        assertThat(row.productClassification()).isEqualTo("MOVIE_TICKET");
    }

    @Test
    void treatsZeroL1AsPricedNotMissing() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(8L).spuCode("MOVIE_TICKET").spuName("电影票")
                .sellableCategory("MOVIE").productType("MOVIE").productClassification("MOVIE_TICKET")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSkuDO sku = PlatformSkuDO.builder()
                .id(11L).skuCode("MOVIE_100").spuId(8L).skuName("电影票 100")
                .faceValue(10_000L).status(CatalogEntityStatus.ACTIVE.getCode())
                .createdAt(LocalDateTime.of(2026, 9, 2, 12, 0)).build();
        when(spuMapper.selectList(any())).thenReturn(List.of(spu));
        when(skuMapper.selectPage(any(PageParam.class), ArgumentMatchers.any()))
                .thenReturn(new PageResult<>(List.of(sku), 1L));
        when(priceMapper.selectById(11L)).thenReturn(
                com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO.builder()
                        .platformSkuId(11L).currentVersionId(90L).build());
        when(versionMapper.selectById(90L)).thenReturn(
                com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO.builder()
                        .id(90L).platformSkuId(11L).unitPriceCents(0L).build());

        PlatformSkuQueryService.SkuListItem row = service.page(
                new PlatformSkuQueryService.SkuPageQuery(
                        null, null, null, null, null, null, null, 1, 20))
                .getList().getFirst();
        assertThat(row.suggestPriceCents()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectCodeKeepsBuyerCatalogSkuAndDropsSkuWithoutBuyerProduct() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(8L).spuCode("MOVIE_TICKET").spuName("电影票")
                .sellableCategory("MOVIE").productType("MOVIE").productClassification("MOVIE_TICKET")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSkuDO inBuyerCatalog = PlatformSkuDO.builder()
                .id(11L).skuCode("MOVIE_100").spuId(8L).skuName("电影票 100")
                .faceValue(10_000L).status(CatalogEntityStatus.ACTIVE.getCode())
                .createdAt(LocalDateTime.of(2026, 9, 2, 12, 0)).build();
        when(spuMapper.selectList(any())).thenReturn(List.of(spu));
        when(buyerProjectMapper.selectByProjectCode("PRJ_1")).thenReturn(
                BuyerProjectDO.builder().id(50L).projectCode("PRJ_1").buyerId(7L).build());
        when(buyerProductMapper.selectList(any())).thenReturn(List.of(
                BuyerProductDO.builder()
                        .id(100L)
                        .buyerId(7L)
                        .platformSkuId(11L)
                        .status(BuyerProductStatus.ACTIVE.getCode())
                        .build()));
        when(projectListingMapper.selectList(any())).thenReturn(List.of());
        ArgumentCaptor<LambdaQueryWrapper<PlatformSkuDO>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(skuMapper.selectPage(any(PageParam.class), wrapperCaptor.capture()))
                .thenReturn(new PageResult<>(List.of(inBuyerCatalog), 1L));

        PageResult<PlatformSkuQueryService.SkuListItem> page = service.page(
                new PlatformSkuQueryService.SkuPageQuery(
                        null, null, null, null, null, null, "PRJ_1", 1, 20));

        assertThat(page.getList()).extracting(PlatformSkuQueryService.SkuListItem::skuCode)
                .containsExactly("MOVIE_100");
        assertThat(wrapperCaptor.getValue()).isNotNull();

        when(buyerProductMapper.selectList(any())).thenReturn(List.of());
        PageResult<PlatformSkuQueryService.SkuListItem> empty = service.page(
                new PlatformSkuQueryService.SkuPageQuery(
                        null, null, null, null, null, null, "PRJ_1", 1, 20));
        assertThat(empty.getList()).isEmpty();
        assertThat(empty.getTotal()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void keywordMatchesSpuCodeAsWellAsName() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(8L).spuCode("SPU_IQY").spuName("爱奇艺黄金会员")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSkuDO sku = PlatformSkuDO.builder()
                .id(11L).skuCode("IQY-GOLD-M").spuId(8L).skuName("月卡")
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .createdAt(LocalDateTime.of(2026, 9, 22, 10, 0)).build();
        when(spuMapper.selectList(any())).thenReturn(List.of(spu));
        ArgumentCaptor<LambdaQueryWrapper<PlatformSkuDO>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(skuMapper.selectPage(any(PageParam.class), wrapperCaptor.capture()))
                .thenReturn(new PageResult<>(List.of(sku), 1L));

        PageResult<PlatformSkuQueryService.SkuListItem> page = service.page(
                new PlatformSkuQueryService.SkuPageQuery(
                        "SPU_IQY", null, null, null, null, null, null, 1, 10));

        assertThat(page.getList()).extracting(PlatformSkuQueryService.SkuListItem::skuCode)
                .containsExactly("IQY-GOLD-M");
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("sku_code");
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("IN");
    }

    @Test
    void projectsCategoryNameAndLowestEffectiveSupplyPrice() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(8L).spuCode("SPU_IQY").spuName("爱奇艺黄金会员")
                .primaryCategoryId(3L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSkuDO sku = PlatformSkuDO.builder()
                .id(11L).skuCode("IQY-GOLD-M").spuId(8L).skuName("月卡")
                .suggestedSalePrice(3_000L)
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .createdAt(LocalDateTime.of(2026, 9, 22, 10, 0)).build();
        when(spuMapper.selectList(any())).thenReturn(List.of(spu));
        when(skuMapper.selectPage(any(PageParam.class), any()))
                .thenReturn(new PageResult<>(List.of(sku), 1L));
        when(categoryQueryService.resolveCategoryNamesByIds(any())).thenReturn(Map.of(3L, "权益商品"));
        when(offerMapper.selectList(any())).thenReturn(List.of(
                SupplierOfferDO.builder().id(1L).platformSkuId(11L).supplierId(4L).currentVersionId(21L)
                        .status(OfferStatus.PUBLISHED.getCode()).build(),
                SupplierOfferDO.builder().id(2L).platformSkuId(11L).supplierId(5L).currentVersionId(22L)
                        .status(OfferStatus.PUBLISHED.getCode()).build()));
        when(offerVersionMapper.selectList(any())).thenReturn(List.of(
                SupplierOfferVersionDO.builder().id(21L).supplierSettlementUnitPrice(1_800L)
                        .validFrom(LocalDateTime.of(2026, 9, 1, 0, 0)).build(),
                SupplierOfferVersionDO.builder().id(22L).supplierSettlementUnitPrice(2_200L)
                        .validFrom(LocalDateTime.of(2026, 9, 1, 0, 0))
                        .validTo(LocalDateTime.of(2026, 9, 1, 0, 0)).build()));
        when(supplierMapper.selectList(any())).thenReturn(List.of(
                SupplierDO.builder().id(4L).status(SupplierStatus.ACTIVE.getCode()).build(),
                SupplierDO.builder().id(5L).status(SupplierStatus.ACTIVE.getCode()).build()));

        PlatformSkuQueryService.SkuListItem row = service.page(
                new PlatformSkuQueryService.SkuPageQuery(
                        null, null, null, null, null, null, null, 1, 10))
                .getList().getFirst();

        assertThat(row.categoryName()).isEqualTo("权益商品");
        assertThat(row.marketPriceCents()).isEqualTo(3_000L);
        assertThat(row.supplyPriceCents()).isEqualTo(1_800L);
    }

    @Test
    void projectsFixedIdentitySupplyPriceWhenOfferHasNoVersion() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(8L).spuCode("SPU_IQY").spuName("爱奇艺黄金会员")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSkuDO sku = PlatformSkuDO.builder()
                .id(11L).skuCode("IQY-GOLD-M").spuId(8L).skuName("月卡")
                .suggestedSalePrice(9_800L)
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .createdAt(LocalDateTime.of(2026, 9, 22, 10, 0)).build();
        when(spuMapper.selectList(any())).thenReturn(List.of(spu));
        when(skuMapper.selectPage(any(PageParam.class), any()))
                .thenReturn(new PageResult<>(List.of(sku), 1L));
        when(offerMapper.selectList(any())).thenReturn(List.of(
                SupplierOfferDO.builder().id(1L).platformSkuId(11L).supplierId(4L)
                        .status(OfferStatus.PUBLISHED.getCode())
                        .priceMode("FIXED").draftUnitPrice(20_000L).build()));
        when(supplierMapper.selectList(any())).thenReturn(List.of(
                SupplierDO.builder().id(4L).status(SupplierStatus.ACTIVE.getCode()).build()));

        PlatformSkuQueryService.SkuListItem row = service.page(
                new PlatformSkuQueryService.SkuPageQuery(
                        null, null, null, null, null, null, null, 1, 10))
                .getList().getFirst();

        assertThat(row.supplyPriceCents()).isEqualTo(20_000L);
    }
}
