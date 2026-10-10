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
    private LocalDateTime updatedAt;
    /** 目录管理模式；AUTO读侧遵守投影过期与价格来源匹配规则，MANUAL保持旧RPC准入行为。 */
    private String managementMode;
    /** 投影最近成功评估时间；AUTO过期判定用。 */
    private LocalDateTime evaluatedAt;
    /** 投影下次必须重建时间；AUTO为空或不超过库时钟视为过期。 */
    private LocalDateTime validUntil;
    /** 商业版本来源买方定价版本身份；AUTO与买方定价当前版本不一致时不得返回旧AVAILABLE。 */
    private Long sourceBuyerPricingVersionId;
    /** 买方定价当前版本身份；用于来源匹配判定。 */
    private Long buyerPricingCurrentVersionId;
}
