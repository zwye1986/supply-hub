package com.aifuli.module.supplychain.api.o2o.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "O2O H5 入口请求")
@Data
public class O2OEntryReqDTO implements Serializable {

    /** 调用来源系统编码 */
    @Schema(description = "调用来源系统编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "AIFULI_MALL")
    @NotBlank(message = "调用来源系统不能为空")
    @Size(max = 64, message = "调用来源系统编码不能超过 64 个字符")
    private String sourceSystem;

    /** 来源系统中的外部租户标识 */
    @Schema(description = "来源系统中的外部租户标识",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "tenant-wusheng")
    @NotBlank(message = "外部租户标识不能为空")
    @Size(max = 128, message = "外部租户标识不能超过 128 个字符")
    private String externalTenantId;

    /** 采购商范围内真实C端用户唯一标识 */
    @Schema(description = "采购商范围内真实C端用户唯一标识",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "member-1024")
    @NotBlank(message = "采购商用户标识不能为空")
    @Size(max = 128, message = "采购商用户标识不能超过 128 个字符")
    private String buyerUserId;

    /** O2O渠道编码，一期为ELEME */
    @Schema(description = "O2O渠道编码，一期为ELEME", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "ELEME")
    @NotBlank(message = "O2O 渠道编码不能为空")
    @Size(max = 32, message = "O2O 渠道编码不能超过 32 个字符")
    private String channelCode;

    /** 场景编码；盒马与淘宝闪购的区别仅体现在 H5 URL 配置，由该编码选择 */
    @Schema(description = "场景编码；盒马与淘宝闪购的区别仅体现在 H5 URL 配置，由该编码选择",
            example = "HEMA")
    @Size(max = 32, message = "场景编码不能超过 32 个字符")
    private String sceneCode;
}
