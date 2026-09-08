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
 * 加价模板发布的L2不可变版本。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#13-模块边界}。
 */
@TableName("sc_buyer_product_settlement_price_version")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerProductSettlementPriceVersionDO {

    /** 模板L2版本主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 分销商品主键 */
    private Long buyerProductId;
    /** 从1递增 */
    private Integer versionNo;
    /** 模板L2，CNY分 */
    private Long unitPriceCents;
    /** 币种CNY */
    private String currency;
    /** 发布时间，Asia/Shanghai */
    private LocalDateTime publishedAt;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
}
