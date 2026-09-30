package com.aifuli.module.system.enums.permission;

/**
 * 管理端菜单应用标识。
 *
 * <p>应用标识仅为兼容既有菜单数据与部署 SQL 保留，不再用于菜单或动态路由隔离，
 * 也不参与业务接口鉴权。
 * 设计见 {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#81-应用隔离模型}。</p>
 */
public final class MenuApplicationCodeConstants {

    public static final String MALL_ADMIN = "mall-admin";
    public static final String SUPPLY_CHAIN_ADMIN = "supply-chain-admin";
    public static final String SUPPLY_CHAIN_ADMIN_PERMISSION = "supply-chain:admin";

    private MenuApplicationCodeConstants() {
    }

}
