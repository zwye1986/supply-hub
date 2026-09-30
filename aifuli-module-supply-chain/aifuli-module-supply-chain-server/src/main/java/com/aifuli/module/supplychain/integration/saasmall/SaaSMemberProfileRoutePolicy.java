package com.aifuli.module.supplychain.integration.saasmall;

import com.aifuli.module.member.api.profile.MemberProfileSourceSystemConstants;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;

import java.math.BigInteger;

/**
 * SaaS 会员资料解析路由策略：冻结路由白名单与框架租户头转换。
 *
 * <p>只支持 {@code MEMBER_PROFILE/AIFULI_MALL/RPC/SAAS_MEMBER_RPC/v1} 一种路由组合；
 * {@code frameworkTenantId} 只解释框架租户头，任何位置都不得解释 buyerUserId。
 * 设计见 {@code docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#33-supply-chain-server}。</p>
 */
final class SaaSMemberProfileRoutePolicy {

    static final String TRANSPORT_MODE = "RPC";
    static final String ADAPTER_CODE = "SAAS_MEMBER_RPC";
    static final String CONTRACT_VERSION = "v1";

    /**
     * 判断冻结路由是否为本适配器支持的唯一组合。
     */
    static boolean supports(BuyerIntegrationRoute route) {
        return route.capability() == BuyerIntegrationCapabilityCode.MEMBER_PROFILE
                && MemberProfileSourceSystemConstants.AIFULI_MALL.equals(route.sourceSystem())
                && TRANSPORT_MODE.equals(route.transportMode())
                && ADAPTER_CODE.equals(route.adapterCode())
                && CONTRACT_VERSION.equals(route.contractVersion());
    }

    /**
     * 把外部租户标识转换为框架租户头；非正整数、带前导零或超出 Long 范围时返回 null。
     */
    static Long frameworkTenantId(String externalTenantId) {
        try {
            long value = new BigInteger(externalTenantId).longValueExact();
            return value > 0 && Long.toString(value).equals(externalTenantId) ? value : null;
        } catch (NumberFormatException | ArithmeticException invalidTenantId) {
            return null;
        }
    }

    /**
     * 日志安全的来源系统值：白名单命中返回原值，否则固定 INVALID。
     */
    static String safeSourceSystem(String sourceSystem) {
        return MemberProfileSourceSystemConstants.AIFULI_MALL.equals(sourceSystem)
                ? MemberProfileSourceSystemConstants.AIFULI_MALL : "INVALID";
    }

    /**
     * 日志安全的适配器编码：白名单命中返回原值，否则固定 INVALID。
     */
    static String safeAdapterCode(String adapterCode) {
        return ADAPTER_CODE.equals(adapterCode) ? ADAPTER_CODE : "INVALID";
    }

    private SaaSMemberProfileRoutePolicy() {
    }
}
