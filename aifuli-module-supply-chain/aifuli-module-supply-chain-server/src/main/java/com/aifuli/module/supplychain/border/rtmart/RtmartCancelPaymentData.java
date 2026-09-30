package com.aifuli.module.supplychain.border.rtmart;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 旅通"取消支付流水"成功响应 data 部分。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RtmartCancelPaymentData implements Serializable {

    @JsonProperty("out_trade_no")
    private String outTradeNo;
}
