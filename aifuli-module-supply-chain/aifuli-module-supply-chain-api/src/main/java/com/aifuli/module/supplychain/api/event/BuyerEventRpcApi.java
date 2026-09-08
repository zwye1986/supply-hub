package com.aifuli.module.supplychain.api.event;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.event.dto.BuyerEventAck;
import com.aifuli.module.supplychain.api.event.dto.BuyerEventEnvelope;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = ApiConstants.BUYER_SERVICE_NAME, primary = false)
@Tag(name = "RPC 服务 - 采购商商城事件接收")
public interface BuyerEventRpcApi {

    String PREFIX = ApiConstants.PREFIX + "/buyer/events";

    @PostMapping(PREFIX + "/receive")
    @Operation(summary = "接收供应链中台事件",
            description = "商城先以 eventId 和载荷摘要持久化 Inbox 与投影，再返回确认")
    CommonResult<BuyerEventAck> receiveEvent(
            @Parameter(description = "供应链中台事件信封", required = true)
            @Valid @RequestBody BuyerEventEnvelope event);
}
