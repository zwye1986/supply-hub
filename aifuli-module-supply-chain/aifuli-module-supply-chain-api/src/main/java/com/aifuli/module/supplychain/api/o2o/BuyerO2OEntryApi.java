package com.aifuli.module.supplychain.api.o2o;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.o2o.dto.O2OEntryReqDTO;
import com.aifuli.module.supplychain.api.o2o.dto.O2OEntryRespDTO;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 采购商 App 获取 O2O H5 入口，由采购商商城调用。
 *
 * <p>信任域：采购商 Principal，scope = O2O_ENTRY。中台先读取或创建 O2OChannelUserBinding，
 * 再以匿名 channelUserId 调渠道 Port 获取 H5 入口；内部 buyerId/buyerUserId 不得暴露给渠道。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4-目标架构与调用链}。</p>
 */
@FeignClient(name = ApiConstants.NAME, primary = false)
@Tag(name = "RPC 服务 - 供应链O2O入口")
public interface BuyerO2OEntryApi {

    String PREFIX = ApiConstants.PREFIX + "/buyer/o2o";

    @PostMapping(PREFIX + "/getH5Entry")
    @Operation(summary = "获取 O2O H5 入口",
            description = "校验 Buyer、BuyerUser 与场景接入配置后生成渠道匿名用户标识与 H5 入口；盒马差异仅体现在 H5 URL 配置")
    CommonResult<O2OEntryRespDTO> getH5Entry(
            @Parameter(description = "O2O H5 入口请求", required = true)
            @Valid @RequestBody O2OEntryReqDTO request);
}
