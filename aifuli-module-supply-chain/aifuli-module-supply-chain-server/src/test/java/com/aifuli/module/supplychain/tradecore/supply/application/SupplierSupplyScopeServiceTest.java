package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductClassificationValidationService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductTypeValidationService;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierSupplyScopeService.ScopeLine;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierSupplyScopeModeDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierSupplyScopeModeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_SCOPE_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供货配置整表替换：三码互相独立不校验父子链，供应商内三码组合唯一；模式写入子表。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#41-rest}。
 */
class SupplierSupplyScopeServiceTest {

    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private final SupplierSupplyScopeMapper scopeMapper = mock(SupplierSupplyScopeMapper.class);
    private final SupplierSupplyScopeModeMapper modeMapper = mock(SupplierSupplyScopeModeMapper.class);
    private final ProductTypeValidationService productTypeValidationService = mock(ProductTypeValidationService.class);
    private final ProductCategoryMapper categoryMapper = mock(ProductCategoryMapper.class);
    private final ProductClassificationValidationService productClassificationValidationService =
            new ProductClassificationValidationService(categoryMapper);
    private final SupplierCooperativeProductService cooperativeProductService = mock(SupplierCooperativeProductService.class);
    private SupplierSupplyScopeService service;

    @BeforeEach
    void setUp() {
        service = new SupplierSupplyScopeService(databaseClock, supplierMapper, scopeMapper, modeMapper,
                productTypeValidationService, productClassificationValidationService, cooperativeProductService);
        when(productTypeValidationService.requireActiveForUpdate(any())).thenAnswer(invocation ->
                ProductTypeDO.builder().typeCode(invocation.getArgument(0)).status((short) 10).build());
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 4, 19, 0));
        when(supplierMapper.selectByCodeForUpdate("SPL_A")).thenReturn(
                SupplierDO.builder().id(9L).supplierCode("SPL_A").build());
        when(scopeMapper.selectBySupplierId(9L)).thenReturn(List.of(
                SupplierSupplyScopeDO.builder().id(88L).supplierId(9L).sellableCategory("EQUITY")
                        .productType("MOVIE").productClassification("MOVIE_TICKET").version(0L).build()));
        when(scopeMapper.insert(any(SupplierSupplyScopeDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, SupplierSupplyScopeDO.class).setId(101L);
            return 1;
        });
    }

    @Test
    void replaceAllWritesModeToChildTableAndDeletesChildRowsFirst() {
        service.replaceAll("SPL_A", List.of(new ScopeLine(
                "EQUITY", "MOVIE", "MOVIE_TICKET", List.of("API_DIRECT"), 1L, 100L)), 7L);

        InOrder order = inOrder(modeMapper, scopeMapper);
        order.verify(modeMapper).deleteByScopeIds(List.of(88L));
        order.verify(scopeMapper).deleteBySupplierId(9L);

        ArgumentCaptor<SupplierSupplyScopeDO> scopeCaptor = ArgumentCaptor.forClass(SupplierSupplyScopeDO.class);
        verify(scopeMapper).insert(scopeCaptor.capture());
        assertThat(scopeCaptor.getValue().getSellableCategory()).isEqualTo("EQUITY");
        assertThat(scopeCaptor.getValue().getProductType()).isEqualTo("MOVIE");
        assertThat(scopeCaptor.getValue().getProductClassification()).isEqualTo("MOVIE_TICKET");
        assertThat(scopeCaptor.getValue().getMaxQuantity()).isEqualTo(1L);
        assertThat(scopeCaptor.getValue().getMinQuantity()).isEqualTo(100L);

        ArgumentCaptor<SupplierSupplyScopeModeDO> modeCaptor =
                ArgumentCaptor.forClass(SupplierSupplyScopeModeDO.class);
        verify(modeMapper).insert(modeCaptor.capture());
        assertThat(modeCaptor.getValue().getSupplyScopeId()).isEqualTo(101L);
        assertThat(modeCaptor.getValue().getSupplyMode()).isEqualTo("API_DIRECT");
    }

    @Test
    void replaceAllPersistsCategoryCodeAsProductClassification() {
        when(categoryMapper.selectByCode("E2E_L1_20260903")).thenReturn(ProductCategoryDO.builder()
                .id(1L).categoryCode("E2E_L1_20260903").status(CatalogEntityStatus.ACTIVE.getCode()).build());

        service.replaceAll("SPL_A", List.of(new ScopeLine(
                88L, "EQUITY", "EQUITY_PRODUCT", "E2E_L1_20260903", List.of("API_DIRECT"), 1L, 100L)), 7L);

        ArgumentCaptor<SupplierSupplyScopeDO> scopeCaptor = ArgumentCaptor.forClass(SupplierSupplyScopeDO.class);
        verify(scopeMapper).insert(scopeCaptor.capture());
        assertThat(scopeCaptor.getValue().getProductClassification()).isEqualTo("E2E_L1_20260903");
    }

    @Test
    void typeOrClassificationChangeTargetsOriginalIdButNonSemanticSaveDoesNot() {
        service.replaceAll("SPL_A", List.of(new ScopeLine(88L,
                "MOVIE", "EQUITY_PRODUCT", "MOVIE_TICKET", List.of("H5"), 100L, 1L)), 7L);
        verify(cooperativeProductService).retireForScopeChanges(any(), any(), eq(Set.of(88L)), eq(7L));
    }

    @Test
    void legacyExactSaveRetainsIdentityWithoutRetiringAndMixedNewRowDoesNotInferByPosition() {
        service.replaceAll("SPL_A", List.of(new ScopeLine(
                "EQUITY", "MOVIE", "MOVIE_TICKET", List.of("H5"), 100L, 1L)), 7L);
        verify(cooperativeProductService).retireForScopeChanges(any(), any(), eq(Set.of()), eq(7L));
        ArgumentCaptor<SupplierSupplyScopeDO> captured = ArgumentCaptor.forClass(SupplierSupplyScopeDO.class);
        verify(scopeMapper).insert(captured.capture());
        // mapper mock会赋id，因此从调用写入前的id另由真实MySQL回归证明；原创建时间及版本在此验证。
        assertThat(captured.getValue().getVersion()).isEqualTo(1L);
    }

    @Test
    void rejectsUnknownDuplicateAndLegacyUnidentifiableIdsBeforeAnyLifecycleWrite() {
        for (Long id : List.of(77L, 999L)) {
            assertThatThrownBy(() -> service.replaceAll("SPL_A", List.of(new ScopeLine(id,
                    "EQUITY", "MOVIE", "MOVIE_TICKET", List.of("H5"), 100L, 1L)), 7L))
                    .hasMessageContaining(SUPPLIER_SCOPE_CONFLICT.getMsg());
        }
        assertThatThrownBy(() -> service.replaceAll("SPL_A", List.of(
                new ScopeLine(88L, "EQUITY", "MOVIE", "MOVIE_TICKET", List.of("H5"), 100L, 1L),
                new ScopeLine(88L, "PHYSICAL", "MOVIE", "MOVIE_TICKET", List.of("H5"), 100L, 1L)), 7L))
                .hasMessageContaining(SUPPLIER_SCOPE_CONFLICT.getMsg());
        assertThatThrownBy(() -> service.replaceAll("SPL_A", List.of(new ScopeLine(
                "EQUITY", "EQUITY_PRODUCT", "MOVIE_TICKET", List.of("H5"), 100L, 1L)), 7L))
                .hasMessageContaining(SUPPLIER_SCOPE_CONFLICT.getMsg());
        verifyNoInteractions(cooperativeProductService);
        verify(scopeMapper, never()).deleteBySupplierId(any());
    }

    @Test
    void newRowsAllowedWhenExistingRowsRetainedAndRepeatableReadRejectedBeforeReads() {
        service.replaceAll("SPL_A", List.of(
                new ScopeLine(88L, "EQUITY", "MOVIE", "MOVIE_TICKET", List.of("H5"), 100L, 1L),
                new ScopeLine("PHYSICAL", "EQUITY_PRODUCT", "VIRTUAL_CARD", List.of("API_DIRECT"), 100L, 1L)), 7L);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            assertThatThrownBy(() -> service.replaceAll("SPL_A", List.of(), 7L))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("READ_COMMITTED");
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void rejectsUnknownClassificationAsCategoryNotFound() {
        when(categoryMapper.selectByCode("NOT_A_CATEGORY")).thenReturn(null);
        assertThatThrownBy(() -> service.replaceAll("SPL_A", List.of(new ScopeLine(
                "EQUITY", "MOVIE", "NOT_A_CATEGORY", List.of("API_DIRECT"), 100L, 1L)), 7L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(CATEGORY_NOT_FOUND.getCode());
    }

    @Test
    void rejectsUnknownEnumCode() {
        assertThatThrownBy(() -> service.replaceAll("SPL_A", List.of(new ScopeLine(
                "NOT_A_CODE", "MOVIE", "MOVIE_TICKET", List.of("API_DIRECT"), 100L, 1L)), 7L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(ENUM_NOT_OPEN.getCode());
    }

    @Test
    void rejectsDuplicateTripleWithinSameRequest() {
        assertThatThrownBy(() -> service.replaceAll("SPL_A", List.of(
                new ScopeLine("EQUITY", "MOVIE", "MOVIE_TICKET", List.of("API_DIRECT"), 100L, 1L),
                new ScopeLine("EQUITY", "MOVIE", "MOVIE_TICKET", List.of("H5"), 200L, 1L)), 7L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLIER_SCOPE_CONFLICT.getCode());
    }
}
