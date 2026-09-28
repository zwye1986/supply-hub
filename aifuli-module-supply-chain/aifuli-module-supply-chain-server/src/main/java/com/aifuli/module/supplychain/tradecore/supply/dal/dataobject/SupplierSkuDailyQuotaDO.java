package com.aifuli.module.supplychain.tradecore.supply.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 供应商对平台 SKU 的自然日累计供货配额配置，不保存用量；用量取权威订单事实。
 * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#4-每日累计配额与并发}。
 */
@TableName("sc_supplier_sku_daily_quota")
@Data
@Builder
public class SupplierSkuDailyQuotaDO {
    /** 供应商SKU自然日供货配额配置内部主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 供应商内部主键，逻辑引用sc_supplier.id。 */
    private Long supplierId;
    /** 平台SKU内部主键，逻辑引用sc_platform_sku.id。 */
    private Long platformSkuId;
    /** 供应商对该平台SKU的自然日累计供货数量上限。 */
    private Long dailyLimit;
    /** 乐观锁版本号。 */
    @Version
    private Long version;
    /** 创建人用户ID。 */
    private Long createdBy;
    /** 创建时间，Asia/Shanghai。 */
    private LocalDateTime createdAt;
    /** 最后修改人用户ID。 */
    private Long updatedBy;
    /** 最后修改时间，Asia/Shanghai。 */
    private LocalDateTime updatedAt;
}
