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

    public static final String PRODUCT_QUERY = "supply-chain:product:query";
    public static final String PRODUCT_CREATE = "supply-chain:product:create";
    public static final String PRODUCT_UPDATE = "supply-chain:product:update";
    public static final String PRODUCT_DELETE = "supply-chain:product:delete";
    public static final String PRODUCT_UPDATE_STATUS = "supply-chain:product:update-status";

    public static final String CATEGORY_QUERY = "supply-chain:category:query";
    public static final String CATEGORY_CREATE = "supply-chain:category:create";
    public static final String CATEGORY_UPDATE = "supply-chain:category:update";
    public static final String CATEGORY_DELETE = "supply-chain:category:delete";
    public static final String CATEGORY_UPDATE_STATUS = "supply-chain:category:update-status";

    public static final String BRAND_QUERY = "supply-chain:brand:query";
    public static final String BRAND_CREATE = "supply-chain:brand:create";
    public static final String BRAND_UPDATE = "supply-chain:brand:update";
    public static final String BRAND_DELETE = "supply-chain:brand:delete";
    public static final String BRAND_UPDATE_STATUS = "supply-chain:brand:update-status";

    public static final String PRODUCT_TYPE_QUERY = "supply-chain:product-type:query";
    public static final String PRODUCT_TYPE_CREATE = "supply-chain:product-type:create";
    public static final String PRODUCT_TYPE_UPDATE = "supply-chain:product-type:update";
    public static final String PRODUCT_TYPE_DELETE = "supply-chain:product-type:delete";
    public static final String PRODUCT_TYPE_UPDATE_STATUS = "supply-chain:product-type:update-status";

    public static final String RECHARGE_FREQUENCY_QUERY = "supply-chain:recharge-frequency:query";
    public static final String RECHARGE_FREQUENCY_RETRY = "supply-chain:recharge-frequency:retry";
    public static final String RECHARGE_FREQUENCY_RELEASE = "supply-chain:recharge-frequency:release";
    public static final String RECHARGE_FREQUENCY_QUERY_EXPRESSION = "@ss.hasPermission('" + RECHARGE_FREQUENCY_QUERY + "')";
    public static final String RECHARGE_FREQUENCY_RETRY_EXPRESSION = "@ss.hasPermission('" + RECHARGE_FREQUENCY_RETRY + "')";
    public static final String RECHARGE_FREQUENCY_RELEASE_EXPRESSION = "@ss.hasPermission('" + RECHARGE_FREQUENCY_RELEASE + "')";

    public static final String ORDER_QUERY = "supply-chain:order:query";
    public static final String ORDER_EXPORT = "supply-chain:order:export";

    public static final String ROUTING_RULE_QUERY = "supply-chain:routing-rule:query";
    public static final String ROUTING_RULE_CREATE = "supply-chain:routing-rule:create";
    public static final String ROUTING_RULE_UPDATE = "supply-chain:routing-rule:update";
    public static final String ROUTING_RULE_DELETE = "supply-chain:routing-rule:delete";
    public static final String ROUTING_RULE_UPDATE_STATUS = "supply-chain:routing-rule:update-status";

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

    public static final String PRODUCT_QUERY_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PRODUCT_QUERY + "')";
    public static final String PRODUCT_CREATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PRODUCT_CREATE + "')";
    public static final String PRODUCT_UPDATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PRODUCT_UPDATE + "')";
    public static final String PRODUCT_DELETE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PRODUCT_DELETE + "')";
    public static final String PRODUCT_UPDATE_STATUS_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PRODUCT_UPDATE_STATUS + "')";
    public static final String CATEGORY_QUERY_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + CATEGORY_QUERY + "')";
    public static final String CATEGORY_CREATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + CATEGORY_CREATE + "')";
    public static final String CATEGORY_UPDATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + CATEGORY_UPDATE + "')";
    public static final String CATEGORY_DELETE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + CATEGORY_DELETE + "')";
    public static final String CATEGORY_UPDATE_STATUS_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + CATEGORY_UPDATE_STATUS + "')";
    public static final String BRAND_QUERY_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + BRAND_QUERY + "')";
    public static final String BRAND_CREATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + BRAND_CREATE + "')";
    public static final String BRAND_UPDATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + BRAND_UPDATE + "')";
    public static final String BRAND_DELETE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + BRAND_DELETE + "')";
    public static final String BRAND_UPDATE_STATUS_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + BRAND_UPDATE_STATUS + "')";
    public static final String PRODUCT_TYPE_QUERY_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PRODUCT_TYPE_QUERY + "')";
    public static final String PRODUCT_TYPE_CREATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PRODUCT_TYPE_CREATE + "')";
    public static final String PRODUCT_TYPE_UPDATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PRODUCT_TYPE_UPDATE + "')";
    public static final String PRODUCT_TYPE_DELETE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PRODUCT_TYPE_DELETE + "')";
    public static final String PRODUCT_TYPE_UPDATE_STATUS_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + PRODUCT_TYPE_UPDATE_STATUS + "')";
    public static final String ORDER_QUERY_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + ORDER_QUERY + "')";
    public static final String ORDER_EXPORT_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + ORDER_EXPORT + "')";

    public static final String ROUTING_RULE_QUERY_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + ROUTING_RULE_QUERY + "')";
    public static final String ROUTING_RULE_CREATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + ROUTING_RULE_CREATE + "')";
    public static final String ROUTING_RULE_UPDATE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + ROUTING_RULE_UPDATE + "')";
    public static final String ROUTING_RULE_DELETE_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + ROUTING_RULE_DELETE + "')";
    public static final String ROUTING_RULE_UPDATE_STATUS_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + ROUTING_RULE_UPDATE_STATUS + "')";

    public static final String READ_OPTIONS_EXPRESSION =
            "@ss.hasAnyPermissions('" + ADMIN + "', '" + SUPPLIER_QUERY + "', '"
                    + BUYER_QUERY + "', '" + PROJECT_QUERY + "')";
    public static final String PLATFORM_SKU_QUERY_EXPRESSION =
            "@supplyChainAdminPermission.canQueryPlatformSku(#supplierCode, #buyerCode, #projectCode)";

    private SupplyChainAdminPermissions() {
    }

}
