package com.aifuli.module.supplychain.api.o2o.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "O2O 渠道支付预下单请求")
@Data
public class PreparePaymentReqDTO implements Serializable {

    /** O2O渠道编码，一期为ELEME */
    @Schema(description = "O2O渠道编码，一期为ELEME", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "ELEME")
    @NotBlank(message = "O2O 渠道编码不能为空")
    @Size(max = 32, message = "O2O 渠道编码不能超过 32 个字符")
    private String channelCode;

    /** 渠道交易唯一标识 */
    @Schema(description = "渠道交易唯一标识；与 channelCode 构成永久幂等键",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "ALSC202608130001")
    @NotBlank(message = "渠道交易号不能为空")
    @Size(max = 128, message = "渠道交易号不能超过 128 个字符")
    private String transactionId;

    /** 受理时使用的渠道匿名用户标识快照 */
    @Schema(description = "受理时使用的渠道匿名用户标识快照",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "ch-anon-9f3a...")
    @NotBlank(message = "渠道匿名用户标识不能为空")
    @Size(max = 128, message = "渠道匿名用户标识不能超过 128 个字符")
    private String channelUserId;

    /** ele.me payAmount，渠道对账权威金额，单位为分 */
    @Schema(description = "ele.me payAmount，渠道对账权威金额，单位为分",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "10000")
    @NotNull(message = "渠道支付金额不能为空")
    @Positive(message = "渠道支付金额必须为正数")
    private Long payAmount;

    /** 渠道外部商户标识；用于校验 SupplierChannelBinding */
    @Schema(description = "渠道外部商户标识；用于校验 SupplierChannelBinding",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "HEMA-STORE-001")
    @NotBlank(message = "渠道外部商户标识不能为空")
    @Size(max = 128, message = "渠道外部商户标识不能超过 128 个字符")
    private String externalMerchantId;

    /** 渠道外部门店标识 */
    @Schema(description = "渠道外部门店标识", example = "STORE-001")
    @Size(max = 128, message = "渠道外部门店标识不能超过 128 个字符")
    private String externalStoreId;

    /** 渠道外部门店名称快照 */
    @Schema(description = "渠道外部门店名称快照", example = "盒马鲜生-XX店")
    @Size(max = 256, message = "渠道外部门店名称不能超过 256 个字符")
    private String externalStoreName;

    /** 交易主题/商品摘要 */
    @Schema(description = "交易主题/商品摘要", example = "盒马鲜生订单")
    @Size(max = 256, message = "交易主题不能超过 256 个字符")
    private String subject;
}
