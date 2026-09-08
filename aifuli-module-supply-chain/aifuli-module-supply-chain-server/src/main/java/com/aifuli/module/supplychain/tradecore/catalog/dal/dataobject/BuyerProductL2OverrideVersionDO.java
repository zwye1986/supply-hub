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
 * 分销商品手工L2覆盖不可变版本。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#13-模块边界}。
 */
@TableName("sc_buyer_product_l2_override_version")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuyerProductL2OverrideVersionDO {

    /** 手工L2覆盖版本主键 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 分销商品主键 */
    private Long buyerProductId;
    /** 从1递增 */
    private Integer versionNo;
    /** EQUAL_L1或CUSTOM */
    private String adjustMode;
    /** CUSTOM时的整数百分比，100等于L1 */
    private Integer percent;
    /** CUSTOM时加减的CNY分 */
    private Long amountCents;
    /** 解析后的覆盖L2，CNY分 */
    private Long resolvedUnitPriceCents;
    /** 操作人 */
    private Long createdBy;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
}
