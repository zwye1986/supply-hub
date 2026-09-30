package com.aifuli.module.supplychain.application.facade;

import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAcceptanceReceiptRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAssetQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAssetRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentResultQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentResultRespDTO;
import com.aifuli.module.supplychain.border.fulfillment.application.BuyerFulfillmentQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;

/**
 * 采购商履约查询门面：参数校验后委派领域查询服务，禁止直接依赖 DAL。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuyerFulfillmentQueryFacade {

    private final BuyerFulfillmentQueryService queryService;

    public FulfillmentAcceptanceReceiptRespDTO getAcceptanceReceipt(FulfillmentReceiptQueryReqDTO request) {
        Objects.requireNonNull(request, "request");
        return queryService.getAcceptanceReceipt(request);
    }

    public FulfillmentResultRespDTO getFulfillmentResult(FulfillmentResultQueryReqDTO request) {
        Objects.requireNonNull(request, "request");
        return queryService.getFulfillmentResult(request);
    }

    public List<FulfillmentAssetRespDTO> getFulfillmentAssets(FulfillmentAssetQueryReqDTO request) {
        Objects.requireNonNull(request, "request");
        if (isBlank(request.getFulfillmentRequestNo()) || isBlank(request.getBuyerUserId())
                || isBlank(request.getSourceSystem()) || isBlank(request.getExternalTenantId())
                || isBlank(request.getScopeCode())) {
            throw exception(BUYER_AUTH_INVALID);
        }
        return queryService.getFulfillmentAssets(request);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
