package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购首页入口请求，只承载页面位置。项目及采购商身份由可信上下文提供；
 * 企业组由中台 HOME 配置读取，禁止请求体传入。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层}。</p>
 */
@Schema(description = "到店购首页入口请求")
@Data
public class HomeEntryReqDTO implements Serializable {

    /** 经度 */
    @Schema(description = "经度", requiredMode = Schema.RequiredMode.REQUIRED, example = "121.4737")
    @NotBlank(message = "经度不能为空")
    @Size(max = 32, message = "经度不能超过 32 个字符")
    private String longitude;

    /** 纬度 */
    @Schema(description = "纬度", requiredMode = Schema.RequiredMode.REQUIRED, example = "31.2304")
    @NotBlank(message = "纬度不能为空")
    @Size(max = 32, message = "纬度不能超过 32 个字符")
    private String latitude;
}
