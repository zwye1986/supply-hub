package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;

/**
 * 到店购建单响应：受理结果与收银台地址。
 *
 * <p>cashierUrl 处理中可空，调用方必须按原 outTradeNo 查询收敛。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#22-命令侧响应-dto}。</p>
 */
@Schema(description = "到店购建单响应")
@Data
public class PaymentTicketRespDTO implements Serializable {

    /** 供应链总订单稳定业务编号 */
    @Schema(description = "供应链总订单稳定业务编号", example = "SC_1")
    private String orderNo;

    /** 拉起采购商收银台的稳定外部交易号 */
    @Schema(description = "拉起采购商收银台的稳定外部交易号", example = "IPF_OTN")
    private String outTradeNo;

    /** 采购商收银台地址，建单时返回；处理中可空，需按原 outTradeNo 查询 */
    @Schema(description = "采购商收银台地址，建单时返回；处理中可空，需按原 outTradeNo 查询")
    @ToString.Exclude
    private String cashierUrl;

    /** 支付状态：0待支付，10支付处理中，20已支付，30支付失败，40已取消，90人工处理 */
    @Schema(description = "支付状态：0待支付，10支付处理中，20已支付，30支付失败，40已取消，90人工处理", example = "10")
    private Integer payStatus;
}
