package com.aifuli.module.supplychain.api.order.dto;

import com.aifuli.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Schema(description = "采购商供应链订单分页查询请求")
@Data
@EqualsAndHashCode(callSuper = true)
public class SupplyChainOrderPageReqDTO extends PageParam {

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

    /** 供应链总订单稳定业务编号 */
    @Schema(description = "供应链总订单稳定业务编号", example = "202610131530274200000001")
    @Size(max = 32)
    private String orderNo;

    /** 采购商侧C端订单号，CARD场景必填 */
    @Schema(description = "采购商侧C端订单号，CARD场景必填", example = "C202608110001")
    @Size(max = 128)
    private String buyerOrderNo;

    /** 列表展示供应商编码快照 */
    @Schema(description = "列表展示供应商编码快照", example = "QNM")
    @Size(max = 32)
    private String supplierCode;

    /** CARD场景平台SKU编码快照 */
    @Schema(description = "CARD场景平台SKU编码快照", example = "TELCO_100")
    @Size(max = 32)
    private String skuCode;

    /** 公共主状态：0已创建，10处理中，20部分成功，30成功，40失败，50已取消，90人工处理 */
    @Schema(description = "公共主状态：0已创建，10处理中，20部分成功，30成功，40失败，50已取消，90人工处理", example = "PROCESSING")
    private SupplyChainOrderStatus orderStatus;
}
