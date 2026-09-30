package com.aifuli.module.supplychain.tradecore.account.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import com.aifuli.module.supplychain.tradecore.ledger.dal.dataobject.AccountLedgerDO;
import com.aifuli.module.supplychain.tradecore.ledger.dal.mysql.AccountLedgerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.INSTORE_ACCOUNT_RETURN_CONFLICT;

/**
 * 已扣采购资金返还的唯一入口：只拥有采购商账户与账本，不依赖 INSTORE 场景模块。
 *
 * <p>以 {@code REFUND_RETURN:{refundCaseNo}} 为账户范围内永久幂等键：锁定账户后先查账本既有流水，
 * 同键同金额幂等返回（不重复返还）；同键不同金额抛 {@code INSTORE_ACCOUNT_RETURN_CONFLICT}
 * （数据异常，整体回滚，RetryDirective.MANUAL 禁止自动重试）。
 * 追加 REFUND 账本（balance_delta &gt; 0、frozen_delta = 0）。
 * 调用方（INSTORE Lifecycle）必须在外层本地事务中调用本服务，任一步失败整笔回滚。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#45-生产事务与幂等不变量} 第 6 条。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerAccountReturnService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String ENTRY_TYPE_REFUND = "REFUND";
    private static final String ENTRY_KEY_PREFIX = "REFUND_RETURN:";

    private final BuyerAccountMapper accountMapper;
    private final AccountLedgerMapper ledgerMapper;
    private final AccountLedgerWriter ledgerWriter;

    /**
     * 返还已扣资金：锁定账户 → 幂等键查重 → 原子加余额 → 追加 REFUND 账本。
     *
     * <p>不变量：同 refundCaseNo 至多返还一次；余额只增不减；frozen 恒不变。
     * 返还幂等回放与冲突均不产生任何账本写入。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void returnFunds(ReturnCommand command) {
        validate(command);
        log.info("[ACCOUNT][return] 资金返还入口 buyerId={}, refundCaseNo={}, amount={}, orderNo={}, traceId={}",
                command.buyerId(), command.refundCaseNo(), command.amountCny(),
                command.orderNo(), command.traceId());
        BuyerAccountDO account = accountMapper.selectByBuyerIdForUpdate(command.buyerId());
        if (account == null) {
            throw new IllegalStateException("Buyer CNY account does not exist");
        }
        String entryKey = ENTRY_KEY_PREFIX + command.refundCaseNo();
        AccountLedgerDO existing = ledgerMapper.selectByEntryKey(account.getId(), entryKey);
        if (existing != null) {
            if (existing.getBalanceDelta() != null
                    && existing.getBalanceDelta() == command.amountCny()) {
                log.info("[ACCOUNT][return] 幂等回放跳过返还 refundCaseNo={}, amount={}",
                        command.refundCaseNo(), command.amountCny());
                return;
            }
            log.warn("[ACCOUNT][return] 幂等键金额冲突转人工 refundCaseNo={}, existing={}, requested={}",
                    command.refundCaseNo(), existing.getBalanceDelta(), command.amountCny());
            throw exception(INSTORE_ACCOUNT_RETURN_CONFLICT);
        }
        LocalDateTime now = now();
        if (accountMapper.atomicReturn(account.getId(), account.getVersion(),
                command.amountCny(), now) != 1) {
            throw new IllegalStateException("Concurrent account update");
        }
        ledgerWriter.append(new AccountLedgerWriter.LedgerEntry(account.getId(), entryKey,
                ENTRY_TYPE_REFUND, account.getBalance(), account.getBalance() + command.amountCny(),
                account.getFrozen(), account.getFrozen(), null, command.orderNo(),
                command.referenceType(), command.referenceNo(), null, null, null, null,
                command.traceId(), command.causationId(), now));
        log.info("[ACCOUNT][return] 资金返还完成 refundCaseNo={}, balanceBefore={}, balanceAfter={}",
                command.refundCaseNo(), account.getBalance(),
                account.getBalance() + command.amountCny());
    }

    private static void validate(ReturnCommand command) {
        if (command.buyerId() == null || command.refundCaseNo() == null
                || command.refundCaseNo().isBlank() || command.amountCny() <= 0
                || command.traceId() == null || command.traceId().isBlank()) {
            throw new IllegalArgumentException("Buyer account return command is invalid");
        }
    }

    private LocalDateTime now() {
        return databaseClock.now();
    }

    /** 返还命令：refundCaseNo 构成永久幂等键；referenceType/referenceNo 由调用方按资金原始引用传入。 */
    public record ReturnCommand(Long buyerId, String refundCaseNo, long amountCny,
                                String orderNo, String referenceType, String referenceNo,
                                String traceId, String causationId) {
    }
}
