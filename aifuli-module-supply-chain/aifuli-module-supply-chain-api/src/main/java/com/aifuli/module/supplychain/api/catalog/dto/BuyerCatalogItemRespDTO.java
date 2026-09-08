package com.aifuli.module.supplychain.api.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "采购商可见的标准商品快照；不包含供应商、外部 SKU、供应成本或库存原始数量")
@Data
public class BuyerCatalogItemRespDTO implements Serializable {

    @Schema(description = "采购商商品编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "TELCO-100")
    private String buyerSkuCode;

    @Schema(description = "采购商商品发布版本", requiredMode = Schema.RequiredMode.REQUIRED, example = "42")
    private Long listingVersion;

    @Schema(description = "标准 SKU 编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "TELCO_100")
    private String platformSkuCode;

    @Schema(description = "标准 SPU 编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "TELCO_RECHARGE")
    private String platformSpuCode;

    @Schema(description = "商品展示名称", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "话费直充 100 元")
    private String displayName;

    @Schema(description = "品牌编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "CMCC")
    private String brandCode;

    @Schema(description = "品牌名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "中国移动")
    private String brandName;

    @Schema(description = "类目编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "DIRECT_RECHARGE")
    private String categoryCode;

    @Schema(description = "类目名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "话费直充")
    private String categoryName;

    @Schema(description = "履约类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "DIRECT_RECHARGE")
    private String fulfillmentType;

    @Schema(description = "采购商结算单价，单位为 CNY 分", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "9800")
    private Long buyerSettlementUnitPrice;

    @Schema(description = "币种，一期固定 CNY", requiredMode = Schema.RequiredMode.REQUIRED, example = "CNY")
    private String currency;

    @Schema(description = "采购商商品生命周期", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "PUBLISHED")
    private String listingStatus;

    @Schema(description = "供给可用性", requiredMode = Schema.RequiredMode.REQUIRED, example = "AVAILABLE")
    private String availabilityStatus;

    @Schema(description = "可用性投影版本", requiredMode = Schema.RequiredMode.REQUIRED, example = "7")
    private Long availabilityVersion;

    @Schema(description = "快照最后更新时间", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:30:00Z")
    private OffsetDateTime updatedAt;
}
