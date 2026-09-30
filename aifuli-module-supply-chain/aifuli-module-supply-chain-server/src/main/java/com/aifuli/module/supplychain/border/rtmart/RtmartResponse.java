package com.aifuli.module.supplychain.border.rtmart;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;

/**
 * 旅通商户接口通用响应体：创建订单、查询支付、取消支付、申请退款、查询退款。
 *
 * <p>成功时 {@code result=success, code=E0000, data} 为业务数据对象；
 * 失败时 {@code result=fail, code=E4xxx/E5xxx, data} 为空数组。字段命名遵循旅通协议。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class RtmartResponse implements Serializable {

    @JsonProperty("result")
    private String result;

    @JsonProperty("code")
    private String code;

    @JsonProperty("msg")
    private String msg;

    @JsonProperty("data")
    private Object data;

    public static RtmartResponse success(Object data) {
        RtmartResponse response = new RtmartResponse();
        response.result = "success";
        response.code = RtmartResponseCode.SUCCESS.getCode();
        response.msg = "";
        response.data = data;
        return response;
    }

    public static RtmartResponse fail(RtmartResponseCode code, String msg) {
        RtmartResponse response = new RtmartResponse();
        response.result = "fail";
        response.code = code.getCode();
        response.msg = msg;
        response.data = Collections.emptyList();
        return response;
    }
}
