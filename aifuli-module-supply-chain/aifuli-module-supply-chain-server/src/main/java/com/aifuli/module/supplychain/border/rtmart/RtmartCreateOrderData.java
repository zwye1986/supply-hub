package com.aifuli.module.supplychain.border.rtmart;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 旅通"创建订单"成功响应 data 部分。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RtmartCreateOrderData implements Serializable {

    @JsonProperty("out_trade_no")
    private String outTradeNo;

    @JsonProperty("cashier_url")
    private String cashierUrl;
}
