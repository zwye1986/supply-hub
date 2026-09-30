package com.aifuli.module.supplychain.api.fulfillment.dto;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "标准履约受理回执；ACCEPTED 只表示中台已承担履约责任，不表示供应商已履约")
@Data
public class FulfillmentAcceptanceReceiptRespDTO implements Serializable {

    @Schema(description = "本次调用请求标识", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "REQ_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String requestId;

    @Schema(description = "永久业务号，即采购商订单号", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "C202608110001")
    private String businessNo;

    @Schema(description = "受理回执状态", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "ACCEPTED")
    private FulfillmentReceiptStatus receiptStatus;

    @Schema(description = "回执 CAS 版本", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long receiptVersion;

    @Schema(description = "供应链中台订单号；处理中或拒绝时可为空",
            example = "SC_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String orderNo;

    @Schema(description = "总订单公共状态；尚未建单时为空", example = "PROCESSING")
    private SupplyChainOrderStatus orderStatus;

    @Schema(description = "履约请求号；尚未建单时为空",
            example = "FR_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String fulfillmentRequestNo;

    @Schema(description = "拒绝原因稳定错误码，处理中或已受理时为空", example = "USER_PURCHASE_LIMIT_EXCEEDED")
    private String rejectionCode;

    @Schema(description = "列表展示供应商编码；尚未路由时为空", example = "QNM")
    private String supplierCode;

    @Schema(description = "列表展示供应商历史名称；尚未路由时为空", example = "奇那梦")
    private String supplierName;

    @Schema(description = "中台受理完成时间；未受理时为空", example = "2026-08-11T02:30:01Z")
    private OffsetDateTime acceptedAt;

    @Schema(description = "是否为永久业务键的幂等重放", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "false")
    private Boolean idempotentReplay;
}
