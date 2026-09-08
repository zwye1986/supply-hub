package com.aifuli.module.supplychain.api.instore;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.dto.InstoreMemberRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.ResolveInstoreMemberReqDTO;
import com.aifuli.module.supplychain.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 到店购渠道会员解析 RPC；渠道身份与项目范围由可信上下文提供，接口只接收短期凭证。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层}。</p>
 */
@FeignClient(name = ApiConstants.NAME, primary = false)
@Tag(name = "RPC 服务 - 到店购渠道会员")
public interface InstoreChannelMemberApi {

    String PREFIX = ApiConstants.PREFIX + "/instore/channel-member";

    @PostMapping(PREFIX + "/resolve")
    @Operation(summary = "解析到店购渠道会员")
    CommonResult<InstoreMemberRespDTO> resolveMember(
            @Parameter(description = "到店购渠道会员解析请求", required = true)
            @Valid @RequestBody ResolveInstoreMemberReqDTO request);
}
