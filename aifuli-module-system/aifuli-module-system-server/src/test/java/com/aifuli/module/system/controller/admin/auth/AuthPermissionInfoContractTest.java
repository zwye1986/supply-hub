package com.aifuli.module.system.controller.admin.auth;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.security.core.LoginUser;
import com.aifuli.module.system.controller.admin.auth.vo.AuthPermissionInfoRespVO;
import com.aifuli.module.system.convert.auth.AuthConvert;
import com.aifuli.module.system.dal.dataobject.permission.MenuDO;
import com.aifuli.module.system.dal.dataobject.user.AdminUserDO;
import com.aifuli.module.system.enums.permission.MenuTypeEnum;
import com.aifuli.module.system.service.permission.AdminMenuProjectionService;
import com.aifuli.module.system.service.permission.PermissionService;
import com.aifuli.module.system.service.user.AdminUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权限信息接口的兼容契约：保留废弃请求参数、跨应用聚合、菜单组件字段。
 * 设计见 {@code docs/superpowers/specs/2026-09-03-supply-chain-admin-rbac-design.md#9-测试门禁}。
 */
@ExtendWith(MockitoExtension.class)
class AuthPermissionInfoContractTest {

    @InjectMocks
    private AuthController controller;
    @Mock
    private AdminUserService userService;
    @Mock
    private PermissionService permissionService;
    @Mock
    private AdminMenuProjectionService projectionService;

    @BeforeEach
    void setUpLoginUser() {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    @AfterEach
    void clearLoginUser() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getPermissionInfoKeepsOptionalApplicationCodeForCompatibility() throws NoSuchMethodException {
        Method method = AuthController.class.getMethod("getPermissionInfo", String.class);
        Parameter parameter = method.getParameters()[0];
        RequestParam requestParam = parameter.getAnnotation(RequestParam.class);

        assertNotNull(requestParam);
        assertEquals("applicationCode", requestParam.value());
        assertFalse(requestParam.required());
    }

    @Test
    void getPermissionInfoIgnoresUnknownApplicationCode() {
        AdminUserDO user = new AdminUserDO().setId(1L).setNickname("运营").setUsername("ops");
        AdminMenuProjectionService.Projection projection =
                new AdminMenuProjectionService.Projection(Collections.emptySet(), Collections.emptyList());
        when(userService.getUser(1L)).thenReturn(user);
        when(permissionService.getUserRoleIdListByUserId(1L)).thenReturn(Collections.emptySet());
        when(projectionService.project(Collections.emptySet(), false)).thenReturn(projection);

        CommonResult<AuthPermissionInfoRespVO> withoutApplication = controller.getPermissionInfo(null);
        CommonResult<AuthPermissionInfoRespVO> withUnknownApplication = controller.getPermissionInfo("unknown");

        assertEquals(0, withoutApplication.getCode());
        assertEquals(withoutApplication.getData(), withUnknownApplication.getData());
        verify(projectionService, times(2)).project(Collections.emptySet(), false);
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
