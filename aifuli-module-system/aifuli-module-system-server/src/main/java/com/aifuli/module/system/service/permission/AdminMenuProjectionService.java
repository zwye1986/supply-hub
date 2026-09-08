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
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.system.dal.dataobject.permission.MenuDO.ID_ROOT;
import static com.aifuli.module.system.enums.ErrorCodeConstants.MENU_APPLICATION_CODE_INVALID;
import static com.aifuli.module.system.enums.permission.MenuApplicationCodeConstants.ALL;
import static com.aifuli.module.system.enums.permission.MenuApplicationCodeConstants.SUPPLY_CHAIN_ADMIN;
import static com.aifuli.module.system.enums.permission.MenuApplicationCodeConstants.SUPPLY_CHAIN_ADMIN_PERMISSION;

/**
 * 登录用户菜单的应用投影服务。
 *
 * <p>角色菜单仍是全局授权事实。本服务在指定管理端应用时生成隔离的权限与动态路由，未指定时
 * 聚合全部应用；补齐父目录不会扩大真实权限，供应链旧 {@code supply-chain:admin} 则按已确认的
 * 超集语义展开供应链整棵菜单。
 * 设计见 {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#81-应用隔离模型}。</p>
 */
@Service
public class AdminMenuProjectionService {

    @Resource
    private MenuService menuService;

    /**
     * 按应用投影登录用户的权限与路由菜单；应用标识为空时聚合全部应用。
     *
     * @param grantedMenuIds 角色实际授予的菜单编号
     * @param applicationCode 管理端应用标识；{@code null} 表示全部应用
     * @param superAdmin 是否 yudao 超管
     * @return 权限与路由菜单；返回集合均为新实例
     */
    public Projection project(Collection<Long> grantedMenuIds, String applicationCode, boolean superAdmin) {
        if (applicationCode != null && !ALL.contains(applicationCode)) {
            throw exception(MENU_APPLICATION_CODE_INVALID);
        }

        List<MenuDO> enabledMenus = menuService.filterDisableMenus(menuService.getMenuList());
        Map<Long, MenuDO> enabledMenuMap = enabledMenus.stream()
                .collect(Collectors.toMap(MenuDO::getId, Function.identity()));
        Set<Long> grantedIds = grantedMenuIds == null
                ? Collections.emptySet() : new LinkedHashSet<>(grantedMenuIds);
        List<MenuDO> grantedMenus = enabledMenus.stream()
                .filter(menu -> grantedIds.contains(menu.getId()))
                .toList();
        Set<String> globalPermissions = toPermissionSet(grantedMenus);

        List<MenuDO> applicationMenus = applicationCode == null ? enabledMenus : enabledMenus.stream()
                .filter(menu -> Objects.equals(applicationCode, menu.getApplicationCode()))
                .toList();
        List<MenuDO> grantedApplicationMenus = grantedMenus.stream()
                .filter(menu -> applicationCode == null
                        || Objects.equals(applicationCode, menu.getApplicationCode()))
                .toList();

        // 超管展开查询范围内的全部菜单；旧 supply-chain:admin 只展开供应链应用，避免越权到商城应用。
        Set<Long> projectedMenuIds = grantedApplicationMenus.stream()
                .map(MenuDO::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (superAdmin) {
            projectedMenuIds.addAll(applicationMenus.stream().map(MenuDO::getId).toList());
        } else if (globalPermissions.contains(SUPPLY_CHAIN_ADMIN_PERMISSION)) {
            applicationMenus.stream()
                    .filter(menu -> SUPPLY_CHAIN_ADMIN.equals(menu.getApplicationCode()))
                    .map(MenuDO::getId)
                    .forEach(projectedMenuIds::add);
        }
        List<MenuDO> projectedMenus = applicationMenus.stream()
                .filter(menu -> projectedMenuIds.contains(menu.getId()))
                .toList();
        Set<Long> routeMenuIds = projectedMenus.stream()
                .filter(AdminMenuProjectionService::isRouteMenu)
                .map(MenuDO::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        addAncestorMenuIds(routeMenuIds, enabledMenuMap);
        List<MenuDO> routeMenus = applicationMenus.stream()
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

    private static void addAncestorMenuIds(Set<Long> routeMenuIds, Map<Long, MenuDO> menuMap) {
        List<Long> seeds = new ArrayList<>(routeMenuIds);
        for (Long menuId : seeds) {
            MenuDO current = menuMap.get(menuId);
            String applicationCode = current == null ? null : current.getApplicationCode();
            while (current != null && !ID_ROOT.equals(current.getParentId())) {
                MenuDO parent = menuMap.get(current.getParentId());
                if (parent == null || !Objects.equals(applicationCode, parent.getApplicationCode())) {
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
