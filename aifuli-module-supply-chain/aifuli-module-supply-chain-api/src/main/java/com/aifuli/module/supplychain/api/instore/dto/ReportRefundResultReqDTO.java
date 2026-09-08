package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * SaaS 收银台退款结果回调请求：按 refundCaseNo 收敛。
 *
 * <p>实际退款金额缺失或与申请金额不一致时转人工且不返还采购商账户。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#23-回调侧-dto-字段边界}。</p>
 */
@Schema(description = "到店购收银台退款结果回调请求")
@Data
public class ReportRefundResultReqDTO implements Serializable {

    @Schema(description = "中台退款稳定业务编号",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "IRC_9")
    @NotBlank(message = "中台退款编号不能为空")
    @Size(max = 32, message = "中台退款编号不能超过 32 个字符")
    private String refundCaseNo;

    @Schema(description = "采购商实际退款金额，单位为分",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "3000")
    @NotNull(message = "实际退款金额不能为空")
    @Positive(message = "实际退款金额必须大于 0")
    private Long actualAmountCny;

    @Schema(description = "采购商收银台退款单引用", example = "CRF_1")
    @Size(max = 128, message = "收银台退款单引用不能超过 128 个字符")
    private String cashierRefundReference;

    @Schema(description = "链路追踪标识，缺省由服务端生成", example = "trace-cb-001")
    @Size(max = 128, message = "链路追踪标识不能超过 128 个字符")
    private String traceId;
}
