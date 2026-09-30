package com.aifuli.module.member.api.profile;

import com.aifuli.framework.tenant.core.context.TenantContextHolder;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.member.enums.ErrorCodeConstants.MEMBER_PROFILE_ROUTE_INVALID;

/**
 * member-server 外部会员身份解释公共边界。
 *
 * <p>必须先校验来源系统与租户上下文，再把 buyerUserId 解释为内部会员主键；供应链等调用方
 * 禁止复制该转换。详细设计见
 * {@code docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md#32-member-server}。</p>
 */
public final class MemberExternalIdentitySupport {

    /** 校验外部来源和租户头与请求三元组严格一致。 */
    public static void validateRoute(String sourceSystem, String externalTenantId) {
        Long tenantId = TenantContextHolder.getTenantId();
        if (!MemberProfileSourceSystemConstants.AIFULI_MALL.equals(sourceSystem)
                || tenantId == null || !tenantId.toString().equals(externalTenantId)) {
            throw exception(MEMBER_PROFILE_ROUTE_INVALID);
        }
    }

    /**
     * 仅在 member-server 边界解释采购商用户标识；格式不合法或超出 Long 范围时返回 null。
     */
    public static Long parseMemberUserId(String buyerUserId) {
        try {
            return Long.valueOf(buyerUserId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 日志只允许输出白名单来源系统。 */
    public static String safeSourceSystem(String sourceSystem) {
        return MemberProfileSourceSystemConstants.AIFULI_MALL.equals(sourceSystem)
                ? MemberProfileSourceSystemConstants.AIFULI_MALL : "INVALID";
    }

    private MemberExternalIdentitySupport() {
    }
}
