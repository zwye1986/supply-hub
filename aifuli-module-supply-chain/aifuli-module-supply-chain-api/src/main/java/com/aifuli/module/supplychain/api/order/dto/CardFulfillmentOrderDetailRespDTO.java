package com.aifuli.module.supplychain.api.order.dto;

import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "CARD 标准商品履约详情；不包含履约目标、路由候选、成本或敏感资产")
@Data
public class CardFulfillmentOrderDetailRespDTO implements Serializable {

    @Schema(description = "履约请求号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "FR_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String fulfillmentRequestNo;

    @Schema(description = "采购商商品编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "TELCO-100")
    private String buyerSkuCode;

    @Schema(description = "受理时采购商商品发布版本", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "42")
    private Long listingVersion;

    @Schema(description = "请求履约数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long requestedQuantity;

    @Schema(description = "已确认交付数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Long deliveredQuantity;

    @Schema(description = "标准履约状态", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "PROCESSING")
    private FulfillmentStatus fulfillmentStatus;

    @Schema(description = "是否已有可授权查询的敏感资产", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "false")
    private Boolean assetAvailable;

    @Schema(description = "履约详情最后更新时间", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:31:00Z")
    private OffsetDateTime updatedAt;
}
