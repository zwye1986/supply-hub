package com.aifuli.module.supplychain.interfaceapi.admin;

/**
 * 供应链运营后台功能权限与方法安全表达式。
 *
 * <p>集中维护权限字符串，避免 Controller 与组合鉴权组件出现语义漂移。旧的
 * {@code supply-chain:admin} 始终是本轮细权限的超集。</p>
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#4-权限标识}。</p>
 */
public final class SupplyChainAdminPermissions {

    public static final String ADMIN = "supply-chain:admin";

    public static final String SUPPLIER_QUERY = "supply-chain:supplier:query";
    public static final String SUPPLIER_CREATE = "supply-chain:supplier:create";
    public static final String SUPPLIER_UPDATE = "supply-chain:supplier:update";
    public static final String SUPPLIER_DELETE = "supply-chain:supplier:delete";
    public static final String SUPPLIER_UPDATE_STATUS = "supply-chain:supplier:update-status";

    public static final String BUYER_QUERY = "supply-chain:buyer:query";
    public static final String BUYER_CREATE = "supply-chain:buyer:create";
    public static final String BUYER_UPDATE = "supply-chain:buyer:update";
    public static final String BUYER_DELETE = "supply-chain:buyer:delete";
    public static final String BUYER_UPDATE_STATUS = "supply-chain:buyer:update-status";

    public static final String PROJECT_QUERY = "supply-chain:project:query";
    public static final String PROJECT_CREATE = "supply-chain:project:create";
    public static final String PROJECT_UPDATE = "supply-chain:project:update";
    public static final String PROJECT_DELETE = "supply-chain:project:delete";
    public static final String PROJECT_UPDATE_STATUS = "supply-chain:project:update-status";

    public static final String ADMIN_EXPRESSION = "@ss.hasPermission('" + ADMIN + "')";
    public static final String SUPPLIER_QUERY_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + SUPPLIER_QUERY + "')";
    public static final String SUPPLIER_CREATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + SUPPLIER_CREATE + "')";
    public static final String SUPPLIER_UPDATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + SUPPLIER_UPDATE + "')";
    public static final String SUPPLIER_DELETE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + SUPPLIER_DELETE + "')";
    public static final String SUPPLIER_UPDATE_STATUS_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + SUPPLIER_UPDATE_STATUS + "')";

    public static final String BUYER_QUERY_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + BUYER_QUERY + "')";
    public static final String BUYER_CREATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + BUYER_CREATE + "')";
    public static final String BUYER_UPDATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + BUYER_UPDATE + "')";
    public static final String BUYER_DELETE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + BUYER_DELETE + "')";
    public static final String BUYER_UPDATE_STATUS_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + BUYER_UPDATE_STATUS + "')";

    public static final String PROJECT_QUERY_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PROJECT_QUERY + "')";
    public static final String PROJECT_CREATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PROJECT_CREATE + "')";
    public static final String PROJECT_UPDATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PROJECT_UPDATE + "')";
    public static final String PROJECT_DELETE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PROJECT_DELETE + "')";
    public static final String PROJECT_UPDATE_STATUS_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PROJECT_UPDATE_STATUS + "')";

    public static final String READ_OPTIONS_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + SUPPLIER_QUERY + "', '"
                    + BUYER_QUERY + "', '" + PROJECT_QUERY + "')";
    public static final String PLATFORM_SKU_QUERY_EXPRESSION =
            "@supplyChainAdminPermission.canQueryPlatformSku(#supplierCode, #buyerCode, #projectCode)";

    private SupplyChainAdminPermissions() {
    }
}
