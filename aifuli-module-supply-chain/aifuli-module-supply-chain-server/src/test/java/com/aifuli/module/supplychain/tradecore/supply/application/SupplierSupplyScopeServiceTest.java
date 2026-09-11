package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
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

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_SCOPE_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
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
    private SupplierSupplyScopeService service;

    @BeforeEach
    void setUp() {
        service = new SupplierSupplyScopeService(databaseClock, supplierMapper, scopeMapper, modeMapper);
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 4, 19, 0));
        when(supplierMapper.selectByCodeForUpdate("SPL_A")).thenReturn(
                SupplierDO.builder().id(9L).supplierCode("SPL_A").build());
        when(scopeMapper.selectBySupplierId(9L)).thenReturn(List.of(
                SupplierSupplyScopeDO.builder().id(88L).supplierId(9L).build()));
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
