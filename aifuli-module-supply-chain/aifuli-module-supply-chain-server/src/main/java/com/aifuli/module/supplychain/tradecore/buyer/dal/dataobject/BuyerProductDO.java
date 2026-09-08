package com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject;

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
 * 采购商对平台SKU的分销商品身份。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md}。
 */
@TableName("sc_buyer_product")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerProductDO {
    /** 分销商品身份主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 分销商品稳定业务编码 */
    private String buyerProductCode;
    /** 采购商内部主键，逻辑引用sc_buyer.id */
    private Long buyerId;
    /** 平台SKU主键 */
    private Long platformSkuId;
    /** 利润空间低于该百分比时C端隐藏，本批不消费 */
    private Integer displayThresholdPercent;
    /** 10有效 90已移除 */
    private Short status;
    @Version
    private Long version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
