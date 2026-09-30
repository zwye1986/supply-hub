package com.aifuli.module.member.api.profile;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.member.api.profile.dto.MemberProfileResolveReqDTO;
import com.aifuli.module.member.api.profile.dto.MemberProfileResolveRespDTO;
import com.aifuli.module.member.dal.dataobject.user.MemberUserDO;
import com.aifuli.module.member.service.user.MemberUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.module.member.api.profile.MemberExternalIdentitySupport.parseMemberUserId;
import static com.aifuli.module.member.api.profile.MemberExternalIdentitySupport.safeSourceSystem;
import static com.aifuli.module.member.api.profile.MemberExternalIdentitySupport.validateRoute;

/**
 * 会员资料三元身份解析 RPC 实现。
 *
 * <p>本类是唯一将采购商用户字符串解释为 {@code member_user.id} 的 member-server 边界；
 * 路由校验必须先于内部主键解析和会员查询，以避免跨来源或跨租户读取。</p>
 *
 * <p>详细设计：docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#32-member-server</p>
 */
@RestController
@Validated
@RequiredArgsConstructor
@Slf4j
public class MemberProfileResolveApiImpl implements MemberProfileResolveApi {

    private final MemberUserService userService;

    /**
     * 根据来源系统、外部租户和采购商用户标识解析可展示的会员资料。
     *
     * <p>路由不可信时抛出稳定业务异常；内部主键格式不合法、用户不存在或没有有效手机号时统一返回空成功，
     * 查询基础设施异常则保持原样上抛，不能伪装为未找到。</p>
     *
     * <p>详细设计：docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#32-member-server</p>
     *
     * @param request SaaS 外部身份三元组
     * @return 仅包含手机号与展示姓名的资料，未解析到时 data 为 {@code null}
     */
    @Override
    public CommonResult<MemberProfileResolveRespDTO> resolve(MemberProfileResolveReqDTO request) {
        long startedAt = System.nanoTime();
        log.info("[MEMBER][profile-resolve] request sourceSystem={}", safeSourceSystem(request.getSourceSystem()));
        validateRoute(request.getSourceSystem(), request.getExternalTenantId());

        Long userId = parseMemberUserId(request.getBuyerUserId());
        if (userId == null) {
            logCompletion(request.getSourceSystem(), "NOT_FOUND", startedAt);
            return success(null);
        }

        MemberUserDO user = userService.getUser(userId);
        if (user == null || !hasText(user.getMobile())) {
            logCompletion(request.getSourceSystem(), "NOT_FOUND", startedAt);
            return success(null);
        }

        MemberProfileResolveRespDTO response = new MemberProfileResolveRespDTO();
        response.setMobile(user.getMobile());
        response.setUserName(hasText(user.getName()) ? user.getName() : user.getNickname());
        logCompletion(request.getSourceSystem(), "FOUND", startedAt);
        return success(response);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void logCompletion(String sourceSystem, String status, long startedAt) {
        log.info("[MEMBER][profile-resolve] response sourceSystem={}, status={}, costMs={}",
                safeSourceSystem(sourceSystem), status,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }
}
