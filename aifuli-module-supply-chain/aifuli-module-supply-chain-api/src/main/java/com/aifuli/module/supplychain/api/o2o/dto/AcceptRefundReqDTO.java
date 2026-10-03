package com.aifuli.module.supplychain.api.o2o.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Schema(description = "O2O 渠道退款受理请求")
@Data
public class AcceptRefundReqDTO implements Serializable {

    /** O2O渠道编码，一期为ELEME */
    @Schema(description = "O2O渠道编码，一期为ELEME", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "ELEME")
    @NotBlank(message = "O2O 渠道编码不能为空")
    @Size(max = 32, message = "O2O 渠道编码不能超过 32 个字符")
    private String channelCode;

    /** ele.me等渠道退款唯一编号 */
    @Schema(description = "ele.me等渠道退款唯一编号",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "RF202608130001")
    @NotBlank(message = "渠道退款号不能为空")
    @Size(max = 128, message = "渠道退款号不能超过 128 个字符")
    private String channelRefundNo;

    /** 渠道交易唯一标识 */
    @Schema(description = "渠道交易唯一标识",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "ALSC202608130001")
    @NotBlank(message = "渠道交易号不能为空")
    @Size(max = 128, message = "渠道交易号不能超过 128 个字符")
    private String transactionId;

    /** 退款类型：CHANNEL_REFUND渠道退款，REJECTION_COMPENSATION拒付补偿，LATE_PAYMENT_COMPENSATION迟到支付补偿 */
    @Schema(description = "退款类型：CHANNEL_REFUND渠道退款，REJECTION_COMPENSATION拒付补偿，LATE_PAYMENT_COMPENSATION迟到支付补偿",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "CHANNEL_REFUND")
    @NotBlank(message = "退款类型不能为空")
    @Size(max = 32, message = "退款类型不能超过 32 个字符")
    private String refundType;

    /** 渠道要求退款金额，单位为分 */
    @Schema(description = "渠道要求退款金额，单位为分", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "10000")
    @NotNull(message = "渠道退款金额不能为空")
    @Positive(message = "渠道退款金额必须为正数")
    private Long channelRefundAmount;
}
