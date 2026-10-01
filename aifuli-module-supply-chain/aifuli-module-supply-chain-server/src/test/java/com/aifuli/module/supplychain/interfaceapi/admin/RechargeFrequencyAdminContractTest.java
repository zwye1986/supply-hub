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
    private static final ThreadLocal<Boolean> ERROR_FAILURE=ThreadLocal.withInitial(()->false);
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
        logs.stop(); context.close(); SecurityContextHolder.clearContext(); GRANTED.remove();ERRORS.remove();ACCESSES.remove();ACCESS_FAILURE.remove();ERROR_FAILURE.remove();
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
    /** 前端使用真实应用时间格式与安全长整型，避免默认MockMvc序列化掩盖契约差异。 */
    @Test void frontendFieldsUseApplicationWireTypesAndActionResult() throws Exception {
        var original=TimeZone.getDefault();
        try {
            for(String zone:List.of("UTC","Asia/Shanghai")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertFrontendWireContract();
            }
        } finally { TimeZone.setDefault(original); }
    }
    private void assertFrontendWireContract() throws Exception {
        var observed=java.time.LocalDateTime.of(2026,10,1,10,0);
        long epoch=observed.atZone(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.DATABASE_ZONE).toInstant().toEpochMilli();
        var waiting=new RechargeFrequencyMonitorQueryService.WaitingOrder();
        waiting.setEntryId(Long.MAX_VALUE);waiting.setQueueId(2L);waiting.setVersion(3L);
        waiting.setMaskedMobile("138****8000");waiting.setPlatformSpuCode("SPU");waiting.setPlatformSpuName("直充商品");
        waiting.setOrderNo("ORD");waiting.setBuyerOrderNo("BUYER-ORD");waiting.setBuyerId(4L);waiting.setBuyerCode("B");waiting.setBuyerName("分销商");
        waiting.setProjectId(5L);waiting.setProjectCode("P");waiting.setProjectName("项目");waiting.setEnqueuedAt(observed);waiting.setWaitSeconds(12);waiting.setQueueSequence(6);
        doReturn(new RechargeFrequencyMonitorQueryService.ObservedPage<>(new PageResult<>(List.of(waiting),207L),observed))
                .when(query).page(anyLong(),eq(RechargeFrequencyMonitorQueryService.View.WAITING),any(),isNull());
        mvc.perform(get(prefix+"/waiting-orders").param("pageNo","2").param("pageSize","20"))
                .andExpect(jsonPath("$.data.observedAt").value(epoch)).andExpect(jsonPath("$.data.list[0].enqueuedAt").value(epoch))
                .andExpect(jsonPath("$.data.list[0].entryId").value(Long.toString(Long.MAX_VALUE)))
                .andExpect(jsonPath("$.data.list[0].version").value(3)).andExpect(jsonPath("$.data.total").value(207))
                .andExpect(jsonPath("$.data.list[0].buyerName").value("分销商")).andExpect(jsonPath("$.data.list[0].projectName").value("项目"))
                .andExpect(jsonPath("$.data.list[0].maskedMobile").value("138****8000")).andExpect(jsonPath("$.data.list[0].waitSeconds").value(12));
        var stats=new RechargeFrequencyMonitorQueryService.Statistics();stats.setWaitingOrderCount(207);stats.setOccupiedQueueCount(3);
        stats.setOverThresholdQueueCount(2);stats.setOverdueQueueCount(1);stats.setSuccessMinimumHoldSeconds(5);stats.setOccupiedAlertSeconds(1800);stats.setBacklogThreshold(5);stats.setObservedAt(observed);
        when(query.statistics(anyLong())).thenReturn(stats);
        mvc.perform(get(prefix+"/statistics")).andExpect(jsonPath("$.data.observedAt").value(epoch))
                .andExpect(jsonPath("$.data.waitingOrderCount").value(207)).andExpect(jsonPath("$.data.occupiedQueueCount").value(3))
                .andExpect(jsonPath("$.data.overThresholdQueueCount").value(2)).andExpect(jsonPath("$.data.overdueQueueCount").value(1))
                .andExpect(jsonPath("$.data.successMinimumHoldSeconds").value(5)).andExpect(jsonPath("$.data.occupiedAlertSeconds").value(1800)).andExpect(jsonPath("$.data.backlogThreshold").value(5));
        when(commands.retry(eq(71L),eq(1L),any())).thenReturn(new RechargeFrequencyAdminCommandService.ActionResult("A","RETRY_SCHEDULED","已触发检查"));
        mvc.perform(post(prefix+"/entries/1/retry").contentType("application/json").content("{\"actionNo\":\"A\",\"expectedVersion\":0,\"reason\":\"核查\"}"))
                .andExpect(jsonPath("$.data.actionNo").value("A")).andExpect(jsonPath("$.data.resultCode").value("RETRY_SCHEDULED")).andExpect(jsonPath("$.data.message").value("已触发检查"));
        when(commands.release(eq(71L),eq(2L),any())).thenReturn(new RechargeFrequencyAdminCommandService.ActionResult("R","RELEASED","已释放"));
        mvc.perform(post(prefix+"/queues/2/release").contentType("application/json").content("{\"actionNo\":\"R\",\"expectedHolderOrderNo\":\"ORD\",\"expectedGeneration\":1,\"expectedVersion\":3,\"reason\":\"核查\"}"))
                .andExpect(jsonPath("$.data.resultCode").value("RELEASED"));
    }
    @Test void occupancyAndBacklogFieldsPreserveNullableMigrationAndShanghaiTimes() throws Exception {
        var original=TimeZone.getDefault();
        var observed=java.time.LocalDateTime.of(2026,10,1,10,0);
        long epoch=observed.atZone(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.DATABASE_ZONE).toInstant().toEpochMilli();
        var occupancy=new RechargeFrequencyMonitorQueryService.Occupancy();occupancy.setQueueId(2L);occupancy.setMaskedMobile("138****8000");
        occupancy.setPlatformSpuCode("SPU");occupancy.setPlatformSpuName("直充商品");occupancy.setHolderOrderNo("ORD");
        occupancy.setAcquiredAt(observed);occupancy.setMinimumHoldUntil(observed.plusSeconds(5));occupancy.setHeldSeconds(1801L);occupancy.setOverdue(true);
        occupancy.setDisplayStatus("RESULT_UNKNOWN");occupancy.setGeneration(Long.MAX_VALUE);occupancy.setVersion(9L);occupancy.setCanRelease(false);
        occupancy.setReleaseBlockedReason("UNCONFIRMED_SEND_RISK");occupancy.setRiskOrderNos(List.of("RISK"));
        var migration=new RechargeFrequencyMonitorQueryService.Occupancy();migration.setQueueId(3L);migration.setDisplayStatus("BLOCKED");migration.setRiskOrderNos(List.of("MIGRATION-RISK"));
        doReturn(new RechargeFrequencyMonitorQueryService.ObservedPage<>(new PageResult<>(List.of(occupancy,migration),2L),observed))
                .when(query).page(anyLong(),eq(RechargeFrequencyMonitorQueryService.View.OCCUPANCIES),any(),isNull());
        var backlog=new RechargeFrequencyMonitorQueryService.Backlog();backlog.setQueueId(2L);backlog.setMaskedMobile("138****8000");backlog.setPlatformSpuCode("SPU");backlog.setPlatformSpuName("直充商品");
        backlog.setWaitingCount(6);backlog.setEarliestEnqueuedAt(observed);backlog.setOverThreshold(true);
        doReturn(new RechargeFrequencyMonitorQueryService.ObservedPage<>(new PageResult<>(List.of(backlog),1L),observed))
                .when(query).page(anyLong(),eq(RechargeFrequencyMonitorQueryService.View.BACKLOGS),any(),isNull());
        try {
            for(String zone:List.of("UTC","Asia/Shanghai")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                var result=mvc.perform(get(prefix+"/occupancies")).andExpect(jsonPath("$.data.list[0].acquiredAt").value(epoch))
                        .andExpect(jsonPath("$.data.list[0].minimumHoldUntil").value(epoch+5000))
                        .andExpect(jsonPath("$.data.list[0].generation").value(Long.toString(Long.MAX_VALUE)))
                        .andExpect(jsonPath("$.data.list[0].version").value(9)).andExpect(jsonPath("$.data.list[0].canRelease").value(false))
                        .andExpect(jsonPath("$.data.list[0].heldSeconds").value(1801)).andExpect(jsonPath("$.data.list[0].overdue").value(true))
                        .andExpect(jsonPath("$.data.list[0].displayStatus").value("RESULT_UNKNOWN"))
                        .andExpect(jsonPath("$.data.list[0].releaseBlockedReason").value("UNCONFIRMED_SEND_RISK"))
                        .andExpect(jsonPath("$.data.list[0].riskOrderNos[0]").value("RISK")).andReturn();
                var json=new ObjectMapper().readTree(result.getResponse().getContentAsString());
                assertThat(json.at("/data/list/1/holderOrderNo").isNull()).isTrue();
                assertThat(json.at("/data/list/1/acquiredAt").isNull()).isTrue();assertThat(json.at("/data/list/1/heldSeconds").isNull()).isTrue();
                mvc.perform(get(prefix+"/backlogs")).andExpect(jsonPath("$.data.list[0].earliestEnqueuedAt").value(epoch))
                        .andExpect(jsonPath("$.data.list[0].waitingCount").value(6)).andExpect(jsonPath("$.data.list[0].overThreshold").value(true));
            }
        } finally { TimeZone.setDefault(original); }
    }
    @Test void postEpochAndGetIsoFiltersHaveSameShanghaiWallTime() throws Exception {
        var original=TimeZone.getDefault();
        var expected=java.time.LocalDateTime.of(2026,10,1,10,0);
        long epoch=expected.atZone(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.DATABASE_ZONE).toInstant().toEpochMilli();
        doReturn(new RechargeFrequencyMonitorQueryService.ObservedPage<>(new PageResult<>(List.of(),0L),expected))
                .when(query).page(anyLong(),any(),any(),any());
        try {
            for(String zone:List.of("UTC","Asia/Shanghai")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));clearInvocations(query);
                mvc.perform(get(prefix+"/waiting-orders").param("enqueuedFrom","2026-10-01T10:00:00").param("enqueuedTo","2026-10-01T10:00:00"))
                        .andExpect(jsonPath("$.code").value(0));
                mvc.perform(post(prefix+"/search").contentType("application/json").content("{\"view\":\"WAITING\",\"mobile\":\"13800138000\",\"enqueuedFrom\":"+epoch+",\"enqueuedTo\":"+epoch+"}"))
                        .andExpect(jsonPath("$.code").value(0));
                var captor=org.mockito.ArgumentCaptor.forClass(RechargeFrequencyMonitorQueryService.Filter.class);
                verify(query,times(2)).page(eq(71L),eq(RechargeFrequencyMonitorQueryService.View.WAITING),captor.capture(),any());
                assertThat(captor.getAllValues()).allSatisfy(filter->{assertThat(filter.enqueuedFrom()).isEqualTo(expected);assertThat(filter.enqueuedTo()).isEqualTo(expected);});
            }
            mvc.perform(post(prefix+"/search").contentType("application/json").content("{\"view\":\"WAITING\",\"mobile\":\"13800138000\",\"enqueuedFrom\":\"2026-10-01T10:00:00\"}"))
                    .andExpect(jsonPath("$.code").value(400));
        } finally { TimeZone.setDefault(original); }
    }
    /** 只验证HTTP参数与权限契约；真实整队列统计由RechargeFrequencyAdminIntegrationTest验证。 */
    @Test void backlogGetAndSensitivePostForwardSameEntryFiltersWithShanghaiTime() throws Exception {
        var original=TimeZone.getDefault();
        var boundary=java.time.LocalDateTime.of(2026,10,1,10,0);
        long epoch=boundary.atZone(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.DATABASE_ZONE).toInstant().toEpochMilli();
        doReturn(new RechargeFrequencyMonitorQueryService.ObservedPage<>(new PageResult<>(List.of(),0L),boundary))
                .when(query).page(anyLong(),any(),any(),any());
        try {
            for(String zone:List.of("UTC","Asia/Shanghai")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));clearInvocations(query);
                mvc.perform(get(prefix+"/backlogs").param("buyerId",Long.toString(Long.MAX_VALUE)).param("projectId","202")
                        .param("platformSpuCode","SPU").param("orderNo","ORDER").param("buyerOrderNo","BUYER-ORDER")
                        .param("enqueuedFrom","2026-10-01T10:00:00").param("enqueuedTo","2026-10-01T10:00:00"))
                        .andExpect(jsonPath("$.code").value(0)).andExpect(header().string("Cache-Control","no-store"));
                mvc.perform(post(prefix+"/search").contentType("application/json").content(
                        "{\"view\":\"BACKLOGS\",\"mobile\":\"13800138000\",\"buyerId\":\""+Long.MAX_VALUE+"\",\"projectId\":202,"
                        +"\"platformSpuCode\":\"SPU\",\"orderNo\":\"ORDER\",\"buyerOrderNo\":\"BUYER-ORDER\",\"enqueuedFrom\":"+epoch+",\"enqueuedTo\":"+epoch+"}"))
                        .andExpect(jsonPath("$.code").value(0)).andExpect(header().string("Cache-Control","no-store"));
                var captor=org.mockito.ArgumentCaptor.forClass(RechargeFrequencyMonitorQueryService.Filter.class);
                verify(query).page(eq(71L),eq(RechargeFrequencyMonitorQueryService.View.BACKLOGS),captor.capture(),isNull());
                verify(query).page(eq(71L),eq(RechargeFrequencyMonitorQueryService.View.BACKLOGS),captor.capture(),eq("13800138000"));
                assertThat(captor.getAllValues()).allSatisfy(filter->{
                    assertThat(filter.buyerId()).isEqualTo(Long.MAX_VALUE);assertThat(filter.projectId()).isEqualTo(202L);
                    assertThat(filter.platformSpuCode()).isEqualTo("SPU");assertThat(filter.orderNo()).isEqualTo("ORDER");
                    assertThat(filter.buyerOrderNo()).isEqualTo("BUYER-ORDER");assertThat(filter.enqueuedFrom()).isEqualTo(boundary);
                    assertThat(filter.enqueuedTo()).isEqualTo(boundary);assertThat(filter.pageNo()).isEqualTo(1);assertThat(filter.pageSize()).isEqualTo(20);
                });
            }
        } finally {TimeZone.setDefault(original);}
    }
    @Test void occupancyDisplayStatesRemainExplicitInHttpAndInternalProofFieldsAreHidden() throws Exception {
        var rows=new ArrayList<RechargeFrequencyMonitorQueryService.Occupancy>();
        var observed=java.time.LocalDateTime.of(2026,10,1,10,0);
        var request=com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentRequestStatus.FULFILLING;
        var operation=com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus.RESULT_UNKNOWN;
        for(String expected:List.of("RESULT_UNKNOWN","MANUAL","SUCCESS_HOLD","SUCCEEDED","BLOCKED","EXECUTING","MANUAL","FAILED")) {
            var row=new RechargeFrequencyMonitorQueryService.Occupancy();row.setQueueId((long)rows.size()+1);
            row.setRequestStatus(request.getCode());row.setOperationStatus(operation.getCode());
            if(rows.size()==1) {row.setOperationStatus(com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus.MANUAL.getCode());}
            if(rows.size()>=2 && rows.size()<=4) {
                row.setRequestStatus(com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentRequestStatus.SUCCEEDED.getCode());
                row.setOperationStatus(com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus.SUCCEEDED.getCode());
                row.setTerminalEvidence(true);row.setAcquiredAt(observed.minusSeconds(rows.size()==2 ? 4 : 5));
            }
            if(rows.size()==4) {row.setBlockedReason("MIGRATION_UNKNOWN");}
            if(rows.size()==5) {row.setOperationStatus(com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus.EXECUTING.getCode());}
            if(rows.size()==6) {row.setRequestStatus(com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentRequestStatus.MANUAL.getCode());}
            if(rows.size()==7) {
                row.setRequestStatus(com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentRequestStatus.FAILED.getCode());
                row.setOperationStatus(com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus.FAILED.getCode());row.setTerminalEvidence(true);
            }
            rows.add(row);
        }
        var mapper=mock(com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyMonitorMapper.class);
        when(mapper.occupancies(any())).thenReturn(rows);when(mapper.occupancyCount(any())).thenReturn(8L);
        var clock=mock(com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock.class);when(clock.now()).thenReturn(observed);
        var realQuery=new RechargeFrequencyMonitorQueryService(mapper,mock(RechargeFrequencyIdentityService.class),clock);
        doAnswer(invocation->realQuery.page(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2),invocation.getArgument(3)))
                .when(query).page(anyLong(),eq(RechargeFrequencyMonitorQueryService.View.OCCUPANCIES),any(),isNull());
        var response=mvc.perform(get(prefix+"/occupancies")).andExpect(jsonPath("$.data.list[0].displayStatus").value("RESULT_UNKNOWN"))
                .andExpect(jsonPath("$.data.list[1].displayStatus").value("MANUAL"))
                .andExpect(jsonPath("$.data.list[2].displayStatus").value("SUCCESS_HOLD"))
                .andExpect(jsonPath("$.data.list[3].displayStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.list[4].displayStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.data.list[5].displayStatus").value("EXECUTING"))
                .andExpect(jsonPath("$.data.list[6].displayStatus").value("MANUAL"))
                .andExpect(jsonPath("$.data.list[7].displayStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.list[2].canRelease").value(false))
                .andExpect(jsonPath("$.data.list[3].canRelease").value(true)).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("requestStatus","operationStatus","terminalEvidence","preparedSafe");
        assertSafeLogs();
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
    @Test void errorLogApiFailuresAreSampledIndependentlyAndPreserveSafeHttpResult() throws Exception {
        ERROR_FAILURE.set(true);
        when(query.page(anyLong(),any(),any(),anyString())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                "body=13800138000 secret-value",new java.sql.SQLException("credential=secret-value")));
        for(int i=0;i<201;i++) {
            var response=mvc.perform(post(prefix+"/search").contentType("application/json").content("{\"mobile\":\"13800138000\",\"view\":\"WAITING\"}"))
                    .andExpect(jsonPath("$.code").value(500)).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain("13800138000","secret-value");
        }
        assertThat(ERRORS.get()).hasSize(201);assertThat(ACCESSES.get()).hasSize(201);
        assertSafeLogs();
        for(var dto:ERRORS.get()) {
            assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(dto)).doesNotContain("13800138000","secret-value");
            assertThat(dto.getRequestParams()).isEqualTo("{}");
        }
        var failures=logs.list.stream().filter(event->event.getFormattedMessage().contains("限频管理异常日志存储失败")).toList();
        assertThat(failures).hasSize(3);
        assertThat(failures.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .satisfiesExactly(first->assertThat(first).contains("failureCount=1,"),
                        hundred->assertThat(hundred).contains("failureCount=100,"),
                        twoHundred->assertThat(twoHundred).contains("failureCount=200,"));
        for(var event:failures) {
            assertThat(event.getFormattedMessage()).contains("DataAccessResourceFailureException","SQLException","RechargeFrequencyAdminContractTest","traceId=");
            assertThat(event.getThrowableProxy()).isNull();
        }
        // 普通路径继续既有完整日志行为，不进入限频计数或脱敏分支。
        var ordinary=new MockHttpServletRequest("POST","/admin-api/ordinary");ordinary.setContentType("application/json");
        ordinary.setContent("{\"marker\":\"ordinary-body\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var result=context.getBean(GlobalExceptionHandler.class).defaultExceptionHandler(ordinary,new IllegalStateException("ordinary-primary"));
        assertThat(result.getCode()).isEqualTo(500);
        assertThat(ERRORS.get().getLast().getRequestParams()).contains("ordinary-body");
        assertThat(logs.list.stream().filter(event->event.getFormattedMessage().startsWith("[createExceptionLog]")).toList())
                .singleElement().satisfies(event->{assertThat(event.getFormattedMessage()).contains("/admin-api/ordinary","ordinary-primary");assertThat(event.getThrowableProxy()).isNotNull();});
        assertThat(logs.list.stream().filter(event->event.getFormattedMessage().contains("限频管理异常日志存储失败"))).hasSize(3);
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
        @Bean GlobalExceptionHandler exceptions(){return new GlobalExceptionHandler("contract", dto->{
            ERRORS.get().add(dto);
            if(ERROR_FAILURE.get()) {
                throw new org.springframework.dao.DataAccessResourceFailureException(
                        dto.getRequestUrl().contains("recharge-frequency") ? "mobile=13800138000 secret-value" : "ordinary-log-api-error",
                        new java.sql.SQLException(dto.getRequestUrl().contains("recharge-frequency") ? "credential=secret-value" : "ordinary-log-api-cause"));
            }
            return success(true);
        });}
        @Bean com.aifuli.framework.web.core.util.WebFrameworkUtils webFrameworkUtils() {return new com.aifuli.framework.web.core.util.WebFrameworkUtils(new WebProperties());}
        @Bean cn.hutool.extra.spring.SpringUtil springUtil(){return new cn.hutool.extra.spring.SpringUtil();}
        /** 与运行应用相同的Jackson定制；MVC默认findModules不是业务wire配置。 */
        @Override protected void configureMessageConverters(List<org.springframework.http.converter.HttpMessageConverter<?>> converters) {
            var appJackson=new com.aifuli.framework.jackson.config.AifuliJacksonAutoConfiguration();
            var builder=org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json();
            appJackson.ldtEpochMillisCustomizer().customize(builder);
            ObjectMapper mapper=builder.build();mapper.registerModule(appJackson.timestampSupportModuleBean());
            converters.add(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper));
        }
        @Override protected RequestMappingHandlerMapping createRequestMappingHandlerMapping() {
            var properties=new WebProperties();properties.setAdminApi(new WebProperties.Api("/admin-api","**.interfaceapi.admin.**"));
            return new AifuliWebAutoConfiguration().webMvcRegistrations(properties).getRequestMappingHandlerMapping();
        }
        @Override protected void addInterceptors(InterceptorRegistry registry) {registry.addInterceptor(new ApiAccessLogInterceptor());}
    }
}
