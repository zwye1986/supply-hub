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

/** 平台SKU通用默认定价身份。设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态。 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("sc_platform_sku_pricing")
public class PlatformSkuPricingDO {
    /** 平台SKU主键，逻辑引用sc_platform_sku.id */
    @TableId(type = IdType.INPUT) private Long platformSkuId;
    /** 当前商品默认定价版本主键，空表示未定价 */
    private Long currentVersionId;
    /** 定价状态 */
    private String pricingStatus;
    /** 用户端不可售原因编码 */
    private String unavailableReason;
    /** 乐观锁版本号 */
    @Version private Long version;
    /** 创建时间，Asia/Shanghai */
    private LocalDateTime createdAt;
    /** 最后修改时间，Asia/Shanghai */
    private LocalDateTime updatedAt;
}
