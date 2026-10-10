package com.aifuli.module.supplychain.tradecore.catalog.readmodel;

import lombok.Data;

import java.time.LocalDateTime;

/** 采购商商品读模型的内部行；不包含任何供应商或供应成本字段。 */
@Data
public class BuyerCatalogReadRow {
    private Long listingId;
    private String buyerSkuCode;
    private Short listingStatus;
    private Integer listingVersion;
    private String platformSkuCode;
    private String platformSpuCode;
    private String displayName;
    private String brandCode;
    private String brandName;
    private String categoryCode;
    private String categoryName;
    private String fulfillmentType;
    private Long buyerSettlementUnitPrice;
    private String currency;
    private Short availability;
    private Long availabilityVersion;
    /** 分销商SKU定价状态；无身份行时为空，目录保持历史 UNKNOWN */
    private String pricingStatus;
    /** 目录管理模式：MANUAL 或 AUTO_SELECTION */
    private String managementMode;
    /** 目录版本引用的分销商定价版本主键 */
    private Long sourceBuyerPricingVersionId;
    /** 当前分销商SKU定价当前版本主键 */
    private Long currentPricingVersionId;
    /** 供给可用性投影下次必须重建时间 */
    private LocalDateTime validUntil;
    private LocalDateTime updatedAt;
}
