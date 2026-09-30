package com.aifuli.module.supplychain.integration.buyer;

/**
 * 采购项目可发布的集成能力编码。
 *
 * <p>枚举值与 {@code sc_buyer_integration_capability.capability_code} 保持一致。设计见
 * {@code docs/superpowers/specs/2026-08-21-supply-chain-buyer-project-design.md#10.2-项目集成档案}。</p>
 */
public enum BuyerIntegrationCapabilityCode {
    MEMBER_PROFILE,
    CASHIER,
    ORDER_EVENT,
    FULFILLMENT_EVENT,
    CATALOG_EVENT
}
