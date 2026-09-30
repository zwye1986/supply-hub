package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierInvoiceService.SaveInvoiceCommand;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierInvoiceService.SupplierInvoiceView;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierContactDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierInvoiceDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierContactMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierInvoiceMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供应商开票：无行预填不 insert；保存明文且不回写档案。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md}。
 */
class SupplierInvoiceServiceTest {

    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private final EnvelopeCryptoPort envelopeCrypto = mock(EnvelopeCryptoPort.class);
    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private final SupplierContactMapper contactMapper = mock(SupplierContactMapper.class);
    private final SupplierInvoiceMapper invoiceMapper = mock(SupplierInvoiceMapper.class);
    private SupplierInvoiceService service;

    @BeforeEach
    void setUp() {
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 7, 11, 0));
        service = new SupplierInvoiceService(
                databaseClock, envelopeCrypto, supplierMapper, contactMapper, invoiceMapper);
    }

    @Test
    void getPrefillsFromProfileAndDoesNotInsert() {
        SupplierDO supplier = profile();
        when(supplierMapper.selectByCode("SPL_A")).thenReturn(supplier);
        when(invoiceMapper.selectBySupplierId(9L)).thenReturn(null);
        when(contactMapper.selectBySupplierId(9L)).thenReturn(List.of(SupplierContactDO.builder()
                .sortNo(0).contactMobile("13800138000").build()));
        when(envelopeCrypto.decrypt(any(), any(), any())).thenReturn("6222021234567890".getBytes(StandardCharsets.UTF_8));

        SupplierInvoiceView view = service.get("SPL_A");

        assertThat(view.persisted()).isFalse();
        assertThat(view.id()).isNull();
        assertThat(view.invoiceTitle()).isEqualTo("测试科技有限公司");
        assertThat(view.phone()).isEqualTo("13800138000");
        assertThat(view.bankAccount()).isEqualTo("6222021234567890");
        verify(invoiceMapper, never()).insert(any(SupplierInvoiceDO.class));
    }

    @Test
    void getMissingSupplierThrows() {
        when(supplierMapper.selectByCode("SPL_X")).thenReturn(null);
        assertThatThrownBy(() -> service.get("SPL_X"))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(SUPPLIER_NOT_FOUND.getCode());
    }

    @Test
    void saveWritesPlaintextAndDoesNotTouchSupplier() {
        SupplierDO supplier = profile();
        when(supplierMapper.selectByCodeForUpdate("SPL_A")).thenReturn(supplier);
        when(invoiceMapper.selectBySupplierId(9L)).thenReturn(null)
                .thenReturn(SupplierInvoiceDO.builder()
                        .id(3L)
                        .supplierId(9L)
                        .titleType("ENTERPRISE")
                        .invoiceTitle("发票抬头")
                        .socialCreditCode("91110000MA1K3ABC0X")
                        .phone("13900000000")
                        .bankName("招商银行")
                        .bankAccount("6225880000000001")
                        .invoiceAddress("浦东")
                        .createdAt(LocalDateTime.of(2026, 9, 7, 11, 0))
                        .updatedAt(LocalDateTime.of(2026, 9, 7, 11, 0))
                        .build());

        SupplierInvoiceView view = service.save("SPL_A", new SaveInvoiceCommand(
                "ENTERPRISE", "发票抬头", "91110000MA1K3ABC0X", "13900000000",
                "招商银行", "6225880000000001", "浦东"), 8L);

        ArgumentCaptor<SupplierInvoiceDO> captor = ArgumentCaptor.forClass(SupplierInvoiceDO.class);
        verify(invoiceMapper).insert(captor.capture());
        assertThat(captor.getValue().getBankAccount()).isEqualTo("6225880000000001");
        assertThat(captor.getValue().getSupplierId()).isEqualTo(9L);
        verify(supplierMapper, never()).updateById(any(SupplierDO.class));
        assertThat(view.persisted()).isTrue();
        assertThat(view.id()).isEqualTo(3L);
        assertThat(view.bankAccount()).isEqualTo("6225880000000001");
    }

    private static SupplierDO profile() {
        return SupplierDO.builder()
                .id(9L)
                .supplierCode("SPL_A")
                .supplierName("供应商甲")
                .legalEntityName("测试科技有限公司")
                .creditCode("91110000000000001X")
                .bankName("测试银行")
                .bankAccountCipher(new byte[]{1, 2, 3})
                .bankAccountKeyVersion("test-v1")
                .contactAddress("上海市浦东新区")
                .build();
    }
}
