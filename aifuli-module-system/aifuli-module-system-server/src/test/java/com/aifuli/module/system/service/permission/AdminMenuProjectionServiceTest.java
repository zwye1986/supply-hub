package com.aifuli.module.system.service.permission;

import com.aifuli.module.system.dal.dataobject.permission.MenuDO;
import com.aifuli.module.system.enums.permission.MenuTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.aifuli.module.system.enums.permission.MenuApplicationCodeConstants.MALL_ADMIN;
import static com.aifuli.module.system.enums.permission.MenuApplicationCodeConstants.SUPPLY_CHAIN_ADMIN;
import static com.aifuli.module.system.enums.permission.MenuApplicationCodeConstants.SUPPLY_CHAIN_ADMIN_PERMISSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AdminMenuProjectionServiceTest {

    @InjectMocks
    private AdminMenuProjectionService projectionService;

    @Mock
    private MenuService menuService;

    private List<MenuDO> allMenus;

    @BeforeEach
    void setUp() {
        allMenus = List.of(
                menu(1L, 0L, MALL_ADMIN, MenuTypeEnum.DIR, "", "/system"),
                menu(2L, 0L, SUPPLY_CHAIN_ADMIN, MenuTypeEnum.DIR,
                        SUPPLY_CHAIN_ADMIN_PERMISSION, "/supplier"),
                menu(3L, 2L, SUPPLY_CHAIN_ADMIN, MenuTypeEnum.MENU,
                        "supply-chain:supplier:query", "manage"),
                menu(4L, 3L, SUPPLY_CHAIN_ADMIN, MenuTypeEnum.BUTTON,
                        "supply-chain:supplier:create", ""),
                // 故意保留不同的历史应用值，验证超集展开只依赖菜单树。
                menu(5L, 2L, MALL_ADMIN, MenuTypeEnum.MENU,
                        "supply-chain:buyer:query", "buyer"),
                // 旧 admin 权限节点属于商城应用，验证供应链投影仍能识别其全局授权事实。
                menu(6L, 1L, MALL_ADMIN, MenuTypeEnum.BUTTON,
                        SUPPLY_CHAIN_ADMIN_PERMISSION, ""));
        lenient().when(menuService.getMenuList()).thenReturn(allMenus);
        lenient().when(menuService.filterDisableMenus(allMenus)).thenReturn(allMenus);
    }

    @Test
    void shouldProjectFinePermissionAndCompleteAncestors() {
        AdminMenuProjectionService.Projection result = projectionService.project(
                Set.of(3L, 4L), false);

        assertEquals(Set.of("supply-chain:supplier:query", "supply-chain:supplier:create"),
                result.permissions());
        assertEquals(Set.of(2L, 3L),
                result.routeMenus().stream().map(MenuDO::getId).collect(Collectors.toSet()));
        assertFalse(result.permissions().contains(SUPPLY_CHAIN_ADMIN_PERMISSION));
    }

    @Test
    void shouldExpandLegacyAdminToWholeSupplyChainMenuTree() {
        AdminMenuProjectionService.Projection result = projectionService.project(
                Set.of(6L), false);

        assertTrue(result.permissions().containsAll(Set.of(
                SUPPLY_CHAIN_ADMIN_PERMISSION,
                "supply-chain:supplier:query",
                "supply-chain:supplier:create",
                "supply-chain:buyer:query")));
        assertEquals(Set.of(2L, 3L, 5L),
                result.routeMenus().stream().map(MenuDO::getId).collect(Collectors.toSet()));
    }

    @Test
    void shouldExpandSuperAdminToAllApplications() {
        AdminMenuProjectionService.Projection result = projectionService.project(Set.of(), true);

        assertTrue(result.permissions().containsAll(Set.of(
                SUPPLY_CHAIN_ADMIN_PERMISSION,
                "supply-chain:supplier:query",
                "supply-chain:supplier:create",
                "supply-chain:buyer:query")));
        assertEquals(Set.of(1L, 2L, 3L, 5L),
                result.routeMenus().stream().map(MenuDO::getId).collect(Collectors.toSet()));
    }

    @Test
    void shouldProjectGrantedMenusAcrossApplications() {
        AdminMenuProjectionService.Projection result = projectionService.project(
                Set.of(1L, 3L, 4L), false);

        assertEquals(Set.of("supply-chain:supplier:query", "supply-chain:supplier:create"),
                result.permissions());
        assertEquals(Set.of(1L, 2L, 3L),
                result.routeMenus().stream().map(MenuDO::getId).collect(Collectors.toSet()));
        assertFalse(result.permissions().contains(SUPPLY_CHAIN_ADMIN_PERMISSION));
    }

    private static MenuDO menu(Long id, Long parentId, String applicationCode,
                               MenuTypeEnum type, String permission, String path) {
        return new MenuDO().setId(id).setParentId(parentId).setApplicationCode(applicationCode)
                .setType(type.getType()).setPermission(permission).setPath(path).setSort(id.intValue())
                .setStatus(0).setName(path.isEmpty() ? permission : path)
                .setVisible(true).setKeepAlive(true).setAlwaysShow(true);
    }

}
