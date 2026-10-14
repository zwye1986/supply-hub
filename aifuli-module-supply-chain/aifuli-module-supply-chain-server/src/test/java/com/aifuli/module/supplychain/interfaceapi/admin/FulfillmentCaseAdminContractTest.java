package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.enums.UserTypeEnum;
import com.aifuli.framework.security.core.LoginUser;
import com.aifuli.framework.security.core.util.SecurityFrameworkUtils;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentCaseAdminService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 设计 §6.4、§8：真实方法授权代理，ADMIN 身份和核实/动账权限独立。 */
class FulfillmentCaseAdminContractTest {
    AnnotationConfigApplicationContext context;
    FulfillmentCaseAdminController controller;
    FulfillmentCaseAdminService service;
    Permissions permissions;
    MockHttpServletResponse response;
    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(FulfillmentCaseAdminController.class);
        service = context.getBean(FulfillmentCaseAdminService.class);
        permissions = context.getBean(Permissions.class);
        response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), response));
    }
    @AfterEach void stop() {
        SecurityContextHolder.clearContext(); RequestContextHolder.resetRequestAttributes(); context.close();
    }
    void login(UserTypeEnum type) {
        var user = new LoginUser(); user.setId(7L); user.setUserType(type.getValue());
        SecurityFrameworkUtils.setLoginUser(user, new MockHttpServletRequest());
    }
    @Test void deniedPermissionCannotReadOrWrite() {
        login(UserTypeEnum.ADMIN);
        assertThatThrownBy(() -> controller.getCase("case")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }
    @Test void memberWithPermissionStillCannotUseInternalCaseCommands() {
        login(UserTypeEnum.MEMBER); permissions.allowed = true;
        assertThatThrownBy(() -> controller.getCase("case")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }
    @Test void authenticatedAdminUsesServerActorAndNoStore() {
        login(UserTypeEnum.ADMIN); permissions.allowed = true;
        controller.submitVerify("case", new FulfillmentCaseAdminController.VerifySubmitRequest("action", 2L, 3L, "reason"));
        verify(service).submitVerify(new FulfillmentCaseAdminService.VerifySubmitCommand("case", "action", 2L, 3L, "reason", 7L));
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    public static class Permissions {
        boolean allowed;
        public boolean hasAnyPermissions(String... permissions) { return allowed; }
    }
    @Configuration(proxyBeanMethods = false) @EnableMethodSecurity
    static class Config {
        @Bean FulfillmentCaseAdminService service() { return mock(FulfillmentCaseAdminService.class); }
        @Bean FulfillmentCaseAdminController controller(FulfillmentCaseAdminService service) { return new FulfillmentCaseAdminController(service); }
        @Bean(name = "ss") Permissions permissions() { return new Permissions(); }
    }
}
