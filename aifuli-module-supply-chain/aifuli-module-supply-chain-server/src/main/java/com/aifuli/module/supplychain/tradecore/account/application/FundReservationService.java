package com.aifuli.module.supplychain.tradecore.account.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.FundReservationDO;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.FundReservationMapper;
import com.aifuli.module.supplychain.tradecore.account.domain.FundReferenceType;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 采购商预付资金冻结、确认扣款与释放的唯一写入口。一期不检查可用余额，允许透支。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-17-supply-chain-phase1-buyer-account-overdraft.md}。</p>
 */
@Service
@RequiredArgsConstructor
public class FundReservationService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final Set<String> REFERENCE_TYPES = Arrays.stream(FundReferenceType.values())
            .map(FundReferenceType::code)
            .collect(Collectors.toUnmodifiableSet());
    private static final short RESERVED = 10;
    private static final short COMMITTED = 30;
    private static final short RELEASED = 40;

    private final BuyerAccountMapper accountMapper;
    private final FundReservationMapper reservationMapper;
    private final AccountLedgerWriter ledgerWriter;
    private final BusinessNoGenerator businessNoGenerator;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public ReservationSnapshot freeze(FreezeCommand command) {
        validateFreeze(command);
        FundReservationDO existing = reservationMapper.selectByReference(
                command.buyerId(), command.referenceType(), command.referenceNo());
        if (existing != null) {
            return requireSameFreeze(existing, command);
        }
        BuyerAccountDO account = accountMapper.selectByBuyerIdForUpdate(command.buyerId());
        if (account == null) {
            throw new IllegalStateException("Buyer CNY account does not exist");
        }
        existing = reservationMapper.selectByReferenceForUpdate(
                command.buyerId(), command.referenceType(), command.referenceNo());
        if (existing != null) {
            return requireSameFreeze(existing, command);
        }
        long amount = command.amount().cents();
        LocalDateTime now = now();
        if (accountMapper.atomicFreeze(account.getId(), account.getVersion(), amount, now) != 1) {
            throw new IllegalStateException("Concurrent account update");
        }
        FundReservationDO reservation = FundReservationDO.builder()
                .reservationNo(businessNoGenerator.next(BusinessNoPrefixes.FR)).accountId(account.getId())
                .buyerId(command.buyerId()).referenceType(command.referenceType())
                .referenceNo(command.referenceNo()).totalAmount(amount).committedAmount(0L)
                .releasedAmount(0L).remainingAmount(amount).currency("CNY")
                .status(RESERVED).version(0L).createdAt(now).updatedAt(now).build();
        reservationMapper.insert(reservation);
        ledgerWriter.append(new AccountLedgerWriter.LedgerEntry(account.getId(),
                "FREEZE:" + reservation.getReservationNo(), "FREEZE",
                account.getBalance(), account.getBalance(), account.getFrozen(), account.getFrozen() + amount,
                reservation.getReservationNo(), command.orderNo(), command.referenceType(),
                command.referenceNo(), null, null, null, null,
                command.traceId(), command.causationId(), now));
        return snapshot(reservation);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public ReservationSnapshot commit(String reservationNo, String orderNo,
                                      String traceId, String causationId) {
        requireTrace(traceId);
        FundReservationDO reservation = requireReservationForUpdate(reservationNo);
        if (reservation.getStatus() == COMMITTED) {
            return snapshot(reservation);
        }
        if (reservation.getStatus() != RESERVED) {
            throw new IllegalStateException("Fund reservation cannot be committed from current status");
        }
        long amount = reservation.getRemainingAmount();
        BuyerAccountDO account = requireAccountForUpdate(reservation.getAccountId());
        LocalDateTime now = now();
        if (accountMapper.atomicCommit(account.getId(), account.getVersion(), amount, now) != 1) {
            throw new IllegalStateException("Fund commit lost account invariant or optimistic lock");
        }
        reservation.setCommittedAmount(reservation.getCommittedAmount() + amount);
        reservation.setRemainingAmount(0L);
        reservation.setStatus(COMMITTED);
        reservation.setUpdatedAt(now);
        requireReservationUpdated(reservation);
        ledgerWriter.append(new AccountLedgerWriter.LedgerEntry(account.getId(),
                "COMMIT:" + reservationNo, "COMMIT",
                account.getBalance(), account.getBalance() - amount,
                account.getFrozen(), account.getFrozen() - amount,
                reservationNo, orderNo, reservation.getReferenceType(), reservation.getReferenceNo(),
                null, null, null, null, traceId, causationId, now));
        return snapshot(reservation);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public ReservationSnapshot release(String reservationNo, String orderNo,
                                       String traceId, String causationId) {
        requireTrace(traceId);
        FundReservationDO reservation = requireReservationForUpdate(reservationNo);
        if (reservation.getStatus() == RELEASED) {
            return snapshot(reservation);
        }
        if (reservation.getStatus() != RESERVED) {
            throw new IllegalStateException("Fund reservation cannot be released from current status");
        }
        long amount = reservation.getRemainingAmount();
        BuyerAccountDO account = requireAccountForUpdate(reservation.getAccountId());
        LocalDateTime now = now();
        if (accountMapper.atomicRelease(account.getId(), account.getVersion(), amount, now) != 1) {
            throw new IllegalStateException("Fund release lost account invariant or optimistic lock");
        }
        reservation.setReleasedAmount(reservation.getReleasedAmount() + amount);
        reservation.setRemainingAmount(0L);
        reservation.setStatus(RELEASED);
        reservation.setUpdatedAt(now);
        requireReservationUpdated(reservation);
        ledgerWriter.append(new AccountLedgerWriter.LedgerEntry(account.getId(),
                "RELEASE:" + reservationNo, "RELEASE",
                account.getBalance(), account.getBalance(),
                account.getFrozen(), account.getFrozen() - amount,
                reservationNo, orderNo, reservation.getReferenceType(), reservation.getReferenceNo(),
                null, null, null, null, traceId, causationId, now));
        return snapshot(reservation);
    }

    private static void validateFreeze(FreezeCommand command) {
        if (command.buyerId() == null || command.amount().cents() <= 0
                || !REFERENCE_TYPES.contains(command.referenceType())
                || command.referenceNo() == null || command.referenceNo().isBlank()
                || command.traceId() == null || command.traceId().isBlank()) {
            throw new IllegalArgumentException("Fund freeze command is invalid");
        }
    }

    private static ReservationSnapshot requireSameFreeze(FundReservationDO existing, FreezeCommand command) {
        if (existing.getTotalAmount() != command.amount().cents()
                || !existing.getCurrency().equals(command.amount().currency())) {
            throw new IllegalStateException("Fund reservation idempotency conflict");
        }
        return snapshot(existing);
    }

    private FundReservationDO requireReservationForUpdate(String reservationNo) {
        FundReservationDO reservation = reservationMapper.selectByNoForUpdate(reservationNo);
        if (reservation == null) {
            throw new IllegalArgumentException("Fund reservation does not exist: " + reservationNo);
        }
        return reservation;
    }

    private BuyerAccountDO requireAccountForUpdate(Long accountId) {
        BuyerAccountDO account = accountMapper.selectByIdForUpdate(accountId);
        if (account == null) {
            throw new IllegalStateException("Buyer account does not exist");
        }
        return account;
    }

    private void requireReservationUpdated(FundReservationDO reservation) {
        if (reservationMapper.updateById(reservation) != 1) {
            throw new IllegalStateException("Fund reservation update lost optimistic lock");
        }
    }

    private static ReservationSnapshot snapshot(FundReservationDO reservation) {
        return new ReservationSnapshot(reservation.getReservationNo(), reservation.getBuyerId(),
                reservation.getReferenceType(), reservation.getReferenceNo(), reservation.getTotalAmount(),
                reservation.getCommittedAmount(), reservation.getReleasedAmount(),
                reservation.getRemainingAmount(), reservation.getStatus(), reservation.getVersion());
    }

    private LocalDateTime now() {
        return databaseClock.now();
    }

    private static void requireTrace(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("Trace ID is required for fund mutation");
        }
    }

    public record FreezeCommand(Long buyerId, String referenceType, String referenceNo,
                                Money amount, String orderNo, String traceId, String causationId) {
    }

    public record ReservationSnapshot(String reservationNo, Long buyerId,
                                      String referenceType, String referenceNo,
                                      long totalAmount, long committedAmount,
                                      long releasedAmount, long remainingAmount,
                                      short status, long version) {
    }
}
