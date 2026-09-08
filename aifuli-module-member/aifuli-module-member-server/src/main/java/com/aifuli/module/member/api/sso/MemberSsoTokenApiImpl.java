package com.aifuli.module.member.api.sso;

import com.aifuli.framework.common.enums.CommonStatusEnum;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.member.api.sso.dto.MemberSsoTokenReqDTO;
import com.aifuli.module.member.api.sso.dto.MemberSsoTokenRespDTO;
import com.aifuli.module.member.controller.app.auth.vo.AppAuthLoginRespVO;
import com.aifuli.module.member.dal.dataobject.user.MemberUserDO;
import com.aifuli.module.member.service.auth.MemberAuthService;
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
 * 会员 SSO 令牌 RPC 实现：在可信租户边界内解释采购商用户标识并创建会员登录态。
 *
 * <p>路由校验先于主键解析；不存在、禁用或不可解释的会员统一返回空成功，基础设施或发令牌失败
 * 保持异常上抛。平台码固定为 {@code rtmart}，日志不得包含身份原文和访问令牌。详细设计见
 * {@code docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md#32-member-server}。</p>
 */
@RestController
@Validated
@RequiredArgsConstructor
@Slf4j
public class MemberSsoTokenApiImpl implements MemberSsoTokenApi {

    private static final String PLATFORM_CODE_RTMART = "rtmart";

    private final MemberUserService userService;
    private final MemberAuthService authService;

    /** 按可信外部三元身份创建短期会员访问令牌。 */
    @Override
    public CommonResult<MemberSsoTokenRespDTO> createToken(MemberSsoTokenReqDTO request) {
        long startedAt = System.nanoTime();
        log.info("[MEMBER][sso-token] request sourceSystem={}", safeSourceSystem(request.getSourceSystem()));
        validateRoute(request.getSourceSystem(), request.getExternalTenantId());

        Long userId = parseMemberUserId(request.getBuyerUserId());
        if (userId == null) {
            logCompletion(request.getSourceSystem(), "NOT_FOUND", false, startedAt);
            return success(null);
        }
        MemberUserDO user = userService.getUser(userId);
        if (user == null || !CommonStatusEnum.isEnable(user.getStatus())) {
            logCompletion(request.getSourceSystem(), "NOT_FOUND", false, startedAt);
            return success(null);
        }

        AppAuthLoginRespVO login = authService.createTokenAfterSsoLogin(userId, PLATFORM_CODE_RTMART);
        if (login == null || login.getAccessToken() == null || login.getAccessToken().isBlank()) {
            throw new IllegalStateException("member SSO token response is empty");
        }
        MemberSsoTokenRespDTO response = new MemberSsoTokenRespDTO();
        response.setAccessToken(login.getAccessToken());
        logCompletion(request.getSourceSystem(), "CREATED", true, startedAt);
        return success(response);
    }

    private void logCompletion(String sourceSystem, String status, boolean hasToken, long startedAt) {
        log.info("[MEMBER][sso-token] response sourceSystem={}, status={}, hasToken={}, costMs={}",
                safeSourceSystem(sourceSystem), status, hasToken,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }
}
