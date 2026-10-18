package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/** 商户当前统一额度；精确账号不随版本冻结。设计：docs/superpowers/specs/2026-10-18-single-step-merchant-quota-design.md#5-数据结构与归属。 */
@TableName("sc_supplier_merchant_quota") @Data @Builder
public class MerchantQuotaDO {
    @TableId(type = IdType.AUTO) private Long id;
    private String platformCode;
    private String merchantAccount;
    private Integer rateLimitPerSecond;
    private Integer concurrencyLimit;
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
    private String changeReason;
}
