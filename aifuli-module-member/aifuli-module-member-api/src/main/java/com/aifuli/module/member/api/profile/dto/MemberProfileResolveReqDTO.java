package com.aifuli.module.member.api.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 会员资料解析请求中的 SaaS 外部身份三元组。
 *
 * <p>三个路由键缺一不可，以保持来源系统和租户范围内的身份唯一性。</p>
 *
 * <p>详细设计：docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#31-member-api</p>
 */
@Data
public class MemberProfileResolveReqDTO {

    /** SaaS外部路由来源系统 */
    @NotBlank(message = "来源系统不能为空")
    @Size(max = 32, message = "来源系统长度不能超过32")
    private String sourceSystem;

    /** SaaS外部租户路由标识 */
    @NotBlank(message = "外部租户标识不能为空")
    @Size(max = 128, message = "外部租户标识长度不能超过128")
    private String externalTenantId;

    /** 采购商范围内C端用户唯一标识 */
    @NotBlank(message = "采购商用户标识不能为空")
    @Size(max = 128, message = "采购商用户标识长度不能超过128")
    private String buyerUserId;

    @Override
    public String toString() {
        return "MemberProfileResolveReqDTO(sourceSystem=" + sourceSystem
                + ", externalTenantId=<redacted>, buyerUserId=<redacted>)";
    }

}
