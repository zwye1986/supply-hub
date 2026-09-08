package com.aifuli.module.supplychain.tradecore.account.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.ledger.dal.dataobject.AccountLedgerDO;
import com.aifuli.module.supplychain.tradecore.ledger.dal.mysql.AccountLedgerMapper;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_ACCOUNT_RETURN_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BuyerAccountReturnService 单测：永久幂等键、余额返还与 REFUND 账本语义。
 *
 * <p>详见 {@code docs/superpowers/plans/2026-08-23-instore-refund-serialization.md}（Task 1）
 * 与 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量}。</p>
 */
@ExtendWith(MockitoExtension.class)
class BuyerAccountReturnServiceTest {

    @Mock
    private BuyerAccountMapper accountMapper;

    @Mock
    private AccountLedgerMapper ledgerMapper;

    @Mock
    private AccountLedgerWriter ledgerWriter;

    @Mock
    private SupplyChainDatabaseClock databaseClock;

    @InjectMocks
    private BuyerAccountReturnService service;

    @BeforeEach
    void setUpDatabaseClock() {
        lenient().when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 9, 4, 11, 0));
    }

    private static BuyerAccountDO account(long balance) {
        return BuyerAccountDO.builder().id(9L).buyerId(5L).currency("CNY")
                .balance(balance).frozen(0L).status((short) 10).version(3L).build();
    }

    private BuyerAccountReturnService.ReturnCommand command(long amount) {
        return new BuyerAccountReturnService.ReturnCommand(5L, "IRC_123", amount,
                "SC_100", "INSTORE_ORDER", "ISC_200", "trace-1", "causation-1");
    }

    @Test
    void returnsFundsAndAppendsRefundLedger() {
        BuyerAccountDO account = account(1000L);
        when(accountMapper.selectByBuyerIdForUpdate(5L)).thenReturn(account);
        when(ledgerMapper.selectByEntryKey(9L, "REFUND_RETURN:IRC_123")).thenReturn(null);
        when(accountMapper.atomicReturn(eq(9L), eq(3L), eq(300L), any())).thenReturn(1);

        service.returnFunds(command(300L));

        ArgumentCaptor<AccountLedgerWriter.LedgerEntry> captor =
                ArgumentCaptor.forClass(AccountLedgerWriter.LedgerEntry.class);
        verify(ledgerWriter).append(captor.capture());
        AccountLedgerWriter.LedgerEntry entry = captor.getValue();
        assertThat(entry.entryKey()).isEqualTo("REFUND_RETURN:IRC_123");
        assertThat(entry.entryType()).isEqualTo("REFUND");
        assertThat(entry.balanceBefore()).isEqualTo(1000L);
        assertThat(entry.balanceAfter()).isEqualTo(1300L);
        assertThat(entry.frozenBefore()).isZero();
        assertThat(entry.frozenAfter()).isZero();
        assertThat(entry.orderNo()).isEqualTo("SC_100");
        assertThat(entry.referenceType()).isEqualTo("INSTORE_ORDER");
        assertThat(entry.referenceNo()).isEqualTo("ISC_200");
    }

    @Test
    void sameKeySameAmountReplayIsIdempotentNoop() {
        when(accountMapper.selectByBuyerIdForUpdate(5L)).thenReturn(account(1300L));
        AccountLedgerDO existing = AccountLedgerDO.builder()
                .accountId(9L).entryKey("REFUND_RETURN:IRC_123").entryType("REFUND")
                .balanceDelta(300L).build();
        when(ledgerMapper.selectByEntryKey(9L, "REFUND_RETURN:IRC_123")).thenReturn(existing);

        service.returnFunds(command(300L));

        verify(accountMapper, never()).atomicReturn(anyLong(), anyLong(), anyLong(), any());
        verify(ledgerWriter, never()).append(any());
    }

    @Test
    void sameKeyDifferentAmountThrowsAccountReturnConflict() {
        when(accountMapper.selectByBuyerIdForUpdate(5L)).thenReturn(account(1300L));
        AccountLedgerDO existing = AccountLedgerDO.builder()
                .accountId(9L).entryKey("REFUND_RETURN:IRC_123").entryType("REFUND")
                .balanceDelta(200L).build();
        when(ledgerMapper.selectByEntryKey(9L, "REFUND_RETURN:IRC_123")).thenReturn(existing);

        assertThatThrownBy(() -> service.returnFunds(command(300L)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining(INSTORE_ACCOUNT_RETURN_CONFLICT.getMsg());
        verify(ledgerWriter, never()).append(any());
    }

    @Test
    void missingAccountFails() {
        when(accountMapper.selectByBuyerIdForUpdate(5L)).thenReturn(null);
        assertThatThrownBy(() -> service.returnFunds(command(300L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Buyer CNY account does not exist");
    }

    @Test
    void concurrentAccountUpdateFails() {
        when(accountMapper.selectByBuyerIdForUpdate(5L)).thenReturn(account(1000L));
        when(ledgerMapper.selectByEntryKey(9L, "REFUND_RETURN:IRC_123")).thenReturn(null);
        when(accountMapper.atomicReturn(eq(9L), eq(3L), eq(300L), any())).thenReturn(0);

        assertThatThrownBy(() -> service.returnFunds(command(300L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Concurrent account update");
    }
}
