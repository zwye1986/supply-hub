package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购支付状态响应：本地最新投影（触发权威查询时已收敛）。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#22-命令侧响应-dto}。</p>
 */
@Schema(description = "到店购支付状态响应")
@Data
public class PaymentStatusRespDTO implements Serializable {

    @Schema(description = "到店购支付流水稳定业务编号", example = "IPF_1")
    private String paymentFlowNo;

    @Schema(description = "拉起采购商收银台的稳定外部交易号", example = "IPF_OTN")
    private String outTradeNo;

    @Schema(description = "旅通支付流水号trade_id，与channel_code构成支付永久幂等键", example = "RT-TRADE-001")
    private String tradeId;

    @Schema(description = "支付状态：0待支付，10支付处理中，20已支付，30支付失败，40已取消，90人工处理", example = "20")
    private Integer payStatus;

    @Schema(description = "本笔支付流水金额，单位为分", example = "10000")
    private Long payAmount;

    @Schema(description = "采购商收银台实际支付金额，单位为分；未支付为空", example = "10000")
    private Long buyerPaidAmount;

    @Schema(description = "采购商收银台支付单引用", example = "CASHIER_1")
    private String cashierReference;

    @Schema(description = "采购商收银台返回的交易号，支付成功时必有值", example = "TXN_1")
    private String externalTransactionId;
}
