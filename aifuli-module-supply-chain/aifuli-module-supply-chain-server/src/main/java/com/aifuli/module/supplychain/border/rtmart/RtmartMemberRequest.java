package com.aifuli.module.supplychain.border.rtmart;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;

/**
 * 旅通"获取会员信息"接口请求体，字段命名遵循旅通 snake_case 协议。
 *
 * <p>旅通调用商户时携带 app_key/version/timestamp/nonce_str/sign/token 六个字段，
 * 其中 sign 为旅通私钥对规范串（除 sign 外字段按 key 字典序 key+value 拼接）的
 * SHA1withRSA 签名，标准 Base64 编码。</p>
 */
@Data
public class RtmartMemberRequest implements Serializable {

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

    @JsonProperty("token")
    private String token;
}
