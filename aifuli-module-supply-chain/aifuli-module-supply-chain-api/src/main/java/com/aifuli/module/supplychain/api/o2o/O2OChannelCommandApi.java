package com.aifuli.module.supplychain.api.o2o;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.o2o.dto.AcceptRefundReqDTO;
import com.aifuli.module.supplychain.api.o2o.dto.PaymentPreparationTicketDTO;
import com.aifuli.module.supplychain.api.o2o.dto.PreparePaymentReqDTO;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * O2O 渠道命令入口，由淘宝闪购渠道服务（taobao-flash-channel-service）调用。
 *
 * <p>信任域：渠道 Principal，scope = O2O_PAYMENT_PREPARE / O2O_REFUND_WRITE。
 * 幂等键：支付 (channelCode, transactionId)，退款 (channelCode, refundNo)。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4-目标架构与调用链}。</p>
 */
@FeignClient(name = ApiConstants.NAME, primary = false)
@Tag(name = "RPC 服务 - 供应链O2O渠道命令")
public interface O2OChannelCommandApi {

    String PREFIX = ApiConstants.PREFIX + "/o2o/channel";

    @PostMapping(PREFIX + "/preparePayment")
    @Operation(summary = "O2O 渠道支付预下单",
            description = "按 (channelCode,transactionId) 幂等建单并冻结资金，抢占后返回收银台 Ticket；本期收银台为 stub")
    CommonResult<PaymentPreparationTicketDTO> preparePayment(
            @Parameter(description = "O2O 支付预下单请求", required = true)
            @Valid @RequestBody PreparePaymentReqDTO request);

    @PostMapping(PREFIX + "/acceptRefund")
    @Operation(summary = "O2O 渠道退款受理",
            description = "按 (channelCode,refundNo) 全局幂等创建退款 Case 并增加退款预留；返回中台 refundCaseNo，供渠道服务回填 RefundResponse.outRefundNo")
    CommonResult<String> acceptRefund(
            @Parameter(description = "O2O 退款受理请求", required = true)
            @Valid @RequestBody AcceptRefundReqDTO request);
}
