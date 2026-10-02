package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.apilog.core.util.ApiAccessLogPathUtils;
import com.aifuli.framework.web.core.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import static org.assertj.core.api.Assertions.assertThat;

/** 真实管理路径及错误报文不得泄漏正文，见限频设计 #7-管理端契约与权限。 */
class RechargeFrequencyAdminControllerTest {
    @Test void frequencyStatisticsEndpointReturnsData() throws Exception {
        var query = org.mockito.Mockito.mock(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMonitorQueryService.class);
        org.mockito.Mockito.when(query.statistics(7L)).thenReturn(new com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyMonitorQueryService.Statistics());
        var controller=new RechargeFrequencyAdminController(query,org.mockito.Mockito.mock(com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyAdminCommandService.class));
        var user=new com.aifuli.framework.security.core.LoginUser(); user.setId(7L); user.setUserType(com.aifuli.framework.common.enums.UserTypeEnum.ADMIN.getValue());
        com.aifuli.framework.security.core.util.SecurityFrameworkUtils.setLoginUser(user,new org.springframework.mock.web.MockHttpServletRequest());
        try {
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/supply-chain/recharge-frequency/statistics"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.waitingOrderCount").value(0));
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }
    @Test void sensitiveExceptionHelperPreservesCyclesAndSuppressedTypesWithoutMessages() {
        var first=new IllegalStateException("13800138000 secret-value");
        var second=new java.sql.SQLException("credential=secret-value");
        first.initCause(second);second.initCause(first);first.addSuppressed(new IllegalArgumentException("13800138000"));
        var types=com.aifuli.framework.apilog.core.util.SensitiveExceptionLogUtils.types(first);
        var stack=com.aifuli.framework.apilog.core.util.SensitiveExceptionLogUtils.stack(first);
        assertThat(types).contains("IllegalStateException","SQLException","IllegalArgumentException").doesNotContain("13800138000","secret-value");
        assertThat(stack).contains("RechargeFrequencyAdminControllerTest").doesNotContain("13800138000","secret-value");
    }
    @Test void adminAndContextPathsSuppressBodyButUnrelatedPathsRetainContract() {
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath("/admin-api/supply-chain/recharge-frequency/search")).isTrue();
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath("/supply-chain/recharge-frequency/statistics")).isTrue();
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath("/deployment/admin-api/supply-chain/recharge-frequency/search", "/deployment")).isTrue();
        assertThat(ApiAccessLogPathUtils.isSensitivePlatformPath("/admin-api/supply-chain/orders/page")).isFalse();
    }
    @Test void malformedSensitiveJsonReturnsSafeBadRequest() {
        var request = new MockHttpServletRequest("POST", "/admin-api/supply-chain/recharge-frequency/search");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var handler = new GlobalExceptionHandler("test", dto -> com.aifuli.framework.common.pojo.CommonResult.success(true));
            var error = new HttpMessageNotReadableException("Invalid JSON 13800138000 secret-value", new MockHttpInputMessage(new byte[0]));
            var result = handler.methodArgumentTypeInvalidFormatExceptionHandler(error);
            assertThat(result.getCode()).isEqualTo(400);
            assertThat(result.getMsg()).doesNotContain("13800138000", "secret-value");
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
}
