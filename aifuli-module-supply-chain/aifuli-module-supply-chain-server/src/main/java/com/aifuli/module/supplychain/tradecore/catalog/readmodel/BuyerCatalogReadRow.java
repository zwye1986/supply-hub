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
}
