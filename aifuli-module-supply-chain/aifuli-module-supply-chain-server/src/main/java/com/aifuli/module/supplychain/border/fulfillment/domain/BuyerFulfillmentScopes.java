package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 采购商履约查询权限 Scope 封闭常量。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#37-事务恢复与权限规则}。</p>
 */
public final class BuyerFulfillmentScopes {

    private BuyerFulfillmentScopes() {
    }

    /** 敏感履约资产授权解密查询；必须独立授予，不得用 ORDER_READ 替代。 */
    public static final String FULFILLMENT_ASSET_READ = "FULFILLMENT_ASSET_READ";
}
