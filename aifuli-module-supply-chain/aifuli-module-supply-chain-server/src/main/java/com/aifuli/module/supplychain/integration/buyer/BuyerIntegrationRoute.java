package com.aifuli.module.supplychain.integration.buyer;

import java.util.Objects;

/**
 * 一次业务操作冻结使用的采购项目集成路由快照。
 *
 * <p>{@code externalTenantId} 只是 SaaS 外部租户路由坐标，不是采购项目业务身份；
 * 本对象只能由已发布的项目档案版本解析得到。设计见
 * {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#10.2-项目集成档案}。</p>
 */
public record BuyerIntegrationRoute(
        Long projectId,
        Long profileVersionId,
        BuyerIntegrationCapabilityCode capability,
        String sourceSystem,
        String externalTenantId,
        String transportMode,
        String adapterCode,
        String contractVersion,
        Long credentialSetId) {

    public BuyerIntegrationRoute {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(profileVersionId, "profileVersionId");
        Objects.requireNonNull(capability, "capability");
        requireText(transportMode, "transportMode");
        requireText(adapterCode, "adapterCode");
        requireText(contractVersion, "contractVersion");
        if (requiresSaasRoute(capability)) {
            requireText(sourceSystem, "sourceSystem");
            requireText(externalTenantId, "externalTenantId");
        }
    }

    private static boolean requiresSaasRoute(BuyerIntegrationCapabilityCode capability) {
        return capability == BuyerIntegrationCapabilityCode.MEMBER_PROFILE
                || capability == BuyerIntegrationCapabilityCode.CASHIER;
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
