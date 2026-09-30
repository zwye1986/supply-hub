package com.aifuli.module.supplychain.tradecore.buyer.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerInvoiceService.BuyerInvoiceView;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerInvoiceService.SaveInvoiceCommand;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerInvoiceDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerContactMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerInvoiceMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
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
 * 分销商开票：无联系人时回落档案手机；保存不回写档案。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md}。
 */
class BuyerInvoiceServiceTest {

    private final SupplyChainDatabaseClock databaseClock = mock(SupplyChainDatabaseClock.class);
    private final EnvelopeCryptoPort envelopeCrypto = mock(EnvelopeCryptoPort.class);
    private final BuyerMapper buyerMapper = mock(BuyerMapper.class);
    private final BuyerContactMapper contactMapper = mock(BuyerContactMapper.class);
    private final BuyerInvoiceMapper invoiceMapper = mock(BuyerInvoiceMapper.class);
    private BuyerInvoiceService service;

    @BeforeEach
    void setUp() {
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 7, 11, 0));
        service = new BuyerInvoiceService(
                databaseClock, envelopeCrypto, buyerMapper, contactMapper, invoiceMapper);
    }

    @Test
    void getPrefillsPhoneFromBuyerWhenNoContacts() {
        BuyerDO buyer = BuyerDO.builder()
                .id(4L)
                .buyerCode("BYR_A")
                .buyerName("分销商甲")
                .legalEntityName("一级科技有限公司")
                .creditCode("91110000000000001X")
                .contactMobile("13700000000")
                .contactAddress("苏州")
                .build();
        when(buyerMapper.selectByBuyerCode("BYR_A")).thenReturn(buyer);
        when(invoiceMapper.selectByBuyerId(4L)).thenReturn(null);
        when(contactMapper.selectByBuyerId(4L)).thenReturn(List.of());

        BuyerInvoiceView view = service.get("BYR_A");

        assertThat(view.persisted()).isFalse();
        assertThat(view.invoiceTitle()).isEqualTo("一级科技有限公司");
        assertThat(view.phone()).isEqualTo("13700000000");
        verify(invoiceMapper, never()).insert(any(BuyerInvoiceDO.class));
    }

    @Test
    void getMissingBuyerThrows() {
        when(buyerMapper.selectByBuyerCode("BYR_X")).thenReturn(null);
        assertThatThrownBy(() -> service.get("BYR_X"))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(BUYER_PROFILE_NOT_FOUND.getCode());
    }

    @Test
    void savePersonalClearsBankColumns() {
        BuyerDO buyer = BuyerDO.builder().id(4L).buyerCode("BYR_A").buyerName("分销商甲").build();
        when(buyerMapper.selectByBuyerCodeForUpdate("BYR_A")).thenReturn(buyer);
        when(invoiceMapper.selectByBuyerId(4L)).thenReturn(null)
                .thenReturn(BuyerInvoiceDO.builder()
                        .id(6L)
                        .buyerId(4L)
                        .titleType("PERSONAL")
                        .invoiceTitle("张三")
                        .phone("13800000000")
                        .createdAt(LocalDateTime.of(2026, 9, 7, 11, 0))
                        .updatedAt(LocalDateTime.of(2026, 9, 7, 11, 0))
                        .build());

        BuyerInvoiceView view = service.save("BYR_A", new SaveInvoiceCommand(
                "PERSONAL", "张三", "91110000MA1K3ABC0X", "13800000000",
                "工商银行", "6222021001001234567", "地址"), 1L);

        ArgumentCaptor<BuyerInvoiceDO> captor = ArgumentCaptor.forClass(BuyerInvoiceDO.class);
        verify(invoiceMapper).insert(captor.capture());
        assertThat(captor.getValue().getBankAccount()).isNull();
        assertThat(captor.getValue().getSocialCreditCode()).isNull();
        assertThat(captor.getValue().getTitleType()).isEqualTo("PERSONAL");
        verify(buyerMapper, never()).updateById(any(BuyerDO.class));
        assertThat(view.persisted()).isTrue();
        assertThat(view.bankAccount()).isNull();
    }

    @Test
    void savePersonalUpdateClearsExistingBankColumns() {
        BuyerDO buyer = BuyerDO.builder().id(4L).buyerCode("BYR_A").buyerName("分销商甲").build();
        BuyerInvoiceDO existing = BuyerInvoiceDO.builder()
                .id(6L)
                .buyerId(4L)
                .titleType("ENTERPRISE")
                .invoiceTitle("公司")
                .socialCreditCode("91110000MA1K3ABC0X")
                .phone("13900000000")
                .bankName("招商银行")
                .bankAccount("6225880000000001")
                .version(0L)
                .build();
        when(buyerMapper.selectByBuyerCodeForUpdate("BYR_A")).thenReturn(buyer);
        when(invoiceMapper.selectByBuyerId(4L)).thenReturn(existing)
                .thenReturn(BuyerInvoiceDO.builder()
                        .id(6L)
                        .buyerId(4L)
                        .titleType("PERSONAL")
                        .invoiceTitle("张三")
                        .phone("13800000000")
                        .createdAt(LocalDateTime.of(2026, 9, 7, 11, 0))
                        .updatedAt(LocalDateTime.of(2026, 9, 7, 11, 0))
                        .build());

        service.save("BYR_A", new SaveInvoiceCommand(
                "PERSONAL", "张三", "91110000MA1K3ABC0X", "13800000000",
                "工商银行", "6222021001001234567", "地址"), 1L);

        ArgumentCaptor<BuyerInvoiceDO> captor = ArgumentCaptor.forClass(BuyerInvoiceDO.class);
        verify(invoiceMapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(6L);
        assertThat(captor.getValue().getBankAccount()).isNull();
        assertThat(captor.getValue().getSocialCreditCode()).isNull();
        assertThat(captor.getValue().getBankName()).isNull();
        verify(invoiceMapper, never()).insert(any(BuyerInvoiceDO.class));
    }
}
