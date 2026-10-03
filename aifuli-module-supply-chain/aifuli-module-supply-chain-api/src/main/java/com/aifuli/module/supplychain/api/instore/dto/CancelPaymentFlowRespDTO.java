package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购支付流水取消响应。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#22-命令侧响应-dto}。</p>
 */
@Schema(description = "到店购支付流水取消响应")
@Data
public class CancelPaymentFlowRespDTO implements Serializable {

    /** 拉起采购商收银台的稳定外部交易号 */
    @Schema(description = "拉起采购商收银台的稳定外部交易号", example = "IPF_OTN")
    private String outTradeNo;

    /** 取消转退款生成的中台退款稳定业务编号；未转退款为空 */
    @Schema(description = "取消转退款生成的中台退款稳定业务编号；未转退款为空", example = "IRC_C")
    private String refundCaseNo;

    /** 是否已转全额退款（已支付流水取消） */
    @Schema(description = "是否已转全额退款（已支付流水取消）", example = "true")
    private Boolean convertedToRefund;

    /** 是否抢占派发（转退款后立即外呼收银台） */
    @Schema(description = "是否抢占派发（转退款后立即外呼收银台）", example = "true")
    private Boolean claimedForDispatch;
}
