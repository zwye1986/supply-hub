package com.aifuli.module.supplychain.integration.saasmall;

import com.aifuli.module.member.api.profile.MemberProfileSourceSystemConstants;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;

/**
 * SaaS H5 收银台路由白名单。
 *
 * <p>只接受 {@code CASHIER/AIFULI_MALL/RPC/SAAS_CASHIER_RPC/v1}；外部租户标识仅转换为
 * 框架租户头，绝不解释 buyerUserId。详细设计见
 * {@code docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md#43-supply-chain-server}。</p>
 */
final class SaaSCashierRoutePolicy {

    static final String TRANSPORT_MODE = "RPC";
    static final String ADAPTER_CODE = "SAAS_CASHIER_RPC";
    static final String CONTRACT_VERSION = "v1";

    static boolean supports(BuyerIntegrationRoute route) {
        return route.capability() == BuyerIntegrationCapabilityCode.CASHIER
                && MemberProfileSourceSystemConstants.AIFULI_MALL.equals(route.sourceSystem())
                && TRANSPORT_MODE.equals(route.transportMode())
                && ADAPTER_CODE.equals(route.adapterCode())
                && CONTRACT_VERSION.equals(route.contractVersion());
    }

    static Long frameworkTenantId(String externalTenantId) {
        return SaaSMemberProfileRoutePolicy.frameworkTenantId(externalTenantId);
    }

    static String safeSourceSystem(String sourceSystem) {
        return SaaSMemberProfileRoutePolicy.safeSourceSystem(sourceSystem);
    }

    static String safeAdapterCode(String adapterCode) {
        return ADAPTER_CODE.equals(adapterCode) ? ADAPTER_CODE : "INVALID";
    }

    private SaaSCashierRoutePolicy() {
    }
}
