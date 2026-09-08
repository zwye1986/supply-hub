package com.aifuli.module.supplychain.api.fulfillment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Schema(description = "平台定义的封闭履约目标；键集合由 schemaCode 和 schemaVersion 严格校验")
@Data
public class FulfillmentTargetDTO implements Serializable {

    @Schema(description = "履约目标 Schema 编码", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "DIRECT_RECHARGE_TARGET")
    @NotBlank(message = "履约目标 Schema 编码不能为空")
    @Size(max = 64, message = "履约目标 Schema 编码不能超过 64 个字符")
    private String schemaCode;

    @Schema(description = "履约目标 Schema 版本", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
    @NotNull(message = "履约目标 Schema 版本不能为空")
    @Positive(message = "履约目标 Schema 版本必须为正数")
    private Integer schemaVersion;

    @Schema(description = "履约目标值；仅允许已发布 Schema 声明的键，禁止 URL、Header 和供应商私有参数",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "{\"accountType\":\"MOBILE\",\"accountValue\":\"138****0000\"}")
    @NotEmpty(message = "履约目标值不能为空")
    @Size(max = 16, message = "履约目标字段不能超过 16 个")
    private Map<@NotBlank String, @NotBlank String> values;
}
