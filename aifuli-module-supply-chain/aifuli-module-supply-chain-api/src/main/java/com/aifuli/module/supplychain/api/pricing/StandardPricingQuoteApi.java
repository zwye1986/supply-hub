package com.aifuli.module.supplychain.api.pricing;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteReqDTO;
import com.aifuli.module.supplychain.api.pricing.dto.StandardPricingQuoteRespDTO;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = ApiConstants.NAME, primary = false)
@Tag(name = "RPC 服务 - 标准商品项目报价")
public interface StandardPricingQuoteApi {
    String PREFIX = ApiConstants.PREFIX + "/buyer/pricing";

    @PostMapping(PREFIX + "/quote")
    @Operation(summary = "按项目+项目订单号签发或重放永久冻结的标准商品首次报价")
    CommonResult<StandardPricingQuoteRespDTO> quote(@Valid @RequestBody StandardPricingQuoteReqDTO request);
}
