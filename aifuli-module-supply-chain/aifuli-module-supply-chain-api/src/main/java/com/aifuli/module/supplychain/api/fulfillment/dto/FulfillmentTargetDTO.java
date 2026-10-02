package com.aifuli.module.supplychain.api.fulfillment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;
import java.util.Map;

@Schema(description = "履约目标值；键集合由服务端按采购商商品关联的平台 SKU Schema 严格校验")
@Data
public class FulfillmentTargetDTO implements Serializable {

    @Schema(description = "履约目标值；仅允许已发布 Schema 声明的键，禁止 URL、Header 和供应商私有参数",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "{\"accountType\":\"MOBILE\",\"accountValue\":\"138****0000\"}")
    @NotEmpty(message = "履约目标值不能为空")
    @Size(max = 16, message = "履约目标字段不能超过 16 个")
    @ToString.Exclude
    private Map<@NotBlank String, @NotBlank String> values;
}
