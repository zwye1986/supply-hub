package com.aifuli.module.supplychain.border.rtmart;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 旅通"取消支付流水"接口请求体。
 */
@Data
public class RtmartCancelPaymentRequest implements Serializable {

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

    @JsonProperty("user_code")
    private String userCode;

    @JsonProperty("company_code")
    private String companyCode;
}
