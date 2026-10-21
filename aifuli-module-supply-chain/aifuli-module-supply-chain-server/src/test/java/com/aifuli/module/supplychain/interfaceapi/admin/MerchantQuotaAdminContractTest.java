package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.biz.system.permission.PermissionCommonApi;
import com.aifuli.framework.common.enums.UserTypeEnum;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.framework.security.core.LoginUser;
import com.aifuli.framework.security.core.service.*;
import com.aifuli.framework.security.core.util.SecurityFrameworkUtils;
import com.aifuli.framework.web.config.*;
import com.aifuli.framework.web.core.handler.GlobalExceptionHandler;
import com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaAdminService;
import com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaAdminService.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.util.*;
import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 管理API真实MVC/方法安全契约；命令服务替身只隔离数据库，鉴权和登录身份不替换。
 * 设计：docs/superpowers/specs/2026-10-19-merchant-quota-admin-design.md#3-管理接口。
 */
class MerchantQuotaAdminContractTest {
    private static final String PREFIX = "/admin-api/supply-chain/merchant-quotas";
    private static final ThreadLocal<Set<String>> GRANTED = ThreadLocal.withInitial(Set::of);
    private AnnotationConfigWebApplicationContext context;
    private MerchantQuotaAdminService service;
    private MockMvc mvc;
    @BeforeEach void start() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Config.class); context.refresh();
        service = context.getBean(MerchantQuotaAdminService.class); mvc = MockMvcBuilders.webAppContextSetup(context).build();
        var user = new LoginUser(); user.setId(71L); user.setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityFrameworkUtils.setLoginUser(user, new MockHttpServletRequest());
        GRANTED.set(Set.of(MERCHANT_QUOTA_QUERY));
    }
    @AfterEach void stop() { context.close(); SecurityContextHolder.clearContext(); GRANTED.remove(); }

    @Test void queryPermissionCannotCreateOrAdjustAndWritesUseAuthenticatedOperator() throws Exception {
        String create = "{\"platformCode\":\"RENYUE\",\"merchantAccount\":\" Merchant \",\"rateLimitPerSecond\":7,\"concurrencyLimit\":2,\"reason\":\"核查后配置\",\"operatorId\":99}";
        String update = "{\"platformCode\":\"QNM\",\"merchantAccount\":\"other\",\"rateLimitPerSecond\":5,\"concurrencyLimit\":3,\"expectedVersion\":\"9007199254740993\",\"reason\":\"调额\",\"operatorId\":99}";
        mvc.perform(post(PREFIX).contentType("application/json").content(create)).andExpect(jsonPath("$.code").value(403));
        mvc.perform(put(PREFIX+"/17").contentType("application/json").content(update)).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(service);
        GRANTED.set(Set.of(MERCHANT_QUOTA_CREATE));
        login(72L); // 权限服务按用户缓存；切换不同权限的真实用户，不篡改缓存。
        when(service.create(anyString(), anyString(), anyInt(), anyInt(), anyString(), anyLong())).thenReturn(new ChangeView(17L,0L,7,2));
        mvc.perform(post(PREFIX).contentType("application/json").content(create)).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.id").value("17"));
        verify(service).create("RENYUE", " Merchant ", 7, 2, "核查后配置", 72L);
        mvc.perform(put(PREFIX+"/17").contentType("application/json").content(update)).andExpect(jsonPath("$.code").value(403));
        GRANTED.set(Set.of(MERCHANT_QUOTA_UPDATE));
        login(73L);
        when(service.update(anyLong(), anyInt(), anyInt(), anyLong(), anyString(), anyLong())).thenReturn(new ChangeView(17L,9007199254740994L,5,3));
        mvc.perform(put(PREFIX+"/17").contentType("application/json").content(update)).andExpect(jsonPath("$.data.version").value("9007199254740994"));
        verify(service).update(17L, 5, 3, 9007199254740993L, "调额", 73L);
        mvc.perform(get(PREFIX)).andExpect(jsonPath("$.code").value(403));
        mvc.perform(get(PREFIX+"/17")).andExpect(jsonPath("$.code").value(403));
        mvc.perform(get(PREFIX+"/17/history")).andExpect(jsonPath("$.code").value(403));
        verify(service, never()).get(anyLong());
        verify(service, never()).history(anyLong(), anyInt(), anyInt());
    }

    /** 登录但无额度权限时，五个映射都必须拒绝，不能仅保护列表与写入。 */
    @Test void noQuotaPermissionCannotReadDetailHistoryOrWrite() throws Exception {
        GRANTED.set(Set.of());
        mvc.perform(get(PREFIX)).andExpect(jsonPath("$.code").value(403));
        mvc.perform(get(PREFIX+"/17")).andExpect(jsonPath("$.code").value(403));
        mvc.perform(get(PREFIX+"/17/history")).andExpect(jsonPath("$.code").value(403));
        mvc.perform(post(PREFIX).contentType("application/json").content("{\"platformCode\":\"RENYUE\",\"merchantAccount\":\"M\",\"rateLimitPerSecond\":1,\"concurrencyLimit\":1,\"reason\":\"核查\"}"))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(put(PREFIX+"/17").contentType("application/json").content("{\"rateLimitPerSecond\":1,\"concurrencyLimit\":1,\"expectedVersion\":\"0\",\"reason\":\"核查\"}"))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(service);
    }

    @Test void queryPreservesPageHistoryAndLargeIdentifiers() throws Exception {
        long large = 9007199254740993L;
        when(service.page(isNull(),isNull(),eq(1),eq(10))).thenReturn(new PageResult<>(List.of(new QuotaView(large,"RENYUE","Merchant",11,4,large,large,null,"历史配置")),1L));
        when(service.get(large)).thenReturn(new QuotaView(large,"RENYUE","Merchant",11,4,large,large,null,"历史配置"));
        when(service.history(large,1,10)).thenReturn(new HistoryView(List.of(),0L,null));
        mvc.perform(get(PREFIX)).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(Long.toString(large)))
                .andExpect(jsonPath("$.data.list[0].version").value(Long.toString(large)))
                .andExpect(jsonPath("$.data.list[0].updatedBy").value(Long.toString(large)));
        mvc.perform(get(PREFIX+"/"+large)).andExpect(jsonPath("$.data.merchantAccount").value("Merchant"));
        mvc.perform(get(PREFIX+"/"+large+"/history")).andExpect(jsonPath("$.data.list").isEmpty()).andExpect(jsonPath("$.data.firstRecordedVersion").doesNotExist());
        when(service.page("RENYUE", " Merchant ", 2, 1)).thenReturn(new PageResult<>(List.of(), 0L));
        mvc.perform(get(PREFIX).param("platformCode", "RENYUE").param("merchantAccount", " Merchant ")
                        .param("pageNo", "2").param("pageSize", "1"))
                .andExpect(jsonPath("$.code").value(0));
        verify(service).page("RENYUE", " Merchant ", 2, 1);
        when(service.history(large, 2, 1)).thenReturn(new HistoryView(
                List.of(new AuditView(large, 11, 7, 4, 2, large, large, "实际调整", null)), 2L, large));
        mvc.perform(get(PREFIX+"/"+large+"/history").param("pageNo", "2").param("pageSize", "1"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.firstRecordedVersion").value(Long.toString(large)))
                .andExpect(jsonPath("$.data.list[0].id").value(Long.toString(large)))
                .andExpect(jsonPath("$.data.list[0].quotaVersion").value(Long.toString(large)))
                .andExpect(jsonPath("$.data.list[0].operatorId").value(Long.toString(large)))
                .andExpect(jsonPath("$.data.list[0].beforeRateLimitPerSecond").value(11))
                .andExpect(jsonPath("$.data.list[0].afterRateLimitPerSecond").value(7));
    }

    @Test void invalidLimitsReasonVersionAndPageNeverReachCommands() throws Exception {
        GRANTED.set(Set.of(ADMIN));
        for (String body : List.of(
                "{\"platformCode\":\"RENYUE\",\"merchantAccount\":\"M\",\"rateLimitPerSecond\":0,\"concurrencyLimit\":2,\"reason\":\"核查\"}",
                "{\"platformCode\":\"RENYUE\",\"merchantAccount\":\"M\",\"rateLimitPerSecond\":1,\"concurrencyLimit\":2,\"reason\":\"  \"}",
                "{\"platformCode\":\"RENYUE\",\"merchantAccount\":\"  \",\"rateLimitPerSecond\":1,\"concurrencyLimit\":2,\"reason\":\"核查\"}")) {
            mvc.perform(post(PREFIX).contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(400));
        }
        mvc.perform(put(PREFIX+"/17").contentType("application/json").content("{\"rateLimitPerSecond\":1,\"concurrencyLimit\":2,\"reason\":\"核查\"}"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(get(PREFIX).param("pageSize","101")).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(service);
    }

    private void login(long id) {
        var user = new LoginUser(); user.setId(id); user.setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityFrameworkUtils.setLoginUser(user, new MockHttpServletRequest());
    }
    @Configuration(proxyBeanMethods = false) @EnableMethodSecurity
    static class Config extends WebMvcConfigurationSupport {
        @Bean static org.springframework.validation.beanvalidation.MethodValidationPostProcessor methodValidation() {
            return new org.springframework.validation.beanvalidation.MethodValidationPostProcessor();
        }
        @Bean MerchantQuotaAdminService service() { return mock(MerchantQuotaAdminService.class); }
        @Bean MerchantQuotaAdminController controller(MerchantQuotaAdminService service) { return new MerchantQuotaAdminController(service); }
        @Bean(name="ss") SecurityFrameworkService security() {
            var api = mock(PermissionCommonApi.class);
            when(api.hasAnyPermissions(anyLong(),any(String[].class))).thenAnswer(invocation -> success(Arrays.stream((String[]) invocation.getRawArguments()[1]).anyMatch(GRANTED.get()::contains)));
            return new SecurityFrameworkServiceImpl(api);
        }
        @Bean GlobalExceptionHandler exceptions() { return new GlobalExceptionHandler("contract", dto -> success(true)); }
        @Bean com.aifuli.framework.web.core.util.WebFrameworkUtils webFrameworkUtils() { return new com.aifuli.framework.web.core.util.WebFrameworkUtils(new WebProperties()); }
        @Bean cn.hutool.extra.spring.SpringUtil springUtil() { return new cn.hutool.extra.spring.SpringUtil(); }
        @Override protected RequestMappingHandlerMapping createRequestMappingHandlerMapping() {
            var properties = new WebProperties(); properties.setAdminApi(new WebProperties.Api("/admin-api","**.interfaceapi.admin.**"));
            return new AifuliWebAutoConfiguration().webMvcRegistrations(properties).getRequestMappingHandlerMapping();
        }
    }
}
