package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购支付状态查询请求（旅通接口③）。
 *
 * <p>本地非终态时服务端按原 outTradeNo 权威查单收敛后返回最新投影。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#21-命令侧-dto-字段边界}。</p>
 */
@Schema(description = "到店购支付状态查询请求")
@Data
public class QueryPaymentStatusReqDTO implements Serializable {

    /** 旅通支付流水号trade_id，与channel_code构成支付永久幂等键 */
    @Schema(description = "旅通支付流水号trade_id，与channel_code构成支付永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RT-TRADE-001")
    @NotBlank(message = "旅通支付流水号不能为空")
    @Size(max = 128, message = "旅通支付流水号不能超过 128 个字符")
    private String tradeId;

    /** 链路追踪标识，缺省由服务端生成 */
    @Schema(description = "链路追踪标识，缺省由服务端生成", example = "trace-q-001")
    @Size(max = 128, message = "链路追踪标识不能超过 128 个字符")
    private String traceId;
}
