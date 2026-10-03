package com.aifuli.module.supplychain.api.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "供应链订单详情结果；详情模块不可用时仍返回成功的总表摘要")
@Data
public class SupplyChainOrderDetailRespDTO implements Serializable {

    /** 供应链总订单摘要 */
    @Schema(description = "供应链总订单摘要", requiredMode = Schema.RequiredMode.REQUIRED)
    private SupplyChainOrderSummaryRespDTO summary;

    /** 详情 Schema 版本 */
    @Schema(description = "详情 Schema 版本", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
    private Integer schemaVersion;

    /** 场景详情可用性；不可用不改变总订单查询的成功语义 */
    @Schema(description = "详情可用性", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "AVAILABLE")
    private DetailAvailability detailAvailability;

    /** 详情不可用时固定为 DETAIL_UNAVAILABLE；可用时为空 */
    @Schema(description = "详情不可用时固定为 DETAIL_UNAVAILABLE；可用时为空",
            example = "DETAIL_UNAVAILABLE")
    private String detailErrorCode;

    /** CARD 标准商品履约详情；仅 orderType=CARD 且详情可用时返回 */
    @Schema(description = "CARD 标准商品履约详情；仅 orderType=CARD 且详情可用时返回")
    private CardFulfillmentOrderDetailRespDTO cardFulfillment;
}
