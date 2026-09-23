package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingCommandService.ListingLine;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerProductL2OverrideVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSettlementPriceVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectListingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerProductL2OverrideVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuPricingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectListingVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProjectSkuPricingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProjectSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.ProjectListingPricingInitializationEvent;
import com.aifuli.module.supplychain.tradecore.profile.domain.L2AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.L3AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.ListingPriceSource;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.time.LocalDateTime;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SETTLEMENT_PRICE_MISSING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_LISTING_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PROJECT_SKU_NOT_IN_BUYER_CATALOG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 项目商品必须已是分销商品；FOLLOW_L2 冻结解析价且不写 L3；改 L2 不回放已发布版本。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
 */
class ProjectListingCommandServiceTest {

    private static final String PROJECT_CODE = "PRJ_1";
    private static final String LISTING_CODE = "PLT_TESTCODE000000000000001";
    private static final long L1_CENTS = 1000L;

    private final BuyerProjectMapper projectMapper = mock(BuyerProjectMapper.class);
    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerProductMapper productMapper = mock(BuyerProductMapper.class);
    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final ProjectListingMapper listingMapper = mock(ProjectListingMapper.class);
    private final ProjectListingVersionMapper listingVersionMapper = mock(ProjectListingVersionMapper.class);
    private final ProjectSkuPricingMapper projectPricingMapper = mock(ProjectSkuPricingMapper.class);
    private final BuyerSkuPricingMapper buyerPricingMapper = mock(BuyerSkuPricingMapper.class);
    private final BuyerSkuPricingVersionMapper buyerPricingVersionMapper = mock(BuyerSkuPricingVersionMapper.class);
    private final PlatformSkuPricingVersionMapper platformPricingVersionMapper = mock(PlatformSkuPricingVersionMapper.class);
    private final PlatformSettlementPriceMapper l1Mapper = mock(PlatformSettlementPriceMapper.class);
    private final PlatformSettlementPriceVersionMapper l1VersionMapper =
            mock(PlatformSettlementPriceVersionMapper.class);
    private final BuyerProductL2OverrideMapper overrideMapper = mock(BuyerProductL2OverrideMapper.class);
    private final BuyerProductL2OverrideVersionMapper overrideVersionMapper =
            mock(BuyerProductL2OverrideVersionMapper.class);
    private final BusinessNoGenerator businessNoGenerator = mock(BusinessNoGenerator.class);
    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private ProjectListingCommandService service;
    private BuyerProductCommandService buyerProducts;
    private ProjectListingDO storedListing;
    private ProjectListingVersionDO version1;

    @BeforeEach
    void setUp() {
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 4, 11, 0));
        SettlementPriceResolver resolver = new SettlementPriceResolver();
        ProjectListingPriceSource priceSource = new ProjectListingPriceSource(
                buyerPricingMapper, buyerPricingVersionMapper, platformPricingVersionMapper,
                l1Mapper, l1VersionMapper, overrideMapper, overrideVersionMapper, new ObjectMapper());
        service = new ProjectListingCommandService(
                databaseClock, eventPublisher, projectMapper, buyerMapper, productMapper, skuMapper,
                listingMapper, listingVersionMapper,
                projectPricingMapper, priceSource,
                businessNoGenerator, resolver);
        buyerProducts = new BuyerProductCommandService(
                databaseClock, eventPublisher, buyerMapper, productMapper, l1Mapper, l1VersionMapper,
                overrideMapper, overrideVersionMapper, buyerPricingMapper, businessNoGenerator, resolver,
                new ProductSellabilityService(skuMapper, spuMapper), null);
        when(projectPricingMapper.selectByListingId(any())).thenReturn(ProjectSkuPricingDO.builder()
                .id(1L).listingId(80L).pricingStatus(PricingStatus.UNPRICED.name()).build());
        when(businessNoGenerator.next(BusinessNoPrefixes.PLT)).thenReturn(LISTING_CODE);
        when(projectMapper.selectByProjectCodeForUpdate(PROJECT_CODE)).thenReturn(BuyerProjectDO.builder()
                .id(9L).projectCode(PROJECT_CODE).buyerId(5L).build());
        when(skuMapper.selectByCode("MOVIE_100")).thenReturn(PlatformSkuDO.builder()
                .id(11L).spuId(101L).skuCode("MOVIE_100").status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(spuMapper.selectById(101L)).thenReturn(PlatformSpuDO.builder()
                .id(101L).status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(listingMapper.insert(any(ProjectListingDO.class))).thenAnswer(inv -> {
            storedListing = inv.getArgument(0);
            storedListing.setId(80L);
            return 1;
        });
        when(listingVersionMapper.insert(any(ProjectListingVersionDO.class))).thenAnswer(inv -> {
            ProjectListingVersionDO row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(row.getVersionNo() != null && row.getVersionNo() == 2 ? 802L : 801L);
            }
            if (row.getVersionNo() != null && row.getVersionNo() == 1) {
                version1 = row;
            }
            return 1;
        });
        when(listingMapper.updateById(any(ProjectListingDO.class))).thenAnswer(inv -> {
            storedListing = inv.getArgument(0);
            return 1;
        });
        when(listingVersionMapper.selectByListingId(80L)).thenReturn(List.of());
    }

    @Test
    void rejectsSkuWithoutLiveBuyerProduct() {
        when(productMapper.selectByBuyerAndSku(5L, 11L)).thenReturn(null);
        assertThatThrownBy(() -> service.addProducts(PROJECT_CODE, List.of(follow("MOVIE_100")), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PROJECT_SKU_NOT_IN_BUYER_CATALOG.getCode());

        when(productMapper.selectByBuyerAndSku(5L, 11L)).thenReturn(BuyerProductDO.builder()
                .id(77L).buyerId(5L).platformSkuId(11L)
                .status(BuyerProductStatus.REMOVED.getCode()).build());
        assertThatThrownBy(() -> service.addProducts(PROJECT_CODE, List.of(follow("MOVIE_100")), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PROJECT_SKU_NOT_IN_BUYER_CATALOG.getCode());
    }

    @Test
    void defaultFollowL2FreezesResolvedPriceWithoutWritingL3() {
        stubLiveBuyerProduct();
        stubL1(L1_CENTS);
        when(overrideMapper.selectByBuyerProductId(77L)).thenReturn(null);

        List<String> codes = service.addProducts(PROJECT_CODE, List.of(follow("MOVIE_100")), 1L);

        assertThat(codes).containsExactly(LISTING_CODE);
        assertThat(storedListing.getStatus()).isEqualTo(ProjectListingStatus.AVAILABLE.getCode());
        assertThat(storedListing.getCurrentVersionId()).isEqualTo(801L);
        ArgumentCaptor<ProjectListingVersionDO> version =
                ArgumentCaptor.forClass(ProjectListingVersionDO.class);
        verify(listingVersionMapper).insert(version.capture());
        ProjectListingVersionDO frozen = version.getValue();
        assertThat(frozen.getVersionNo()).isEqualTo(1);
        assertThat(frozen.getBuyerSettlementUnitPrice()).isEqualTo(L1_CENTS);
        assertThat(frozen.getSettlementPriceLevel()).isEqualTo(PriceSource.L1.name());
        assertThat(frozen.getPriceSource()).isEqualTo(ListingPriceSource.INHERITED.name());
        assertThat(frozen.getL3AdjustMode()).isEqualTo(L3AdjustMode.FOLLOW_L2.name());
        assertThat(frozen.getCurrency()).isEqualTo(Money.CURRENCY_CNY);
        verify(listingVersionMapper, never()).updateById(any(ProjectListingVersionDO.class));
    }

    /** 新 SKU 无旧 L1 时仍按分销商通用版本冻结，避免列表有价但项目发布失败。 */
    @Test
    void defaultFollowL2UsesCurrentGenericBuyerPriceWithoutLegacyL1() {
        stubLiveBuyerProduct();
        stubGenericPricing(9_000L, 9_800L, false);

        service.addProducts(PROJECT_CODE, List.of(follow("MOVIE_100")), 1L);

        ArgumentCaptor<ProjectListingVersionDO> version = ArgumentCaptor.forClass(ProjectListingVersionDO.class);
        verify(listingVersionMapper).insert(version.capture());
        assertThat(version.getValue().getBuyerSettlementUnitPrice()).isEqualTo(9_800L);
        assertThat(version.getValue().getSettlementPriceLevel()).isEqualTo(PriceSource.L2.name());
        verify(l1Mapper, never()).selectById(any());
    }

    @Test
    void inheritedGenericDistributionKeepsPlatformPriceSource() {
        stubLiveBuyerProduct();
        stubGenericPricing(9_000L, 9_000L, true);

        service.addProducts(PROJECT_CODE, List.of(follow("MOVIE_100")), 1L);

        ArgumentCaptor<ProjectListingVersionDO> version = ArgumentCaptor.forClass(ProjectListingVersionDO.class);
        verify(listingVersionMapper).insert(version.capture());
        assertThat(version.getValue().getSettlementPriceLevel()).isEqualTo(PriceSource.L1.name());
    }

    /** EQUAL_L1 历史枚举在通用链使用分销商版本引用的平台默认分销价。 */
    @Test
    void equalL1UsesReferencedGenericPlatformVersion() {
        stubLiveBuyerProduct();
        stubGenericPricing(9_000L, 9_800L, false);

        service.addProducts(PROJECT_CODE,
                List.of(new ListingLine("MOVIE_100", L3AdjustMode.EQUAL_L1, null, null)), 1L);

        ArgumentCaptor<ProjectListingVersionDO> version = ArgumentCaptor.forClass(ProjectListingVersionDO.class);
        verify(listingVersionMapper).insert(version.capture());
        assertThat(version.getValue().getBuyerSettlementUnitPrice()).isEqualTo(9_000L);
        assertThat(version.getValue().getSettlementPriceLevel()).isEqualTo(PriceSource.L3.name());
    }

    /** 通用版本存在但引用的平台版本缺失时不能偷偷回退旧 L1。 */
    @Test
    void brokenGenericReferenceDoesNotFallBackToLegacyL1() {
        stubLiveBuyerProduct();
        stubL1(L1_CENTS);
        when(buyerPricingMapper.selectByBuyerProductId(77L)).thenReturn(BuyerSkuPricingDO.builder()
                .buyerProductId(77L).currentVersionId(701L).build());
        when(buyerPricingVersionMapper.selectById(701L)).thenReturn(BuyerSkuPricingVersionDO.builder()
                .id(701L).platformPricingVersionId(702L).distributionPriceCents(9_800L).build());

        assertThatThrownBy(() -> service.addProducts(PROJECT_CODE, List.of(follow("MOVIE_100")), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PLATFORM_SETTLEMENT_PRICE_MISSING.getCode());
        verify(l1Mapper, never()).selectById(any());
    }

    @Test
    void addProductsWritesUnpricedIdentityWhenMissing() {
        when(projectPricingMapper.selectByListingId(any())).thenReturn(null);
        when(projectPricingMapper.insert(any(ProjectSkuPricingDO.class))).thenReturn(1);
        stubLiveBuyerProduct();
        stubL1(L1_CENTS);
        when(overrideMapper.selectByBuyerProductId(77L)).thenReturn(null);

        service.addProducts(PROJECT_CODE, List.of(follow("MOVIE_100")), 1L);

        ArgumentCaptor<ProjectSkuPricingDO> identity = ArgumentCaptor.forClass(ProjectSkuPricingDO.class);
        verify(projectPricingMapper).insert(identity.capture());
        assertThat(identity.getValue().getListingId()).isEqualTo(80L);
        assertThat(identity.getValue().getPricingStatus()).isEqualTo(PricingStatus.UNPRICED.name());
        assertThat(identity.getValue().getCurrentVersionId()).isNull();
        ArgumentCaptor<ProjectListingPricingInitializationEvent> event =
                ArgumentCaptor.forClass(ProjectListingPricingInitializationEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().listingId()).isEqualTo(80L);
        assertThat(event.getValue().operatorId()).isEqualTo(1L);
    }

    @Test
    void customL3FreezesOverrideAndDoesNotUpdatePreviousVersion() {
        stubLiveBuyerProduct();
        stubL1(L1_CENTS);
        when(overrideMapper.selectByBuyerProductId(77L)).thenReturn(null);
        service.addProducts(PROJECT_CODE, List.of(follow("MOVIE_100")), 1L);
        long frozenV1 = version1.getBuyerSettlementUnitPrice();
        storedListing.setListingCode(LISTING_CODE);
        storedListing.setCurrentVersionId(801L);
        storedListing.setStatus(ProjectListingStatus.AVAILABLE.getCode());
        storedListing.setProjectId(9L);
        storedListing.setBuyerProductId(77L);
        storedListing.setVersion(0L);
        when(listingMapper.selectByCodeForUpdate(LISTING_CODE)).thenReturn(storedListing);
        when(listingVersionMapper.selectByListingId(80L)).thenReturn(List.of(version1));
        when(listingVersionMapper.selectById(801L)).thenReturn(version1);

        service.patchL3(PROJECT_CODE, LISTING_CODE, L3AdjustMode.CUSTOM, 90, 0L, 1L);

        ArgumentCaptor<ProjectListingVersionDO> versions =
                ArgumentCaptor.forClass(ProjectListingVersionDO.class);
        verify(listingVersionMapper, org.mockito.Mockito.times(2)).insert(versions.capture());
        ProjectListingVersionDO v2 = versions.getAllValues().get(1);
        assertThat(v2.getVersionNo()).isEqualTo(2);
        assertThat(v2.getBuyerSettlementUnitPrice()).isEqualTo(900L);
        assertThat(v2.getSettlementPriceLevel()).isEqualTo(PriceSource.L3.name());
        assertThat(v2.getPriceSource()).isEqualTo(ListingPriceSource.PROJECT_OVERRIDE.name());
        assertThat(storedListing.getCurrentVersionId()).isEqualTo(802L);
        assertThat(version1.getBuyerSettlementUnitPrice()).isEqualTo(frozenV1);
        verify(listingVersionMapper, never()).updateById(any(ProjectListingVersionDO.class));
    }

    @Test
    void changingL2OverrideDoesNotUpdateFrozenListingVersion() {
        stubLiveBuyerProduct();
        stubL1(L1_CENTS);
        when(overrideMapper.selectByBuyerProductId(77L)).thenReturn(null);
        service.addProducts(PROJECT_CODE, List.of(follow("MOVIE_100")), 1L);
        when(productMapper.selectByCodeForUpdate("BPD_1")).thenReturn(liveProduct());
        when(buyerMapper.selectByBuyerCode("BYR_A")).thenReturn(BuyerDO.builder().id(5L).buyerCode("BYR_A").build());
        when(skuMapper.selectById(11L)).thenReturn(PlatformSkuDO.builder()
                .id(11L).spuId(101L).skuCode("MOVIE_100").status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(overrideVersionMapper.selectByBuyerProductId(77L)).thenReturn(List.of());
        when(overrideVersionMapper.insert(any(BuyerProductL2OverrideVersionDO.class))).thenAnswer(inv -> {
            BuyerProductL2OverrideVersionDO row = inv.getArgument(0);
            row.setId(501L);
            return 1;
        });

        buyerProducts.patchL2("BYR_A", "BPD_1", L2AdjustMode.CUSTOM, 85, 0L, null, 1L);

        verify(listingVersionMapper, never()).updateById(any(ProjectListingVersionDO.class));
        assertThat(version1.getBuyerSettlementUnitPrice()).isEqualTo(L1_CENTS);
    }

    @Test
    void followL2WithoutAnyPriceFails() {
        stubLiveBuyerProduct();
        when(l1Mapper.selectById(11L)).thenReturn(null);
        when(overrideMapper.selectByBuyerProductId(77L)).thenReturn(null);
        assertThatThrownBy(() -> service.addProducts(PROJECT_CODE, List.of(follow("MOVIE_100")), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PLATFORM_SETTLEMENT_PRICE_MISSING.getCode());
    }

    private void stubGenericPricing(Long platformCents, Long buyerCents, boolean inherited) {
        when(buyerPricingMapper.selectByBuyerProductId(77L)).thenReturn(BuyerSkuPricingDO.builder()
                .buyerProductId(77L).currentVersionId(701L).build());
        when(buyerPricingVersionMapper.selectById(701L)).thenReturn(BuyerSkuPricingVersionDO.builder()
                .id(701L).platformPricingVersionId(702L).distributionPriceCents(buyerCents)
                .overrideJson("{\"distributionInherited\":" + inherited + "}").build());
        when(platformPricingVersionMapper.selectById(702L)).thenReturn(PlatformSkuPricingVersionDO.builder()
                .id(702L).distributionPriceCents(platformCents).build());
    }

    /** L3 试算严格限定项目作用域，SKU 税务元数据不改变试算金额。 */
    @Test
    void previewL3IsProjectScopedAndIgnoresTaxMetadata() {
        when(projectMapper.selectByProjectCode(PROJECT_CODE)).thenReturn(BuyerProjectDO.builder()
                .id(9L).projectCode(PROJECT_CODE).buyerId(5L).build());
        ProjectListingDO listing = ProjectListingDO.builder().id(80L).listingCode(LISTING_CODE)
                .projectId(9L).buyerProductId(77L).currentVersionId(801L)
                .status(ProjectListingStatus.AVAILABLE.getCode()).build();
        when(listingMapper.selectByCode(LISTING_CODE)).thenReturn(listing);
        when(productMapper.selectById(77L)).thenReturn(liveProduct());
        when(skuMapper.selectById(11L)).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("MOVIE_100").taxIncluded(true)
                .taxRate(new java.math.BigDecimal("0.2500"))
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        stubL1(L1_CENTS);
        when(overrideMapper.selectByBuyerProductId(77L)).thenReturn(null);
        when(listingVersionMapper.selectById(801L)).thenReturn(ProjectListingVersionDO.builder()
                .id(801L).listingId(80L).buyerSettlementUnitPrice(L1_CENTS)
                .settlementPriceLevel(PriceSource.L1.name()).build());

        var result = service.previewL3Batch(PROJECT_CODE, List.of(LISTING_CODE),
                L3AdjustMode.CUSTOM, 75, 0L);

        assertThat(result).singleElement().satisfies(line -> {
            assertThat(line.currentFinalPriceCents()).isEqualTo(L1_CENTS);
            assertThat(line.previewFinalPriceCents()).isEqualTo(750L);
        });
    }

    /** 即使属于同一分销商，其它项目的 listing 也不能通过当前项目路径试算或改价。 */
    @Test
    void previewL3RejectsListingFromAnotherProject() {
        when(projectMapper.selectByProjectCode(PROJECT_CODE)).thenReturn(BuyerProjectDO.builder()
                .id(9L).projectCode(PROJECT_CODE).buyerId(5L).build());
        when(listingMapper.selectByCode("PLT_OTHER_PROJECT")).thenReturn(ProjectListingDO.builder()
                .id(81L).listingCode("PLT_OTHER_PROJECT").projectId(10L).buyerProductId(77L)
                .status(ProjectListingStatus.AVAILABLE.getCode()).build());

        assertThatThrownBy(() -> service.previewL3Batch(PROJECT_CODE, List.of("PLT_OTHER_PROJECT"),
                L3AdjustMode.FOLLOW_L2, null, null))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PROJECT_LISTING_NOT_FOUND.getCode());
    }

    private void stubLiveBuyerProduct() {
        when(productMapper.selectByBuyerAndSku(5L, 11L)).thenReturn(liveProduct());
        when(productMapper.selectById(77L)).thenReturn(liveProduct());
        when(listingMapper.selectByProjectAndBuyerProduct(9L, 77L)).thenReturn(null);
    }

    private static BuyerProductDO liveProduct() {
        return BuyerProductDO.builder()
                .id(77L).buyerProductCode("BPD_1").buyerId(5L).platformSkuId(11L)
                .status(BuyerProductStatus.ACTIVE.getCode()).version(0L).build();
    }

    private void stubL1(long cents) {
        when(l1Mapper.selectById(11L)).thenReturn(PlatformSettlementPriceDO.builder()
                .platformSkuId(11L).currentVersionId(31L).build());
        when(l1VersionMapper.selectById(31L)).thenReturn(PlatformSettlementPriceVersionDO.builder()
                .id(31L).platformSkuId(11L).unitPriceCents(cents).build());
    }

    private static ListingLine follow(String skuCode) {
        return new ListingLine(skuCode, L3AdjustMode.FOLLOW_L2, null, null);
    }
}
