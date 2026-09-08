package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.fulfillment.BuyerFulfillmentCommandApi;
import com.aifuli.module.supplychain.api.fulfillment.dto.AcceptFulfillmentReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAcceptanceReceiptRespDTO;
import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentCommandFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static com.aifuli.framework.common.pojo.CommonResult.success;

/**
 * 采购商标准履约受理 RPC 实现。
 *
 * <p>本类只做入参日志与委托 Facade。buyerUserId 按契约不得写入日志。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#7.2-标准履约受理}。</p>
 */
@Slf4j
@RestController
@Primary
@Validated
@RequiredArgsConstructor
public class BuyerFulfillmentCommandApiImpl implements BuyerFulfillmentCommandApi {

    private final BuyerFulfillmentCommandFacade facade;

    /**
     * 标准履约受理入口：记录采购商订单号后委托 Facade。
     */
    @Override
    public CommonResult<FulfillmentAcceptanceReceiptRespDTO> acceptFulfillment(
            AcceptFulfillmentReqDTO request) {
        log.info("[履约][rpc] acceptFulfillment 入口 sourceSystem={}, externalTenantId={}, buyerOrderNo={}, buyerSkuCode={}, quantity={}",
                request.getSourceSystem(), request.getExternalTenantId(), request.getBuyerOrderNo(),
                request.getBuyerSkuCode(), request.getQuantity());
        FulfillmentAcceptanceReceiptRespDTO response = facade.acceptFulfillment(request);
        log.info("[履约][rpc] acceptFulfillment 出口 businessNo={}, receiptStatus={}, orderNo={}",
                response.getBusinessNo(), response.getReceiptStatus(), response.getOrderNo());
        return success(response);
    }
}
