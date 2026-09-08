package com.aifuli.module.supplychain.api.instore;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.dto.ConfirmPaymentReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.ReportRefundResultReqDTO;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import jakarta.validation.Valid;

/**
 * SaaS 商城收银台回调 RPC：支付确认与退款结果上报。
 *
 * <p>回调只提交原业务号与实际金额，归属按 outTradeNo/refundCaseNo 加载订单固化数据，
 * 已认证 SaaS 租户主体由可信项目上下文核验，回调参数不得改变归属。详见
 * {@code docs/superpowers/specs/2026-08-23-instore-payment-refund-api-contract-design.md#23-回调侧-dto-字段边界}。</p>
 */
@FeignClient(name = ApiConstants.NAME, primary = false)
@Tag(name = "RPC 服务 - 到店购收银台回调")
public interface InstoreBuyerCashierCallbackApi {

    String PREFIX = ApiConstants.PREFIX + "/buyer/instore/payment";

    @PostMapping(PREFIX + "/confirmPayment")
    @Operation(summary = "确认到店购支付成功（按 outTradeNo 收敛）")
    CommonResult<Void> confirmPayment(@Valid @RequestBody ConfirmPaymentReqDTO request);

    @PostMapping(PREFIX + "/reportRefundResult")
    @Operation(summary = "上报到店购退款结果（按 refundCaseNo 收敛）")
    CommonResult<Void> reportRefundResult(@Valid @RequestBody ReportRefundResultReqDTO request);
}
