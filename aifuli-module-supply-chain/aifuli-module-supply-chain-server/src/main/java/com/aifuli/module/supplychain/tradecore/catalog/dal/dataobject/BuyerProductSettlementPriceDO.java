package com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 021 变更集已发布的分销商品模板 L2 预留身份，仅为保持数据库兼容而保留。
 * 本期应用服务不得读写本表，也不得把历史行作为有效 L2。
 * 设计见 {@code docs/superpowers/specs/2026-09-12-supply-chain-prd-scope-alignment-design.md#52-l2-与-follow_template}。
 */
@TableName("sc_buyer_product_settlement_price")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerProductSettlementPriceDO {

    /** 分销商品主键 */
    @TableId(type = IdType.INPUT)
    private Long buyerProductId;
    /** 历史预留的当前模板 L2 版本；本期业务不得读取 */
    private Long currentVersionId;
    /** 乐观锁版本号 */
    @Version
    private Long version;
    /** 最后修改时间，Asia/Shanghai */
    private LocalDateTime updatedAt;
}
