package com.aifuli.module.supplychain.border.rtmart;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 旅通"获取会员信息"接口响应体。
 *
 * <p>成功时 {@code result=success, code=E0000, data} 为会员资料对象；
 * 失败时 {@code result=fail, code=E4xxx, data} 为空数组。字段命名遵循旅通协议。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class RtmartMemberResponse implements Serializable {

    @JsonProperty("result")
    private String result;

    @JsonProperty("code")
    private String code;

    @JsonProperty("msg")
    private String msg;

    @JsonProperty("data")
    private Object data;

    public static RtmartMemberResponse success(RtmartMemberData data) {
        RtmartMemberResponse response = new RtmartMemberResponse();
        response.result = "success";
        response.code = RtmartResponseCode.SUCCESS.getCode();
        response.msg = "";
        response.data = data;
        return response;
    }

    public static RtmartMemberResponse fail(RtmartResponseCode code, String msg) {
        RtmartMemberResponse response = new RtmartMemberResponse();
        response.result = "fail";
        response.code = code.getCode();
        response.msg = msg;
        response.data = java.util.Collections.emptyList();
        return response;
    }
}
