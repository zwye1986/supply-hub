package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购退款状态响应：本地最新投影（触发权威查询时已收敛）。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#22-命令侧响应-dto}。</p>
 */
@Schema(description = "到店购退款状态响应")
@Data
public class RefundStatusRespDTO implements Serializable {

    /** 中台退款稳定业务编号 */
    @Schema(description = "中台退款稳定业务编号", example = "IRC_9")
    private String refundCaseNo;

    /** 旅通退款单号refund_bn，与channel_code构成退款永久幂等键 */
    @Schema(description = "旅通退款单号refund_bn，与channel_code构成退款永久幂等键", example = "RT-REFUND-001")
    private String channelRefundNo;

    /** 退款类型：CHANNEL_REFUND渠道发起退款，CANCEL_REFUND取消已支付流水触发的退款 */
    @Schema(description = "退款类型：CHANNEL_REFUND渠道发起退款，CANCEL_REFUND取消已支付流水触发的退款",
            example = "CHANNEL_REFUND")
    private String refundType;

    /** 退款状态：10排队中，20采购商退款中，30退款成功，40退款失败，60已完成，90人工处理；0待处理为内部初始态，Wire 层不会返回（Case 创建即为 10） */
    @Schema(description = "退款状态：10排队中，20采购商退款中，30退款成功，40退款失败，60已完成，90人工处理；"
            + "0待处理为内部初始态，Wire 层不会返回（Case 创建即为 10）",
            example = "30")
    private Integer status;

    /** 渠道要求退款金额，单位为分 */
    @Schema(description = "渠道要求退款金额，单位为分", example = "3000")
    private Long channelRefundAmount;

    /** 采购商实际退给C端用户的金额，单位为分；未收敛为空 */
    @Schema(description = "采购商实际退给C端用户的金额，单位为分；未收敛为空", example = "3000")
    private Long buyerActualRefundAmount;
}
