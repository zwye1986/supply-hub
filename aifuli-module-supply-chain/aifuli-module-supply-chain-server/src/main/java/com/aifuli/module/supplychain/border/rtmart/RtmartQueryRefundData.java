package com.aifuli.module.supplychain.border.rtmart;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 旅通「查询退款状态」成功响应 data。
 *
 * <p>成功时必须输出全部字段，禁止因 null 省略键。{@code refuse_reason} 没有时填空字符串；
 * {@code refund_time} 为 Integer、单位秒，无完成时间则取接口调用当前时间。
 * 详见 {@code docs/superpowers/specs/2026-08-27-rtmart-command-callback-hardening-design.md#82-申请退款--查询退款成功-data-字段始终输出}。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class RtmartQueryRefundData implements Serializable {

    /** 商户支付单号 */
    @JsonProperty("out_trade_no")
    private String outTradeNo;

    /** 商户退款单号 */
    @JsonProperty("out_refund_no")
    private String outRefundNo;

    /** 退款状态：0待退款，1退款成功，2退款失败 */
    @JsonProperty("refund_status")
    private Integer refundStatus;

    /** 退款金额，单位：分。失败/人工处理可为 null，但键必须保留 */
    @JsonProperty("refund_fee")
    private Long refundFee;

    /** 退款时间，单位：秒 */
    @JsonProperty("refund_time")
    private Integer refundTime;

    /** 失败原因；没有时填空字符串 */
    @JsonProperty("refuse_reason")
    private String refuseReason;
}
