package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.fulfillment.BuyerFulfillmentQueryApi;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAcceptanceReceiptRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAssetQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAssetRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentResultQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentResultRespDTO;
import com.aifuli.module.supplychain.application.facade.BuyerFulfillmentQueryFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.aifuli.framework.common.pojo.CommonResult.success;

/**
 * 采购商标准履约查询 RPC 实现。
 *
 * <p>本类只做日志与委托 Facade。buyerUserId 及敏感凭证按契约不得写入明文业务日志。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}。</p>
 */
@Slf4j
@RestController
@Primary
@Validated
@RequiredArgsConstructor
public class BuyerFulfillmentQueryApiImpl implements BuyerFulfillmentQueryApi {

    private final BuyerFulfillmentQueryFacade facade;

    @Override
    public CommonResult<FulfillmentAcceptanceReceiptRespDTO> getAcceptanceReceipt(FulfillmentReceiptQueryReqDTO request) {
        log.info("[履约查询][rpc] getAcceptanceReceipt 入口 sourceSystem={}, externalTenantId={}, buyerOrderNo={}",
                request.getSourceSystem(), request.getExternalTenantId(), request.getBuyerOrderNo());
        FulfillmentAcceptanceReceiptRespDTO response = facade.getAcceptanceReceipt(request);
        log.info("[履约查询][rpc] getAcceptanceReceipt 出口 businessNo={}, receiptStatus={}, orderNo={}",
                response.getBusinessNo(), response.getReceiptStatus(), response.getOrderNo());
        return success(response);
    }

    @Override
    public CommonResult<FulfillmentResultRespDTO> getFulfillmentResult(FulfillmentResultQueryReqDTO request) {
        log.info("[履约查询][rpc] getFulfillmentResult 入口 sourceSystem={}, externalTenantId={}, buyerOrderNo={}",
                request.getSourceSystem(), request.getExternalTenantId(), request.getBuyerOrderNo());
        FulfillmentResultRespDTO response = facade.getFulfillmentResult(request);
        log.info("[履约查询][rpc] getFulfillmentResult 出口 fulfillmentRequestNo={}, fulfillmentStatus={}, orderNo={}",
                response.getFulfillmentRequestNo(), response.getFulfillmentStatus(), response.getOrderNo());
        return success(response);
    }

    @Override
    public CommonResult<List<FulfillmentAssetRespDTO>> getFulfillmentAssets(FulfillmentAssetQueryReqDTO request) {
        log.info("[履约查询][rpc] getFulfillmentAssets 入口 sourceSystem={}, externalTenantId={}, fulfillmentRequestNo={}",
                request.getSourceSystem(), request.getExternalTenantId(), request.getFulfillmentRequestNo());
        List<FulfillmentAssetRespDTO> response = facade.getFulfillmentAssets(request);
        log.info("[履约查询][rpc] getFulfillmentAssets 出口 assetCount={}", response != null ? response.size() : 0);
        return success(response);
    }
}
