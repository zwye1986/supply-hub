package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCategoryCoverageService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.PricingDependencyChangedEvent;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceVersionMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService.DraftLine;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferVersionMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferFulfillmentScore;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.time.LocalDateTime;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_OFFER_OUT_OF_SCOPE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_STATUS_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 合作商品新增默认下架、正式发布校验、商业草稿不改变生效定价版本。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-10-11-supplier-offer-lifecycle-design.md#1-核心业务规则}。</p>
 */
class SupplierCooperativeProductServiceTest {

    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private final SupplierSupplyScopeMapper scopeMapper = mock(SupplierSupplyScopeMapper.class);
    private final SupplierOfferMapper offerMapper = mock(SupplierOfferMapper.class);
    private final SupplierOfferVersionMapper offerVersionMapper = mock(SupplierOfferVersionMapper.class);
    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final BusinessNoGenerator businessNoGenerator = mock(BusinessNoGenerator.class);
    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ProductCategoryCoverageService categoryCoverageService = mock(ProductCategoryCoverageService.class);
    private final SupplierOfferPublicationService publicationService = mock(SupplierOfferPublicationService.class);
    private final SupplierOfferEligibilityService eligibilityService = mock(SupplierOfferEligibilityService.class);
    private SupplierCooperativeProductService service;

    @BeforeEach
    void setUp() {
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 4, 11, 0));
        // 默认空类目树：枚举名全等命中不依赖类目数据，既有用例不受影响
        stubCategoryTree(List.of());
        service = new SupplierCooperativeProductService(
                databaseClock, supplierMapper, scopeMapper, offerMapper, offerVersionMapper,
                skuMapper, spuMapper,
                mock(PlatformSettlementPriceMapper.class),
                mock(PlatformSettlementPriceVersionMapper.class),
                businessNoGenerator, eventPublisher, categoryCoverageService,
                publicationService, eligibilityService);
    }

    /** 用给定类目快照构建真实归属判定器（与生产同一实现），避免在命中规则上再打 mock。 */
    private void stubCategoryTree(List<ProductCategoryDO> categories) {
        when(categoryCoverageService.loadResolver())
                .thenReturn(new ProductCategoryCoverageService.CoverageResolver(categories));
    }

    @Test
    void scopeRetirementWritesAllStatesBeforePublishingSortedDistinctSkuEvents() {
        var supplier = SupplierDO.builder().id(9L).supplierCode("SPL_A").build();
        var scope = SupplierSupplyScopeDO.builder().id(88L).build();
        var a = SupplierOfferDO.builder().id(1L).supplyScopeId(88L).platformSkuId(30L).status(OfferStatus.PUBLISHED.getCode()).build();
        var b = SupplierOfferDO.builder().id(2L).supplyScopeId(88L).platformSkuId(10L).status(OfferStatus.SUSPENDED.getCode()).build();
        var c = SupplierOfferDO.builder().id(3L).supplyScopeId(88L).platformSkuId(30L).status(OfferStatus.SUSPENDED.getCode()).build();
        when(offerMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(a, b, c));
        service.retireForScopeChanges(supplier, List.of(scope), java.util.Set.of(88L), 7L);
        var order = org.mockito.Mockito.inOrder(offerMapper, eventPublisher);
        order.verify(offerMapper).updateById(a);
        order.verify(offerMapper).updateById(b);
        order.verify(offerMapper).updateById(c);
        order.verify(eventPublisher).publishEvent(new PricingDependencyChangedEvent(10L));
        order.verify(eventPublisher).publishEvent(new PricingDependencyChangedEvent(30L));
        order.verifyNoMoreInteractions();
        assertThat(List.of(a, b, c)).allSatisfy(offer -> assertThat(offer.getStatus()).isEqualTo(OfferStatus.RETIRED.getCode()));
    }

    @Test
    void createUsesOneInsteadOfReservedScopeMinQuantity() {
        stubCreatePath();
        when(businessNoGenerator.next(BusinessNoPrefixes.OFR)).thenReturn("OFR_PUBLISHED1");

        service.createPublished("SPL_A", List.of(
                new DraftLine("MOVIE_100", "FIXED", 9_800L, null, null, "API_DIRECT", null, null, "QNM-MOVIE-100")), 1L);

        ArgumentCaptor<SupplierOfferDO> captor = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper).insert(captor.capture());
        SupplierOfferDO inserted = captor.getValue();
        assertThat(inserted.getStatus()).isEqualTo(OfferStatus.SUSPENDED.getCode());
        assertThat(inserted.getDraftMaxQuantity()).isEqualTo(100L);
        assertThat(inserted.getDraftMinQuantity()).isEqualTo(1L);
        assertThat(inserted.getStabilityScore()).isEqualTo(OfferFulfillmentScore.DEFAULT);
        assertThat(inserted.getTimelinessScore()).isEqualTo(OfferFulfillmentScore.DEFAULT);
        verifyNoInteractions(eventPublisher);
    }

    /**
     * f1：选合作商品时录入的供应商外部商品编码必须冻结进身份行 {@code sc_supplier_offer.external_sku_code}，
     * 上架即发布时再继承进版本行，执行侧据此作为供应商 productId 发出。
     * 设计见 {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#54-f1-external_sku_code变更集-053}。
     */
    @Test
    void createPublishedPersistsExternalSkuCode() {
        stubCreatePath();
        when(businessNoGenerator.next(BusinessNoPrefixes.OFR)).thenReturn("OFR_EXT1");

        service.createPublished("SPL_A", List.of(
                new DraftLine("MOVIE_100", "FIXED", 9_800L, null, null, "API_DIRECT", null, null,
                        "QNM-MOVIE-100")), 1L);

        ArgumentCaptor<SupplierOfferDO> captor = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper).insert(captor.capture());
        assertThat(captor.getValue().getExternalSkuCode()).isEqualTo("QNM-MOVIE-100");
    }

    /** f1：外部商品编码为空白时服务层强制拒绝，不落库（前端另以 {@code @NotBlank} 兜底）。 */
    @Test
    void createPublishedRejectsBlankExternalSkuCode() {
        stubCreatePath();

        assertThatThrownBy(() -> service.createPublished("SPL_A", List.of(
                new DraftLine("MOVIE_100", "FIXED", 9_800L, null, null, "API_DIRECT", null, null, "  ")), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("externalSkuCode");
        verify(offerMapper, never()).insert(any(SupplierOfferDO.class));
    }

    @Test
    void patchStatusRejectsRetiredAndUnknown() {
        stubSupplierAndOffer(OfferStatus.PUBLISHED);

        assertThatThrownBy(() -> service.changeOfferStatus("SPL_A", "OFR_1", OfferStatus.RETIRED, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLIER_STATUS_CONFLICT.getCode());
        assertThatThrownBy(() -> service.changeOfferStatus("SPL_A", "OFR_1", OfferStatus.DRAFT, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLIER_STATUS_CONFLICT.getCode());
    }

    @Test
    void updateCommercialOnPublishedOfferDoesNotInsertOfferVersion() {
        stubCreatePath();
        stubSupplierAndOffer(OfferStatus.PUBLISHED);

        service.updateCommercial("SPL_A", "OFR_1",
                new DraftLine("MOVIE_100", "FIXED", 8_800L, null, null, "API_DIRECT", 80L, 1L, "QNM-MOVIE-100"), 1L);

        verify(offerMapper).updateById(any(SupplierOfferDO.class));
        verify(offerVersionMapper, never()).insert(any(com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void updateCommercialDoesNotClearFulfillmentScores() {
        stubCreatePath();
        when(offerMapper.selectByNoForUpdate("OFR_1")).thenReturn(SupplierOfferDO.builder()
                .id(77L).offerNo("OFR_1").supplierId(9L).platformSkuId(11L)
                .supplyScopeId(101L).status(OfferStatus.PUBLISHED.getCode()).version(0L)
                .stabilityScore(80).timelinessScore(70).build());

        service.updateCommercial("SPL_A", "OFR_1",
                new DraftLine("MOVIE_100", "FIXED", 8_800L, null, null, "API_DIRECT", 80L, 1L, "QNM-MOVIE-100"), 1L);

        ArgumentCaptor<SupplierOfferDO> captor = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStabilityScore()).isEqualTo(80);
        assertThat(captor.getValue().getTimelinessScore()).isEqualTo(70);
        verify(offerVersionMapper, never()).insert(any(com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO.class));
    }

    /**
     * f1：改价改供货模式时同步更新身份行外部商品编码（"校验与新增相同"）。编码变化后当前版本冻结值与身份列
     * 不一致，列表投影据此判为 STALE，须重新上架即发布生成新版本。
     * 设计见 {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#54-f1-external_sku_code变更集-053}。
     */
    @Test
    void updateCommercialUpdatesExternalSkuCode() {
        stubCreatePath();
        stubSupplierAndOffer(OfferStatus.PUBLISHED);

        service.updateCommercial("SPL_A", "OFR_1",
                new DraftLine("MOVIE_100", "FIXED", 8_800L, null, null, "API_DIRECT", 80L, 1L, "QNM-MOVIE-100-V2"), 1L);

        ArgumentCaptor<SupplierOfferDO> captor = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper).updateById(captor.capture());
        assertThat(captor.getValue().getExternalSkuCode()).isEqualTo("QNM-MOVIE-100-V2");
    }

    /** f1：改价路径同样强制外部商品编码非空，空白即拒绝且不落库。 */
    @Test
    void updateCommercialRejectsBlankExternalSkuCode() {
        stubCreatePath();
        stubSupplierAndOffer(OfferStatus.PUBLISHED);

        assertThatThrownBy(() -> service.updateCommercial("SPL_A", "OFR_1",
                new DraftLine("MOVIE_100", "FIXED", 8_800L, null, null, "API_DIRECT", 80L, 1L, " "), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("externalSkuCode");
        verify(offerMapper, never()).updateById(any(SupplierOfferDO.class));
    }

    /** 可售品类不参与供货准入，新增与编辑均沿用唯一配置及其供货上限。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PHYSICAL", ""})
    void createAndUpdateIgnoreSpuSellableCategory(String sellableCategory) {
        stubCreatePath();
        stubSupplierAndOffer(OfferStatus.SUSPENDED);
        when(spuMapper.selectById(21L)).thenReturn(PlatformSpuDO.builder()
                .id(21L).sellableCategory(sellableCategory).productType("MOVIE")
                .productClassification("MOVIE_TICKET").status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(businessNoGenerator.next(BusinessNoPrefixes.OFR)).thenReturn("OFR_CROSS_CATEGORY");
        DraftLine line = new DraftLine("MOVIE_100", "FIXED", 9800L, null, null,
                "API_DIRECT", null, null, "external");

        org.assertj.core.api.Assertions.assertThatCode(() -> {
            service.createPublished("SPL_A", List.of(line), 1L);
            service.updateCommercial("SPL_A", "OFR_1", line, 1L);
        }).doesNotThrowAnyException();

        ArgumentCaptor<SupplierOfferDO> inserted = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getSupplyScopeId()).isEqualTo(101L);
        assertThat(inserted.getValue().getDraftMaxQuantity()).isEqualTo(100L);
        verify(offerMapper).updateById(any(SupplierOfferDO.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void differentSellableCategoriesWithSameTypeAndClassificationAreConflicting() {
        stubCreatePath();
        when(scopeMapper.selectBySupplierId(9L)).thenReturn(List.of(
                SupplierSupplyScopeDO.builder().id(101L).sellableCategory("MOVIE").productType("MOVIE")
                        .productClassification("MOVIE_TICKET").maxQuantity(100L).build(),
                SupplierSupplyScopeDO.builder().id(102L).sellableCategory("PHYSICAL").productType("MOVIE")
                        .productClassification("MOVIE_TICKET").maxQuantity(200L).build()));
        assertThatThrownBy(() -> service.createPublished("SPL_A", List.of(
                new DraftLine("MOVIE_100", "FIXED", 9800L, null, null, "API_DIRECT", null, null, "external")), 1L))
                .isInstanceOf(ServiceException.class)
                .hasFieldOrPropertyWithValue("code",
                        com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_SCOPE_CONFLICT.getCode());
        verify(offerMapper, never()).insert(any(SupplierOfferDO.class));
    }

    @Test
    void rejectsWhenSpuMissingProductType() {
        stubCreatePath();
        when(spuMapper.selectById(21L)).thenReturn(PlatformSpuDO.builder()
                .id(21L).sellableCategory("MOVIE").productType(null).productClassification("MOVIE_TICKET")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());

        assertThatThrownBy(() -> service.createPublished("SPL_A", List.of(
                new DraftLine("MOVIE_100", "FIXED", 9_800L, null, null, "API_DIRECT", null, null, "QNM-MOVIE-100")), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLIER_OFFER_OUT_OF_SCOPE.getCode());
    }

    /**
     * 归属命中：配置 L1 类目码覆盖挂 L3 类目的 SPU；供货上限继承命中的 L1 配置。
     * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#1-已确认决策}（d3）。
     */
    @Test
    void ancestorScopeCoversSpuUnderDescendantCategory() {
        stubCategoryTree(equityCategoryTree());
        stubSupplier();
        when(scopeMapper.selectBySupplierId(9L)).thenReturn(List.of(SupplierSupplyScopeDO.builder()
                .id(101L).supplierId(9L)
                .sellableCategory("EQUITY").productType("EQUITY_PRODUCT").productClassification("CAT_L1")
                .maxQuantity(500L).minQuantity(1L).build()));
        stubEquitySkuAndSpu("CAT_L3");
        when(businessNoGenerator.next(BusinessNoPrefixes.OFR)).thenReturn("OFR_ANCESTOR1");
        when(offerMapper.selectCount(any())).thenReturn(0L);

        service.createPublished("SPL_A", List.of(
                new DraftLine("EQUITY_100", "FIXED", 5_000L, null, null, "API_DIRECT", null, null, "QNM-EQUITY-100")), 1L);

        ArgumentCaptor<SupplierOfferDO> captor = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper).insert(captor.capture());
        assertThat(captor.getValue().getSupplyScopeId()).isEqualTo(101L);
        assertThat(captor.getValue().getDraftMaxQuantity()).isEqualTo(500L);
    }

    /** 多条命中取类目层级最深（最具体）的一条：L1 与 L3 配置同时覆盖时落 L3。 */
    @Test
    void mostSpecificScopeWinsWhenNestedScopesBothCover() {
        stubCategoryTree(equityCategoryTree());
        stubSupplier();
        when(scopeMapper.selectBySupplierId(9L)).thenReturn(List.of(
                SupplierSupplyScopeDO.builder()
                        .id(101L).supplierId(9L)
                        .sellableCategory("EQUITY").productType("EQUITY_PRODUCT").productClassification("CAT_L1")
                        .maxQuantity(500L).minQuantity(1L).build(),
                SupplierSupplyScopeDO.builder()
                        .id(102L).supplierId(9L)
                        .sellableCategory("EQUITY").productType("EQUITY_PRODUCT").productClassification("CAT_L3")
                        .maxQuantity(50L).minQuantity(1L).build()));
        stubEquitySkuAndSpu("CAT_L3");
        when(businessNoGenerator.next(BusinessNoPrefixes.OFR)).thenReturn("OFR_SPECIFIC1");
        when(offerMapper.selectCount(any())).thenReturn(0L);

        service.createPublished("SPL_A", List.of(
                new DraftLine("EQUITY_100", "FIXED", 5_000L, null, null, "API_DIRECT", null, null, "QNM-EQUITY-100")), 1L);

        ArgumentCaptor<SupplierOfferDO> captor = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper).insert(captor.capture());
        assertThat(captor.getValue().getSupplyScopeId()).isEqualTo(102L);
        assertThat(captor.getValue().getDraftMaxQuantity()).isEqualTo(50L);
    }

    /** 归档不断链：中间 L2 已归档，L1 配置仍覆盖其下 L3 的 SPU。 */
    @Test
    void archivedMiddleCategoryDoesNotBreakAncestry() {
        List<ProductCategoryDO> tree = equityCategoryTree();
        tree.get(1).setStatus(CatalogEntityStatus.ARCHIVED.getCode());
        stubCategoryTree(tree);
        stubSupplier();
        when(scopeMapper.selectBySupplierId(9L)).thenReturn(List.of(SupplierSupplyScopeDO.builder()
                .id(101L).supplierId(9L)
                .sellableCategory("EQUITY").productType("EQUITY_PRODUCT").productClassification("CAT_L1")
                .maxQuantity(500L).minQuantity(1L).build()));
        stubEquitySkuAndSpu("CAT_L3");
        when(businessNoGenerator.next(BusinessNoPrefixes.OFR)).thenReturn("OFR_ARCHIVED1");
        when(offerMapper.selectCount(any())).thenReturn(0L);

        service.createPublished("SPL_A", List.of(
                new DraftLine("EQUITY_100", "FIXED", 5_000L, null, null, "API_DIRECT", null, null, "QNM-EQUITY-100")), 1L);

        verify(offerMapper).insert(any(SupplierOfferDO.class));
    }

    /** 反方向不命中：配置挂在 L3、SPU 挂在祖先 L1，配置层级更深不算覆盖。 */
    @Test
    void descendantScopeDoesNotCoverSpuOnAncestorCategory() {
        stubCategoryTree(equityCategoryTree());
        stubSupplier();
        when(scopeMapper.selectBySupplierId(9L)).thenReturn(List.of(SupplierSupplyScopeDO.builder()
                .id(101L).supplierId(9L)
                .sellableCategory("EQUITY").productType("EQUITY_PRODUCT").productClassification("CAT_L3")
                .maxQuantity(500L).minQuantity(1L).build()));
        stubEquitySkuAndSpu("CAT_L1");

        assertThatThrownBy(() -> service.createPublished("SPL_A", List.of(
                new DraftLine("EQUITY_100", "FIXED", 5_000L, null, null, "API_DIRECT", null, null, "QNM-EQUITY-100")), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLIER_OFFER_OUT_OF_SCOPE.getCode());
    }

    /** 一枚举一类目码不命中：配置为枚举名、SPU 为类目码（或反之），不存在祖孙关系。 */
    @Test
    void enumScopeDoesNotCoverCategoryCodedSpu() {
        stubCategoryTree(equityCategoryTree());
        stubSupplier();
        when(scopeMapper.selectBySupplierId(9L)).thenReturn(List.of(SupplierSupplyScopeDO.builder()
                .id(101L).supplierId(9L)
                .sellableCategory("EQUITY").productType("EQUITY_PRODUCT").productClassification("VIRTUAL_CARD")
                .maxQuantity(500L).minQuantity(1L).build()));
        stubEquitySkuAndSpu("CAT_L3");

        assertThatThrownBy(() -> service.createPublished("SPL_A", List.of(
                new DraftLine("EQUITY_100", "FIXED", 5_000L, null, null, "API_DIRECT", null, null, "QNM-EQUITY-100")), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLIER_OFFER_OUT_OF_SCOPE.getCode());
    }

    /** 兄弟子树不命中：配置挂在另一棵 L3 子树，与 SPU 类目不在同一祖先链。 */
    @Test
    void siblingSubtreeScopeDoesNotCover() {
        List<ProductCategoryDO> tree = new java.util.ArrayList<>(equityCategoryTree());
        tree.add(ProductCategoryDO.builder()
                .id(4L).categoryCode("CAT_L3_OTHER").parentId(2L).categoryLevel((short) 3)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        stubCategoryTree(tree);
        stubSupplier();
        when(scopeMapper.selectBySupplierId(9L)).thenReturn(List.of(SupplierSupplyScopeDO.builder()
                .id(101L).supplierId(9L)
                .sellableCategory("EQUITY").productType("EQUITY_PRODUCT").productClassification("CAT_L3_OTHER")
                .maxQuantity(500L).minQuantity(1L).build()));
        stubEquitySkuAndSpu("CAT_L3");

        assertThatThrownBy(() -> service.createPublished("SPL_A", List.of(
                new DraftLine("EQUITY_100", "FIXED", 5_000L, null, null, "API_DIRECT", null, null, "QNM-EQUITY-100")), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLIER_OFFER_OUT_OF_SCOPE.getCode());
    }

    /** 权益类目树：CAT_L1 → CAT_L2 → CAT_L3，全部启用。 */
    private static List<ProductCategoryDO> equityCategoryTree() {
        return new java.util.ArrayList<>(List.of(
                ProductCategoryDO.builder()
                        .id(1L).categoryCode("CAT_L1").parentId(null).categoryLevel((short) 1)
                        .status(CatalogEntityStatus.ACTIVE.getCode()).build(),
                ProductCategoryDO.builder()
                        .id(2L).categoryCode("CAT_L2").parentId(1L).categoryLevel((short) 2)
                        .status(CatalogEntityStatus.ACTIVE.getCode()).build(),
                ProductCategoryDO.builder()
                        .id(3L).categoryCode("CAT_L3").parentId(2L).categoryLevel((short) 3)
                        .status(CatalogEntityStatus.ACTIVE.getCode()).build()));
    }

    /** 销售建议价来自SKU；旧L1为空或不同均不能改变新响应字段。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"9600,8000", "9600,", "0,8000", ",8000"})
    void pageKeepsSkuSalePriceAndLegacyL1Separate(Long salePrice, Long legacyPrice) throws Exception {
        stubSupplier();
        var prices = mock(PlatformSettlementPriceMapper.class);
        var versions = mock(PlatformSettlementPriceVersionMapper.class);
        service = new SupplierCooperativeProductService(databaseClock, supplierMapper, scopeMapper,
                offerMapper, offerVersionMapper, skuMapper, spuMapper, prices, versions,
                businessNoGenerator, eventPublisher, categoryCoverageService,
                publicationService, eligibilityService);
        when(skuMapper.selectById(11L)).thenReturn(PlatformSkuDO.builder()
                .id(11L).spuId(21L).suggestedSalePrice(salePrice).marketPrice(10000L).build());
        if (legacyPrice != null) {
            when(prices.selectById(11L)).thenReturn(com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject
                    .PlatformSettlementPriceDO.builder().platformSkuId(11L).currentVersionId(99L).build());
            when(versions.selectById(99L)).thenReturn(com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject
                    .PlatformSettlementPriceVersionDO.builder().unitPriceCents(legacyPrice).build());
        }
        when(offerMapper.selectPage(any(com.aifuli.framework.common.pojo.PageParam.class), any())).thenReturn(
                new com.aifuli.framework.common.pojo.PageResult<>(List.of(SupplierOfferDO.builder()
                        .offerNo("OFR_1").platformSkuId(11L).priceMode("FIXED").draftUnitPrice(9500L)
                        .status(OfferStatus.PUBLISHED.getCode()).build()), 1L));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
                service.page("SPL_A", null, null, null, 1, 20).getList().getFirst());
        assertThat(json.has("suggestedSalePriceCents")).isTrue();
        assertThat(json.get("suggestedSalePriceCents").isNull() ? null : json.get("suggestedSalePriceCents").longValue())
                .isEqualTo(salePrice);
        assertThat(json.get("suggestPriceCents").isNull() ? null : json.get("suggestPriceCents").longValue())
                .isEqualTo(legacyPrice);
        assertThat(json.get("unitPriceCents").longValue()).isEqualTo(9500L);
    }

    @Test
    void pageWithMissingSkuKeepsSalePriceNullRatherThanFallingBackToL1() {
        stubSupplier();
        when(offerMapper.selectPage(any(com.aifuli.framework.common.pojo.PageParam.class), any())).thenReturn(
                new com.aifuli.framework.common.pojo.PageResult<>(List.of(SupplierOfferDO.builder()
                        .offerNo("OFR_1").platformSkuId(11L).status(OfferStatus.PUBLISHED.getCode()).build()), 1L));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(
                service.page("SPL_A", null, null, null, 1, 20).getList().getFirst());
        assertThat(json.has("suggestedSalePriceCents")).isTrue();
        assertThat(json.get("suggestedSalePriceCents").isNull()).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, -1, 1, 10000})
    void discountCreateAndUpdateUseSkuPriceDespiteMissingOrForgedHint(Long hint) {
        stubCreatePath();
        when(skuMapper.selectByCode("MOVIE_100")).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("MOVIE_100").spuId(21L).suggestedSalePrice(9600L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(businessNoGenerator.next(BusinessNoPrefixes.OFR)).thenReturn("OFR_DISCOUNT");
        DraftLine line = new DraftLine("MOVIE_100", "DISCOUNT", null, 9000, hint,
                "API_DIRECT", 80L, 1L, "external");
        service.createPublished("SPL_A", List.of(line), 1L);
        stubSupplierAndOffer(OfferStatus.PUBLISHED);
        service.updateCommercial("SPL_A", "OFR_1", line, 1L);
        verify(offerMapper).insert(any(SupplierOfferDO.class));
        verify(offerMapper).updateById(any(SupplierOfferDO.class));
        verifyNoInteractions(eventPublisher);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, -1})
    void discountRejectsInvalidSkuPriceBeforeAnyWriteOrEvent(Long price) {
        stubCreatePath();
        stubSupplierAndOffer(OfferStatus.PUBLISHED);
        when(skuMapper.selectByCode("MOVIE_100")).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("MOVIE_100").spuId(21L).suggestedSalePrice(price)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        DraftLine line = new DraftLine("MOVIE_100", "DISCOUNT", null, 9000, 10000L,
                "API_DIRECT", 80L, 1L, "external");
        for (Runnable action : List.<Runnable>of(() -> service.createPublished("SPL_A", List.of(line), 1L),
                () -> service.updateCommercial("SPL_A", "OFR_1", line, 1L))) {
            assertThatThrownBy(action::run).isInstanceOf(ServiceException.class)
                    .hasMessage("折扣模式要求SKU销售建议价大于0，请先在商品中心配置")
                    .extracting(ex -> ((ServiceException) ex).getCode()).isEqualTo(1_050_001_178);
        }
        verify(offerMapper, never()).insert(any(SupplierOfferDO.class));
        verify(offerMapper, never()).updateById(any(SupplierOfferDO.class));
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    private void stubSupplier() {
        SupplierDO supplier = SupplierDO.builder().id(9L).supplierCode("SPL_A").build();
        when(supplierMapper.selectByCodeForUpdate("SPL_A")).thenReturn(supplier);
        when(supplierMapper.selectByCode("SPL_A")).thenReturn(supplier);
    }

    private void stubEquitySkuAndSpu(String spuClassification) {
        when(skuMapper.selectByCode("EQUITY_100")).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("EQUITY_100").spuId(21L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(spuMapper.selectById(21L)).thenReturn(PlatformSpuDO.builder()
                .id(21L).sellableCategory("EQUITY").productType("EQUITY_PRODUCT")
                .productClassification(spuClassification)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
    }

    private void stubCreatePath() {
        SupplierDO supplier = SupplierDO.builder().id(9L).supplierCode("SPL_A").build();
        when(supplierMapper.selectByCodeForUpdate("SPL_A")).thenReturn(supplier);
        when(supplierMapper.selectByCode("SPL_A")).thenReturn(supplier);
        when(scopeMapper.selectBySupplierId(9L)).thenReturn(List.of(SupplierSupplyScopeDO.builder()
                .id(101L).supplierId(9L)
                .sellableCategory("MOVIE").productType("MOVIE").productClassification("MOVIE_TICKET")
                .maxQuantity(100L).minQuantity(50L).build()));
        when(skuMapper.selectByCode("MOVIE_100")).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("MOVIE_100").spuId(21L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(spuMapper.selectById(21L)).thenReturn(PlatformSpuDO.builder()
                .id(21L).sellableCategory("MOVIE").productType("MOVIE").productClassification("MOVIE_TICKET")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());
        when(offerMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void changeOfferStatusPublishedDelegatesToListOnShelf() {
        service.changeOfferStatus("SPL_A", "OFR_1", OfferStatus.PUBLISHED, 2L);
        // 委派时按管理端同一约定补齐非空 traceId（outbox trace_id 非空），其余字段透传。
        verify(publicationService).listOnShelf(org.mockito.ArgumentMatchers.argThat(command ->
                "SPL_A".equals(command.supplierCode()) && "OFR_1".equals(command.offerNo())
                        && command.validTo() == null && Long.valueOf(2L).equals(command.operatorId())
                        && command.traceId() != null && command.traceId().startsWith("admin-")
                        && command.correlationId() == null && command.causationId() == null));
        verify(offerMapper, never()).updateById(any(SupplierOfferDO.class));
    }

    @Test
    void changeOfferStatusSuspendedLocksSupplierUpdatesStatusAndPublishesEvent() {
        stubSupplierAndOffer(OfferStatus.PUBLISHED);
        when(offerMapper.updateById(any(SupplierOfferDO.class))).thenReturn(1);

        service.changeOfferStatus("SPL_A", "OFR_1", OfferStatus.SUSPENDED, 2L);

        verify(supplierMapper).selectByCodeForUpdate("SPL_A");
        ArgumentCaptor<SupplierOfferDO> captor = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(OfferStatus.SUSPENDED.getCode());
        verify(eventPublisher).publishEvent(new PricingDependencyChangedEvent(11L));
    }

    @Test
    void pagePopulatesCanPublishAndPublishBlockedReasonFromEligibilityService() {
        stubSupplier();
        SupplierOfferDO offer = SupplierOfferDO.builder()
                .offerNo("OFR_1").platformSkuId(11L).status(OfferStatus.SUSPENDED.getCode())
                .priceMode("FIXED").draftUnitPrice(9500L).build();
        when(offerMapper.selectPage(any(com.aifuli.framework.common.pojo.PageParam.class), any())).thenReturn(
                new com.aifuli.framework.common.pojo.PageResult<>(List.of(offer), 1L));
        when(skuMapper.selectById(11L)).thenReturn(PlatformSkuDO.builder()
                .id(11L).spuId(21L).suggestedSalePrice(9600L).marketPrice(10000L).build());
        when(eligibilityService.checkPublishEligibility(any(), any(), any(), any()))
                .thenReturn(SupplierOfferEligibilityService.PublishEligibility.blocked("供应商履约连接器未配置"));

        var result = service.page("SPL_A", null, null, null, 1, 20);
        var item = result.getList().getFirst();
        assertThat(item.canPublish()).isFalse();
        assertThat(item.publishBlockedReason()).isEqualTo("供应商履约连接器未配置");
    }

    private void stubSupplierAndOffer(OfferStatus status) {
        SupplierDO supplier = SupplierDO.builder().id(9L).supplierCode("SPL_A").build();
        when(supplierMapper.selectByCode("SPL_A")).thenReturn(supplier);
        when(supplierMapper.selectByCodeForUpdate("SPL_A")).thenReturn(supplier);
        when(offerMapper.selectByNoForUpdate("OFR_1")).thenReturn(SupplierOfferDO.builder()
                .id(77L).offerNo("OFR_1").supplierId(9L).platformSkuId(11L)
                .supplyScopeId(101L).status(status.getCode()).version(0L).build());
    }
}
