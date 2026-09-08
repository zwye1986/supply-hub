package com.aifuli.module.system.controller.admin.auth;

import com.aifuli.module.system.controller.admin.auth.vo.AuthPermissionInfoRespVO;
import com.aifuli.module.system.convert.auth.AuthConvert;
import com.aifuli.module.system.dal.dataobject.permission.MenuDO;
import com.aifuli.module.system.dal.dataobject.user.AdminUserDO;
import com.aifuli.module.system.enums.permission.MenuTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 权限信息接口的应用隔离契约：请求参数、跨应用聚合、菜单组件字段。
 * 设计见 {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#9-测试门禁}。
 */
class AuthPermissionInfoContractTest {

    @Test
    void getPermissionInfoAllowsApplicationCodeOmissionForAllApplications() throws NoSuchMethodException {
        Method method = AuthController.class.getMethod("getPermissionInfo", String.class);
        Parameter parameter = method.getParameters()[0];
        RequestParam requestParam = parameter.getAnnotation(RequestParam.class);

        assertNotNull(requestParam);
        assertEquals("applicationCode", requestParam.value());
        assertFalse(requestParam.required());
    }

    @Test
    void convertKeepsMenuComponentFieldsForDynamicRoutes() {
        AdminUserDO user = new AdminUserDO().setId(1L).setNickname("运营").setUsername("ops");
        MenuDO menu = new MenuDO()
                .setId(10L)
                .setParentId(MenuDO.ID_ROOT)
                .setName("供应商管理")
                .setType(MenuTypeEnum.MENU.getType())
                .setSort(1)
                .setPath("manage")
                .setComponent("supplier/manage/index")
                .setComponentName("SupplierManage");

        AuthPermissionInfoRespVO vo = AuthConvert.INSTANCE.convert(
                user, List.of(), Set.of("supply-chain:supplier:query"), List.of(menu));

        assertEquals(Set.of("supply-chain:supplier:query"), vo.getPermissions());
        assertEquals(1, vo.getMenus().size());
        AuthPermissionInfoRespVO.MenuVO menuVo = vo.getMenus().get(0);
        assertEquals("manage", menuVo.getPath());
        assertEquals("supplier/manage/index", menuVo.getComponent());
        assertEquals("SupplierManage", menuVo.getComponentName());
    }
}
