package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 供应价基准建议价计算事实；人工公式仍由SKU持有，非READY的当前金额必须为空。
 * 设计见 docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#41-独立建议价计算状态。
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("sc_sku_suggested_price_state")
public class SkuSuggestedPriceStateDO {
    /** 平台SKU主键，逻辑引用sc_platform_sku.id */
    @TableId(type = IdType.INPUT) private Long platformSkuId;
    /** 建议价人工公式规范化摘要，不含服务端快照 */
    private String ruleDigest;
    /** 建议价计算状态：READY可用，DEPENDENCY_MISSING待计算，CALCULATION_FAILED计算失败 */
    private String calculationStatus;
    /** 当前有效销售建议价，单位CNY分；非READY为空 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Long effectivePriceCents;
    /** 本轮供应价基数快照，单位CNY分；缺失为空 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Long basePriceSnapshotCents;
    /** 最近一次成功销售建议价，仅供历史参考，单位CNY分 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private Long lastValidPriceCents;
    /** 最近一次成功建议价计算时间，Asia/Shanghai */
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private LocalDateTime lastValidCalculatedAt;
    /** 建议价不可用原因编码；READY为空 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private String unavailableReason;
    /** 候选当前供给版本下一有效期边界，Asia/Shanghai */
    @TableField(updateStrategy = FieldStrategy.ALWAYS) private LocalDateTime nextCheckAt;
    /** 建议价计算事实时间，Asia/Shanghai */
    private LocalDateTime calculatedAt;
    /** 建议价计算状态修订号，无变化不递增 */
    private Long revision;
}
