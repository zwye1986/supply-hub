package com.aifuli.module.system.service.permission;

import cn.hutool.core.util.StrUtil;
import com.aifuli.module.system.dal.dataobject.permission.MenuDO;
import com.aifuli.module.system.enums.permission.MenuTypeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.aifuli.module.system.dal.dataobject.permission.MenuDO.ID_ROOT;
import static com.aifuli.module.system.enums.permission.MenuApplicationCodeConstants.SUPPLY_CHAIN_ADMIN_PERMISSION;

/**
 * 登录用户菜单的应用投影服务。
 *
 * <p>角色菜单仍是全局授权事实。本服务始终聚合全部应用的权限与动态路由，不再接收前端传入的
 * {@code applicationCode}；补齐父目录不会扩大真实权限，供应链旧 {@code supply-chain:admin}
 * 则按已确认的超集语义展开供应链整棵菜单。
 * 设计见 {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#81-应用隔离模型}。</p>
 */
@Service
public class AdminMenuProjectionService {

    @Resource
    private MenuService menuService;

    /**
     * 聚合登录用户在全部应用下的权限与路由菜单。
     *
     * @param grantedMenuIds 角色实际授予的菜单编号
     * @param superAdmin 是否 yudao 超管
     * @return 权限与路由菜单；返回集合均为新实例
     */
    public Projection project(Collection<Long> grantedMenuIds, boolean superAdmin) {
        List<MenuDO> enabledMenus = menuService.filterDisableMenus(menuService.getMenuList());
        Map<Long, MenuDO> enabledMenuMap = enabledMenus.stream()
                .collect(Collectors.toMap(MenuDO::getId, Function.identity()));
        Set<Long> grantedIds = grantedMenuIds == null
                ? Collections.emptySet() : new LinkedHashSet<>(grantedMenuIds);
        List<MenuDO> grantedMenus = enabledMenus.stream()
                .filter(menu -> grantedIds.contains(menu.getId()))
                .toList();
        Set<String> globalPermissions = toPermissionSet(grantedMenus);

        // 超管展开全部菜单；旧 supply-chain:admin 仍只展开供应链菜单，保持既有超集权限语义。
        Set<Long> projectedMenuIds = grantedMenus.stream()
                .map(MenuDO::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (superAdmin) {
            projectedMenuIds.addAll(enabledMenus.stream().map(MenuDO::getId).toList());
        } else if (globalPermissions.contains(SUPPLY_CHAIN_ADMIN_PERMISSION)) {
            projectedMenuIds.addAll(findSupplyChainMenuTreeIds(enabledMenus));
        }
        List<MenuDO> projectedMenus = enabledMenus.stream()
                .filter(menu -> projectedMenuIds.contains(menu.getId()))
                .toList();
        Set<Long> routeMenuIds = projectedMenus.stream()
                .filter(AdminMenuProjectionService::isRouteMenu)
                .map(MenuDO::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        addAncestorMenuIds(routeMenuIds, enabledMenuMap);
        List<MenuDO> routeMenus = enabledMenus.stream()
                .filter(menu -> routeMenuIds.contains(menu.getId()))
                .filter(AdminMenuProjectionService::isRouteMenu)
                .toList();
        return new Projection(toPermissionSet(projectedMenus), new ArrayList<>(routeMenus));
    }

    private static boolean isRouteMenu(MenuDO menu) {
        return !MenuTypeEnum.BUTTON.getType().equals(menu.getType());
    }

    private static Set<String> toPermissionSet(Collection<MenuDO> menus) {
        return menus.stream()
                .map(MenuDO::getPermission)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 根据 {@code supply-chain:admin} 根目录和父子关系展开供应链菜单，不再依赖历史应用标识。
     */
    private static Set<Long> findSupplyChainMenuTreeIds(List<MenuDO> menus) {
        Set<Long> menuIds = menus.stream()
                .filter(AdminMenuProjectionService::isRouteMenu)
                .filter(menu -> ID_ROOT.equals(menu.getParentId()))
                .filter(menu -> SUPPLY_CHAIN_ADMIN_PERMISSION.equals(menu.getPermission()))
                .map(MenuDO::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean added;
        do {
            added = false;
            for (MenuDO menu : menus) {
                if (menuIds.contains(menu.getParentId())) {
                    added |= menuIds.add(menu.getId());
                }
            }
        } while (added);
        return menuIds;
    }

    private static void addAncestorMenuIds(Set<Long> routeMenuIds, Map<Long, MenuDO> menuMap) {
        List<Long> seeds = new ArrayList<>(routeMenuIds);
        for (Long menuId : seeds) {
            MenuDO current = menuMap.get(menuId);
            while (current != null && !ID_ROOT.equals(current.getParentId())) {
                MenuDO parent = menuMap.get(current.getParentId());
                if (parent == null) {
                    break;
                }
                routeMenuIds.add(parent.getId());
                current = parent;
            }
        }
    }

    public record Projection(Set<String> permissions, List<MenuDO> routeMenus) {
    }

}
