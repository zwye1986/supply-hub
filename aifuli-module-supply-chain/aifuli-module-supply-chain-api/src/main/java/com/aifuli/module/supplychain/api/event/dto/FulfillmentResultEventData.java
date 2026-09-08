package com.aifuli.module.supplychain.api.event.dto;

import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Schema(description = "履约结果事件载荷；不包含履约目标、卡号、卡密、成本、路由或协议私有字段")
@Data
public final class FulfillmentResultEventData implements BuyerEventData {

    @Schema(description = "供应链中台订单号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SC_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String orderNo;

    @Schema(description = "履约请求号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "FR_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String fulfillmentRequestNo;

    @Schema(description = "请求履约数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long requestedQuantity;

    @Schema(description = "已确认交付数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long deliveredQuantity;

    @Schema(description = "标准履约状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "SUCCEEDED")
    private FulfillmentStatus fulfillmentStatus;

    @Schema(description = "履约完成时间", example = "2026-08-11T02:31:00Z")
    private OffsetDateTime completedAt;

    @Schema(description = "是否已有可授权查询的敏感资产", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "true")
    private Boolean assetAvailable;

    @Schema(description = "敏感资产查询引用；不包含资产内容",
            example = "ASSET_REF_01K1XXXXXXXXXXXXXXXX")
    private String assetReference;
}
