package com.aifuli.module.supplychain.border.rtmart;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 旅通"申请退款"接口请求体。
 */
@Data
public class RtmartAcceptRefundRequest implements Serializable {

    @JsonProperty("app_key")
    private String appKey;

    @JsonProperty("version")
    private String version;

    @JsonProperty("timestamp")
    private Long timestamp;

    @JsonProperty("nonce_str")
    private String nonceStr;

    @JsonProperty("sign")
    private String sign;

    @JsonProperty("order_id")
    private String orderId;

    @JsonProperty("trade_id")
    private String tradeId;

    @JsonProperty("refund_bn")
    private String refundBn;

    @JsonProperty("refund_fee")
    @NotNull(message = "退款金额不能为空")
    @Positive(message = "退款金额必须大于0")
    private Long refundFee;

    @JsonProperty("user_code")
    private String userCode;

    @JsonProperty("company_code")
    private String companyCode;
}
