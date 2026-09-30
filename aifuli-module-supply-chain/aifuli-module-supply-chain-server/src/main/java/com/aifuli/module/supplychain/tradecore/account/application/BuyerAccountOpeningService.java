package com.aifuli.module.supplychain.tradecore.account.application;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.tradecore.account.dal.dataobject.BuyerAccountDO;
import com.aifuli.module.supplychain.tradecore.account.dal.mysql.BuyerAccountMapper;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;

/** M1 唯一允许写入采购商期初余额的入口；一期允许期初为 0。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-17-supply-chain-phase1-buyer-account-overdraft.md}。</p>
 */
@Service
@RequiredArgsConstructor
public class BuyerAccountOpeningService {

    private final SupplyChainDatabaseClock databaseClock;

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final BuyerMapper buyerMapper;
    private final BuyerAccountMapper accountMapper;
    private final AccountLedgerWriter ledgerWriter;
    private final BusinessNoGenerator businessNoGenerator;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public AccountSnapshot open(OpeningBalanceCommand command) {
        validate(command);
        BuyerDO buyer = buyerMapper.selectByBuyerCodeForUpdate(command.buyerCode());
        if (buyer == null || buyer.getStatus() != BuyerStatus.ACTIVE.getCode()) {
            throw new IllegalArgumentException("Active buyer does not exist: " + command.buyerCode());
        }
        // 幂等：账户已存在则直接回放已有快照，避免重复期初入账。
        BuyerAccountDO existing = accountMapper.selectByBuyerIdForUpdate(buyer.getId());
        if (existing != null) {
            return snapshot(existing);
        }
        LocalDateTime now = databaseClock.now();
        BuyerAccountDO account = BuyerAccountDO.builder()
                .accountNo(businessNoGenerator.next("ACC_")).buyerId(buyer.getId()).currency("CNY")
                .balance(command.initialBalance().cents()).frozen(0L).status((short) 10).version(0L)
                .createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException duplicate) {
            // 并发首开竞争：唯一索引裁决后回放已落库的账户。
            BuyerAccountDO retry = accountMapper.selectByBuyerIdForUpdate(buyer.getId());
            if (retry == null) {
                throw duplicate;
            }
            return snapshot(retry);
        }
        ledgerWriter.append(new AccountLedgerWriter.LedgerEntry(account.getId(),
                "OPENING_BALANCE:" + buyer.getId() + ":CNY", "DEPOSIT",
                0L, command.initialBalance().cents(), 0L, 0L,
                null, null, "ACCOUNT_OPENING", buyer.getBuyerCode(), command.operatorId(),
                command.evidenceRef(), command.evidenceDigest(), command.auditNote(),
                command.traceId(), command.causationId(), now));
        return snapshot(account);
    }

    private static void validate(OpeningBalanceCommand command) {
        if (command.operatorId() == null
                || command.evidenceRef() == null || command.evidenceRef().isBlank()
                || command.evidenceDigest() == null || command.evidenceDigest().length != 32
                || command.auditNote() == null || command.auditNote().isBlank()
                || command.traceId() == null || command.traceId().isBlank()) {
            throw new IllegalArgumentException("Opening balance requires complete audit evidence");
        }
    }

    private static AccountSnapshot snapshot(BuyerAccountDO account) {
        return new AccountSnapshot(account.getAccountNo(), account.getBuyerId(), account.getBalance(),
                account.getFrozen(), account.getBalance() - account.getFrozen(), account.getVersion());
    }

    public record OpeningBalanceCommand(String buyerCode, Money initialBalance, Long operatorId,
                                        String evidenceRef, byte[] evidenceDigest, String auditNote,
                                        String traceId, String causationId) {
        public OpeningBalanceCommand {
            evidenceDigest = evidenceDigest == null ? null : Arrays.copyOf(evidenceDigest, evidenceDigest.length);
        }
    }

    public record AccountSnapshot(String accountNo, Long buyerId, long balance,
                                  long frozen, long available, long version) {
    }
}
