package com.aifuli.channel.taobaoflash.mock.dto;

import lombok.Data;

/**
 * mock-eleme 驱动响应：透出对真实 {@code /tbsg/channel/*} 的 HTTP 结果与验签结论。
 */
@Data
public class MockElemeResponse {

    private String operation;
    private String targetUrl;
    private Integer statusCode;
    private String redirectLocation;
    private Boolean signatureValid;
    private String requestBody;
    private String responseBody;
    private String errorMessage;
}
