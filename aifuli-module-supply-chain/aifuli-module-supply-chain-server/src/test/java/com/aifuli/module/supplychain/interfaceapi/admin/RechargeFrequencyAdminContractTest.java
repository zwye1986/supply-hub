package com.aifuli.module.supplychain.interfaceapi.admin;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.apilog.core.interceptor.ApiAccessLogInterceptor;
import com.aifuli.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import com.aifuli.framework.common.biz.infra.logger.dto.ApiErrorLogCreateReqDTO;
import com.aifuli.framework.common.biz.system.permission.PermissionCommonApi;
import com.aifuli.framework.common.enums.UserTypeEnum;
import com.aifuli.framework.common.pojo.*;
import com.aifuli.framework.security.core.LoginUser;
import com.aifuli.framework.security.core.service.*;
import com.aifuli.framework.security.core.util.SecurityFrameworkUtils;
import com.aifuli.framework.web.config.*;
import com.aifuli.framework.web.core.handler.GlobalExceptionHandler;
import com.aifuli.module.supplychain.border.fulfillment.application.*;
import com.aifuli.module.supplychain.interfaceapi.admin.vo.RechargeFrequencyAdminVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.util.*;
import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实Spring方法安全、实际admin prefix和HTTP JSON形状；日志同时检查控制台Throwable与持久化错误DTO。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限}。
 */
class RechargeFrequencyAdminContractTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private RechargeFrequencyMonitorQueryService query;
    private RechargeFrequencyAdminCommandService commands;
    private static final ThreadLocal<Set<String>> GRANTED=ThreadLocal.withInitial(Set::of);
    private static final ThreadLocal<List<com.aifuli.framework.common.biz.infra.logger.dto.ApiAccessLogCreateReqDTO>> ACCESSES=ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<Boolean> ACCESS_FAILURE=ThreadLocal.withInitial(()->false);
    private static final ThreadLocal<List<ApiErrorLogCreateReqDTO>> ERRORS=ThreadLocal.withInitial(ArrayList::new);
    private ListAppender<ILoggingEvent> logs;
    private final String prefix="/admin-api/supply-chain/recharge-frequency";
    @BeforeEach void start() {
        context=new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
        query=context.getBean(RechargeFrequencyMonitorQueryService.class); commands=context.getBean(RechargeFrequencyAdminCommandService.class);
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(new com.aifuli.framework.web.core.filter.CacheRequestBodyFilter(),accessFilter()).build();
        logs=new ListAppender<>();logs.start();
        ((Logger)LoggerFactory.getLogger(ApiAccessLogInterceptor.class)).addAppender(logs);
        ((Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class)).addAppender(logs);
        ((Logger)LoggerFactory.getLogger(com.aifuli.framework.apilog.core.filter.ApiAccessLogFilter.class)).addAppender(logs);
        login(71L,UserTypeEnum.ADMIN.getValue()); GRANTED.set(Set.of(RECHARGE_FREQUENCY_QUERY,RECHARGE_FREQUENCY_RETRY,RECHARGE_FREQUENCY_RELEASE));
    }
    @AfterEach void stop() {
        ((Logger)LoggerFactory.getLogger(ApiAccessLogInterceptor.class)).detachAppender(logs);
        ((Logger)LoggerFactory.getLogger(GlobalExceptionHandler.class)).detachAppender(logs);
        ((Logger)LoggerFactory.getLogger(com.aifuli.framework.apilog.core.filter.ApiAccessLogFilter.class)).detachAppender(logs);
        logs.stop(); context.close(); SecurityContextHolder.clearContext(); GRANTED.remove();ERRORS.remove();ACCESSES.remove();ACCESS_FAILURE.remove();
    }
    @Test void statisticsAndAllPagesPreserveCommonResultPageShapeAndNoStore() throws Exception {
        when(query.statistics(anyLong())).thenReturn(new RechargeFrequencyMonitorQueryService.Statistics());
        when(query.page(anyLong(),any(),any(),isNull())).thenReturn(new RechargeFrequencyMonitorQueryService.ObservedPage<>(new PageResult<>(List.of(),207L),java.time.LocalDateTime.of(2026,10,1,10,0)));
        mvc.perform(get(prefix+"/statistics")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.waitingOrderCount").value(0)).andExpect(header().string("Cache-Control","no-store"));
        for(String endpoint:List.of("waiting-orders","occupancies","backlogs")) {
            mvc.perform(get(prefix+"/"+endpoint)).andExpect(jsonPath("$.data.total").value(207)).andExpect(jsonPath("$.data.list").isArray())
                    .andExpect(jsonPath("$.data.observedAt").exists()).andExpect(jsonPath("$.data.page").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));
        }
    }
    @Test void separatePermissionsAndRealAdminIdentityPrecedeServiceAndAuditLookup() throws Exception {
        GRANTED.set(Set.of(ORDER_QUERY));
        mvc.perform(get(prefix+"/statistics")).andExpect(jsonPath("$.code").value(403));
        login(72L,UserTypeEnum.ADMIN.getValue()); GRANTED.set(Set.of(RECHARGE_FREQUENCY_QUERY));
        mvc.perform(post(prefix+"/entries/1/retry").contentType("application/json").content("{\"actionNo\":\"A\",\"expectedVersion\":0,\"reason\":\"核查\"}"))
                .andExpect(jsonPath("$.code").value(403));
        login(73L,UserTypeEnum.MEMBER.getValue());
        mvc.perform(get(prefix+"/statistics")).andExpect(jsonPath("$.code").value(403));
        SecurityContextHolder.clearContext();
        mvc.perform(get(prefix+"/statistics")).andExpect(jsonPath("$.code").value(403));
        login(0L,UserTypeEnum.ADMIN.getValue());
        mvc.perform(get(prefix+"/statistics")).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(query,commands);
    }
    @Test void bodySearchAndBindingFailuresNeverLogOrReturnPhoneOrRawJson() throws Exception {
        when(query.page(anyLong(),any(),any(),anyString())).thenThrow(new com.aifuli.framework.common.exception.ServiceException(
                com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.FREQUENCY_PARAMETER_INVALID));
        for(String body:List.of("{\"mobile\":\"13800138000-secret-value\",\"view\":\"WAITING\"}",
                "{\"mobile\":\"13800138000\",\"view\":\"13800138000-secret-value\"}",
                "{\"mobile\":\"13800138000-secret-value\",", "{\"mobile\":\"13800138000\"}")) {
            var full=mvc.perform(post(prefix+"/search").contentType("application/json").content(body)).andReturn();
            var response=full.getResponse();
            assertThat(response.getContentAsString()).doesNotContain("13800138000","secret-value");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
        mvc.perform(get(prefix+"/waiting-orders").param("pageNo","13800138000-secret-value")).andExpect(jsonPath("$.code").value(400));
        assertSafeLogs();
    }
    @Test void storageFailureRetainsSafeCausalTypesAndFramesInErrorAudit() throws Exception {
        when(query.statistics(anyLong())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("body=13800138000 secret-value",
                new java.sql.SQLException("credential=secret-value mobile=13800138000")));
        var response=mvc.perform(get(prefix+"/statistics")).andReturn().getResponse();
        assertThat(response.getContentAsString()).contains("500").doesNotContain("13800138000","secret-value");
        assertThat(ERRORS.get()).hasSize(1);
        var dto=ERRORS.get().getFirst();
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(dto)).doesNotContain("13800138000","secret-value");
        assertThat(dto.getRequestParams()).isEqualTo("{}");
        assertThat(dto.getExceptionStackTrace()).contains("DataAccessResourceFailureException","SQLException","RechargeFrequencyAdminController");
        assertSafeLogs();
    }
    @Test void accessFilterUpstreamAndLoggerFailureKeepSafeCausesAndBusinessResult() throws Exception {
        var request=new MockHttpServletRequest("POST",prefix+"/search");request.setContentType("application/json");
        request.setContent("{mobile:13800138000, secret:secret-value}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response=new MockHttpServletResponse();
        assertThatThrownBy(()->accessFilter().doFilter(request,response,(req,res)->{
            throw new jakarta.servlet.ServletException("body=13800138000 secret-value",new java.sql.SQLException("credential=secret-value"));
        })).isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(ACCESSES.get()).hasSize(1);
        assertThat(ACCESSES.get().getFirst().getResultMsg()).doesNotContain("13800138000","secret-value");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        ACCESS_FAILURE.set(true);
        when(query.statistics(anyLong())).thenReturn(new RechargeFrequencyMonitorQueryService.Statistics());
        mvc.perform(get(prefix+"/statistics")).andExpect(jsonPath("$.code").value(0));
        assertSafeLogs();
        String messages=logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
        assertThat(messages).contains("SQLException","访问日志存储失败","failureCount");
    }
    private com.aifuli.framework.apilog.core.filter.ApiAccessLogFilter accessFilter() {
        return new com.aifuli.framework.apilog.core.filter.ApiAccessLogFilter(new WebProperties(),"contract",dto->{
            ACCESSES.get().add(dto);
            if(ACCESS_FAILURE.get()) {throw new org.springframework.dao.DataAccessResourceFailureException("mobile=13800138000 secret-value",new java.sql.SQLException("credential=secret-value"));}
            return success(true);
        });
    }
    @Test void mobileInUrlIsRejectedAndReleaseRequiresItsOwnPermission() throws Exception {
        mvc.perform(get(prefix+"/waiting-orders").param("mobile","13800138000")).andExpect(jsonPath("$.code").value(
                com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.FREQUENCY_PARAMETER_INVALID.getCode()));
        GRANTED.set(Set.of(RECHARGE_FREQUENCY_RETRY));login(74L,UserTypeEnum.ADMIN.getValue());
        mvc.perform(post(prefix+"/queues/1/release").contentType("application/json").content("{\"actionNo\":\"R\",\"expectedHolderOrderNo\":\"ORD\",\"expectedGeneration\":1,\"expectedVersion\":1,\"reason\":\"核查\"}"))
                .andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(query,commands);assertSafeLogs();
    }
    @Test void unsupportedMediaAndMethodRemainSafeBeforeMvcParameterBinding() throws Exception {
        for(var builder:List.of(post(prefix+"/search").contentType("text/plain"),post(prefix+"/statistics").contentType("application/json"))) {
            var response=mvc.perform(builder.content("13800138000 secret-value")).andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain("13800138000","secret-value");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
        verifyNoInteractions(query,commands);assertSafeLogs();
    }
    @Test void requestToStringExcludesMobileAndReasonAndCannotSupplyOperator() throws Exception {
        var request=new RechargeFrequencyAdminVO.SearchRequest();request.setMobile("13800138000");request.setView(RechargeFrequencyAdminVO.View.WAITING);
        assertThat(request.toString()).doesNotContain("13800138000");
        assertThat(new RechargeFrequencyAdminVO.RetryRequest("A",0L,"13800138000").toString()).doesNotContain("13800138000");
        when(commands.retry(eq(71L),eq(1L),any())).thenReturn(new RechargeFrequencyAdminCommandService.ActionResult("A","RETRY_SCHEDULED","已触发检查"));
        mvc.perform(post(prefix+"/entries/1/retry").contentType("application/json").content("{\"operatorId\":99,\"actionNo\":\"A\",\"expectedVersion\":0,\"reason\":\"核查\"}"))
                .andExpect(jsonPath("$.data.resultCode").value("RETRY_SCHEDULED"));
        verify(commands).retry(eq(71L),eq(1L),any());
    }
    private void assertSafeLogs() {
        String rendered=logs.list.stream().map(event->event.getFormattedMessage()+ (event.getThrowableProxy()==null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(rendered).doesNotContain("13800138000","secret-value");
        for (var dto:ACCESSES.get()) {
            assertThat(dto.getRequestParams()).isNull();
            assertThat(dto.getResultMsg()).doesNotContain("13800138000","secret-value");
        }
    }
    private void login(Long id,Integer type) { var user=new LoginUser();user.setId(id);user.setUserType(type);SecurityFrameworkUtils.setLoginUser(user,new MockHttpServletRequest()); }
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity
    static class Config extends WebMvcConfigurationSupport {
        @Bean RechargeFrequencyMonitorQueryService query(){return mock(RechargeFrequencyMonitorQueryService.class);}
        @Bean RechargeFrequencyAdminCommandService commands(){return mock(RechargeFrequencyAdminCommandService.class);}
        @Bean RechargeFrequencyAdminController controller(RechargeFrequencyMonitorQueryService query,RechargeFrequencyAdminCommandService commands){return new RechargeFrequencyAdminController(query,commands);}
        @Bean(name="ss") SecurityFrameworkService security() {
            var api=mock(PermissionCommonApi.class);
            when(api.hasAnyPermissions(anyLong(),any(String[].class))).thenAnswer(invocation->success(Arrays.stream((String[])invocation.getRawArguments()[1]).anyMatch(GRANTED.get()::contains)));
            return new SecurityFrameworkServiceImpl(api);
        }
        @Bean GlobalExceptionHandler exceptions(){return new GlobalExceptionHandler("contract", dto->{ERRORS.get().add(dto);return success(true);});}
        @Bean com.aifuli.framework.web.core.util.WebFrameworkUtils webFrameworkUtils() {return new com.aifuli.framework.web.core.util.WebFrameworkUtils(new WebProperties());}
        @Bean cn.hutool.extra.spring.SpringUtil springUtil(){return new cn.hutool.extra.spring.SpringUtil();}
        @Override protected RequestMappingHandlerMapping createRequestMappingHandlerMapping() {
            var properties=new WebProperties();properties.setAdminApi(new WebProperties.Api("/admin-api","**.interfaceapi.admin.**"));
            return new AifuliWebAutoConfiguration().webMvcRegistrations(properties).getRequestMappingHandlerMapping();
        }
        @Override protected void addInterceptors(InterceptorRegistry registry) {registry.addInterceptor(new ApiAccessLogInterceptor());}
    }
}
