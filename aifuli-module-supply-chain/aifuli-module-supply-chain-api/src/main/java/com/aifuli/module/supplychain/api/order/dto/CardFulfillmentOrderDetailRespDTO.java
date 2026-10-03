package com.aifuli.module.supplychain.api.order.dto;

import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "CARD 标准商品履约详情；不包含履约目标、路由候选、成本或敏感资产")
@Data
public class CardFulfillmentOrderDetailRespDTO implements Serializable {

    /** 履约请求稳定业务编号 */
    @Schema(description = "履约请求稳定业务编号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "FR_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String fulfillmentRequestNo;

    /** 采购商范围内的商品编码 */
    @Schema(description = "采购商范围内的商品编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "TELCO-100")
    private String buyerSkuCode;

    /** 受理时冻结的采购商商品版本主键 */
    @Schema(description = "受理时冻结的采购商商品版本主键", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "42")
    private Long listingVersion;

    /** 请求履约数量 */
    @Schema(description = "请求履约数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long requestedQuantity;

    /** 已经成功交付的数量 */
    @Schema(description = "已经成功交付的数量", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    private Long deliveredQuantity;

    /** 履约请求状态：0待路由，10路由中，20履约中，40成功，50失败，90人工处理；一期不支持部分成功 */
    @Schema(description = "履约请求状态：0待路由，10路由中，20履约中，40成功，50失败，90人工处理；一期不支持部分成功", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "PROCESSING")
    private FulfillmentStatus fulfillmentStatus;

    /** 是否已有可授权查询的敏感资产 */
    @Schema(description = "是否已有可授权查询的敏感资产", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "false")
    private Boolean assetAvailable;

    /** 履约请求最后更新时间，Asia/Shanghai */
    @Schema(description = "履约请求最后更新时间，Asia/Shanghai", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-11T02:31:00Z")
    private OffsetDateTime updatedAt;
}
