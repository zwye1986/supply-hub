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

/** 分销商SKU通用定价身份。设计见 docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#5-版本继承与可售状态。 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
@TableName("sc_buyer_sku_pricing")
public class BuyerSkuPricingDO {
    /** 分销商SKU定价身份主键 */
    @TableId(type = IdType.AUTO) private Long id;
    /** 分销商品主键，逻辑引用sc_buyer_product.id */
    private Long buyerProductId;
    /** 当前通用定价版本主键，空表示未定价 */
    private Long currentVersionId;
    /** 定价状态：UNPRICED未定价，READY可售，RECALCULATING重算中，DEPENDENCY_MISSING依赖缺失，CALCULATION_FAILED计算失败，MARGIN_HIDDEN利润隐藏 */
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
