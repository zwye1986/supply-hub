package com.aifuli.module.supplychain.tradecore.account.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_ACCOUNT_NOT_OPENED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_STATUS_CONFLICT;

/**
 * 管理端采购商余额修正入口：设置目标总余额，并在同一档案事务追加差额流水。
 *
 * <p>账户行锁与锁后版本 CAS 保护写入一致性，冻结和预留保持不变；同值不动账。
 * 必须加入调用方的供应链事务，流水失败时连同档案一起回滚，不自动开户。
 * 服务端操作编号用于审计，不表示客户端重试幂等或旧页面覆盖已解决。
 * 设计见 {@code docs/superpowers/specs/2026-10-23-buyer-admin-balance-adjustment-design.md#3-事务分层与并发}。</p>
 */
@Service
@RequiredArgsConstructor
public class BuyerAccountAdjustmentService {

    private static final short ACCOUNT_STATUS_NORMAL = 10;
    private static final String ENTRY_TYPE = "ADJUSTMENT";
    private static final String REFERENCE_TYPE = "ADMIN_BALANCE_ADJUSTMENT";
    private static final String AUDIT_NOTE = "管理端分销商余额修正";

    private final SupplyChainDatabaseClock databaseClock;
    private final BuyerAccountMapper accountMapper;
    private final AccountLedgerWriter ledgerWriter;

    /**
     * 锁后计算差额并写账户及唯一账本，允许原余额透支、目标低于冻结额，但禁止负目标及差额溢出。
     * 审计依据由服务端操作事实生成，不冒充用户上传的财务审批文件。
     * 设计见 {@code docs/superpowers/specs/2026-10-23-buyer-admin-balance-adjustment-design.md#4-流水与审计}。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY,
            rollbackFor = Exception.class)
    public void adjust(AdjustmentCommand command) {
        if (command.buyerId() == null || command.targetBalanceCents() < 0
                || command.operatorId() == null || command.traceId() == null || command.traceId().isBlank()) {
            throw new IllegalArgumentException("Balance adjustment requires buyer, nonnegative target and audit identity");
        }
        BuyerAccountDO account = accountMapper.selectByBuyerIdForUpdate(command.buyerId());
        if (account == null) {
            throw exception(BUYER_ACCOUNT_NOT_OPENED);
        }
        if (account.getStatus() != ACCOUNT_STATUS_NORMAL) {
            throw exception(BUYER_STATUS_CONFLICT);
        }
        long before = account.getBalance();
        long after = command.targetBalanceCents();
        if (before == after) {
            return;
        }
        // Writer 按 after - before 计算发生额；先校验，避免 long 溢出变成错误方向的调账。
        try {
            Math.subtractExact(after, before);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Balance adjustment delta exceeds signed long range", overflow);
        }
        LocalDateTime now = databaseClock.now();
        if (accountMapper.update(null, new LambdaUpdateWrapper<BuyerAccountDO>()
                .eq(BuyerAccountDO::getId, account.getId())
                .eq(BuyerAccountDO::getStatus, ACCOUNT_STATUS_NORMAL)
                .eq(BuyerAccountDO::getVersion, account.getVersion())
                .set(BuyerAccountDO::getBalance, after)
                .set(BuyerAccountDO::getVersion, Math.addExact(account.getVersion(), 1L))
                .set(BuyerAccountDO::getUpdatedBy, command.operatorId())
                .set(BuyerAccountDO::getUpdatedAt, now)) != 1) {
            throw new IllegalStateException("Concurrent buyer account adjustment");
        }
        String operationNo = UUID.randomUUID().toString();
        String evidenceRef = REFERENCE_TYPE + ":" + operationNo;
        String evidence = evidenceRef + "|" + command.buyerId() + "|" + account.getId()
                + "|" + command.operatorId() + "|" + before + "|" + after
                + "|" + account.getFrozen() + "|" + account.getFrozen() + "|" + AUDIT_NOTE;
        ledgerWriter.append(new AccountLedgerWriter.LedgerEntry(account.getId(), evidenceRef, ENTRY_TYPE,
                before, after, account.getFrozen(), account.getFrozen(), null, null,
                REFERENCE_TYPE, operationNo, command.operatorId(), evidenceRef, sha256(evidence), AUDIT_NOTE,
                command.traceId(), operationNo, now));
    }

    private static byte[] sha256(String content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record AdjustmentCommand(Long buyerId, long targetBalanceCents, Long operatorId, String traceId) {
    }
}
