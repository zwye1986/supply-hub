package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.supply.application.OfferFulfillmentScoreService.SkuSupplierScoreLine;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.FULFILLMENT_SCORE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.FULFILLMENT_SCORE_ITEMS_REQUIRED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.FULFILLMENT_SCORE_SUPPLIER_NOT_COOPERATING;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SKU_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 履约能力评分挂在未退役 Offer 身份列，供应商批量与商品弹窗读写同一事实。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-09-21-offer-fulfillment-score-design.md}。</p>
 */
class OfferFulfillmentScoreServiceTest {

    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private final SupplierOfferMapper offerMapper = mock(SupplierOfferMapper.class);
    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private OfferFulfillmentScoreService service;

    @BeforeEach
    void setUp() {
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 21, 16, 0));
        service = new OfferFulfillmentScoreService(
                databaseClock, supplierMapper, offerMapper, skuMapper, spuMapper);
    }

    @Test
    void saveSupplierScoresWritesAllLiveOffers() {
        stubSupplier();
        SupplierOfferDO first = liveOffer(77L, "OFR_1", 11L, null, null);
        SupplierOfferDO second = liveOffer(78L, "OFR_2", 12L, 10, 20);
        when(offerMapper.selectLiveBySupplierId(9L)).thenReturn(List.of(first, second));
        when(offerMapper.selectByNoForUpdate("OFR_1")).thenReturn(first);
        when(offerMapper.selectByNoForUpdate("OFR_2")).thenReturn(second);

        int updated = service.saveSupplierScores("SPL_A", 80, 70, null, 1L);

        assertThat(updated).isEqualTo(2);
        ArgumentCaptor<SupplierOfferDO> captor = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper, org.mockito.Mockito.times(2)).updateById(captor.capture());
        assertThat(captor.getAllValues()).extracting(SupplierOfferDO::getStabilityScore).containsOnly(80);
        assertThat(captor.getAllValues()).extracting(SupplierOfferDO::getTimelinessScore).containsOnly(70);
    }

    @Test
    void saveSupplierScoresSubsetOnlyTouchesListedSkus() {
        stubSupplier();
        when(skuMapper.selectByCode("MOVIE_100")).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("MOVIE_100").build());
        SupplierOfferDO offer = liveOffer(77L, "OFR_1", 11L, null, null);
        when(offerMapper.selectLiveBySupplierAndSku(9L, 11L)).thenReturn(offer);
        when(offerMapper.selectByNoForUpdate("OFR_1")).thenReturn(offer);

        int updated = service.saveSupplierScores("SPL_A", 60, 50, List.of("MOVIE_100"), 1L);

        assertThat(updated).isEqualTo(1);
        verify(offerMapper, never()).selectLiveBySupplierId(any());
        ArgumentCaptor<SupplierOfferDO> captor = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStabilityScore()).isEqualTo(60);
        assertThat(captor.getValue().getTimelinessScore()).isEqualTo(50);
    }

    @Test
    void saveSupplierScoresRejectsOutOfRange() {
        stubSupplier();
        assertThatThrownBy(() -> service.saveSupplierScores("SPL_A", 101, 10, null, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(FULFILLMENT_SCORE_INVALID.getCode());
        assertThatThrownBy(() -> service.saveSupplierScores("SPL_A", -1, null, null, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(FULFILLMENT_SCORE_INVALID.getCode());
        verify(offerMapper, never()).updateById(any(SupplierOfferDO.class));
    }

    @Test
    void saveSupplierScoresUpdatesOnlySubmittedColumn() {
        stubSupplier();
        SupplierOfferDO offer = liveOffer(77L, "OFR_1", 11L, 80, 70);
        when(offerMapper.selectLiveBySupplierId(9L)).thenReturn(List.of(offer));
        when(offerMapper.selectByNoForUpdate("OFR_1")).thenReturn(offer);

        int updated = service.saveSupplierScores("SPL_A", 90, null, null, 1L);

        assertThat(updated).isEqualTo(1);
        ArgumentCaptor<SupplierOfferDO> captor = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStabilityScore()).isEqualTo(90);
        assertThat(captor.getValue().getTimelinessScore()).isEqualTo(70);
    }

    @Test
    void saveSupplierScoresRejectsUnknownSupplier() {
        when(supplierMapper.selectByCodeForUpdate("SPL_MISSING")).thenReturn(null);
        assertThatThrownBy(() -> service.saveSupplierScores("SPL_MISSING", 80, 70, null, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLIER_NOT_FOUND.getCode());
    }

    @Test
    void saveSupplierScoresRejectsSkuWithoutLiveOffer() {
        stubSupplier();
        when(skuMapper.selectByCode("MOVIE_100")).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("MOVIE_100").build());
        when(offerMapper.selectLiveBySupplierAndSku(9L, 11L)).thenReturn(null);

        assertThatThrownBy(() -> service.saveSupplierScores("SPL_A", 80, 70, List.of("MOVIE_100"), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(FULFILLMENT_SCORE_SUPPLIER_NOT_COOPERATING.getCode());
    }

    @Test
    void saveSupplierScoresRejectsUnknownSkuInSubset() {
        stubSupplier();
        when(skuMapper.selectByCode("MISSING")).thenReturn(null);
        assertThatThrownBy(() -> service.saveSupplierScores("SPL_A", 80, 70, List.of("MISSING"), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PLATFORM_SKU_NOT_FOUND.getCode());
    }

    @Test
    void getSkuScoresReturnsEmptyItemsWhenNoLiveOffers() {
        stubSku();
        when(offerMapper.selectLiveBySkuId(11L)).thenReturn(List.of());

        var view = service.getSkuScores("MOVIE_100");

        assertThat(view.skuCode()).isEqualTo("MOVIE_100");
        assertThat(view.skuName()).isEqualTo("电影票 100");
        assertThat(view.spuCode()).isEqualTo("MOVIE_TICKET");
        assertThat(view.items()).isEmpty();
    }

    @Test
    void getSkuScoresReturnsCurrentCooperatingSuppliers() {
        stubSku();
        when(offerMapper.selectLiveBySkuId(11L)).thenReturn(List.of(
                liveOffer(77L, "OFR_1", 11L, 80, 70)));
        when(supplierMapper.selectById(9L)).thenReturn(SupplierDO.builder()
                .id(9L).supplierCode("SPL_A").supplierName("甲供应商").build());

        var view = service.getSkuScores("MOVIE_100");

        assertThat(view.items()).hasSize(1);
        assertThat(view.items().getFirst().supplierCode()).isEqualTo("SPL_A");
        assertThat(view.items().getFirst().supplierName()).isEqualTo("甲供应商");
        assertThat(view.items().getFirst().offerNo()).isEqualTo("OFR_1");
        assertThat(view.items().getFirst().stabilityScore()).isEqualTo(80);
        assertThat(view.items().getFirst().timelinessScore()).isEqualTo(70);
    }

    @Test
    void getSkuScoresRejectsUnknownSku() {
        when(skuMapper.selectByCode("MISSING")).thenReturn(null);
        assertThatThrownBy(() -> service.getSkuScores("MISSING"))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PLATFORM_SKU_NOT_FOUND.getCode());
    }

    @Test
    void saveSkuScoresUpdatesSubmittedRowsOnly() {
        stubSku();
        stubSupplier();
        SupplierOfferDO offer = liveOffer(77L, "OFR_1", 11L, 10, 20);
        when(offerMapper.selectLiveBySupplierAndSku(9L, 11L)).thenReturn(offer);
        when(offerMapper.selectByNoForUpdate("OFR_1")).thenReturn(offer);

        int updated = service.saveSkuScores("MOVIE_100", List.of(
                new SkuSupplierScoreLine("SPL_A", 90, 90)), 1L);

        assertThat(updated).isEqualTo(1);
        ArgumentCaptor<SupplierOfferDO> captor = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStabilityScore()).isEqualTo(90);
        assertThat(captor.getValue().getTimelinessScore()).isEqualTo(90);
    }

    @Test
    void saveSkuScoresUpdatesOnlySubmittedColumn() {
        stubSku();
        stubSupplier();
        SupplierOfferDO offer = liveOffer(77L, "OFR_1", 11L, 80, 70);
        when(offerMapper.selectLiveBySupplierAndSku(9L, 11L)).thenReturn(offer);
        when(offerMapper.selectByNoForUpdate("OFR_1")).thenReturn(offer);

        int updated = service.saveSkuScores("MOVIE_100", List.of(
                new SkuSupplierScoreLine("SPL_A", null, 55)), 1L);

        assertThat(updated).isEqualTo(1);
        ArgumentCaptor<SupplierOfferDO> captor = ArgumentCaptor.forClass(SupplierOfferDO.class);
        verify(offerMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStabilityScore()).isEqualTo(80);
        assertThat(captor.getValue().getTimelinessScore()).isEqualTo(55);
    }

    @Test
    void saveSkuScoresRejectsEmptyItems() {
        stubSku();
        assertThatThrownBy(() -> service.saveSkuScores("MOVIE_100", List.of(), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(FULFILLMENT_SCORE_ITEMS_REQUIRED.getCode());
        assertThatThrownBy(() -> service.saveSkuScores("MOVIE_100", null, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(FULFILLMENT_SCORE_ITEMS_REQUIRED.getCode());
    }

    @Test
    void saveSkuScoresRejectsDuplicateSupplier() {
        stubSku();
        assertThatThrownBy(() -> service.saveSkuScores("MOVIE_100", List.of(
                new SkuSupplierScoreLine("SPL_A", 80, 70),
                new SkuSupplierScoreLine("SPL_A", 81, 71)), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(FULFILLMENT_SCORE_INVALID.getCode());
    }

    @Test
    void saveSkuScoresRejectsNonCooperatingSupplier() {
        stubSku();
        stubSupplier();
        when(offerMapper.selectLiveBySupplierAndSku(9L, 11L)).thenReturn(null);
        assertThatThrownBy(() -> service.saveSkuScores("MOVIE_100", List.of(
                new SkuSupplierScoreLine("SPL_A", 80, 70)), 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(FULFILLMENT_SCORE_SUPPLIER_NOT_COOPERATING.getCode());
    }

    @Test
    void saveSupplierScoresReturnsZeroWhenNoLiveOffers() {
        stubSupplier();
        when(offerMapper.selectLiveBySupplierId(9L)).thenReturn(List.of());

        int updated = service.saveSupplierScores("SPL_A", 80, 70, null, 1L);

        assertThat(updated).isEqualTo(0);
        verify(offerMapper, never()).updateById(any(SupplierOfferDO.class));
    }

    private void stubSupplier() {
        SupplierDO supplier = SupplierDO.builder().id(9L).supplierCode("SPL_A").supplierName("甲供应商").build();
        when(supplierMapper.selectByCodeForUpdate("SPL_A")).thenReturn(supplier);
        when(supplierMapper.selectByCode("SPL_A")).thenReturn(supplier);
    }

    private void stubSku() {
        when(skuMapper.selectByCode("MOVIE_100")).thenReturn(PlatformSkuDO.builder()
                .id(11L).skuCode("MOVIE_100").skuName("电影票 100").spuId(21L).build());
        when(spuMapper.selectById(21L)).thenReturn(PlatformSpuDO.builder()
                .id(21L).spuCode("MOVIE_TICKET").spuName("电影票").build());
    }

    private static SupplierOfferDO liveOffer(Long id, String offerNo, Long skuId,
                                             Integer stability, Integer timeliness) {
        return SupplierOfferDO.builder()
                .id(id)
                .offerNo(offerNo)
                .supplierId(9L)
                .platformSkuId(skuId)
                .status(OfferStatus.PUBLISHED.getCode())
                .stabilityScore(stability)
                .timelinessScore(timeliness)
                .version(0L)
                .build();
    }
}
