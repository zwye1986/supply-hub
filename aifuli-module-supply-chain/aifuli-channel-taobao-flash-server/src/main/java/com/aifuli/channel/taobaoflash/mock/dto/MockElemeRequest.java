package com.aifuli.channel.taobaoflash.mock.dto;

import lombok.Data;

/**
 * mock-eleme 驱动请求体。
 *
 * <p>对标 novo-mall {@code MockElemeRequest}；联调支付闭环只用 pay/queryPay 字段。
 * 详见 {@code docs/集成测试.md}「淘宝闪购 O2O 支付闭环」。</p>
 */
@Data
public class MockElemeRequest {

    private String transactionId;
    private Integer payAmount;
    private String subject;
    private String body;
    private String notifyUrl;
    private String redirectUrl;
    private String backUrl;
    private String uid;
    private String extendParams;
}
