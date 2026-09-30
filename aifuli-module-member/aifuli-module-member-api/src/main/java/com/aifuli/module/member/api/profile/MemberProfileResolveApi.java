package com.aifuli.module.member.api.profile;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.member.api.profile.dto.MemberProfileResolveReqDTO;
import com.aifuli.module.member.api.profile.dto.MemberProfileResolveRespDTO;
import com.aifuli.module.member.enums.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 会员资料解析 RPC：以 SaaS 外部身份三元组解析会员可展示资料。
 *
 * <p>调用方必须完整提供来源系统、外部租户和采购商用户标识，避免跨租户或跨来源误关联。</p>
 *
 * <p>详细设计：docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#31-member-api</p>
 */
@FeignClient(name = ApiConstants.NAME)
@Tag(name = "RPC 服务 - 会员资料解析")
public interface MemberProfileResolveApi {

    String PREFIX = ApiConstants.PREFIX + "/profile";

    /**
     * 按 SaaS 外部身份三元组解析会员资料。
     *
     * <p>仅返回手机号码与用户名；实现方不得从不完整的身份键推断会员归属。</p>
     *
     * <p>详细设计：docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#31-member-api</p>
     *
     * @param request SaaS 外部身份三元组
     * @return 已脱敏日志约束下可供调用方使用的会员资料
    */
    @PostMapping(PREFIX + "/resolve")
    @Operation(summary = "按外部三元身份解析会员资料")
    CommonResult<MemberProfileResolveRespDTO> resolve(@Valid @RequestBody MemberProfileResolveReqDTO request);

}
