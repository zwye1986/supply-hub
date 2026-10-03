package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;

/**
 * 到店购渠道会员响应，仅返回前端展示和渠道对接所需的稳定会员资料。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层}。</p>
 */
@Schema(description = "到店购渠道会员响应")
@Data
public class InstoreMemberRespDTO implements Serializable {

    /** 旅通会员编号user_code，受理时固化用于归属二次校验；属于个人信息，禁止经 toString() 写入日志 */
    @Schema(description = "渠道会员编码", example = "user-001")
    @ToString.Exclude
    private String userCode;

    /** 会员手机号；属于个人信息，禁止经 toString() 写入日志 */
    @Schema(description = "会员手机号", example = "13800000000")
    @ToString.Exclude
    private String mobile;

    /** 会员姓名；属于个人信息，禁止经 toString() 写入日志 */
    @Schema(description = "会员姓名", nullable = true, example = "张三")
    @ToString.Exclude
    private String userName;

    /** 旅通企业组编号company_code，受理时固化 */
    @Schema(description = "企业编码", example = "COMPANY-001")
    private String companyCode;
}
