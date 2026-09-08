package com.aifuli.module.supplychain.border.rtmart;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;

/**
 * 旅通"获取会员信息"成功响应 data 部分，字段命名遵循旅通 snake_case 协议。
 */
@Data
public class RtmartMemberData implements Serializable {

    @JsonProperty("user_code")
    @ToString.Exclude
    private String userCode;

    @JsonProperty("mobile")
    @ToString.Exclude
    private String mobile;

    @JsonProperty("user_name")
    @ToString.Exclude
    private String userName;

    @JsonProperty("company_code")
    private String companyCode;
}
