package com.aifuli.channel.taobaoflash.mock.dto;

import lombok.Data;

/**
 * mock 收银台点支付结果。
 */
@Data
public class CashierPayResponse {

    private boolean success;
    private String message;
    private String transactionId;
    private String orderNo;
    private String outTradeNo;
    private String payStatus;
    private Boolean middleConfirmed;
}
