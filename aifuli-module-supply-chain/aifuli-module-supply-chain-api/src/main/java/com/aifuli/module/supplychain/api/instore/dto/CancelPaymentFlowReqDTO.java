package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购支付流水取消请求（旅通接口④）。
 *
 * <p>未支付则关单；已支付且从未存在退款协同时转全额 CANCEL_REFUND 退款。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#21-命令侧-dto-字段边界}。</p>
 */
@Schema(description = "到店购支付流水取消请求")
@Data
public class CancelPaymentFlowReqDTO implements Serializable {

    /** 旅通支付流水号trade_id，与channel_code构成支付永久幂等键 */
    @Schema(description = "旅通支付流水号trade_id，与channel_code构成支付永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RT-TRADE-001")
    @NotBlank(message = "旅通支付流水号不能为空")
    @Size(max = 128, message = "旅通支付流水号不能超过 128 个字符")
    private String tradeId;

    /** 链路追踪标识，缺省由服务端生成 */
    @Schema(description = "链路追踪标识，缺省由服务端生成", example = "trace-cancel-001")
    @Size(max = 128, message = "链路追踪标识不能超过 128 个字符")
    private String traceId;
}
