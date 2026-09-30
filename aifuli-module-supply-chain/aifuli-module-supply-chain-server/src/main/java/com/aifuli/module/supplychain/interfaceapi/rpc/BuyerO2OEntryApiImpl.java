package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.o2o.BuyerO2OEntryApi;
import com.aifuli.module.supplychain.api.o2o.dto.O2OEntryReqDTO;
import com.aifuli.module.supplychain.api.o2o.dto.O2OEntryRespDTO;
import com.aifuli.module.supplychain.application.facade.BuyerO2OEntryFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static com.aifuli.framework.common.pojo.CommonResult.success;

/**
 * 采购商 O2O H5 入口 RPC 实现（组合部署本地 @Primary；拆分部署由 Feign 走 ApiImpl）。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.4-目标架构与调用链}。</p>
 */
@Slf4j
@RestController
@Primary
@Validated
@RequiredArgsConstructor
public class BuyerO2OEntryApiImpl implements BuyerO2OEntryApi {

    private final BuyerO2OEntryFacade facade;

    /**
     * 采购商获取 O2O H5 入口：记录租户与渠道后委托 Facade。
     */
    @Override
    public CommonResult<O2OEntryRespDTO> getH5Entry(O2OEntryReqDTO request) {
        log.info("[O2O][rpc] getH5Entry 入口 sourceSystem={}, externalTenantId={}, channelCode={}, sceneCode={}",
                request.getSourceSystem(), request.getExternalTenantId(),
                request.getChannelCode(), request.getSceneCode());
        O2OEntryRespDTO response = facade.getH5Entry(request);
        log.info("[O2O][rpc] getH5Entry 出口 channelUserId={}", response.getChannelUserId());
        return success(response);
    }
}
