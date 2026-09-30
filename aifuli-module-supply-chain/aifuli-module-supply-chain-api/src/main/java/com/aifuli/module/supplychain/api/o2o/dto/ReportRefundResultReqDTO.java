package com.aifuli.module.supplychain.api.o2o.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "采购商退款结果上报请求")
@Data
public class ReportRefundResultReqDTO implements Serializable {

    @Schema(description = "中台退款稳定业务编号；按此加载已固化的归属",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RF_01HZ...")
    @NotBlank(message = "中台退款号不能为空")
    @Size(max = 32, message = "中台退款号不能超过 32 个字符")
    private String middleRefundNo;

    @Schema(description = "退款结果状态：PROCESSING/SUCCEEDED/REJECTED/UNKNOWN",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "SUCCEEDED")
    @NotBlank(message = "退款结果状态不能为空")
    @Size(max = 32, message = "退款结果状态不能超过 32 个字符")
    private String refundStatus;

    @Schema(description = "采购商实际退给 C 端用户的金额，单位为分", example = "10000")
    @PositiveOrZero(message = "采购商实际退款金额不能为负数")
    private Long buyerActualRefundAmount;

    @Schema(description = "采购商收银台退款单引用", example = "CASHRF-202608130001")
    @Size(max = 128, message = "收银台退款单引用不能超过 128 个字符")
    private String cashierRefundReference;

    @Schema(description = "收银台观测到退款结果的时间", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-13T11:00:00+08:00")
    @NotNull(message = "退款观测时间不能为空")
    private OffsetDateTime observedAt;
}
