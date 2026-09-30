package com.aifuli.module.supplychain.tradecore.ledger.application;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.tradecore.ledger.dal.dataobject.AccountLedgerDO;
import com.aifuli.module.supplychain.tradecore.ledger.dal.mysql.AccountLedgerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;

/** 唯一账本的追加入口；不得提供更新或删除流水的方法。 */
@Component
@RequiredArgsConstructor
public class AccountLedgerWriter {

    private final AccountLedgerMapper ledgerMapper;
    private final BusinessNoGenerator businessNoGenerator;

    public void append(LedgerEntry entry) {
        ledgerMapper.insert(AccountLedgerDO.builder()
                .ledgerNo(businessNoGenerator.next("LED_")).accountId(entry.accountId())
                .entryKey(entry.entryKey()).entryType(entry.entryType())
                .balanceDelta(entry.balanceAfter() - entry.balanceBefore())
                .frozenDelta(entry.frozenAfter() - entry.frozenBefore())
                .balanceBefore(entry.balanceBefore()).balanceAfter(entry.balanceAfter())
                .frozenBefore(entry.frozenBefore()).frozenAfter(entry.frozenAfter())
                .reservationNo(entry.reservationNo()).orderNo(entry.orderNo())
                .referenceType(entry.referenceType()).referenceNo(entry.referenceNo())
                .operatorId(entry.operatorId()).evidenceRef(entry.evidenceRef())
                .evidenceDigest(entry.evidenceDigest()).auditNote(entry.auditNote())
                .traceId(entry.traceId()).causationId(entry.causationId())
                .occurredAt(entry.occurredAt()).createdAt(entry.occurredAt()).build());
    }

    public record LedgerEntry(Long accountId, String entryKey, String entryType,
                              long balanceBefore, long balanceAfter,
                              long frozenBefore, long frozenAfter,
                              String reservationNo, String orderNo,
                              String referenceType, String referenceNo,
                              Long operatorId, String evidenceRef, byte[] evidenceDigest,
                              String auditNote, String traceId, String causationId,
                              LocalDateTime occurredAt) {
        public LedgerEntry {
            evidenceDigest = evidenceDigest == null
                    ? null : Arrays.copyOf(evidenceDigest, evidenceDigest.length);
        }
    }
}
