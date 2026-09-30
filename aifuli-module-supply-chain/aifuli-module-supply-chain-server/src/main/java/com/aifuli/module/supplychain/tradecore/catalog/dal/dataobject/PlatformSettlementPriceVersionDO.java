package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 平台默认定价不可变版本。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#44-标品-sku-分页}。
 */
@TableName("sc_platform_settlement_price_version")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformSettlementPriceVersionDO {
    /** L1版本主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 平台SKU主键 */
    private Long platformSkuId;
    /** 从1递增 */
    private Integer versionNo;
    /** L1 B端结算单价，CNY分，允许0 */
    private Long unitPriceCents;
    /** 币种，一期CNY */
    private String currency;
    /** 发布人 */
    private Long publishedBy;
    /** 发布时间，Asia/Shanghai */
    private LocalDateTime publishedAt;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
}
