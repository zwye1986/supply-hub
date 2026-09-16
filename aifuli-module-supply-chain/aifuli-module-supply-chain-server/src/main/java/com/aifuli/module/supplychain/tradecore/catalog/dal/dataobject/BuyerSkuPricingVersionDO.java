package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 分销商SKU通用定价不可变版本。设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态。 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("sc_buyer_sku_pricing_version")
public class BuyerSkuPricingVersionDO {
    /** 分销商SKU通用定价版本主键 */
    @TableId(type = IdType.AUTO) private Long id;
    /** 分销商SKU定价身份主键，逻辑引用sc_buyer_sku_pricing.id */
    private Long pricingId;
    /** 定价版本号，从1递增 */
    private Integer versionNo;
    /** 分销价规则快照JSON */
    private String distributionRuleJson;
    /** 销售价规则快照JSON */
    private String salesRuleJson;
    /** 服务费规则与文案快照JSON */
    private String serviceFeeRuleJson;
    /** 分销区和销售区利润隐藏阈值快照JSON */
    private String hideRuleJson;
    /** 税务展示属性快照JSON */
    private String taxMetadataJson;
    /** 计算时市场价快照，单位CNY分 */
    private Long marketPriceCents;
    /** 计算时销售建议价快照，单位CNY分 */
    private Long suggestedSalePriceCents;
    /** 计算时最低有效供货价快照，单位CNY分；无有效报价时为空 */
    private Long supplyPriceCents;
    /** 计算后的商品分销单价，单位CNY分；依赖缺失时为空 */
    private Long distributionPriceCents;
    /** 计算后的商品销售单价，单位CNY分；依赖缺失时为空 */
    private Long salesPriceCents;
    /** 单品分销利润率百分数快照 */
    private BigDecimal distributionMarginPercent;
    /** 实际生效的利润隐藏阈值百分数 */
    private BigDecimal effectiveHideThresholdPercent;
    /** 该版本定价状态 */
    private String pricingStatus;
    /** 该版本用户端不可售原因编码 */
    private String unavailableReason;
    /** 币种，一期固定CNY */
    private String currency;
    /** 发布人用户ID；依赖重算可为空 */
    private Long publishedBy;
    /** 发布时间，Asia/Shanghai */
    private LocalDateTime publishedAt;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
}
