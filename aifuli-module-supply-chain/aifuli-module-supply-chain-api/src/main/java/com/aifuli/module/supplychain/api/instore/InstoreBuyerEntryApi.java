package com.aifuli.module.supplychain.api.instore;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.dto.HomeEntryReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.InstoreEntryUrlRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.OrderDetailEntryReqDTO;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 采购商侧到店购入口 RPC；调用方身份、项目和采购商范围均从可信上下文读取，不能由请求体传入。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层}。</p>
 */
@FeignClient(name = ApiConstants.NAME, primary = false)
@Tag(name = "RPC 服务 - 到店购采购商入口")
public interface InstoreBuyerEntryApi {

    String PREFIX = ApiConstants.PREFIX + "/instore/buyer-entry";

    @PostMapping(PREFIX + "/home")
    @Operation(summary = "获取到店购首页入口")
    CommonResult<InstoreEntryUrlRespDTO> getHomeEntry(
            @Parameter(description = "到店购首页入口请求", required = true)
            @Valid @RequestBody HomeEntryReqDTO request);

    @PostMapping(PREFIX + "/detail")
    @Operation(summary = "获取到店购订单详情入口")
    CommonResult<InstoreEntryUrlRespDTO> getOrderDetailEntry(
            @Parameter(description = "到店购订单详情入口请求", required = true)
            @Valid @RequestBody OrderDetailEntryReqDTO request);
}
