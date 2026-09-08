package com.aifuli.module.member.api.sso;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.member.api.sso.dto.MemberSsoTokenReqDTO;
import com.aifuli.module.member.api.sso.dto.MemberSsoTokenRespDTO;
import com.aifuli.module.member.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 会员 SSO 令牌 RPC：按 SaaS 外部身份三元组创建指定平台的会员登录态。
 *
 * <p>供应链只透传 {@code sourceSystem + externalTenantId + buyerUserId}，采购商用户标识仅允许
 * member-server 解释为内部会员主键。详细设计见
 * {@code docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md#31-member-api}。</p>
 */
@FeignClient(name = ApiConstants.NAME)
@Tag(name = "RPC 服务 - 会员 SSO 令牌")
public interface MemberSsoTokenApi {

    String PREFIX = ApiConstants.PREFIX + "/sso";

    /**
     * 按外部三元身份创建会员 SSO 访问令牌。
     *
     * @param request SaaS 外部身份三元组
     * @return 仅包含访问令牌；会员无法解析时 data 为 {@code null}
     */
    @PostMapping(PREFIX + "/token")
    @Operation(summary = "按外部三元身份创建会员 SSO 令牌")
    CommonResult<MemberSsoTokenRespDTO> createToken(@Valid @RequestBody MemberSsoTokenReqDTO request);
}
