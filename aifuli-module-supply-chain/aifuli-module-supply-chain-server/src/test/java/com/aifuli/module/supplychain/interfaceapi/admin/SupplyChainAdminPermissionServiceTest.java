package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.security.core.service.SecurityFrameworkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ADMIN;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BUYER_QUERY;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PROJECT_QUERY;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.SUPPLIER_QUERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKU 共用下拉的组合权限测试。
 * 设计见 {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#54-只读下拉}。
 */
class SupplyChainAdminPermissionServiceTest {

    private SecurityFrameworkService securityFrameworkService;
    private SupplyChainAdminPermissionService permissionService;

    @BeforeEach
    void setUp() {
        securityFrameworkService = mock(SecurityFrameworkService.class);
        permissionService = new SupplyChainAdminPermissionService(securityFrameworkService);
    }

    @Test
    void adminCanQueryEveryResourceCombination() {
        when(securityFrameworkService.hasPermission(ADMIN)).thenReturn(true);

        assertThat(permissionService.canQueryPlatformSku("SPL_1", "BYR_1", "PRJ_1")).isTrue();

        verify(securityFrameworkService, never()).hasAnyPermissions(SUPPLIER_QUERY, BUYER_QUERY, PROJECT_QUERY);
    }

    @Test
    void anyResourceQueryPermissionCanQueryWithoutResourceCode() {
        when(securityFrameworkService.hasPermission(ADMIN)).thenReturn(false);
        when(securityFrameworkService.hasAnyPermissions(SUPPLIER_QUERY, BUYER_QUERY, PROJECT_QUERY))
                .thenReturn(true);

        assertThat(permissionService.canQueryPlatformSku(null, " ", null)).isTrue();
    }

    @Test
    void noResourceQueryPermissionCannotQuery() {
        when(securityFrameworkService.hasPermission(ADMIN)).thenReturn(false);
        when(securityFrameworkService.hasAnyPermissions(SUPPLIER_QUERY, BUYER_QUERY, PROJECT_QUERY))
                .thenReturn(false);

        assertThat(permissionService.canQueryPlatformSku(null, null, null)).isFalse();
    }

    @Test
    void resourceCodesRequireEveryMatchingQueryPermission() {
        when(securityFrameworkService.hasPermission(ADMIN)).thenReturn(false);
        when(securityFrameworkService.hasAnyPermissions(SUPPLIER_QUERY, BUYER_QUERY, PROJECT_QUERY))
                .thenReturn(true);
        when(securityFrameworkService.hasPermission(SUPPLIER_QUERY)).thenReturn(true);
        when(securityFrameworkService.hasPermission(BUYER_QUERY)).thenReturn(false);

        assertThat(permissionService.canQueryPlatformSku("SPL_1", "BYR_1", "PRJ_1")).isFalse();

        verify(securityFrameworkService, never()).hasPermission(PROJECT_QUERY);
    }
}
