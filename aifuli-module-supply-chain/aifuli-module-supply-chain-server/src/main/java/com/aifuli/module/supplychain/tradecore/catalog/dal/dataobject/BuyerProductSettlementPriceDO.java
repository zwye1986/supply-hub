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
 * 分销商品模板L2身份。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#13-模块边界}。
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
    /** 当前模板L2版本，空表示尚无模板 */
    private Long currentVersionId;
    /** 乐观锁版本号 */
    @Version
    private Long version;
    /** 最后修改时间，Asia/Shanghai */
    private LocalDateTime updatedAt;
}
