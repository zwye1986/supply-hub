package com.aifuli.module.supplychain.api.fulfillment;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.fulfillment.dto.AcceptFulfillmentReqDTO;
import com.aifuli.module.supplychain.api.fulfillment.dto.FulfillmentAcceptanceReceiptRespDTO;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = ApiConstants.NAME, primary = false)
@Tag(name = "RPC 服务 - 供应链履约受理")
public interface BuyerFulfillmentCommandApi {

    String PREFIX = ApiConstants.PREFIX + "/buyer/fulfillment";

    @PostMapping(PREFIX + "/accept")
    @Operation(summary = "受理标准商品履约",
            description = "一期只接受单商品、数量一和 CNY 结算；projectCode 用于项目级 SKU 限购隔离；"
                    + "受理成功不表示供应商已完成履约")
    CommonResult<FulfillmentAcceptanceReceiptRespDTO> acceptFulfillment(
            @Parameter(description = "标准商品履约受理请求", required = true)
            @Valid @RequestBody AcceptFulfillmentReqDTO request);
}
