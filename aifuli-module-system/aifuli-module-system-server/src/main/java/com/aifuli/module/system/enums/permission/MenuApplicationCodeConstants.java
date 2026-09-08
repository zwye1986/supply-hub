package com.aifuli.module.system.enums.permission;

import java.util.Set;

/**
 * 管理端菜单应用标识。
 *
 * <p>应用标识只用于隔离不同管理端的菜单与动态路由，不参与业务接口鉴权。
 * 设计见 {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#81-应用隔离模型}。</p>
 */
public final class MenuApplicationCodeConstants {

    public static final String MALL_ADMIN = "mall-admin";
    public static final String SUPPLY_CHAIN_ADMIN = "supply-chain-admin";
    public static final String SUPPLY_CHAIN_ADMIN_PERMISSION = "supply-chain:admin";

    public static final Set<String> ALL = Set.of(MALL_ADMIN, SUPPLY_CHAIN_ADMIN);

    private MenuApplicationCodeConstants() {
    }

}
