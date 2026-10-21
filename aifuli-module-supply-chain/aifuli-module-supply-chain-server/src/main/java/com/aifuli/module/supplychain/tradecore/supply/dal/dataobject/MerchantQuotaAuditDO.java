package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 商户额度真实操作追加事实；不补造存量创建事件，不提供更新/删除业务入口。
 * 设计：docs/superpowers/specs/2026-10-19-merchant-quota-admin-design.md#2-配置与审计原子性。
 */
@TableName("sc_supplier_merchant_quota_audit") @Data @Builder
public class MerchantQuotaAuditDO {
    @TableId(type = IdType.AUTO) private Long id;
    private Long quotaId;
    private Integer beforeRateLimitPerSecond;
    private Integer afterRateLimitPerSecond;
    private Integer beforeConcurrencyLimit;
    private Integer afterConcurrencyLimit;
    private Long quotaVersion;
    private Long operatorId;
    private String reason;
    private LocalDateTime occurredAt;
}
