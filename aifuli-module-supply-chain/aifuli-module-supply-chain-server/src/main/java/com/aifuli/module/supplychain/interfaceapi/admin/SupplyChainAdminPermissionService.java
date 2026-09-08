package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.security.core.service.SecurityFrameworkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ADMIN;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BUYER_QUERY;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PROJECT_QUERY;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.SUPPLIER_QUERY;

/**
 * 供应链管理端组合权限判定。
 *
 * <p>标品 SKU 是三个资源页面共用的下拉数据。无资源过滤条件时，任一资源查询权限即可访问；
 * 指定资源 code 后，还必须逐项具备对应资源的查询权限。多个 code 同时出现时取 AND，旧
 * {@code supply-chain:admin} 对所有组合直接放行。</p>
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#54-只读下拉}。</p>
 */
@Component("supplyChainAdminPermission")
@RequiredArgsConstructor
@Slf4j
public class SupplyChainAdminPermissionService {

    private final SecurityFrameworkService securityFrameworkService;

    public boolean canQueryPlatformSku(String supplierCode, String buyerCode, String projectCode) {
        boolean allowed = securityFrameworkService.hasPermission(ADMIN)
                || (securityFrameworkService.hasAnyPermissions(SUPPLIER_QUERY, BUYER_QUERY, PROJECT_QUERY)
                    && hasPermissionWhenPresent(supplierCode, SUPPLIER_QUERY)
                    && hasPermissionWhenPresent(buyerCode, BUYER_QUERY)
                    && hasPermissionWhenPresent(projectCode, PROJECT_QUERY));
        log.debug("[canQueryPlatformSku][用户({}) 查询 SKU 下拉，supplierCode({}) buyerCode({}) "
                        + "projectCode({})，权限结果({})]",
                getLoginUserId(), supplierCode, buyerCode, projectCode, allowed);
        return allowed;
    }

    private boolean hasPermissionWhenPresent(String resourceCode, String permission) {
        return !StringUtils.hasText(resourceCode) || securityFrameworkService.hasPermission(permission);
    }
}
