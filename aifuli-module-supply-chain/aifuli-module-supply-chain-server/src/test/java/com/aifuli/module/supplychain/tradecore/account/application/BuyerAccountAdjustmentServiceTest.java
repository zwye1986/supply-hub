package com.aifuli.module.supplychain.tradecore.account.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountAdjustmentService.AdjustmentCommand;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_ACCOUNT_NOT_OPENED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_STATUS_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** 金额方向、审计和拒绝分支；真实事务回滚见 BuyerAdminIntegrationTest。
 * 设计：docs/superpowers/specs/2026-10-23-buyer-admin-balance-adjustment-design.md#6-自动化与联调验收。
 */
@SuppressWarnings("unchecked")
class BuyerAccountAdjustmentServiceTest {
    private final BuyerAccountMapper accounts = mock(BuyerAccountMapper.class);
    private final AccountLedgerWriter ledgers = mock(AccountLedgerWriter.class);
    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final BuyerAccountAdjustmentService service = new BuyerAccountAdjustmentService(clock, accounts, ledgers);

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), BuyerAccountDO.class);
        when(clock.now()).thenReturn(LocalDateTime.of(2026, 10, 23, 11, 0));
        when(accounts.update(isNull(), any(Wrapper.class))).thenReturn(1);
    }

    @ParameterizedTest
    @CsvSource({"1000,1500", "1000,600", "1000,0", "-1000,0"})
    void recordsSignedDifferenceAndPreservesFrozen(long before, long target) {
        when(accounts.selectByBuyerIdForUpdate(5L)).thenReturn(account(before, (short) 10));
        service.adjust(command(target));
        ArgumentCaptor<AccountLedgerWriter.LedgerEntry> capture = ArgumentCaptor.forClass(AccountLedgerWriter.LedgerEntry.class);
        verify(ledgers).append(capture.capture());
        var entry = capture.getValue();
        assertThat(entry.balanceBefore()).isEqualTo(before);
        assertThat(entry.balanceAfter()).isEqualTo(target);
        assertThat(entry.frozenBefore()).isEqualTo(800L);
        assertThat(entry.frozenAfter()).isEqualTo(800L);
        assertThat(entry.operatorId()).isEqualTo(8L);
        assertThat(entry.traceId()).isEqualTo("trace-adjust");
        assertThat(entry.evidenceDigest()).hasSize(32);
    }

    @Test
    void equalTargetDoesNotUpdateOrAppend() {
        when(accounts.selectByBuyerIdForUpdate(5L)).thenReturn(account(1000, (short) 10));
        service.adjust(command(1000));
        verify(accounts, never()).update(isNull(), any(Wrapper.class));
        verifyNoInteractions(ledgers);
    }

    @Test
    void missingAccountDoesNotOpenOrWriteLedger() {
        assertThatThrownBy(() -> service.adjust(command(0)))
                .isInstanceOf(ServiceException.class).hasMessage(BUYER_ACCOUNT_NOT_OPENED.getMsg());
        verifyNoInteractions(ledgers);
    }

    @ParameterizedTest
    @CsvSource({"20", "90"})
    void rejectsNonNormalAccount(short status) {
        when(accounts.selectByBuyerIdForUpdate(5L)).thenReturn(account(1000, status));
        assertThatThrownBy(() -> service.adjust(command(0)))
                .isInstanceOf(ServiceException.class).hasMessage(BUYER_STATUS_CONFLICT.getMsg());
        verifyNoInteractions(ledgers);
    }

    @Test
    void rejectsNegativeTarget() {
        assertThatThrownBy(() -> service.adjust(command(-1))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(accounts, ledgers);
    }

    @Test
    void rejectsMissingAuditIdentity() {
        for (var invalid : new AdjustmentCommand[]{
                new AdjustmentCommand(5L, 0, null, "trace"),
                new AdjustmentCommand(5L, 0, 8L, null),
                new AdjustmentCommand(5L, 0, 8L, " "),
                new AdjustmentCommand(null, 0, 8L, "trace")}) {
            assertThatThrownBy(() -> service.adjust(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(accounts, ledgers);
    }

    @Test
    void rejectsOverflowBeforeWriting() {
        when(accounts.selectByBuyerIdForUpdate(5L)).thenReturn(account(Long.MIN_VALUE, (short) 10));
        assertThatThrownBy(() -> service.adjust(command(0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("signed long");
        verify(accounts, never()).update(isNull(), any(Wrapper.class));
        verifyNoInteractions(ledgers);
    }

    @Test
    void casFailureDoesNotAppend() {
        when(accounts.selectByBuyerIdForUpdate(5L)).thenReturn(account(1000, (short) 10));
        when(accounts.update(isNull(), any(Wrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> service.adjust(command(0)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Concurrent");
        verifyNoInteractions(ledgers);
    }

    @Test
    void ledgerFailurePropagatesToOuterTransaction() {
        when(accounts.selectByBuyerIdForUpdate(5L)).thenReturn(account(1000, (short) 10));
        IllegalStateException failure = new IllegalStateException("ledger unavailable");
        doThrow(failure).when(ledgers).append(any());
        assertThatThrownBy(() -> service.adjust(command(0))).isSameAs(failure);
    }

    private static AdjustmentCommand command(long target) {
        return new AdjustmentCommand(5L, target, 8L, "trace-adjust");
    }

    private static BuyerAccountDO account(long balance, short status) {
        return BuyerAccountDO.builder().id(9L).buyerId(5L).balance(balance).frozen(800L)
                .status(status).version(3L).build();
    }
}
