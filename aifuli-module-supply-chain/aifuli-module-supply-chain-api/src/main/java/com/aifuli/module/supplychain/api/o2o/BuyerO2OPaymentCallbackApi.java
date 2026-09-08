package com.aifuli.module.supplychain.api.o2o;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.o2o.dto.ConfirmPaymentReqDTO;
import com.aifuli.module.supplychain.api.o2o.dto.ReportRefundResultReqDTO;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 采购商收银台回调入口，由 SaaS 商城收银台调用。
 *
 * <p>信任域：采购商收银台 Principal，scope = O2O_PAYMENT_CALLBACK / O2O_REFUND_CALLBACK。
 * 回调按 outTradeNo / middleRefundNo 加载已固化的 Buyer、BuyerUser 与金额，再核对 Principal，
 * 不依赖回调参数重新传递归属。详见
 * {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4-目标架构与调用链}。</p>
 */
@FeignClient(name = ApiConstants.NAME, primary = false)
@Tag(name = "RPC 服务 - 供应链O2O收银台回调")
public interface BuyerO2OPaymentCallbackApi {

    String PREFIX = ApiConstants.PREFIX + "/buyer/o2o/payment";

    @PostMapping(PREFIX + "/confirmPayment")
    @Operation(summary = "确认采购商支付",
            description = "按 outTradeNo 加载固化归属后确认 FundReservation、追加账本、更新详情并写通知 Outbox")
    CommonResult<Void> confirmPayment(
            @Parameter(description = "采购商支付确认请求", required = true)
            @Valid @RequestBody ConfirmPaymentReqDTO request);

    @PostMapping(PREFIX + "/reportRefundResult")
    @Operation(summary = "上报采购商退款结果",
            description = "按 middleRefundNo 加载固化归属后确认或释放退款预留、返还账户并写通知 Outbox")
    CommonResult<Void> reportRefundResult(
            @Parameter(description = "采购商退款结果上报请求", required = true)
            @Valid @RequestBody ReportRefundResultReqDTO request);
}
