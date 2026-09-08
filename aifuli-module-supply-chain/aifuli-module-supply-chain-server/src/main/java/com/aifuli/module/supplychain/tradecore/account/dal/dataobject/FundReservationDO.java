package com.aifuli.module.supplychain.tradecore.account.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("sc_fund_reservation")
@Data
@Builder
public class FundReservationDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String reservationNo;
    private Long accountId;
    private Long buyerId;
    private String referenceType;
    private String referenceNo;
    private Long totalAmount;
    private Long committedAmount;
    private Long releasedAmount;
    private Long remainingAmount;
    private String currency;
    private Short status;
    @Version
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
