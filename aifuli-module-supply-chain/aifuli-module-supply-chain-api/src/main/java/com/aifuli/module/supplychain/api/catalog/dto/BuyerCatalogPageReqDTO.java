package com.aifuli.module.supplychain.api.catalog.dto;

import com.aifuli.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "采购商标准商品池分页查询请求")
@Data
@EqualsAndHashCode(callSuper = true)
public class BuyerCatalogPageReqDTO extends PageParam {

    /** 调用来源系统编码 */
    @Schema(description = "调用来源系统编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "AIFULI_MALL")
    @NotBlank(message = "调用来源系统不能为空")
    @Size(max = 64)
    private String sourceSystem;

    /** 来源系统中的外部租户标识 */
    @Schema(description = "来源系统中的外部租户标识", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "tenant-wusheng")
    @NotBlank(message = "外部租户标识不能为空")
    @Size(max = 128)
    private String externalTenantId;

    /** 采购商商品编码 */
    @Schema(description = "采购商商品编码", example = "TELCO-100")
    @Size(max = 64)
    private String buyerSkuCode;

    /** 履约类型：DIRECT_RECHARGE直充，VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益 */
    @Schema(description = "履约类型：DIRECT_RECHARGE直充，VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益", example = "DIRECT_RECHARGE")
    @Size(max = 32)
    private String fulfillmentType;

    /** 仅返回当前可用且分销商定价为 READY 的标准商品 */
    @Schema(description = "仅返回当前可用且分销商定价为 READY 的标准商品", example = "true")
    private Boolean availableOnly;
}
