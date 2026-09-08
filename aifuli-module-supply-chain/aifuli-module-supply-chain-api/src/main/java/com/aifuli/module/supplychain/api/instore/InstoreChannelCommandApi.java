package com.aifuli.module.supplychain.api.instore;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.dto.AcceptRefundReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CancelPaymentFlowReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.CancelPaymentFlowRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.CreatePaymentOrderReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.PaymentStatusRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.PaymentTicketRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.QueryPaymentStatusReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.QueryRefundStatusReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.RefundAcceptanceRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.RefundStatusRespDTO;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import jakarta.validation.Valid;

/**
 * 到店购支付/退款命令 RPC：渠道服务按旅通接口②③④⑤⑥调用。
 *
 * <p>projectId/channelCode 由服务端可信渠道上下文解析，禁止由调用方提交；
 * 幂等键为支付 {@code (channelCode, tradeId)}、退款 {@code (channelCode, refund_bn)}、
 * 订单头 {@code (channelCode, channelOrderId)}。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#2-api-层}。</p>
 */
@FeignClient(name = ApiConstants.NAME, primary = false)
@Tag(name = "RPC 服务 - 到店购支付退款命令")
public interface InstoreChannelCommandApi {

    String PREFIX = ApiConstants.PREFIX + "/instore/channel-command";

    @PostMapping(PREFIX + "/createPaymentOrder")
    @Operation(summary = "受理到店购支付单（旅通接口②）")
    CommonResult<PaymentTicketRespDTO> createPaymentOrder(
            @Valid @RequestBody CreatePaymentOrderReqDTO request);

    @PostMapping(PREFIX + "/queryPaymentStatus")
    @Operation(summary = "查询到店购支付状态（旅通接口③，本地非终态触发权威查询收敛）")
    CommonResult<PaymentStatusRespDTO> queryPaymentStatus(
            @Valid @RequestBody QueryPaymentStatusReqDTO request);

    @PostMapping(PREFIX + "/cancelPaymentFlow")
    @Operation(summary = "取消到店购支付流水（旅通接口④，已支付转全额退款）")
    CommonResult<CancelPaymentFlowRespDTO> cancelPaymentFlow(
            @Valid @RequestBody CancelPaymentFlowReqDTO request);

    @PostMapping(PREFIX + "/acceptRefund")
    @Operation(summary = "受理到店购退款（旅通接口⑤，同流水串行）")
    CommonResult<RefundAcceptanceRespDTO> acceptRefund(
            @Valid @RequestBody AcceptRefundReqDTO request);

    @PostMapping(PREFIX + "/queryRefundStatus")
    @Operation(summary = "查询到店购退款状态（旅通接口⑥，非终态按原退款号权威查询）")
    CommonResult<RefundStatusRespDTO> queryRefundStatus(
            @Valid @RequestBody QueryRefundStatusReqDTO request);
}
