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

/** 平台SKU通用默认定价不可变版本。设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态。 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("sc_platform_sku_pricing_version")
public class PlatformSkuPricingVersionDO {
    /** 平台SKU默认定价版本主键 */
    @TableId(type = IdType.AUTO) private Long id;
    /** 平台SKU主键 */ private Long platformSkuId;
    /** 定价版本号，从1递增 */ private Integer versionNo;
    /** 商品五组默认定价完整配置JSON */ private String effectiveConfigJson;
    /** 计算时市场价快照，单位CNY分 */ private Long marketPriceCents;
    /** 计算时销售建议价快照，单位CNY分 */ private Long suggestedSalePriceCents;
    /** 计算时最低有效供货价快照，单位CNY分 */ private Long supplyPriceCents;
    /** 计算后默认商品分销单价，单位CNY分 */ private Long distributionPriceCents;
    /** 计算后默认商品销售单价，单位CNY分 */ private Long salesPriceCents;
    /** 单品分销利润率百分数快照 */ private BigDecimal distributionMarginPercent;
    /** 实际生效的利润隐藏阈值百分数 */ private BigDecimal effectiveHideThresholdPercent;
    /** 该版本定价状态 */ private String pricingStatus;
    /** 该版本用户端不可售原因编码 */ private String unavailableReason;
    /** 币种，一期固定CNY */ private String currency;
    /** 发布人用户ID；依赖重算可为空 */ private Long publishedBy;
    /** 发布时间，Asia/Shanghai */ private LocalDateTime publishedAt;
    /** 创建时间，Asia/Shanghai */ private LocalDateTime createdAt;
}
