package com.aifuli.module.member.api.sso.dto;

import lombok.Data;

/**
 * 会员 SSO 令牌响应。
 *
 * <p>访问令牌属于完整登录凭证，任何 DTO 诊断字符串均不得回显。详细设计见
 * {@code docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md#31-member-api}。</p>
 */
@Data
public class MemberSsoTokenRespDTO {

    /** 会员访问令牌 */
    private String accessToken;

    @Override
    public String toString() {
        return "MemberSsoTokenRespDTO(accessToken=<redacted>)";
    }
}
