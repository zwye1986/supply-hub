package com.aifuli.module.member.api.sso.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 会员 SSO 令牌请求中的 SaaS 外部身份三元组。
 *
 * <p>字段约束与会员资料解析端口保持一致。详细设计见
 * {@code docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md#31-member-api}。</p>
 */
@Data
public class MemberSsoTokenReqDTO {

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
        return "MemberSsoTokenReqDTO(sourceSystem=" + sourceSystem
                + ", externalTenantId=<redacted>, buyerUserId=<redacted>)";
    }
}
