package com.aifuli.module.supplychain.tradecore.account.application;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.FundReservationDO;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.FundReservationMapper;
import com.aifuli.module.supplychain.tradecore.account.domain.FundReferenceType;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProjectDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 项目归属与资金身份分离，见 docs/superpowers/specs/2026-10-14-project-settlement-buyer-design.md#3-数据与资金。 */
@ExtendWith(MockitoExtension.class)
class ProjectSettlementFundsTest {
    @Mock BuyerAccountMapper accountMapper;
    @Mock FundReservationMapper reservationMapper;
    @Mock BuyerProjectMapper projectMapper;
    @Mock AccountLedgerWriter ledgerWriter;
    @Mock BusinessNoGenerator businessNoGenerator;
    @Mock SupplyChainDatabaseClock databaseClock;
    @InjectMocks FundReservationService service;

    @BeforeEach
    void setup() {
        lenient().when(projectMapper.selectById(9L)).thenReturn(BuyerProjectDO.builder()
                .id(9L).buyerId(1L).settlementBuyerId(2L).build());
        when(databaseClock.now()).thenReturn(LocalDateTime.of(2026, 10, 14, 12, 0));
        when(accountMapper.selectByBuyerIdForUpdate(anyLong())).thenAnswer(i -> BuyerAccountDO.builder()
                .id(i.<Long>getArgument(0) * 10).buyerId(i.getArgument(0))
                .balance(1000L).frozen(0L).version(0L).build());
        when(accountMapper.atomicFreeze(anyLong(), eq(0L), eq(100L), any())).thenReturn(1);
        when(businessNoGenerator.next(anyString())).thenReturn("FR_TEST");
    }

    @Test
    void projectFreezesSelectedAccountAndPersistsItsIdentity() {
        var result = service.freeze(command(9L));
        assertThat(result.buyerId()).isEqualTo(2L);
        verify(accountMapper, never()).selectByBuyerIdForUpdate(1L);
        var captor = org.mockito.ArgumentCaptor.forClass(FundReservationDO.class);
        verify(reservationMapper).insert(captor.capture());
        assertThat(captor.getValue().getAccountId()).isEqualTo(20L);
    }

    @Test
    void nullLegacyProjectSettlementFallsBackToOwner() {
        lenient().when(projectMapper.selectById(9L)).thenReturn(BuyerProjectDO.builder().id(9L).buyerId(1L).build());
        assertThat(service.freeze(command(9L)).buyerId()).isEqualTo(1L);
    }

    @Test
    void nonProjectFreezeKeepsOriginalBuyer() {
        assertThat(service.freeze(command(null)).buyerId()).isEqualTo(1L);
        verifyNoInteractions(projectMapper);
    }

    private static FundReservationService.FreezeCommand command(Long projectId) {
        return new FundReservationService.FreezeCommand(1L, FundReferenceType.CARD_ORDER.code(),
                "REQ_TEST", Money.cny(100L), "ORDER_TEST", "trace", "cause", projectId);
    }
}
