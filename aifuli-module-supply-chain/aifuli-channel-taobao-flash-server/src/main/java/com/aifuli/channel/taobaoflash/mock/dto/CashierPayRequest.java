package com.aifuli.channel.taobaoflash.mock.dto;

import lombok.Data;

/**
 * mock 收银台点支付请求。
 *
 * <p>aifuli 以中台 {@code outTradeNo} 为主键（不再走海狸 orderNo）。</p>
 */
@Data
public class CashierPayRequest {

    /** 中台 outTradeNo；H5 收银台页主参数 */
    private String outTradeNo;
    private String transactionId;
    private String orderNo;
    private String payAmount;
}
