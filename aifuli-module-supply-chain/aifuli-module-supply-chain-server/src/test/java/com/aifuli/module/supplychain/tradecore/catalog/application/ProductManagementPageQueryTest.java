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
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

    private final ProductCatalogQueryService service = new ProductCatalogQueryService(
            spuMapper, skuMapper, brandMapper, categoryMapper, typeMapper,
            offerMapper, offerVersionMapper, supplierMapper,
            typeValidationService, sellabilityService);

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformSpuDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), PlatformSkuDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SupplierOfferDO.class);
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
                SupplierOfferVersionDO.builder().id(5001L).supplierSettlementUnitPrice(2014L).build(),
                SupplierOfferVersionDO.builder().id(5002L).supplierSettlementUnitPrice(2100L).build(),
                SupplierOfferVersionDO.builder().id(5003L).supplierSettlementUnitPrice(5000L).build()));

        when(supplierMapper.selectBatchIds(any())).thenReturn(List.of(
                SupplierDO.builder().id(4001L).supplierCode("SUP_001").supplierName("腾讯会员供应商").build(),
                SupplierDO.builder().id(4002L).supplierCode("SUP_002").supplierName("南京腾飞有限责任公司").build()));

        ProductManagementPageQuery query = new ProductManagementPageQuery(
                null, "腾讯", null, null, null, null, null, null,
                null, null, null, null, null, null, null, 1, 20);

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
        assertThat(sku1.supplyPrice()).isEqualTo(2014L);
        assertThat(sku1.supplyPriceExclusive()).isEqualTo(1900L);
        assertThat(sku1.taxRateDisplay()).isEqualTo("6%");
        assertThat(sku1.taxAmount()).isEqualTo(114L);
        assertThat(sku1.suppliers()).hasSize(2);
        assertThat(sku1.suppliers().get(0).supplierName()).isEqualTo("腾讯会员供应商");
        assertThat(sku1.fulfillmentTypeDisplay()).isEqualTo("卡号卡密");

        // 季卡（不含税）
        var sku2 = spuItem.skus().get(1);
        assertThat(sku2.skuCode()).isEqualTo("SKU_SEASON");
        assertThat(sku2.supplyPrice()).isEqualTo(5000L);
        assertThat(sku2.supplyPriceExclusive()).isEqualTo(5000L);
        assertThat(sku2.taxRateDisplay()).isEqualTo("不含税");
        assertThat(sku2.taxAmount()).isEqualTo(0L);
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
                "CARD_CODE_SECRET", null, null, null, null, null, null, null, 1, 20);

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
}
