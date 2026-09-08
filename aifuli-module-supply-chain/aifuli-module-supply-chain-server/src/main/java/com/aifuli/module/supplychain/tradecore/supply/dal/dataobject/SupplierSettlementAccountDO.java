package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@TableName("sc_supplier_settlement_account")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierSettlementAccountDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String accountNo;
    private Long supplierId;
    private String currency;
    private Long balance;
    private Long frozen;
    private Short status;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
