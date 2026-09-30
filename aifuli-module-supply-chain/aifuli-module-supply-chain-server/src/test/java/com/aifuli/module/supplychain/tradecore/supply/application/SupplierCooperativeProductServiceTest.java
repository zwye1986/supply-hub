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
import static org.mockito.Mockito.when;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 合作商品新增即上架、状态仅 PUBLISHED↔SUSPENDED、改价不写版本表。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#55-合作商品}。</p>
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
                businessNoGenerator, eventPublisher, categoryCoverageService);
    }

    /** 用给定类目快照构建真实归属判定器（与生产同一实现），避免在命中规则上再打 mock。 */
    private void stubCategoryTree(List<ProductCategoryDO> categories) {
        when(categoryCoverageService.loadResolver())
                .thenReturn(new ProductCategoryCoverageService.CoverageResolver(categories));
    }

    @Test
    void createUsesOneInsteadOfReservedScopeMinQuantity() {
        stubCreatePath();
        when(businessNoGenerator.next(BusinessNoPrefixes.OFR)).thenReturn("OFR_PUBLISHED1");

        service.createPublished("SPL_A", List.of(
                new DraftLine("MOVIE_100", "FIXED", 9_800L, null, null, "API_DIRECT", null, null)), 1L);

        ArgumentCaptor<SupplierOfferDO> captor = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper).insert(captor.capture());
        SupplierOfferDO inserted = captor.getValue();
        assertThat(inserted.getStatus()).isEqualTo(OfferStatus.PUBLISHED.getCode());
        assertThat(inserted.getDraftMaxQuantity()).isEqualTo(100L);
        assertThat(inserted.getDraftMinQuantity()).isEqualTo(1L);
        assertThat(inserted.getStabilityScore()).isEqualTo(OfferFulfillmentScore.DEFAULT);
        assertThat(inserted.getTimelinessScore()).isEqualTo(OfferFulfillmentScore.DEFAULT);
        verify(eventPublisher).publishEvent(new PricingDependencyChangedEvent(11L));
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
                new DraftLine("MOVIE_100", "FIXED", 8_800L, null, null, "API_DIRECT", 80L, 1L), 1L);

        verify(offerMapper).updateById(any(SupplierOfferDO.class));
        verify(offerVersionMapper, never()).insert(any(com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO.class));
        verify(eventPublisher).publishEvent(new PricingDependencyChangedEvent(11L));
    }

    @Test
    void updateCommercialDoesNotClearFulfillmentScores() {
        stubCreatePath();
        when(offerMapper.selectByNoForUpdate("OFR_1")).thenReturn(SupplierOfferDO.builder()
                .id(77L).offerNo("OFR_1").supplierId(9L).platformSkuId(11L)
                .supplyScopeId(101L).status(OfferStatus.PUBLISHED.getCode()).version(0L)
                .stabilityScore(80).timelinessScore(70).build());

        service.updateCommercial("SPL_A", "OFR_1",
                new DraftLine("MOVIE_100", "FIXED", 8_800L, null, null, "API_DIRECT", 80L, 1L), 1L);

        ArgumentCaptor<SupplierOfferDO> captor = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStabilityScore()).isEqualTo(80);
        assertThat(captor.getValue().getTimelinessScore()).isEqualTo(70);
        verify(offerVersionMapper, never()).insert(any(com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO.class));
    }

    @Test
    void rejectsWhenSpuMissingAnyOfTheThreeCodes() {
        stubCreatePath();
        when(spuMapper.selectById(21L)).thenReturn(PlatformSpuDO.builder()
                .id(21L).sellableCategory("MOVIE").productType(null).productClassification("MOVIE_TICKET")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build());

        assertThatThrownBy(() -> service.createPublished("SPL_A", List.of(
                new DraftLine("MOVIE_100", "FIXED", 9_800L, null, null, "API_DIRECT", null, null)), 1L))
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
                new DraftLine("EQUITY_100", "FIXED", 5_000L, null, null, "API_DIRECT", null, null)), 1L);

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
                new DraftLine("EQUITY_100", "FIXED", 5_000L, null, null, "API_DIRECT", null, null)), 1L);

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
                new DraftLine("EQUITY_100", "FIXED", 5_000L, null, null, "API_DIRECT", null, null)), 1L);

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
                new DraftLine("EQUITY_100", "FIXED", 5_000L, null, null, "API_DIRECT", null, null)), 1L))
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
                new DraftLine("EQUITY_100", "FIXED", 5_000L, null, null, "API_DIRECT", null, null)), 1L))
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
                new DraftLine("EQUITY_100", "FIXED", 5_000L, null, null, "API_DIRECT", null, null)), 1L))
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

    private void stubSupplierAndOffer(OfferStatus status) {
        SupplierDO supplier = SupplierDO.builder().id(9L).supplierCode("SPL_A").build();
        when(supplierMapper.selectByCode("SPL_A")).thenReturn(supplier);
        when(offerMapper.selectByNoForUpdate("OFR_1")).thenReturn(SupplierOfferDO.builder()
                .id(77L).offerNo("OFR_1").supplierId(9L).platformSkuId(11L)
                .supplyScopeId(101L).status(status.getCode()).version(0L).build());
    }
}
