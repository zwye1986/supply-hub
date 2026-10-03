package com.aifuli.module.supplychain.api.o2o.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Schema(description = "采购商支付确认请求")
@Data
public class ConfirmPaymentReqDTO implements Serializable {

    /** 拉起采购商收银台的稳定外部交易号 */
    @Schema(description = "拉起采购商收银台的稳定外部交易号",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "OT202608130001")
    @NotBlank(message = "外部交易号不能为空")
    @Size(max = 128, message = "外部交易号不能超过 128 个字符")
    private String outTradeNo;

    /** 采购商收银台实际支付金额，单位为分 */
    @Schema(description = "采购商收银台实际支付金额，单位为分",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "10000")
    @NotNull(message = "采购商实际支付金额不能为空")
    @Positive(message = "采购商实际支付金额必须为正数")
    private Long buyerPaidAmount;

    /** 采购商收银台支付单引用 */
    @Schema(description = "采购商收银台支付单引用", example = "CASH-202608130001")
    @Size(max = 128, message = "收银台支付单引用不能超过 128 个字符")
    private String cashierReference;

    /** 收银台观测到支付结果的时间 */
    @Schema(description = "收银台观测到支付结果的时间", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-08-13T10:30:00+08:00")
    @NotNull(message = "支付观测时间不能为空")
    private OffsetDateTime observedAt;
}
