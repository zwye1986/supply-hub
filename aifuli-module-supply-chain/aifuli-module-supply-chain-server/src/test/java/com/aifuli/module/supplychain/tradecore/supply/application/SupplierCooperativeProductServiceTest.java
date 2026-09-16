package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSettlementPriceMapper;
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
    private SupplierCooperativeProductService service;

    @BeforeEach
    void setUp() {
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 4, 11, 0));
        service = new SupplierCooperativeProductService(
                databaseClock, supplierMapper, scopeMapper, offerMapper, offerVersionMapper,
                skuMapper, spuMapper,
                mock(PlatformSettlementPriceMapper.class),
                mock(PlatformSettlementPriceVersionMapper.class),
                businessNoGenerator, eventPublisher);
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
