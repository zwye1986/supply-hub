package com.aifuli.module.system.service.permission;

import com.aifuli.module.system.controller.admin.permission.vo.menu.MenuSaveVO;
import com.aifuli.module.system.dal.dataobject.permission.MenuDO;
import com.aifuli.module.system.dal.mysql.permission.MenuMapper;
import com.aifuli.module.system.enums.permission.MenuTypeEnum;
import com.aifuli.module.system.service.tenant.TenantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.aifuli.module.system.enums.permission.MenuApplicationCodeConstants.MALL_ADMIN;
import static com.aifuli.module.system.enums.permission.MenuApplicationCodeConstants.SUPPLY_CHAIN_ADMIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 菜单维护接口忽略历史应用参数的回归测试。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#81-应用隔离模型}。</p>
 */
@ExtendWith(MockitoExtension.class)
class MenuApplicationCodeCompatibilityTest {

    @InjectMocks
    private MenuServiceImpl menuService;
    @Mock
    private MenuMapper menuMapper;
    @Mock
    private PermissionService permissionService;
    @Mock
    private TenantService tenantService;

    @Test
    void createChildIgnoresRequestApplicationAndInheritsParent() {
        MenuDO parent = new MenuDO().setId(10L).setParentId(MenuDO.ID_ROOT)
                .setType(MenuTypeEnum.DIR.getType()).setApplicationCode(SUPPLY_CHAIN_ADMIN);
        when(menuMapper.selectById(10L)).thenReturn(parent);

        MenuSaveVO request = menuRequest(null, 10L, "定价策略").setApplicationCode(MALL_ADMIN);
        menuService.createMenu(request);

        ArgumentCaptor<MenuDO> inserted = ArgumentCaptor.forClass(MenuDO.class);
        verify(menuMapper).insert(inserted.capture());
        assertEquals(SUPPLY_CHAIN_ADMIN, request.getApplicationCode());
        assertEquals(SUPPLY_CHAIN_ADMIN, inserted.getValue().getApplicationCode());
    }

    @Test
    void updateMenuIgnoresRequestApplicationAndKeepsExisting() {
        MenuDO existing = new MenuDO().setId(20L).setParentId(MenuDO.ID_ROOT)
                .setName("供应链中台").setType(MenuTypeEnum.DIR.getType())
                .setApplicationCode(SUPPLY_CHAIN_ADMIN);
        when(menuMapper.selectById(20L)).thenReturn(existing);

        MenuSaveVO request = menuRequest(20L, MenuDO.ID_ROOT, "供应链中台")
                .setType(MenuTypeEnum.DIR.getType()).setApplicationCode("unknown-application");
        menuService.updateMenu(request);

        ArgumentCaptor<MenuDO> updated = ArgumentCaptor.forClass(MenuDO.class);
        verify(menuMapper).updateById(updated.capture());
        assertEquals(SUPPLY_CHAIN_ADMIN, request.getApplicationCode());
        assertEquals(SUPPLY_CHAIN_ADMIN, updated.getValue().getApplicationCode());
    }

    private static MenuSaveVO menuRequest(Long id, Long parentId, String name) {
        return new MenuSaveVO().setId(id).setParentId(parentId).setName(name)
                .setType(MenuTypeEnum.MENU.getType()).setSort(1).setStatus(0)
                .setPath("pricing").setComponent("").setComponentName("");
    }
}
