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
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProjectMapper;
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
    public static final short RESERVED = 10;
    public static final short COMMITTED = 30;
    public static final short RELEASED = 40;

    private final BuyerAccountMapper accountMapper;
    private final BuyerProjectMapper projectMapper;
    private final FundReservationMapper reservationMapper;
    private final AccountLedgerWriter ledgerWriter;
    private final BusinessNoGenerator businessNoGenerator;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public ReservationSnapshot freeze(FreezeCommand command) {
        validateFreeze(command);
        // 鉴权/目录使用项目所属 Buyer，资金单独绑定结算 Buyer，随后预留是不可变资金身份。
        // 设计：docs/superpowers/specs/2026-10-14-project-settlement-buyer-design.md#3-数据与资金。
        if (command.projectId() != null) {
            Long settlementBuyerId = resolveSettlementBuyer(command.projectId(), command.buyerId());
            command = new FreezeCommand(settlementBuyerId, command.referenceType(), command.referenceNo(),
                    command.amount(), command.orderNo(), command.traceId(), command.causationId());
        }
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

    /**
     * 项目资金入口只解析本模块项目，校验归属后读取结算方；历史 NULL 回退原关联方。
     * 非项目资金仍使用原 Buyer，不能以选择结算方改变项目鉴权和商品归属。
     * 设计：docs/superpowers/specs/2026-10-14-project-settlement-buyer-design.md#3-数据与资金。
     */
    private Long resolveSettlementBuyer(Long projectId, Long ownerBuyerId) {
        var project = projectMapper.selectById(projectId);
        if (project == null || !java.util.Objects.equals(project.getBuyerId(), ownerBuyerId)) {
            throw new IllegalStateException("Project funding owner mismatch or project missing");
        }
        return project.getSettlementBuyerId() == null ? ownerBuyerId : project.getSettlementBuyerId();
    }

    /**
     * 退款返还原支付预留所属账户，禁止按当前项目配置或关联分销商重新推断。
     * 必须在退款事务内读取并锁定原预留，缺失时终止而非向其他账户返还。
     * 设计：docs/superpowers/specs/2026-10-14-project-settlement-buyer-design.md#3-数据与资金。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER,
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Long requireBuyerIdForReservation(String reservationNo) {
        return requireReservationForUpdate(reservationNo).getBuyerId();
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public ReservationSnapshot commit(String reservationNo, String orderNo,
                                      String traceId, String causationId) {
        requireTrace(traceId);
        FundReservationDO reservation = requireReservationForUpdate(reservationNo);
        return commitInternal(reservation, orderNo, traceId, causationId);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public ReservationSnapshot commitByReference(Long buyerId, String referenceType, String referenceNo,
                                                 String orderNo, String traceId, String causationId) {
        requireTrace(traceId);
        FundReservationDO reservation = reservationMapper.selectByReferenceForUpdate(buyerId, referenceType, referenceNo);
        if (reservation == null) {
            throw new IllegalArgumentException("Fund reservation does not exist for reference: " + referenceType + ":" + referenceNo);
        }
        return commitInternal(reservation, orderNo, traceId, causationId);
    }

    private ReservationSnapshot commitInternal(FundReservationDO reservation, String orderNo,
                                               String traceId, String causationId) {
        String reservationNo = reservation.getReservationNo();
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
        return releaseInternal(reservation, orderNo, traceId, causationId);
    }

    /**
     * 管理端只读预留快照（不加行锁）；不能作为关单事务内的动账前检查。
     * 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#52-可信取消失败的锁后检查。
     */
    public ReservationSnapshot findSnapshotByReference(Long buyerId, String referenceType, String referenceNo) {
        FundReservationDO reservation = reservationMapper.selectByReference(buyerId, referenceType, referenceNo);
        return reservation == null ? null : snapshot(reservation);
    }

    /**
     * 关单事务内锁定预留并读取最新组合，锁持有至订单与释放共同提交；禁止脱离调用者事务。
     * 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#7-持久化与协议兼容。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER,
            propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public ReservationSnapshot lockSnapshotByReference(Long buyerId, String referenceType, String referenceNo) {
        FundReservationDO reservation = reservationMapper.selectByReferenceForUpdate(buyerId, referenceType, referenceNo);
        return reservation == null ? null : snapshot(reservation);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public ReservationSnapshot releaseByReference(Long buyerId, String referenceType, String referenceNo,
                                                  String orderNo, String traceId, String causationId) {
        requireTrace(traceId);
        FundReservationDO reservation = reservationMapper.selectByReferenceForUpdate(buyerId, referenceType, referenceNo);
        if (reservation == null) {
            throw new IllegalArgumentException("Fund reservation does not exist for reference: " + referenceType + ":" + referenceNo);
        }
        return releaseInternal(reservation, orderNo, traceId, causationId);
    }

    private ReservationSnapshot releaseInternal(FundReservationDO reservation, String orderNo,
                                                String traceId, String causationId) {
        String reservationNo = reservation.getReservationNo();
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
            reservation = reservationMapper.selectOneForUpdate(FundReservationDO::getReferenceNo, reservationNo);
        }
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
                                Money amount, String orderNo, String traceId, String causationId,
                                Long projectId) {
        /** 非项目历史入口保持原账户身份。 */
        public FreezeCommand(Long buyerId, String referenceType, String referenceNo,
                             Money amount, String orderNo, String traceId, String causationId) {
            this(buyerId, referenceType, referenceNo, amount, orderNo, traceId, causationId, null);
        }
    }

    public record ReservationSnapshot(String reservationNo, Long buyerId,
                                      String referenceType, String referenceNo,
                                      long totalAmount, long committedAmount,
                                      long releasedAmount, long remainingAmount,
                                      short status, long version) {
    }
}
