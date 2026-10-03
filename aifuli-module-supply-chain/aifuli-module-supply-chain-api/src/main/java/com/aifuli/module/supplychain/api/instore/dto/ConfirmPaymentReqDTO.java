package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * SaaS 收银台支付成功回调请求：按 outTradeNo 收敛。
 *
 * <p>回调只提交原业务号与实际金额；归属按订单固化数据加载，回调参数不得改变归属。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#23-回调侧-dto-字段边界}。</p>
 */
@Schema(description = "到店购收银台支付成功回调请求")
@Data
public class ConfirmPaymentReqDTO implements Serializable {

    /** 拉起采购商收银台的稳定外部交易号 */
    @Schema(description = "拉起采购商收银台的稳定外部交易号",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "IPF_OTN")
    @NotBlank(message = "外部交易号不能为空")
    @Size(max = 128, message = "外部交易号不能超过 128 个字符")
    private String outTradeNo;

    /** 采购商实际支付金额，单位为分 */
    @Schema(description = "采购商实际支付金额，单位为分",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "10000")
    @NotNull(message = "实际支付金额不能为空")
    @Positive(message = "实际支付金额必须大于 0")
    private Long actualAmountCny;

    /** 采购商收银台支付单引用 */
    @Schema(description = "采购商收银台支付单引用", example = "CASHIER_1")
    @Size(max = 128, message = "收银台支付单引用不能超过 128 个字符")
    private String cashierOrderNo;

    /** 收银台交易号 */
    @Schema(description = "收银台交易号", example = "TXN_1")
    @Size(max = 128, message = "收银台交易号不能超过 128 个字符")
    private String cashierTransactionNo;

    /** 链路追踪标识，缺省由服务端生成 */
    @Schema(description = "链路追踪标识，缺省由服务端生成", example = "trace-cb-001")
    @Size(max = 128, message = "链路追踪标识不能超过 128 个字符")
    private String traceId;
}
