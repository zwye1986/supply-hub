package com.aifuli.module.supplychain.border.fulfillment;

import com.aifuli.module.supplychain.border.fulfillment.application.singlestep.SingleStepCallbackApplicationService;
import com.aifuli.module.supplychain.border.fulfillment.domain.SingleStepCallbackException;
import com.aifuli.module.supplychain.interfaceapi.callback.SingleStepSupplierCallbackController;
import com.aifuli.module.supplychain.infrastructure.security.SupplyChainSecurityConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

/** 仁跃200错误JSON与提交后纯文本ACK；不修改华昇HTTP合同。 */
class SingleStepCallbackRestContractTest {
    private SingleStepCallbackApplicationService application;
    private MockMvc mvc;
    @BeforeEach void start() {
        application = mock(SingleStepCallbackApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SingleStepSupplierCallbackController(application))
                .addFilters(new com.aifuli.framework.tracer.core.filter.TraceFilter()).build();
    }
    @Test void supplierWithoutTraceHeaderUsesExistingEntryTrace() throws Exception {
        var response = mvc.perform(post("/callbacks/suppliers/renyue/5").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("x=y"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var trace = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(application).handleRenyue(eq(5L), any(), trace.capture());
        assertThat(trace.getValue()).isNotBlank().isEqualTo(response.getHeader("trace-id"));
        assertThat(org.slf4j.MDC.get("traceId")).isNull();
    }
    @Test void successIsExactPlaintextAndServiceReceivesRawBodyBeforeAck() throws Exception {
        doAnswer(call -> { assertThat(((java.io.InputStream) call.getArgument(1)).readAllBytes()).containsExactly("StoreNo=raw%2Bbody".getBytes()); return null; })
                .when(application).handleRenyue(eq(5L), any(), eq("trace-http"));
        mvc.perform(post("/callbacks/suppliers/renyue/5").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("trace-id", "trace-http").content("StoreNo=raw%2Bbody"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN)).andExpect(content().bytes("success".getBytes()));
        verify(application).handleRenyue(eq(5L), any(), eq("trace-http"));
    }
    @ParameterizedTest @EnumSource(SingleStepCallbackException.Code.class)
    void failureIs200WithStableCodeAndSafeMessage(SingleStepCallbackException.Code code) throws Exception {
        doThrow(new SingleStepCallbackException(code)).when(application).handleRenyue(any(), any(), any());
        String message = switch (code) {
            case INVALID_CALLBACK -> "回调格式或参数无效";
            case CALLBACK_NOT_FOUND -> "回调原单不存在";
            case AUTHENTICATION_FAILED -> "回调认证失败";
            case IDENTITY_CONFLICT -> "回调原单身份不符";
            case CALLBACK_PROCESSING_FAILED -> "回调处理失败，请稍后重试";
        };
        mvc.perform(post("/callbacks/suppliers/renyue/5").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("x=y"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code.name())).andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
    @Test void rolledBackOrUnexpectedFailureCannotAckOrEchoException() throws Exception {
        doThrow(new IllegalStateException("PRIVATE_TARGET_OR_SQL", new java.sql.SQLException("PRIVATE_KEY")))
                .when(application).handleRenyue(any(), any(), any());
        var response = mvc.perform(post("/callbacks/suppliers/renyue/5").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("x=y"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("CALLBACK_PROCESSING_FAILED")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("PRIVATE", "success");
    }
    /** 业务拒绝单行WARN；不以供应商HTTP200或异常原文冒充成功/安全响应。 */
    @Test void serviceRejectionIsWarnWithoutStackOrOriginalMessage() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SingleStepSupplierCallbackController.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            doThrow(new com.aifuli.framework.common.exception.ServiceException(12345, "SYNTHETIC_PRIVATE_REJECTION"))
                    .when(application).handleRenyue(any(), any(), any());
            var response = mvc.perform(post("/callbacks/suppliers/renyue/5").contentType(MediaType.APPLICATION_FORM_URLENCODED))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("CALLBACK_PROCESSING_FAILED"))
                    .andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain("SYNTHETIC_PRIVATE_REJECTION");
            assertThat(logs.list).hasSize(1).allSatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("SYNTHETIC_PRIVATE_REJECTION");
            });
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    /** 安全异常副本不能丢失suppressed；循环异常图必须有界且不读取原消息。 */
    @Test void systemFailurePreservesSanitizedCauseAndSuppressedWithoutCycles() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SingleStepSupplierCallbackController.class);
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start(); logger.addAppender(logs);
        try {
            var cause = new java.sql.SQLException("SYNTHETIC_PRIVATE_CAUSE");
            var failure = new IllegalStateException("SYNTHETIC_PRIVATE_FAILURE", cause);
            var suppressed = new java.io.IOException("SYNTHETIC_PRIVATE_SUPPRESSED");
            failure.addSuppressed(suppressed); suppressed.addSuppressed(failure);
            doThrow(failure).when(application).handleRenyue(any(), any(), any());
            mvc.perform(post("/callbacks/suppliers/renyue/5").contentType(MediaType.APPLICATION_FORM_URLENCODED))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("CALLBACK_PROCESSING_FAILED"));
            assertThat(logs.list).hasSize(1);
            var event = logs.list.getFirst();
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
            var safe = event.getThrowableProxy();
            assertThat(safe.getMessage()).isEqualTo(IllegalStateException.class.getName());
            assertThat(safe.getCause().getMessage()).isEqualTo(java.sql.SQLException.class.getName());
            assertThat(safe.getSuppressed()).hasSize(1);
            assertThat(safe.getSuppressed()[0].getMessage()).isEqualTo(java.io.IOException.class.getName());
            assertThat(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(safe)).doesNotContain("SYNTHETIC_PRIVATE");
        } finally { logger.detachAppender(logs); logs.stop(); }
    }

    @ParameterizedTest @ValueSource(strings = {"bad", "0", "-1", "9223372036854775808"})
    void invalidVersionReturns200ErrorNotFramework400(String version) throws Exception {
        mvc.perform(post("/callbacks/suppliers/renyue/" + version).contentType(MediaType.APPLICATION_FORM_URLENCODED).content("x=y"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("INVALID_CALLBACK"));
        verifyNoInteractions(application);
    }
    @ParameterizedTest @ValueSource(strings = {"application/json", "text/plain", "application/*", "*/*", "application/x-www-form-urlencoded;charset=GBK"})
    void unsupportedContentTypeReturns200Error(String type) throws Exception {
        mvc.perform(post("/callbacks/suppliers/renyue/5").contentType(type).content("x=y"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("INVALID_CALLBACK"));
        verifyNoInteractions(application);
    }
    @Test void queryParametersCannotEnterFormAuthentication() throws Exception {
        mvc.perform(post("/callbacks/suppliers/renyue/5?StoreNo=query").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("x=y"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("INVALID_CALLBACK"));
        verifyNoInteractions(application);
    }
    @Test void tokenBypassIsOnlyOnePostPathSegment() throws Exception {
        try (var context = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new org.springframework.mock.web.MockServletContext()); context.register(Security.class); context.refresh();
            var filter = context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class);
            var secured = MockMvcBuilders.standaloneSetup(new SingleStepSupplierCallbackController(application)).addFilters(filter).build();
            secured.perform(post("/callbacks/suppliers/renyue/5").contentType(MediaType.APPLICATION_FORM_URLENCODED)).andExpect(status().isOk());
            for (String path : java.util.List.of("/callbacks/suppliers/renyue/5/extra", "/callbacks/suppliers/renyue", "/admin-api/supply-chain/supplier")) {
                assertThat(secured.perform(post(path)).andReturn().getResponse().getStatus()).isIn(401, 403);
            }
            assertThat(secured.perform(get("/callbacks/suppliers/renyue/5")).andReturn().getResponse().getStatus()).isIn(401, 403);
        }
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class Security {
        @org.springframework.context.annotation.Bean org.springframework.security.web.SecurityFilterChain filter(org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
            http.csrf(c -> c.disable());
            http.authorizeHttpRequests(registry -> { new SupplyChainSecurityConfiguration().supplyChainAuthorizeRequestsCustomizer().customize(registry); registry.anyRequest().denyAll(); });
            return http.build();
        }
    }
}
