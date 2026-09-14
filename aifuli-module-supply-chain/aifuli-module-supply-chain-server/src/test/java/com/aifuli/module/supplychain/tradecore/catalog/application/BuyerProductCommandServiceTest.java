package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService.ProductLine;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService.L2PricingPreview;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.profile.domain.L2AdjustMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.time.LocalDateTime;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_PRODUCT_DUPLICATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SETTLEMENT_PRICE_MISSING;
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
 * 分销商品关联与手工 L2：重复 SKU、复活、EQUAL_L1 取结算分、FOLLOW_TEMPLATE 清覆盖。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#63-分销商品}。
 */
class BuyerProductCommandServiceTest {

    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerProductMapper productMapper = mock(BuyerProductMapper.class);
    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final PlatformSettlementPriceMapper l1Mapper = mock(PlatformSettlementPriceMapper.class);
    private final PlatformSettlementPriceVersionMapper l1VersionMapper =
            mock(PlatformSettlementPriceVersionMapper.class);
    private final BuyerProductL2OverrideMapper overrideMapper = mock(BuyerProductL2OverrideMapper.class);
    private final BuyerProductL2OverrideVersionMapper overrideVersionMapper =
            mock(BuyerProductL2OverrideVersionMapper.class);
    private final BusinessNoGenerator businessNoGenerator = mock(BusinessNoGenerator.class);
    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private BuyerProductCommandService service;

    @BeforeEach
    void setUp() {
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 4, 11, 0));
        service = new BuyerProductCommandService(
                databaseClock, buyerMapper, productMapper, l1Mapper, l1VersionMapper,
                overrideMapper, overrideVersionMapper, businessNoGenerator,
                new SettlementPriceResolver(), new ProductSellabilityService(skuMapper, spuMapper));
        when(buyerMapper.selectByBuyerCodeForUpdate("BYR_A")).thenReturn(
                BuyerDO.builder().id(5L).buyerCode("BYR_A").build());
        when(skuMapper.selectByCode("MOVIE_100")).thenReturn(PlatformSkuDO.builder()
                .id(11L).spuId(101L).skuCode("MOVIE_100").faceValue(10_000L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(spuMapper.selectById(101L)).thenReturn(PlatformSpuDO.builder()
                .id(101L).status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(productMapper.updateById(any(BuyerProductDO.class))).thenReturn(1);
        when(overrideMapper.updateById(any(BuyerProductL2OverrideDO.class))).thenReturn(1);
        when(overrideMapper.clearCurrentVersion(any(), any(), any())).thenReturn(1);
    }

    @Test
    void addDuplicateSkuFails() {
        when(productMapper.selectByBuyerAndSku(5L, 11L)).thenReturn(BuyerProductDO.builder()
                .id(77L).buyerProductCode("BPD_LIVE").buyerId(5L).platformSkuId(11L)
                .status(BuyerProductStatus.ACTIVE.getCode()).build());

        assertThatThrownBy(() -> service.addProducts("BYR_A", List.of(follow("MOVIE_100")), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_PRODUCT_DUPLICATE.getCode());
        verify(productMapper, never()).insert(any(BuyerProductDO.class));
    }

    @Test
    void retiredSkuCanBeReaddedWithNewCode() {
        when(productMapper.selectByBuyerAndSku(5L, 11L)).thenReturn(BuyerProductDO.builder()
                .id(77L).buyerProductCode("BPD_OLD").buyerId(5L).platformSkuId(11L)
                .status(BuyerProductStatus.REMOVED.getCode()).version(0L).build());
        when(overrideMapper.selectByBuyerProductId(77L)).thenReturn(BuyerProductL2OverrideDO.builder()
                .buyerProductId(77L).currentVersionId(9L).version(0L).build());

        List<String> codes = service.addProducts("BYR_A", List.of(follow("MOVIE_100")), 1L);

        assertThat(codes).containsExactly("BPD_OLD");
        ArgumentCaptor<BuyerProductDO> updated = ArgumentCaptor.forClass(BuyerProductDO.class);
        verify(productMapper).updateById(updated.capture());
        assertThat(updated.getValue().getStatus()).isEqualTo(BuyerProductStatus.ACTIVE.getCode());
        verify(productMapper, never()).insert(any(BuyerProductDO.class));
        verify(overrideMapper).clearCurrentVersion(eq(77L), eq(0L), any());
    }

    @Test
    void customL2WritesOverrideAndFollowTemplateClears() {
        stubLiveProduct();
        stubL1(800L);
        when(overrideMapper.selectByBuyerProductId(77L)).thenReturn(null);
        when(overrideVersionMapper.selectByBuyerProductId(77L)).thenReturn(List.of());
        when(overrideVersionMapper.insert(any(BuyerProductL2OverrideVersionDO.class))).thenAnswer(inv -> {
            BuyerProductL2OverrideVersionDO row = inv.getArgument(0);
            row.setId(501L);
            return 1;
        });

        service.patchL2("BYR_A", "BPD_1", L2AdjustMode.CUSTOM, 110, 0L, null, 1L);

        ArgumentCaptor<BuyerProductL2OverrideVersionDO> version =
                ArgumentCaptor.forClass(BuyerProductL2OverrideVersionDO.class);
        verify(overrideVersionMapper).insert(version.capture());
        assertThat(version.getValue().getAdjustMode()).isEqualTo(L2AdjustMode.CUSTOM.name());
        assertThat(version.getValue().getResolvedUnitPriceCents()).isEqualTo(880L);
        verify(overrideMapper).insert(any(BuyerProductL2OverrideDO.class));

        when(overrideMapper.selectByBuyerProductId(77L)).thenReturn(BuyerProductL2OverrideDO.builder()
                .buyerProductId(77L).currentVersionId(501L).version(0L).build());
        service.patchL2("BYR_A", "BPD_1", L2AdjustMode.FOLLOW_TEMPLATE, null, null, null, 1L);
        verify(overrideMapper).clearCurrentVersion(eq(77L), eq(0L), any());
    }

    @Test
    void equalL1UsesSettlementL1NotFaceValue() {
        stubLiveProduct();
        stubL1(800L);
        when(overrideMapper.selectByBuyerProductId(77L)).thenReturn(null);
        when(overrideVersionMapper.selectByBuyerProductId(77L)).thenReturn(List.of());
        when(overrideVersionMapper.insert(any(BuyerProductL2OverrideVersionDO.class))).thenAnswer(inv -> {
            BuyerProductL2OverrideVersionDO row = inv.getArgument(0);
            row.setId(502L);
            return 1;
        });

        service.patchL2("BYR_A", "BPD_1", L2AdjustMode.EQUAL_L1, null, null, null, 1L);

        ArgumentCaptor<BuyerProductL2OverrideVersionDO> version =
                ArgumentCaptor.forClass(BuyerProductL2OverrideVersionDO.class);
        verify(overrideVersionMapper).insert(version.capture());
        assertThat(version.getValue().getResolvedUnitPriceCents()).isEqualTo(800L);
        assertThat(version.getValue().getAdjustMode()).isEqualTo(L2AdjustMode.EQUAL_L1.name());
    }

    @Test
    void equalL1WithoutL1FailsAndFollowTemplateStillAllowed() {
        stubLiveProduct();
        when(l1Mapper.selectById(11L)).thenReturn(null);
        assertThatThrownBy(() -> service.patchL2("BYR_A", "BPD_1", L2AdjustMode.EQUAL_L1, null, null, null, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PLATFORM_SETTLEMENT_PRICE_MISSING.getCode());
        service.patchL2("BYR_A", "BPD_1", L2AdjustMode.FOLLOW_TEMPLATE, null, null, null, 1L);
    }

    /** 税务元数据即使存在，也不改变 L2 试算结果。 */
    @Test
    void previewL2IgnoresTaxMetadata() {
        when(buyerMapper.selectByBuyerCode("BYR_A")).thenReturn(BuyerDO.builder().id(5L).buyerCode("BYR_A").build());
        when(productMapper.selectByCode("BPD_1")).thenReturn(BuyerProductDO.builder()
                .id(77L).buyerProductCode("BPD_1").buyerId(5L).platformSkuId(11L)
                .status(BuyerProductStatus.ACTIVE.getCode()).build());
        when(skuMapper.selectById(11L)).thenReturn(PlatformSkuDO.builder()
                .id(11L).spuId(101L).skuCode("MOVIE_100").taxIncluded(true)
                .taxRate(new java.math.BigDecimal("0.2500"))
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        stubL1(1_000L);
        when(overrideMapper.selectByBuyerProductId(77L)).thenReturn(null);

        List<L2PricingPreview> result = service.previewL2Batch(
                "BYR_A", List.of("BPD_1"), L2AdjustMode.CUSTOM, 80, 0L);

        assertThat(result).singleElement().satisfies(line -> {
            assertThat(line.currentL2PriceCents()).isEqualTo(1_000L);
            assertThat(line.previewL2PriceCents()).isEqualTo(800L);
        });
    }

    /** 批量 L2 在分销商作用域内逐条发布不可变版本。 */
    @Test
    void batchL2PublishesEveryScopedProduct() {
        stubL1(1_000L);
        when(productMapper.selectByCodeForUpdate("BPD_1")).thenReturn(liveProduct(77L, "BPD_1"));
        when(productMapper.selectByCodeForUpdate("BPD_2")).thenReturn(liveProduct(78L, "BPD_2"));
        when(skuMapper.selectById(11L)).thenReturn(PlatformSkuDO.builder()
                .id(11L).spuId(101L).skuCode("MOVIE_100")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(overrideMapper.selectByBuyerProductId(any())).thenReturn(null);
        when(overrideVersionMapper.selectByBuyerProductId(any())).thenReturn(List.of());
        when(overrideVersionMapper.insert(any(BuyerProductL2OverrideVersionDO.class))).thenReturn(1);

        service.patchL2Batch("BYR_A", List.of("BPD_1", "BPD_2"),
                L2AdjustMode.CUSTOM, 90, 0L, null, 1L);

        ArgumentCaptor<BuyerProductL2OverrideVersionDO> versions =
                ArgumentCaptor.forClass(BuyerProductL2OverrideVersionDO.class);
        verify(overrideVersionMapper, times(2)).insert(versions.capture());
        assertThat(versions.getAllValues()).allSatisfy(version ->
                assertThat(version.getResolvedUnitPriceCents()).isEqualTo(900L));
    }

    private void stubLiveProduct() {
        when(productMapper.selectByCodeForUpdate("BPD_1")).thenReturn(BuyerProductDO.builder()
                .id(77L).buyerProductCode("BPD_1").buyerId(5L).platformSkuId(11L)
                .status(BuyerProductStatus.ACTIVE.getCode()).version(0L).build());
        when(buyerMapper.selectByBuyerCode("BYR_A")).thenReturn(
                BuyerDO.builder().id(5L).buyerCode("BYR_A").build());
        when(skuMapper.selectById(11L)).thenReturn(PlatformSkuDO.builder()
                .id(11L).spuId(101L).skuCode("MOVIE_100").faceValue(10_000L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
    }

    private void stubL1(long cents) {
        when(l1Mapper.selectById(11L)).thenReturn(PlatformSettlementPriceDO.builder()
                .platformSkuId(11L).currentVersionId(31L).build());
        when(l1VersionMapper.selectById(31L)).thenReturn(PlatformSettlementPriceVersionDO.builder()
                .id(31L).platformSkuId(11L).unitPriceCents(cents).build());
    }

    private static BuyerProductDO liveProduct(long id, String code) {
        return BuyerProductDO.builder().id(id).buyerProductCode(code).buyerId(5L).platformSkuId(11L)
                .status(BuyerProductStatus.ACTIVE.getCode()).version(0L).build();
    }

    private static ProductLine follow(String skuCode) {
        return new ProductLine(skuCode, L2AdjustMode.FOLLOW_TEMPLATE, null, null, null);
    }
}
