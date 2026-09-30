package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("sc_supplier_settlement_ledger")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierSettlementLedgerDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ledgerNo;
    private Long accountId;
    private String entryKey;
    private String entryType;
    private Long balanceDelta;
    private Long frozenDelta;
    private Long balanceBefore;
    private Long balanceAfter;
    private Long frozenBefore;
    private Long frozenAfter;
    private Long operatorId;
    private String evidenceRef;
    private byte[] evidenceDigest;
    private String auditNote;
    private String traceId;
    private String causationId;
    private LocalDateTime occurredAt;
    private LocalDateTime createdAt;
}
