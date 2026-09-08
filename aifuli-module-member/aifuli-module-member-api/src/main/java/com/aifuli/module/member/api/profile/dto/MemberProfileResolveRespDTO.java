package com.aifuli.module.member.api.profile.dto;

import lombok.Data;

/**
 * 会员资料解析响应。
 *
 * <p>手机号和用户名均属个人资料，DTO 日志字符串必须固定脱敏。</p>
 *
 * <p>详细设计：docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#31-member-api</p>
 */
@Data
public class MemberProfileResolveRespDTO {

    /** 会员手机号 */
    private String mobile;

    /** 会员展示姓名（真实姓名优先，昵称回退） */
    private String userName;

    @Override
    public String toString() {
        return "MemberProfileResolveRespDTO(mobile=<redacted>, userName=<redacted>)";
    }

}
