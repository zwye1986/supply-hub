package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购退款状态查询请求（旅通接口⑥）。
 *
 * <p>非终态时服务端按原 refundCaseNo 权威查单兜底收敛。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#21-命令侧-dto-字段边界}。</p>
 */
@Schema(description = "到店购退款状态查询请求")
@Data
public class QueryRefundStatusReqDTO implements Serializable {

    /** 旅通退款单号refund_bn，与channel_code构成退款永久幂等键 */
    @Schema(description = "旅通退款单号refund_bn，与channel_code构成退款永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RT-REFUND-001")
    @NotBlank(message = "旅通退款单号不能为空")
    @Size(max = 128, message = "旅通退款单号不能超过 128 个字符")
    private String channelRefundNo;

    /** 链路追踪标识，缺省由服务端生成 */
    @Schema(description = "链路追踪标识，缺省由服务端生成", example = "trace-qr-001")
    @Size(max = 128, message = "链路追踪标识不能超过 128 个字符")
    private String traceId;
}
