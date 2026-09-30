package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购退款受理请求（旅通接口⑤）。
 *
 * <p>同一支付流水至多一个活动退款，其余 Case 串行排队；可退金额 = 已付 − 已累计 − 在途。
 * 详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#21-命令侧-dto-字段边界}。</p>
 */
@Schema(description = "到店购退款受理请求")
@Data
public class AcceptRefundReqDTO implements Serializable {

    @Schema(description = "旅通退款单号refund_bn，与channel_code构成退款永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RT-REFUND-001")
    @NotBlank(message = "旅通退款单号不能为空")
    @Size(max = 128, message = "旅通退款单号不能超过 128 个字符")
    private String channelRefundNo;

    @Schema(description = "旅通支付流水号trade_id，与channel_code构成支付永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RT-TRADE-001")
    @NotBlank(message = "旅通支付流水号不能为空")
    @Size(max = 128, message = "旅通支付流水号不能超过 128 个字符")
    private String tradeId;

    @Schema(description = "退款类型：CHANNEL_REFUND渠道发起退款，CANCEL_REFUND取消已支付流水触发的退款",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "CHANNEL_REFUND")
    @NotBlank(message = "退款类型不能为空")
    @Size(max = 32, message = "退款类型不能超过 32 个字符")
    private String refundType;

    @Schema(description = "渠道要求退款金额，单位为分",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "3000")
    @NotNull(message = "渠道退款金额不能为空")
    @Positive(message = "渠道退款金额必须大于 0")
    private Long channelRefundAmount;

    @Schema(description = "链路追踪标识，缺省由服务端生成", example = "trace-refund-001")
    @Size(max = 128, message = "链路追踪标识不能超过 128 个字符")
    private String traceId;
}
