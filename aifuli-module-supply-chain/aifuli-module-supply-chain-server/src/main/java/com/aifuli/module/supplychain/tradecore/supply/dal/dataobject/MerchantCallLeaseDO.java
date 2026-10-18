package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/** 成功准入保守占用；释放并发不退还滚动速率。设计：docs/superpowers/specs/2026-10-18-single-step-merchant-quota-design.md#6-准入算法与事务。 */
@TableName("sc_supplier_merchant_call_lease") @Data @Builder
public class MerchantCallLeaseDO {
    @TableId(type = IdType.AUTO) private Long id;
    private Long quotaId;
    private String operationNo;
    private Long executionGeneration;
    private LocalDateTime admittedAt;
    private LocalDateTime leaseUntil;
    private LocalDateTime releasedAt;
}
