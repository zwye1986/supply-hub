package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService.ProductManagementPageQuery;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService.ProductManagementSpuItem;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BrandDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BrandMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.UnshelveMode;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierEligibilityTestFixture;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_CODE_REQUIRED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端商品列表聚合查询单测。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#419-管理端商品列表聚合查询get-supply-chainproductsmanagement-page}。
 */
class ProductManagementPageQueryTest {

    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final BrandMapper brandMapper = mock(BrandMapper.class);
    private final ProductCategoryMapper categoryMapper = mock(ProductCategoryMapper.class);
    private final ProductTypeMapper typeMapper = mock(ProductTypeMapper.class);
    private final SupplierOfferMapper offerMapper = mock(SupplierOfferMapper.class);
    private final SupplierOfferVersionMapper offerVersionMapper = mock(SupplierOfferVersionMapper.class);
    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private final ProductTypeValidationService typeValidationService = mock(ProductTypeValidationService.class);
    private final ProductSellabilityService sellabilityService = mock(ProductSellabilityService.class);
    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerProductMapper buyerProductMapper = mock(BuyerProductMapper.class);
    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private final com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper scopeMapper =
            mock(com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper.class);
    private final ProductCategoryCoverageService categoryCoverageService = mock(ProductCategoryCoverageService.class);

    private final ProductCatalogQueryService service = new ProductCatalogQueryService(
            spuMapper, skuMapper, brandMapper, categoryMapper, typeMapper,
            offerMapper, offerVersionMapper, supplierMapper,
            typeValidationService, sellabilityService, buyerMapper, buyerProductMapper, databaseClock,
            SupplierEligibilityTestFixture.create(supplierMapper, offerMapper, offerVersionMapper, skuMapper, spuMapper),
            scopeMapper, categoryCoverageService);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 22, 12, 0);

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformSpuDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformSkuDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SupplierOfferDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ProductCategoryDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), BuyerProductDO.class);
        when(databaseClock.now()).thenReturn(NOW);
        when(categoryCoverageService.loadResolver()).thenReturn(new ProductCategoryCoverageService.CoverageResolver(List.of()));
        // 分销商选品与显式供应商筛选的用例沿用已有正常供应商合作关系；普通列表不依赖此集合。
        when(supplierMapper.selectActiveIds()).thenReturn(List.of(11L));
        when(offerMapper.selectLiveSkuIdsBySupplierIds(any()))
                .thenReturn(List.of(2001L, 2002L, 2003L, 8888L));
        when(skuMapper.selectBatchIds(any(Collection.class))).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return ids.stream()
                    .map(id -> PlatformSkuDO.builder().id(id).spuId(1001L).build())
                    .toList();
        });
    }

    @Test
    void managementPageAggregatesMultiSkuWithTaxAndSuppliers() {
        LocalDateTime now = LocalDateTime.now();

        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(1001L)
                .spuCode("SPU_TENCENT")
                .spuName("腾讯视频VIP会员")
                .coverUrl("https://example.com/tencent.png")
                .brandId(10L)
                .primaryCategoryId(20L)
                .productType("PT_CARD")
                .status(CatalogEntityStatus.INACTIVE.getCode())
                .unshelveMode(UnshelveMode.SYSTEM_UNSHELVE.getCode())
                .createdAt(now.minusDays(1))
                .updatedAt(now)
                .build();

        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(spu), 1L));

        when(brandMapper.selectBatchIds(any())).thenReturn(List.of(
                BrandDO.builder().id(10L).brandName("腾讯视频").build()));
        when(categoryMapper.selectBatchIds(any())).thenReturn(List.of(
                ProductCategoryDO.builder().id(20L).categoryName("虚拟产品/影视会员").build()));
        when(typeMapper.selectList(any())).thenReturn(List.of(
                ProductTypeDO.builder().typeCode("PT_CARD").typeName("虚拟卡密").build()));

        PlatformSkuDO skuMonth = PlatformSkuDO.builder()
                .id(2001L)
                .spuId(1001L)
                .skuCode("SKU_MONTH")
                .skuName("月卡")
                .marketPrice(2000L)
                .suggestedSalePrice(2014L)
                .taxRate(new BigDecimal("0.0600"))
                .taxIncluded(true)
                .fulfillmentType("CARD_CODE_SECRET")
                .skuInventory(1000L)
                .status(CatalogEntityStatus.INACTIVE.getCode())
                .build();

        PlatformSkuDO skuSeason = PlatformSkuDO.builder()
                .id(2002L)
                .spuId(1001L)
                .skuCode("SKU_SEASON")
                .skuName("季卡")
                .marketPrice(4800L)
                .suggestedSalePrice(5406L)
                .taxIncluded(false)
                .taxRate(null) // 不含税
                .fulfillmentType("CARD_CODE_SECRET")
                .status(CatalogEntityStatus.INACTIVE.getCode())
                .build();

        when(skuMapper.selectList(any())).thenReturn(List.of(skuMonth, skuSeason));

        SupplierOfferDO offer1 = SupplierOfferDO.builder()
                .id(3001L)
                .platformSkuId(2001L)
                .supplierId(4001L)
                .currentVersionId(5001L)
                .status((short) 10)
                .build();

        SupplierOfferDO offer2 = SupplierOfferDO.builder()
                .id(3002L)
                .platformSkuId(2001L)
                .supplierId(4002L)
                .currentVersionId(5002L)
                .status((short) 10)
                .build();

        SupplierOfferDO offer3 = SupplierOfferDO.builder()
                .id(3003L)
                .platformSkuId(2002L)
                .supplierId(4001L)
                .currentVersionId(5003L)
                .status((short) 10)
                .build();

        when(offerMapper.selectList(any())).thenReturn(List.of(offer1, offer2, offer3));

        when(offerVersionMapper.selectBatchIds(any())).thenReturn(List.of(
                SupplierOfferVersionDO.builder().id(5001L).supplierSettlementUnitPrice(2014L)
                        .validFrom(NOW.minusDays(1)).build(),
                SupplierOfferVersionDO.builder().id(5002L).supplierSettlementUnitPrice(2100L)
                        .validFrom(NOW.minusDays(1)).build(),
                SupplierOfferVersionDO.builder().id(5003L).supplierSettlementUnitPrice(5000L)
                        .validFrom(NOW.minusDays(1)).build()));

        when(supplierMapper.selectBatchIds(any())).thenReturn(List.of(
                SupplierDO.builder().id(4001L).supplierCode("SUP_001").supplierName("腾讯会员供应商")
                        .status(SupplierStatus.ACTIVE.getCode()).build(),
                SupplierDO.builder().id(4002L).supplierCode("SUP_002").supplierName("南京腾飞有限责任公司")
                        .status(SupplierStatus.ACTIVE.getCode()).build()));

        ProductManagementPageQuery query = new ProductManagementPageQuery(
                null, "腾讯", null, null, null, null, null, null,
                null, null, null, null, null, null, null, 1, 20, null, null);

        PageResult<ProductManagementSpuItem> result = service.managementPage(query);

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getList()).hasSize(1);

        ProductManagementSpuItem spuItem = result.getList().get(0);
        assertThat(spuItem.spuCode()).isEqualTo("SPU_TENCENT");
        assertThat(spuItem.spuName()).isEqualTo("腾讯视频VIP会员");
        assertThat(spuItem.brandName()).isEqualTo("腾讯视频");
        assertThat(spuItem.primaryCategoryName()).isEqualTo("虚拟产品/影视会员");
        assertThat(spuItem.productTypeName()).isEqualTo("虚拟卡密");
        assertThat(spuItem.status()).isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
        assertThat(spuItem.unshelveMode()).isEqualTo("SYSTEM_UNSHELVE");
        assertThat(spuItem.unshelveModeDisplay()).isEqualTo("系统下架");

        assertThat(spuItem.skus()).hasSize(2);

        // 月卡（含 6% 税）
        var sku1 = spuItem.skus().get(0);
        assertThat(sku1.skuCode()).isEqualTo("SKU_MONTH");
        assertThat(sku1.skuName()).isEqualTo("月卡");
        assertThat(sku1.marketPrice()).isEqualTo(2000L);
        assertThat(sku1.supplyPrice()).isNull();
        assertThat(sku1.supplyPriceExclusive()).isNull();
        assertThat(sku1.taxRateDisplay()).isEqualTo("6%");
        assertThat(sku1.taxAmount()).isNull();
        assertThat(sku1.suppliers()).hasSize(2);
        assertThat(sku1.suppliers().get(0).supplierName()).isEqualTo("腾讯会员供应商");
        assertThat(sku1.fulfillmentTypeDisplay()).isEqualTo("卡号卡密");
        assertThat(sku1.totalInventory()).isEqualTo(1000L);
        assertThat(sku1.skuInventory()).isEqualTo(1000L);

        // 季卡（不含税）
        var sku2 = spuItem.skus().get(1);
        assertThat(sku2.skuCode()).isEqualTo("SKU_SEASON");
        assertThat(sku2.supplyPrice()).isNull();
        assertThat(sku2.supplyPriceExclusive()).isNull();
        assertThat(sku2.taxRateDisplay()).isEqualTo("不含税");
        assertThat(sku2.taxAmount()).isNull();
        assertThat(sku2.totalInventory()).isEqualTo(0L);
        assertThat(sku2.skuInventory()).isEqualTo(0L);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void productStatusFiltersSkuInsteadOfSpuAndFiltersReturnedSkuRows() {
        PlatformSpuDO inactiveSpu = PlatformSpuDO.builder()
                .id(1001L)
                .spuCode("SPU_INACTIVE")
                .spuName("SPU自身已下架")
                .brandId(10L)
                .primaryCategoryId(20L)
                .productType("PT_CARD")
                .status(CatalogEntityStatus.INACTIVE.getCode())
                .updatedAt(LocalDateTime.now())
                .build();
        PlatformSkuDO activeSku = PlatformSkuDO.builder()
                .id(2001L)
                .spuId(1001L)
                .skuCode("SKU_ACTIVE")
                .skuName("上架规格")
                .fulfillmentType("CARD_CODE_SECRET")
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .build();

        when(skuMapper.selectList(any())).thenReturn(List.of(activeSku));
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(inactiveSpu), 1L));
        when(brandMapper.selectBatchIds(any())).thenReturn(List.of(
                BrandDO.builder().id(10L).brandName("测试品牌").build()));
        when(categoryMapper.selectBatchIds(any())).thenReturn(List.of(
                ProductCategoryDO.builder().id(20L).categoryName("测试分类").build()));
        when(typeMapper.selectList(any())).thenReturn(List.of(
                ProductTypeDO.builder().typeCode("PT_CARD").typeName("虚拟卡密").build()));
        when(offerMapper.selectList(any())).thenReturn(List.of());

        ProductManagementPageQuery query = new ProductManagementPageQuery(
                null, null, null, null, null, CatalogEntityStatus.ACTIVE.getCode(), null,
                "CARD_CODE_SECRET", null, null, null, null, null, null, null, 1, 20, null, null);

        PageResult<ProductManagementSpuItem> result = service.managementPage(query);

        assertThat(result.getList()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
            assertThat(item.skus()).extracting(ProductCatalogQueryService.ProductManagementSkuItem::skuCode)
                    .containsExactly("SKU_ACTIVE");
        });

        ArgumentCaptor<LambdaQueryWrapper<PlatformSkuDO>> skuQueryCaptor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(skuMapper, times(2)).selectList(skuQueryCaptor.capture());
        assertThat(skuQueryCaptor.getAllValues()).allSatisfy(wrapper -> {
            assertThat(wrapper.getSqlSegment()).contains("status", "fulfillment_type");
            assertThat(wrapper.getParamNameValuePairs().values())
                    .contains(CatalogEntityStatus.ACTIVE.getCode(), "CARD_CODE_SECRET");
        });

        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> spuQueryCaptor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(PageParam.class), spuQueryCaptor.capture());
        spuQueryCaptor.getValue().getSqlSegment();
        assertThat(spuQueryCaptor.getValue().getParamNameValuePairs().values())
                .doesNotContain(CatalogEntityStatus.ACTIVE.getCode());
    }

    @Test
    void managementPageHandlesNullProductTypeAndCategoriesSafely() {
        LocalDateTime now = LocalDateTime.now();

        PlatformSpuDO spuWithNulls = PlatformSpuDO.builder()
                .id(9999L)
                .spuCode("SPU_HISTORICAL")
                .spuName("历史存量商品")
                .coverUrl("https://example.com/hist.png")
                .brandId(null)
                .primaryCategoryId(null)
                .productType(null)
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .createdAt(now.minusDays(10))
                .updatedAt(now)
                .build();

        PlatformSkuDO sku = PlatformSkuDO.builder()
                .id(8888L)
                .spuId(9999L)
                .skuCode("SKU_HISTORICAL")
                .skuName("存量规格")
                .fulfillmentType("DIRECT_RECHARGE")
                .marketPrice(0L)
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .build();

        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(spuWithNulls), 1L));
        when(skuMapper.selectList(any())).thenReturn(List.of(sku));
        when(offerMapper.selectList(any())).thenReturn(List.of());

        ProductManagementPageQuery query = new ProductManagementPageQuery(
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, 1, 20, null, null);

        PageResult<ProductManagementSpuItem> result = service.managementPage(query);

        assertThat(result.getList()).singleElement().satisfies(item -> {
            assertThat(item.spuCode()).isEqualTo("SPU_HISTORICAL");
            assertThat(item.productType()).isNull();
            assertThat(item.productTypeName()).isNull();
            assertThat(item.primaryCategoryName()).isNull();
            assertThat(item.brandName()).isNull();
            assertThat(item.skus()).singleElement().satisfies(skuItem -> {
                assertThat(skuItem.skuCode()).isEqualTo("SKU_HISTORICAL");
                assertThat(skuItem.fulfillmentType()).isEqualTo("DIRECT_RECHARGE");
                assertThat(skuItem.fulfillmentTypeDisplay()).isEqualTo("虚拟直充");
            });
        });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managementPageFiltersBySingleProductType() {
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(), 0L));

        service.managementPage(managementQuery(List.of("EQUITY_PRODUCT")));

        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(PageParam.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("IN");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains("EQUITY_PRODUCT");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managementPageFiltersByMultipleProductTypesWithInClause() {
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(), 0L));

        service.managementPage(managementQuery(List.of("EQUITY_PRODUCT", "JD")));

        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(PageParam.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("IN");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains("EQUITY_PRODUCT", "JD");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managementPageSplitsCommaSeparatedProductTypes() {
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(), 0L));

        service.managementPage(managementQuery(List.of("EQUITY_PRODUCT, JD")));

        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(PageParam.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("IN");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains("EQUITY_PRODUCT", "JD");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managementPageSkipsBlankProductTypes() {
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(), 0L));

        service.managementPage(managementQuery(List.of("  ", "")));

        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(PageParam.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).doesNotContain("product_type");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managementPageExplicitProductTypesOverrideTab() {
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(), 0L));

        service.managementPage(new ProductManagementPageQuery(
                "JD", null, List.of("EQUITY_PRODUCT"), null, null, null, null, null,
                null, null, null, null, null, null, null, 1, 20, null, null));

        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(PageParam.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("IN");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains("EQUITY_PRODUCT");
        assertThat(captor.getValue().getParamNameValuePairs().values()).doesNotContain("JD");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managementPageFallsBackToTabProductTypeWhenQueryTypesMissing() {
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(), 0L));

        service.managementPage(new ProductManagementPageQuery(
                "JD", null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, 1, 20, null, null));

        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(PageParam.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("IN");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains("JD");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managementPageFiltersBySingleCategoryId() {
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(), 0L));

        service.managementPage(managementQuery(null, List.of("10")));

        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(PageParam.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("IN");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(10L);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managementPageFiltersByMultipleCategoryIdsWithInClause() {
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(), 0L));

        service.managementPage(managementQuery(null, List.of("10", "20")));

        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(PageParam.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("IN");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(10L, 20L);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managementPageSplitsCommaSeparatedCategoryIds() {
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(), 0L));

        service.managementPage(managementQuery(null, List.of("10, 20")));

        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(PageParam.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("IN");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(10L, 20L);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managementPageSkipsBlankCategoryIds() {
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(), 0L));

        service.managementPage(managementQuery(null, List.of("  ", "")));

        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(PageParam.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).doesNotContain("primary_category_id");
        verify(categoryMapper, never()).selectList(any());
    }

    /**
     * 勾选父级与二级节点时，必须把未归档子孙一并纳入主分类 IN 条件。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d42）。
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managementPageExpandsSelectedCategoryIdsToUnarchivedDescendants() {
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(), 0L));
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                category(1L, null),
                category(2L, 1L),
                category(3L, 2L),
                category(4L, 1L),
                category(8L, 4L)));

        service.managementPage(managementQuery(null, List.of("1,4")));

        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(PageParam.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("IN");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains(1L, 2L, 3L, 4L, 8L);
    }

    /**
     * 归档子孙不得进入展开集；类目查询必须排除 ARCHIVED。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d42）。
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managementPageDoesNotExpandArchivedDescendantCategoryIds() {
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(), 0L));
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                category(1L, null),
                category(4L, 1L)));

        service.managementPage(managementQuery(null, List.of("1")));

        ArgumentCaptor<LambdaQueryWrapper<ProductCategoryDO>> categoryCaptor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(categoryMapper).selectList(categoryCaptor.capture());
        assertThat(categoryCaptor.getValue().getSqlSegment()).contains("status");
        assertThat(categoryCaptor.getValue().getParamNameValuePairs().values())
                .contains(CatalogEntityStatus.ARCHIVED.getCode());

        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> spuCaptor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(PageParam.class), spuCaptor.capture());
        assertThat(spuCaptor.getValue().getSqlSegment()).contains("IN");
        assertThat(spuCaptor.getValue().getParamNameValuePairs().values()).contains(1L, 4L);
        assertThat(spuCaptor.getValue().getParamNameValuePairs().values()).doesNotContain(5L);
    }

    @Test
    void managementPageKeywordMatchesSkuCodeWithoutReturningUnmatchedSibling() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(1001L).spuCode("SPU_IQY").spuName("爱奇艺黄金会员").status(CatalogEntityStatus.ACTIVE.getCode())
                .updatedAt(NOW).build();
        PlatformSkuDO matched = PlatformSkuDO.builder()
                .id(2001L).spuId(1001L).skuCode("IQY-GOLD-Q").skuName("季卡")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSkuDO sibling = PlatformSkuDO.builder()
                .id(2002L).spuId(1001L).skuCode("IQY-GOLD-Y").skuName("年卡")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(spu), 1L));
        when(skuMapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<?> wrapper = invocation.getArgument(0);
            String sql = wrapper.getSqlSegment();
            if (sql.contains("sku_code") || sql.contains("sku_name")) {
                return List.of(matched);
            }
            return List.of(matched, sibling);
        });
        when(offerMapper.selectList(any())).thenReturn(List.of());

        PageResult<ProductManagementSpuItem> result = service.managementPage(new ProductManagementPageQuery(
                null, "IQY-GOLD-Q", null, null, null, null, null, null,
                null, null, null, null, null, null, null, 1, 20, null, null));

        assertThat(result.getList()).singleElement().satisfies(item ->
                assertThat(item.skus()).extracting(ProductCatalogQueryService.ProductManagementSkuItem::skuCode)
                        .containsExactly("IQY-GOLD-Q"));
    }

    @Test
    void managementPageBuyerCodeDropsActiveDistributorSkuAndKeepsSibling() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(1001L).spuCode("SPU_IQY").spuName("爱奇艺黄金会员").status(CatalogEntityStatus.ACTIVE.getCode())
                .updatedAt(NOW).build();
        PlatformSkuDO kept = PlatformSkuDO.builder()
                .id(2001L).spuId(1001L).skuCode("IQY-GOLD-M").skuName("月卡")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSkuDO excluded = PlatformSkuDO.builder()
                .id(2002L).spuId(1001L).skuCode("IQY-GOLD-Q").skuName("季卡")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(buyerMapper.selectByBuyerCode("BYR_A")).thenReturn(BuyerDO.builder().id(5L).buyerCode("BYR_A").build());
        when(buyerProductMapper.selectList(any())).thenReturn(List.of(BuyerProductDO.builder()
                .buyerId(5L).platformSkuId(2002L).status(BuyerProductStatus.ACTIVE.getCode()).build()));
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(spu), 1L));
        when(skuMapper.selectList(any())).thenReturn(List.of(kept, excluded));
        when(offerMapper.selectList(any())).thenReturn(List.of());

        PageResult<ProductManagementSpuItem> result = service.managementPage(new ProductManagementPageQuery(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, 1, 20, "BYR_A", null));

        assertThat(result.getList()).singleElement().satisfies(item ->
                assertThat(item.skus()).extracting(ProductCatalogQueryService.ProductManagementSkuItem::skuCode)
                        .containsExactly("IQY-GOLD-M"));
    }

    /** 没有供货配置时不能把整个商品池暴露为可新增范围。 */
    @Test
    void supplierSelectionWithoutSupplyScopeReturnsEmptyPage() {
        when(supplierMapper.selectByCode("SPL_EMPTY")).thenReturn(SupplierDO.builder().id(13L).build());
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(
                PlatformSpuDO.builder().id(1001L).spuCode("SPU_UNSCOPED")
                        .status(CatalogEntityStatus.ACTIVE.getCode()).updatedAt(NOW).build()), 1L));
        when(skuMapper.selectList(any())).thenReturn(List.of(PlatformSkuDO.builder().id(2001L)
                .spuId(1001L).skuCode("SKU_UNSCOPED").status(CatalogEntityStatus.ACTIVE.getCode()).build()));
        var result = service.managementPage(new ProductManagementPageQuery(
                null, null, null, null, null, null, null, null,
                null, null, null, null, "SPL_EMPTY", null, null, 1, 20, null, true));
        assertThat(result.getTotal()).isZero();
        assertThat(result.getList()).isEmpty();
    }

    @Test
    void managementPageUnknownBuyerCodeIsRejected() {
        when(buyerMapper.selectByBuyerCode("BYR_MISSING")).thenReturn(null);

        assertThatThrownBy(() -> service.managementPage(new ProductManagementPageQuery(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, 1, 20, "BYR_MISSING", null)))
                .hasFieldOrPropertyWithValue("code", BUYER_PROFILE_NOT_FOUND.getCode());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void managementPageExcludeAddedSupplierOffersDropsLiveSkuAndKeepsRetiredSibling() {
        PlatformSpuDO spu = stubSelectionScope();
        PlatformSkuDO kept = PlatformSkuDO.builder()
                .id(2001L).spuId(1001L).skuCode("IQY-GOLD-M").skuName("月卡")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSkuDO live = PlatformSkuDO.builder()
                .id(2002L).spuId(1001L).skuCode("IQY-GOLD-Q").skuName("季卡")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSkuDO retired = PlatformSkuDO.builder()
                .id(2003L).spuId(1001L).skuCode("IQY-GOLD-Y").skuName("年卡")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(supplierMapper.selectByCode("SPL_A")).thenReturn(
                SupplierDO.builder().id(11L).supplierCode("SPL_A").supplierName("供应商甲").build());
        when(offerMapper.selectList(any())).thenReturn(List.of(
                SupplierOfferDO.builder().id(1L).supplierId(11L).platformSkuId(2002L)
                        .status(OfferStatus.PUBLISHED.getCode()).build(),
                SupplierOfferDO.builder().id(2L).supplierId(11L).platformSkuId(2003L)
                        .status(OfferStatus.RETIRED.getCode()).build()));
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(spu), 1L));
        when(skuMapper.selectList(any())).thenReturn(List.of(kept, live, retired));

        PageResult<ProductManagementSpuItem> result = service.managementPage(new ProductManagementPageQuery(
                null, null, null, null, null, null, null, null,
                null, null, null, null, "SPL_A", null, null, 1, 20, null, true));

        assertThat(result.getList()).singleElement().satisfies(item ->
                assertThat(item.skus()).extracting(ProductCatalogQueryService.ProductManagementSkuItem::skuCode)
                        .containsExactly("IQY-GOLD-M", "IQY-GOLD-Y"));
        verify(buyerMapper, never()).selectByBuyerCode(any());
        ArgumentCaptor<LambdaQueryWrapper<SupplierOfferDO>> offerQuery =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(offerMapper, times(3)).selectList(offerQuery.capture());
        assertThat(offerQuery.getAllValues().getFirst().getSqlSegment()).contains("supplier_id", "status");
        assertThat(offerQuery.getAllValues().getFirst().getParamNameValuePairs().values())
                .contains(11L, OfferStatus.RETIRED.getCode());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void supplierSelectionRequiresActiveParentAndSkuEvenWithoutStatusParameter() {
        stubSelectionScope();
        when(supplierMapper.selectByCode("SPL_A")).thenReturn(SupplierDO.builder().id(11L).build());
        PlatformSkuDO sku = PlatformSkuDO.builder().id(2001L).spuId(1001L).skuCode("SKU_A")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(skuMapper.selectList(any())).thenReturn(List.of(sku));
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(), 0L));
        service.managementPage(new ProductManagementPageQuery(null, null, null, null, null, null, null, null,
                null, null, null, null, "SPL_A", null, null, 1, 20, null, true));
        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> parent = ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(spuMapper).selectPage(any(PageParam.class), parent.capture());
        assertThat(parent.getValue().getSqlSegment()).contains("status =");
        assertThat(parent.getValue().getParamNameValuePairs().values()).contains(CatalogEntityStatus.ACTIVE.getCode());
        ArgumentCaptor<LambdaQueryWrapper<PlatformSkuDO>> child = ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(skuMapper).selectList(child.capture());
        assertThat(child.getValue().getSqlSegment()).contains("status =");
        assertThat(child.getValue().getParamNameValuePairs().values()).contains(CatalogEntityStatus.ACTIVE.getCode());
    }

    /** 旧用例显式补齐有效范围；跨品类仍命中，不能靠默认无范围夹具绕过新准入。 */
    private PlatformSpuDO stubSelectionScope() {
        PlatformSpuDO spu = PlatformSpuDO.builder().id(1001L).spuCode("SPU_IQY").spuName("爱奇艺黄金会员")
                .sellableCategory("EQUITY").productType("PT_CARD").productClassification("VIRTUAL_CARD")
                .status(CatalogEntityStatus.ACTIVE.getCode()).updatedAt(NOW).build();
        when(scopeMapper.selectBySupplierId(11L)).thenReturn(List.of(
                com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO.builder()
                        .id(101L).sellableCategory("PHYSICAL").productType("PT_CARD")
                        .productClassification("VIRTUAL_CARD").build()));
        when(spuMapper.selectList(any())).thenReturn(List.of(spu));
        return spu;
    }

    @Test
    void managementPageExcludeAddedSupplierOffersRequiresSupplierCode() {
        assertThatThrownBy(() -> service.managementPage(new ProductManagementPageQuery(
                null, null, null, null, null, null, null, null,
                null, null, null, null, "  ", null, null, 1, 20, null, true)))
                .hasFieldOrPropertyWithValue("code", SUPPLIER_CODE_REQUIRED.getCode());
        verify(supplierMapper, never()).selectByCode(any());
    }

    @Test
    void managementPageExcludeAddedSupplierOffersRejectsUnknownSupplier() {
        when(supplierMapper.selectByCode("SPL_MISSING")).thenReturn(null);

        assertThatThrownBy(() -> service.managementPage(new ProductManagementPageQuery(
                null, null, null, null, null, null, null, null,
                null, null, null, null, "SPL_MISSING", null, null, 1, 20, null, true)))
                .hasFieldOrPropertyWithValue("code", SUPPLIER_NOT_FOUND.getCode());
    }

    @Test
    void managementPageSupplierCodeWithoutExcludeFlagStillKeepsOnlySuppliedSpus() {
        when(supplierMapper.selectOne(org.mockito.ArgumentMatchers.<SFunction<SupplierDO, ?>>any(), eq("SPL_A"))).thenReturn(
                SupplierDO.builder().id(11L).supplierCode("SPL_A")
                        .status(SupplierStatus.ACTIVE.getCode()).build());
        when(offerMapper.selectList(any())).thenReturn(List.of());

        PageResult<ProductManagementSpuItem> result = service.managementPage(new ProductManagementPageQuery(
                null, null, null, null, null, null, null, null,
                null, null, null, null, "SPL_A", null, null, 1, 20, null, null));

        assertThat(result.getList()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(offerMapper).selectList(any());
        verify(spuMapper, never()).selectPage(any(PageParam.class), any());
        verify(buyerMapper, never()).selectByBuyerCode(any());
    }

    @Test
    void managementPageSupplyPriceIgnoresExpiredAndInactiveOffers() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(1001L).spuCode("SPU_IQY").spuName("爱奇艺黄金会员").status(CatalogEntityStatus.ACTIVE.getCode())
                .updatedAt(NOW).build();
        PlatformSkuDO sku = PlatformSkuDO.builder()
                .id(2001L).spuId(1001L).skuCode("IQY-GOLD-M").skuName("月卡")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(spu), 1L));
        when(skuMapper.selectList(any())).thenReturn(List.of(sku));
        when(offerMapper.selectList(any())).thenReturn(List.of(
                SupplierOfferDO.builder().id(1L).platformSkuId(2001L).supplierId(11L)
                        .currentVersionId(101L).status((short) 10).build(),
                SupplierOfferDO.builder().id(2L).platformSkuId(2001L).supplierId(12L)
                        .currentVersionId(102L).status((short) 10).build()));
        when(offerVersionMapper.selectBatchIds(any())).thenReturn(List.of(
                SupplierOfferVersionDO.builder().id(101L).supplierSettlementUnitPrice(1800L)
                        .validFrom(NOW.minusDays(2)).validTo(NOW.plusDays(2)).build(),
                SupplierOfferVersionDO.builder().id(102L).supplierSettlementUnitPrice(900L)
                        .validFrom(NOW.minusDays(10)).validTo(NOW.minusDays(1)).build()));
        when(supplierMapper.selectBatchIds(any())).thenReturn(List.of(
                SupplierDO.builder().id(11L).supplierCode("SUP_A").supplierName("有效供应商")
                        .status(SupplierStatus.ACTIVE.getCode()).build(),
                SupplierDO.builder().id(12L).supplierCode("SUP_B").supplierName("过期供应商")
                        .status(SupplierStatus.SUSPENDED.getCode()).build()));

        SupplierEligibilityTestFixture.seedDependencies(service, List.of(sku), List.of(spu));

        PageResult<ProductManagementSpuItem> result = service.managementPage(new ProductManagementPageQuery(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, 1, 20, null, null));

        assertThat(result.getList().getFirst().skus().getFirst().supplyPrice()).isEqualTo(1800L);
        assertThat(result.getList().getFirst().skus().getFirst().suppliers())
                .extracting(ProductCatalogQueryService.SupplierSimpleInfo::supplierCode)
                .containsExactly("SUP_A");
    }

    @Test
    void managementPageKeepsUnsuppliedSkusWhenNoSupplierIsActive() {
        when(supplierMapper.selectActiveIds()).thenReturn(List.of());
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(1001L).spuCode("SPU_IQY").spuName("爱奇艺会员")
                .status(CatalogEntityStatus.ACTIVE.getCode()).updatedAt(NOW).build();
        PlatformSkuDO month = PlatformSkuDO.builder()
                .id(2001L).spuId(1001L).skuCode("SKU_MONTH").skuName("月卡")
                .status(CatalogEntityStatus.ACTIVE.getCode()).marketPrice(9800L).build();
        PlatformSkuDO year = PlatformSkuDO.builder()
                .id(2003L).spuId(1001L).skuCode("SKU_YEAR").skuName("年卡")
                .status(CatalogEntityStatus.ACTIVE.getCode()).marketPrice(59900L).build();
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(spu), 1L));
        when(skuMapper.selectList(any())).thenReturn(List.of(month, year));

        PageResult<ProductManagementSpuItem> result = service.managementPage(managementQuery(null));

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getList()).singleElement().satisfies(item -> {
            assertThat(item.skus()).extracting(ProductCatalogQueryService.ProductManagementSkuItem::skuCode)
                    .containsExactly("SKU_MONTH", "SKU_YEAR");
            assertThat(item.skus()).allSatisfy(sku -> {
                assertThat(sku.suppliers()).isEmpty();
                assertThat(sku.supplyPrice()).isNull();
                assertThat(sku.supplyPriceExclusive()).isNull();
                assertThat(sku.taxAmount()).isNull();
            });
        });
        verify(offerMapper, never()).selectLiveSkuIdsBySupplierIds(any());
    }

    @Test
    void managementPageKeepsSkuAfterItsOnlySupplierOfferWasRetired() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(1001L).spuCode("SPU_IQY").spuName("爱奇艺会员")
                .status(CatalogEntityStatus.ACTIVE.getCode()).updatedAt(NOW).build();
        PlatformSkuDO month = PlatformSkuDO.builder()
                .id(2001L).spuId(1001L).skuCode("SKU_MONTH").skuName("月卡")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSkuDO year = PlatformSkuDO.builder()
                .id(2003L).spuId(1001L).skuCode("SKU_YEAR").skuName("年卡")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(spu), 1L));
        when(skuMapper.selectList(any())).thenReturn(List.of(month, year));
        // Offer 查询已排除 RETIRED，因此只会查到月卡的未退役合作商品。
        when(offerMapper.selectList(any())).thenReturn(List.of(SupplierOfferDO.builder()
                .id(3001L).platformSkuId(2001L).supplierId(11L)
                .status(OfferStatus.PUBLISHED.getCode()).build()));
        when(supplierMapper.selectBatchIds(any())).thenReturn(List.of(SupplierDO.builder()
                .id(11L).supplierCode("SPL_A").supplierName("正常供应商")
                .status(SupplierStatus.ACTIVE.getCode()).build()));

        PageResult<ProductManagementSpuItem> result = service.managementPage(managementQuery(null));

        assertThat(result.getList()).singleElement().satisfies(item -> {
            assertThat(item.skus()).extracting(ProductCatalogQueryService.ProductManagementSkuItem::skuCode)
                    .containsExactly("SKU_MONTH", "SKU_YEAR");
            assertThat(item.skus().getFirst().suppliers()).hasSize(1);
            assertThat(item.skus().get(1).suppliers()).isEmpty();
            assertThat(item.skus().get(1).supplyPrice()).isNull();
        });
    }

    @Test
    void managementPageBuyerSelectionStillRequiresLiveOfferFromActiveSupplier() {
        when(supplierMapper.selectActiveIds()).thenReturn(List.of());
        when(buyerMapper.selectByBuyerCode("BYR_A"))
                .thenReturn(BuyerDO.builder().id(51L).buyerCode("BYR_A").build());

        PageResult<ProductManagementSpuItem> result = service.managementPage(new ProductManagementPageQuery(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, 1, 20, "BYR_A", null));

        assertThat(result.getList()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(spuMapper, never()).selectPage(any(PageParam.class), any());
    }

    @Test
    void managementPageSupplyPriceRangeHasNoMatchesWithoutActiveSupplier() {
        when(supplierMapper.selectActiveIds()).thenReturn(List.of());

        PageResult<ProductManagementSpuItem> result = service.managementPage(new ProductManagementPageQuery(
                null, null, null, null, null, null, null, null,
                null, null, 100L, 500L, null, null, null, 1, 20, null, null));

        assertThat(result.getList()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(spuMapper, never()).selectPage(any(PageParam.class), any());
    }

    @Test
    void managementPageRejectsExplicitNonActiveSupplierFilter() {
        when(supplierMapper.selectOne(org.mockito.ArgumentMatchers.<SFunction<SupplierDO, ?>>any(), eq("SPL_STOP")))
                .thenReturn(SupplierDO.builder().id(12L).supplierCode("SPL_STOP")
                        .status(SupplierStatus.SUSPENDED.getCode()).build());

        PageResult<ProductManagementSpuItem> result = service.managementPage(new ProductManagementPageQuery(
                null, null, null, null, null, null, null, null,
                null, null, null, null, "SPL_STOP", null, null, 1, 20, null, null));

        assertThat(result.getList()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(spuMapper, never()).selectPage(any(PageParam.class), any());
    }

    @Test
    void managementPageHasNoSupplyPriceWhenOfferHasNoVersion() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(1001L).spuCode("SPU_IQY").spuName("爱奇艺黄金会员").status(CatalogEntityStatus.ACTIVE.getCode())
                .updatedAt(NOW).build();
        PlatformSkuDO sku = PlatformSkuDO.builder()
                .id(2001L).spuId(1001L).skuCode("IQY-GOLD-M").skuName("月卡")
                .suggestedSalePrice(9_800L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(spu), 1L));
        when(skuMapper.selectList(any())).thenReturn(List.of(sku));
        when(offerMapper.selectList(any())).thenReturn(List.of(
                SupplierOfferDO.builder().id(1L).platformSkuId(2001L).supplierId(11L)
                        .status((short) 10).priceMode("FIXED").draftUnitPrice(20_000L).build()));
        when(supplierMapper.selectBatchIds(any())).thenReturn(List.of(
                SupplierDO.builder().id(11L).supplierCode("SPL_TEST").supplierName("测试001")
                        .status(SupplierStatus.ACTIVE.getCode()).build()));

        PageResult<ProductManagementSpuItem> result = service.managementPage(new ProductManagementPageQuery(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, 1, 20, null, null));

        assertThat(result.getList().getFirst().skus().getFirst().supplyPrice()).isNull();
        assertThat(result.getList().getFirst().skus().getFirst().suppliers()).hasSize(1);
    }

    @Test
    void managementPageSupplyPriceRangeRejectsOfferWithoutVersion() {
        PlatformSpuDO spu = PlatformSpuDO.builder()
                .id(1001L).spuCode("SPU_IQY").spuName("爱奇艺黄金会员").status(CatalogEntityStatus.ACTIVE.getCode())
                .updatedAt(NOW).build();
        PlatformSkuDO sku = PlatformSkuDO.builder()
                .id(2001L).spuId(1001L).skuCode("IQY-GOLD-M").skuName("月卡")
                .suggestedSalePrice(9_800L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        SupplierOfferDO offer = SupplierOfferDO.builder().id(1L).platformSkuId(2001L).supplierId(11L)
                .status((short) 10).priceMode("FIXED").draftUnitPrice(20_000L).build();
        when(offerMapper.selectList(any())).thenReturn(List.of(offer));
        when(supplierMapper.selectBatchIds(any())).thenReturn(List.of(
                SupplierDO.builder().id(11L).supplierCode("SPL_TEST").supplierName("测试001")
                        .status(SupplierStatus.ACTIVE.getCode()).build()));
        when(skuMapper.selectBatchIds(any())).thenReturn(List.of(sku));
        when(spuMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(spu), 1L));
        when(skuMapper.selectList(any())).thenReturn(List.of(sku));

        PageResult<ProductManagementSpuItem> result = service.managementPage(new ProductManagementPageQuery(
                null, null, null, null, null, null, null, null,
                null, null, 15_000L, 25_000L, null, null, null, 1, 20, null, null));

        assertThat(result.getList()).isEmpty();
        assertThat(result.getTotal()).isZero();
    }

    private static ProductCategoryDO category(Long id, Long parentId) {
        return ProductCategoryDO.builder()
                .id(id)
                .parentId(parentId)
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .build();
    }

    private static ProductManagementPageQuery managementQuery(List<String> productTypes) {
        return managementQuery(productTypes, null);
    }

    private static ProductManagementPageQuery managementQuery(List<String> productTypes, List<String> categoryIds) {
        return new ProductManagementPageQuery(
                null, null, productTypes, categoryIds, null, null, null, null,
                null, null, null, null, null, null, null, 1, 20, null, null);
    }
}
