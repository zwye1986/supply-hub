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
 * 项目商品不可变结算价版本。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#13-模块边界}。
 */
@TableName("sc_project_listing_version")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectListingVersionDO {

    /** 项目商品版本主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 项目商品主键 */
    private Long listingId;
    /** 从1递增 */
    private Integer versionNo;
    /** 冻结的B端结算单价，CNY分 */
    private Long buyerSettlementUnitPrice;
    /** L1 L2 L3 */
    private String settlementPriceLevel;
    /** INHERITED或PROJECT_OVERRIDE */
    private String priceSource;
    /** FOLLOW_L2 EQUAL_L1 CUSTOM */
    private String l3AdjustMode;
    /** CUSTOM百分比 */
    private Integer l3Percent;
    /** CUSTOM加减分 */
    private Long l3AmountCents;
    /** CNY */
    private String currency;
    /** 发布人 */
    private Long publishedBy;
    /** 发布时间，Asia/Shanghai */
    private LocalDateTime publishedAt;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
}
