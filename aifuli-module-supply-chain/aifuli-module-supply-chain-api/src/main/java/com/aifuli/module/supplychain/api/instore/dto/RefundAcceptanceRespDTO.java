package com.aifuli.module.supplychain.api.instore.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 到店购退款受理响应。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#22-命令侧响应-dto}。</p>
 */
@Schema(description = "到店购退款受理响应")
@Data
public class RefundAcceptanceRespDTO implements Serializable {

    /** 中台退款稳定业务编号 */
    @Schema(description = "中台退款稳定业务编号", example = "IRC_9")
    private String refundCaseNo;

    /** 退款状态：10排队中，20采购商退款中 */
    @Schema(description = "退款状态：10排队中，20采购商退款中", example = "20")
    private Integer refundStatus;

    /** 是否抢占派发（本 Case 立即外呼收银台） */
    @Schema(description = "是否抢占派发（本 Case 立即外呼收银台）", example = "true")
    private Boolean claimedForDispatch;
}
