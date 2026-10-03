package com.aifuli.module.supplychain.api.event.dto;

import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;

@Schema(description = "履约结果事件载荷；不包含履约目标、卡号、卡密、成本、路由或协议私有字段")
@Data
public final class FulfillmentResultEventData implements BuyerEventData {

    /** 供应链总订单稳定业务编号 */
    @Schema(description = "供应链总订单稳定业务编号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "SC_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String orderNo;

    /** 履约请求稳定业务编号 */
    @Schema(description = "履约请求稳定业务编号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "FR_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String fulfillmentRequestNo;

    /** 请求履约数量 */
    @Schema(description = "请求履约数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long requestedQuantity;

    /** 已经成功交付的数量 */
    @Schema(description = "已经成功交付的数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long deliveredQuantity;

    /** 履约请求状态：0待路由，10路由中，20履约中，40成功，50失败，90人工处理；一期不支持部分成功 */
    @Schema(description = "履约请求状态：0待路由，10路由中，20履约中，40成功，50失败，90人工处理；一期不支持部分成功", requiredMode = Schema.RequiredMode.REQUIRED, example = "SUCCEEDED")
    private FulfillmentStatus fulfillmentStatus;

    /** 订单进入业务终态时间，Asia/Shanghai */
    @Schema(description = "订单进入业务终态时间，Asia/Shanghai", example = "2026-08-11T02:31:00Z")
    private OffsetDateTime completedAt;

    /** 是否已有可授权查询的敏感资产 */
    @Schema(description = "是否已有可授权查询的敏感资产", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "true")
    private Boolean assetAvailable;

    /** 敏感资产查询引用；不包含资产内容 */
    @Schema(description = "敏感资产查询引用；不包含资产内容",
            example = "ASSET_REF_01K1XXXXXXXXXXXXXXXX")
    private String assetReference;
}
