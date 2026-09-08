package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.InstoreChannelMemberApi;
import com.aifuli.module.supplychain.api.instore.dto.InstoreMemberRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.ResolveInstoreMemberReqDTO;
import com.aifuli.module.supplychain.application.facade.InstoreChannelMemberFacade;
import com.aifuli.module.supplychain.application.facade.InstoreChannelMemberFacade.ResolveMemberCommand;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.ChannelContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import static com.aifuli.framework.common.pojo.CommonResult.success;

/**
 * 旅通渠道会员解析 RPC 实现（组合部署本地 @Primary；拆分部署由 Feign 走 ApiImpl）。
 *
 * <p>渠道身份只从 {@link ProjectCallContextAccessor} 的 ChannelContext 读取，
 * 请求 DTO 只提供 token；userCode 以不透明字符串原样透传，禁止数值化转换。
 * 本类不注入 Resolver、Service 或 Mapper。日志不得打印 token、手机号、姓名或
 * userCode。详见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层}。</p>
 */
@Slf4j
@RestController
@Primary
@Validated
@RequiredArgsConstructor
public class InstoreChannelMemberApiImpl implements InstoreChannelMemberApi {

    private final ProjectCallContextAccessor contextAccessor;
    private final InstoreChannelMemberFacade facade;

    /**
     * 解析到店购渠道会员：读取可信渠道上下文后委托 Facade，一次性凭证只进认领链路。
     */
    @Override
    public CommonResult<InstoreMemberRespDTO> resolveMember(ResolveInstoreMemberReqDTO request) {
        ChannelContext context = contextAccessor.requireChannelContext();
        log.info("[INSTORE][rpc] resolveMember 入口 projectId={}, channelCode={}",
                context.projectId(), context.channelCode());
        InstoreMemberRespDTO response = facade.resolveMember(new ResolveMemberCommand(
                context.projectId(), context.channelCode(), request.getToken()));
        log.info("[INSTORE][rpc] resolveMember 出口 projectId={}, channelCode={}",
                context.projectId(), context.channelCode());
        return success(response);
    }
}
