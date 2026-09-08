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
 * 平台 SKU 默认结算价身份。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#44-标品-sku-分页}。
 */
@TableName("sc_platform_settlement_price")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformSettlementPriceDO {
    /** 平台SKU主键，逻辑引用sc_platform_sku.id */
    @TableId(type = IdType.INPUT)
    private Long platformSkuId;
    /** 当前L1版本主键 */
    private Long currentVersionId;
    /** 乐观锁版本号 */
    @Version
    private Long version;
    /** 最后修改时间，Asia/Shanghai */
    private LocalDateTime updatedAt;
}
