package com.aifuli.module.supplychain.api.fulfillment.dto;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "标准履约受理回执；ACCEPTED 只表示中台已承担履约责任，不表示供应商已履约")
@Data
public class FulfillmentAcceptanceReceiptRespDTO implements Serializable {

    /** 本次调用请求标识 */
    @Schema(description = "本次调用请求标识", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "REQ_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String requestId;

    /** 采购商侧C端订单号，与buyer_id组成永久受理键 */
    @Schema(description = "采购商侧C端订单号，与buyer_id组成永久受理键", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "C202608110001")
    private String businessNo;

    /** 回执状态：0 PROCESSING处理中，10 ACCEPTED已受理，20 REJECTED_RETRYABLE条件修复后可重试，30 REJECTED_FINAL最终拒绝 */
    @Schema(description = "回执状态：0 PROCESSING处理中，10 ACCEPTED已受理，20 REJECTED_RETRYABLE条件修复后可重试，30 REJECTED_FINAL最终拒绝", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "ACCEPTED")
    private FulfillmentReceiptStatus receiptStatus;

    /** 回执状态及条件重试CAS版本号 */
    @Schema(description = "回执状态及条件重试CAS版本号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long receiptVersion;

    /** 受理成功后的供应链总订单号快照 */
    @Schema(description = "受理成功后的供应链总订单号快照",
            example = "SC_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String orderNo;

    /** 公共主状态：0已创建，10处理中，20部分成功，30成功，40失败，50已取消，90人工处理 */
    @Schema(description = "公共主状态：0已创建，10处理中，20部分成功，30成功，40失败，50已取消，90人工处理", example = "PROCESSING")
    private SupplyChainOrderStatus orderStatus;

    /** 受理成功后的履约请求号快照 */
    @Schema(description = "受理成功后的履约请求号快照",
            example = "FR_01K1XXXXXXXXXXXXXXXXXXXXXX")
    private String fulfillmentRequestNo;

    /** 拒绝原因稳定错误码，处理中或已受理时为空 */
    @Schema(description = "拒绝原因稳定错误码，处理中或已受理时为空", example = "USER_PURCHASE_LIMIT_EXCEEDED")
    private String rejectionCode;

    /** 列表展示供应商编码快照 */
    @Schema(description = "列表展示供应商编码快照", example = "QNM")
    private String supplierCode;

    /** 列表展示供应商名称快照 */
    @Schema(description = "列表展示供应商名称快照", example = "奇那梦")
    private String supplierName;

    /** 受理成功时间，Asia/Shanghai */
    @Schema(description = "受理成功时间，Asia/Shanghai", example = "2026-08-11T02:30:01Z")
    private OffsetDateTime acceptedAt;

    /** 是否为永久业务键的幂等重放 */
    @Schema(description = "是否为永久业务键的幂等重放", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "false")
    private Boolean idempotentReplay;
}
