package com.aifuli.module.supplychain.border.rtmart;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 旅通「查询支付单状态」成功响应 data。
 *
 * <p>成功时必须输出全部字段，禁止因 null 省略键。{@code pay_fee} 为 Integer、单位分，未支付也要填；
 * {@code pay_time} 未支付填 0；{@code transaction_id} 可为空，支付成功时必传。
 * 详见 {@code docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#81-查询支付成功-data-字段始终输出}。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class RtmartQueryPaymentData implements Serializable {

    /** 商户支付单号 */
    @JsonProperty("out_trade_no")
    private String outTradeNo;

    /** 支付状态：0待支付，1支付成功，2取消支付 */
    @JsonProperty("pay_status")
    private Integer payStatus;

    /** 支付金额，单位：分。待支付、取消、支付成功均必填 */
    @JsonProperty("pay_fee")
    private Integer payFee;

    /** 支付时间，单位：秒。未支付、取消填 0 */
    @JsonProperty("pay_time")
    private Integer payTime;

    /** 支付流水号（可为空，支付成功时必传）。待支付/取消为空字符串 */
    @JsonProperty("transaction_id")
    private String transactionId;
}
