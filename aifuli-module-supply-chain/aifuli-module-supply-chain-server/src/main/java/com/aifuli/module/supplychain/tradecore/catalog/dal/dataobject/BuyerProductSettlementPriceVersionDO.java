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
 * 021 变更集已发布的模板 L2 不可变版本预留表映射，仅为保持数据库兼容而保留。
 * 本期应用服务不得读写本表，也不得把历史行作为有效 L2。
 * 设计见 {@code docs/superpowers/specs/2026-09-12-supply-chain-prd-scope-alignment-design.md#52-l2-与-follow_template}。
 */
@TableName("sc_buyer_product_settlement_price_version")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerProductSettlementPriceVersionDO {

    /** 历史模板 L2 版本主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 分销商品主键 */
    private Long buyerProductId;
    /** 从1递增 */
    private Integer versionNo;
    /** 历史模板 L2，CNY 分；本期业务不得读取 */
    private Long unitPriceCents;
    /** 币种CNY */
    private String currency;
    /** 发布时间，Asia/Shanghai */
    private LocalDateTime publishedAt;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
}
