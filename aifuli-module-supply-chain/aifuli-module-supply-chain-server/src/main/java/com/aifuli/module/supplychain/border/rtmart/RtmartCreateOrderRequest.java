package com.aifuli.module.supplychain.border.rtmart;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 旅通"创建订单"接口请求体，字段命名遵循旅通 snake_case 协议。
 */
@Data
public class RtmartCreateOrderRequest implements Serializable {

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

    @JsonProperty("total_amount")
    private Long totalAmount;

    @JsonProperty("order_time")
    private Long orderTime;

    @JsonProperty("order_type")
    private Integer orderType;

    @JsonProperty("shop_code")
    private String shopCode;

    @JsonProperty("shop_name")
    private String shopName;

    @JsonProperty("dynamic_code")
    private String dynamicCode;
}
