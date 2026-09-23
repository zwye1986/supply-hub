package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.PageParam;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PROFILE_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分销商品列表按平台 SPU 的商品类型收窄后再分页。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
 */
class BuyerProductQueryServiceTest {

    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerProductMapper productMapper = mock(BuyerProductMapper.class);
    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final ProjectListingMapper listingMapper = mock(ProjectListingMapper.class);
    private final BuyerSkuPricingMapper buyerPricingMapper = mock(BuyerSkuPricingMapper.class);
    private final BuyerSkuPricingVersionMapper buyerPricingVersionMapper = mock(BuyerSkuPricingVersionMapper.class);
    private final PlatformSkuPricingMapper platformPricingMapper = mock(PlatformSkuPricingMapper.class);
    private final PlatformSkuPricingVersionMapper platformPricingVersionMapper = mock(PlatformSkuPricingVersionMapper.class);
    private BuyerProductQueryService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, BuyerProductDO.class);
        TableInfoHelper.initTableInfo(assistant, PlatformSpuDO.class);
        service = new BuyerProductQueryService(
                buyerMapper, productMapper, skuMapper, spuMapper,
                mock(PlatformSettlementPriceMapper.class),
                mock(PlatformSettlementPriceVersionMapper.class),
                mock(BuyerProductL2OverrideMapper.class),
                mock(BuyerProductL2OverrideVersionMapper.class),
                buyerPricingMapper,
                buyerPricingVersionMapper,
                platformPricingMapper,
                platformPricingVersionMapper,
                listingMapper,
                mock(ProjectListingVersionMapper.class),
                mock(BuyerProjectMapper.class),
                new SettlementPriceResolver(),
                new ObjectMapper());
        when(buyerMapper.selectByBuyerCode("BYR_A")).thenReturn(BuyerDO.builder().id(9L).buyerCode("BYR_A").build());
    }

    @Test
    void rejectsMissingBuyer() {
        when(buyerMapper.selectByBuyerCode("BYR_MISSING")).thenReturn(null);

        assertThatThrownBy(() -> service.page("BYR_MISSING", List.of("MOVIE"), 1, 20))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_PROFILE_NOT_FOUND.getCode());
    }

    @Test
    void blankProductTypeDoesNotNarrowCandidates() {
        when(productMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(), 0L));

        PageResult<BuyerProductQueryService.BuyerProductListItem> page =
                service.page("BYR_A", List.of("  ", ","), null, null);

        assertThat(page.getTotal()).isEqualTo(0L);
        verify(productMapper, never()).selectList(any());
        verify(skuMapper, never()).selectList(any());
    }

    @Test
    void unknownProductTypeReturnsEmptyPage() {
        stubCatalog(false);

        PageResult<BuyerProductQueryService.BuyerProductListItem> page =
                service.page("BYR_A", List.of("MOVIE"), 1, 20);

        assertThat(page.getList()).isEmpty();
        assertThat(page.getTotal()).isEqualTo(0L);
        verify(productMapper, never()).selectPage(any(PageParam.class), any());
        assertThat(capturedSpuQuery().getParamNameValuePairs().values()).contains("MOVIE");
    }

    @Test
    void productTypeKeepsOnlyMatchingSku() {
        stubCatalog(true);
        BuyerProductDO product = BuyerProductDO.builder()
                .id(31L).buyerProductCode("BPD_1").buyerId(9L).platformSkuId(11L).build();
        when(productMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(product), 1L));
        when(skuMapper.selectById(11L)).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("MOVIE_100").spuId(21L).build());
        when(spuMapper.selectById(21L)).thenReturn(PlatformSpuDO.builder()
                .id(21L).productType("MOVIE").build());
        when(listingMapper.selectList(any())).thenReturn(List.of());

        PageResult<BuyerProductQueryService.BuyerProductListItem> page =
                service.page("BYR_A", List.of("MOVIE", "FOOD"), 1, 20);

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).extracting(BuyerProductQueryService.BuyerProductListItem::productType)
                .containsExactly("MOVIE");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<BuyerProductDO>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(productMapper).selectPage(any(PageParam.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("IN");
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(11L);
        assertThat(capturedSpuQuery().getParamNameValuePairs().values()).contains("MOVIE", "FOOD");
    }

    @Test
    void commaSeparatedProductTypesUseInClause() {
        stubCatalog(false);

        PageResult<BuyerProductQueryService.BuyerProductListItem> page =
                service.page("BYR_A", List.of(" MOVIE, FOOD "), 1, 20);

        assertThat(page.getList()).isEmpty();
        assertThat(capturedSpuQuery().getSqlSegment()).contains("IN");
        assertThat(capturedSpuQuery().getParamNameValuePairs().values()).contains("MOVIE", "FOOD");
    }

    @Test
    void listReturnsSkuMarketPriceAndBuyerEffectiveTaxWithoutTaxAmount() {
        BuyerProductDO product = BuyerProductDO.builder()
                .id(31L).buyerProductCode("BPD_1").buyerId(9L).platformSkuId(11L).build();
        when(productMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(product), 1L));
        when(skuMapper.selectById(11L)).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("SKU_1").spuId(21L).marketPrice(9600L).build());
        when(spuMapper.selectById(21L)).thenReturn(PlatformSpuDO.builder().id(21L).build());
        when(listingMapper.selectList(any())).thenReturn(List.of());
        when(buyerPricingMapper.selectByBuyerProductId(31L)).thenReturn(BuyerSkuPricingDO.builder()
                .id(71L).buyerProductId(31L).currentVersionId(72L).build());
        when(buyerPricingVersionMapper.selectById(72L)).thenReturn(BuyerSkuPricingVersionDO.builder()
                .id(72L).pricingId(71L)
                .taxMetadataJson("{\"taxIncluded\":false,\"taxRateCode\":\"NON_TAXABLE\",\"taxRateLabel\":\"不征税\"}")
                .build());

        BuyerProductQueryService.BuyerProductListItem item =
                service.page("BYR_A", List.of(), 1, 20).getList().getFirst();

        assertThat(item.marketPriceCents()).isEqualTo(9600L);
        assertThat(item.taxIncluded()).isFalse();
        assertThat(item.taxRateCode()).isEqualTo("NON_TAXABLE");
        assertThat(item.taxRateDisplay()).isEqualTo("不征税");
    }

    /**
     * 新增分销商品的行内 pricing 发布到通用定价版本后，列表必须读取该版本的分销价和覆盖规则，
     * 不能继续只读历史 L1/L2 链而展示空价。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#6-管理端接口}。
     */
    @Test
    void listUsesCurrentGenericBuyerPricingForDistributionPriceAndRule() {
        BuyerProductDO product = BuyerProductDO.builder()
                .id(31L).buyerProductCode("BPD_1").buyerId(9L).platformSkuId(11L).build();
        when(productMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(product), 1L));
        when(skuMapper.selectById(11L)).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("SKU_1").spuId(21L).marketPrice(59_900L).build());
        when(spuMapper.selectById(21L)).thenReturn(PlatformSpuDO.builder().id(21L).build());
        when(listingMapper.selectList(any())).thenReturn(List.of());
        when(buyerPricingMapper.selectByBuyerProductId(31L)).thenReturn(BuyerSkuPricingDO.builder()
                .id(71L).buyerProductId(31L).currentVersionId(72L).build());
        when(buyerPricingVersionMapper.selectById(72L)).thenReturn(BuyerSkuPricingVersionDO.builder()
                .id(72L).pricingId(71L).distributionPriceCents(62_900L)
                .overrideJson("{\"distributionInherited\":false,\"distributionRule\":{"
                        + "\"mode\":\"CUSTOM\",\"base\":\"SUGGESTED_SALE_PRICE\","
                        + "\"percentage\":100,\"operator\":\"ADD\",\"operand\":30}}")
                .build());

        BuyerProductQueryService.BuyerProductListItem item =
                service.page("BYR_A", List.of(), 1, 20).getList().getFirst();

        assertThat(item.distPriceCents()).isEqualTo(62_900L);
        assertThat(item.finalPriceCents()).isEqualTo(62_900L);
        assertThat(item.priceSource()).isEqualTo("L2");
        assertThat(item.pricingRule()).isEqualTo("自定义公式");
    }

    @Test
    void inheritedGenericDistributionKeepsPlatformPriceSource() {
        BuyerProductDO product = BuyerProductDO.builder()
                .id(31L).buyerProductCode("BPD_1").buyerId(9L).platformSkuId(11L).build();
        when(productMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(product), 1L));
        when(skuMapper.selectById(11L)).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("SKU_1").spuId(21L).marketPrice(59_900L).build());
        when(spuMapper.selectById(21L)).thenReturn(PlatformSpuDO.builder().id(21L).build());
        when(listingMapper.selectList(any())).thenReturn(List.of());
        when(buyerPricingMapper.selectByBuyerProductId(31L)).thenReturn(BuyerSkuPricingDO.builder()
                .id(71L).buyerProductId(31L).currentVersionId(72L).build());
        when(buyerPricingVersionMapper.selectById(72L)).thenReturn(BuyerSkuPricingVersionDO.builder()
                .id(72L).pricingId(71L).distributionPriceCents(59_900L)
                .overrideJson("{\"distributionInherited\":true}")
                .build());

        BuyerProductQueryService.BuyerProductListItem item =
                service.page("BYR_A", List.of(), 1, 20).getList().getFirst();

        assertThat(item.distPriceCents()).isEqualTo(59_900L);
        assertThat(item.finalPriceCents()).isEqualTo(59_900L);
        assertThat(item.priceSource()).isEqualTo("L1");
        assertThat(item.pricingRule()).isEqualTo("继承商品默认");
    }

    @Test
    void listFallsBackToPlatformTaxForHistoricalBuyerWithoutPricingVersion() {
        BuyerProductDO product = BuyerProductDO.builder()
                .id(31L).buyerProductCode("BPD_1").buyerId(9L).platformSkuId(11L).build();
        when(productMapper.selectPage(any(PageParam.class), any())).thenReturn(new PageResult<>(List.of(product), 1L));
        when(skuMapper.selectById(11L)).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("SKU_1").spuId(21L).marketPrice(9600L).build());
        when(spuMapper.selectById(21L)).thenReturn(PlatformSpuDO.builder().id(21L).build());
        when(listingMapper.selectList(any())).thenReturn(List.of());
        when(platformPricingMapper.selectById(11L)).thenReturn(PlatformSkuPricingDO.builder()
                .platformSkuId(11L).currentVersionId(81L).build());
        when(platformPricingVersionMapper.selectById(81L)).thenReturn(PlatformSkuPricingVersionDO.builder()
                .id(81L).platformSkuId(11L)
                .effectiveConfigJson("{\"distributionRule\":null,\"salesRule\":null,"
                        + "\"serviceFeeRule\":null,\"hideRule\":null,"
                        + "\"taxMetadata\":{\"taxIncluded\":false,\"taxRateCode\":\"NON_TAXABLE\","
                        + "\"taxRateLabel\":\"不征税\"}}")
                .build());

        BuyerProductQueryService.BuyerProductListItem item =
                service.page("BYR_A", List.of(), 1, 20).getList().getFirst();

        assertThat(item.taxIncluded()).isFalse();
        assertThat(item.taxRateCode()).isEqualTo("NON_TAXABLE");
        assertThat(item.taxRateDisplay()).isEqualTo("不征税");
    }

    private void stubCatalog(boolean spuMatches) {
        when(productMapper.selectList(any())).thenReturn(List.of(BuyerProductDO.builder()
                .id(31L).buyerId(9L).platformSkuId(11L).build()));
        when(skuMapper.selectList(any())).thenReturn(List.of(PlatformSkuDO.builder()
                .id(11L).spuId(21L).build()));
        when(spuMapper.selectList(any())).thenReturn(spuMatches
                ? List.of(PlatformSpuDO.builder().id(21L).productType("MOVIE").build())
                : List.of());
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<PlatformSpuDO> capturedSpuQuery() {
        ArgumentCaptor<LambdaQueryWrapper<PlatformSpuDO>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(spuMapper).selectList(captor.capture());
        captor.getValue().getSqlSegment();
        return captor.getValue();
    }
}
