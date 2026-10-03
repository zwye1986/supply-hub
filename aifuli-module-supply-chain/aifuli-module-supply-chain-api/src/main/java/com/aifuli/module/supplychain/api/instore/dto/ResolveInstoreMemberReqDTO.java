package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;

/**
 * 到店购渠道会员解析请求，凭证只用于一次受控解析，不承载项目或会员路由字段。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层}。</p>
 */
@Schema(description = "到店购渠道会员解析请求")
@Data
public class ResolveInstoreMemberReqDTO implements Serializable {

    /** 到店购入口凭证；属于敏感认证信息，禁止经 toString() 写入日志 */
    @Schema(description = "到店购入口凭证", requiredMode = Schema.RequiredMode.REQUIRED, example = "credential-token")
    @NotBlank(message = "到店购入口凭证不能为空")
    @Size(max = 256, message = "到店购入口凭证不能超过 256 个字符")
    @ToString.Exclude
    private String token;
}
