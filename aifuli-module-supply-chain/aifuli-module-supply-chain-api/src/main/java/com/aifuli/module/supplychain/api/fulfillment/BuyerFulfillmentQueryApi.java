package com.aifuli.module.supplychain.api.fulfillment;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAcceptanceReceiptRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAssetQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAssetRespDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentReceiptQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentResultQueryReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentResultRespDTO;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@FeignClient(name = ApiConstants.NAME, primary = false)
@Tag(name = "RPC 服务 - 供应链履约查询")
public interface BuyerFulfillmentQueryApi {

    String PREFIX = ApiConstants.PREFIX + "/buyer/fulfillment";

    @GetMapping(PREFIX + "/receipt")
    @Operation(summary = "按永久业务键查询履约受理回执",
            description = "RPC 超时后使用该接口确认中台是否已经受理")
    CommonResult<FulfillmentAcceptanceReceiptRespDTO> getAcceptanceReceipt(
            @Parameter(description = "受理回执查询条件", required = true)
            @Valid @SpringQueryMap FulfillmentReceiptQueryReqDTO request);

    @GetMapping(PREFIX + "/result")
    @Operation(summary = "查询履约结果摘要", description = "不返回卡号、卡密、券码或履约目标")
    CommonResult<FulfillmentResultRespDTO> getFulfillmentResult(
            @Parameter(description = "履约结果查询条件", required = true)
            @Valid @SpringQueryMap FulfillmentResultQueryReqDTO request);

    @GetMapping(PREFIX + "/assets")
    @Operation(summary = "授权查询履约敏感资产",
            description = "同时校验采购商、采购商用户归属和 FULFILLMENT_ASSET_READ Scope，并写不可变审计")
    CommonResult<List<FulfillmentAssetRespDTO>> getFulfillmentAssets(
            @Parameter(description = "敏感资产查询条件", required = true)
            @Valid @SpringQueryMap FulfillmentAssetQueryReqDTO request);
}
