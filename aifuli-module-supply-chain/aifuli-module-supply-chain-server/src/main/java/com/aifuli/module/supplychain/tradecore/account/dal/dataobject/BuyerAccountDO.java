package com.aifuli.module.supplychain.tradecore.account.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_buyer_account")
@Data
@Builder
public class BuyerAccountDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String accountNo;
    private Long buyerId;
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
