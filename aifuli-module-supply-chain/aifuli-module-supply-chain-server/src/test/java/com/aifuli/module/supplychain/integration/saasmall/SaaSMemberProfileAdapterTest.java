package com.aifuli.module.supplychain.integration.saasmall;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.tenant.core.context.TenantContextHolder;
import com.aifuli.module.member.api.profile.MemberProfileResolveApi;
import com.aifuli.module.member.api.profile.dto.MemberProfileResolveReqDTO;
import com.aifuli.module.member.api.profile.dto.MemberProfileResolveRespDTO;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort.MemberProfileResult;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort.MemberResolutionStatus;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort.ResolveMemberCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SaaS 会员资料 Adapter 单测：验证三元身份字符串原样透传、路由白名单、
 * 结果映射与租户上下文恢复。设计见
 * {@code docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#33-supply-chain-server}。
 */
@ExtendWith(MockitoExtension.class)
class SaaSMemberProfileAdapterTest {

    private static final String TENANT_SENTINEL = "TENANT-SECRET-SENTINEL";
    private static final String NUMERIC_TENANT_SENTINEL = "987654321098765432";
    private static final String BUYER_USER_SENTINEL = "BUYER-SECRET-SENTINEL";
    private static final String MOBILE_SENTINEL = "13800138000";
    private static final String USER_NAME_SENTINEL = "NAME-SECRET-SENTINEL";
    private static final String FAILURE_MESSAGE_SENTINEL = "RPC-SECRET-SENTINEL";

    @Mock
    private MemberProfileResolveApi memberProfileResolveApi;

    @InjectMocks
    private SaaSMemberProfileAdapter adapter;

    private Long savedTenantId;
    private Boolean savedIgnore;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger adapterLogger;

    @BeforeEach
    void saveTenantContext() {
        savedTenantId = TenantContextHolder.getTenantId();
        savedIgnore = TenantContextHolder.isIgnore();
        adapterLogger = (Logger) LoggerFactory.getLogger(SaaSMemberProfileAdapter.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        adapterLogger.addAppender(logAppender);
    }

    @AfterEach
    void restoreTenantContext() {
        TenantContextHolder.setTenantId(savedTenantId);
        TenantContextHolder.setIgnore(savedIgnore);
        adapterLogger.detachAppender(logAppender);
    }

    private BuyerIntegrationRoute route(String sourceSystem, String externalTenantId,
                                        String transportMode, String adapterCode, String contractVersion) {
        return new BuyerIntegrationRoute(1L, 10L, BuyerIntegrationCapabilityCode.MEMBER_PROFILE,
                sourceSystem, externalTenantId, transportMode, adapterCode, contractVersion, null);
    }

    private BuyerIntegrationRoute validRoute(String externalTenantId) {
        return route("AIFULI_MALL", externalTenantId, "RPC", "SAAS_MEMBER_RPC", "v1");
    }

    private ResolveMemberCommand command(BuyerIntegrationRoute route, String buyerUserId) {
        return new ResolveMemberCommand(route, buyerUserId);
    }

    private MemberProfileResolveRespDTO profile(String mobile, String userName) {
        MemberProfileResolveRespDTO response = new MemberProfileResolveRespDTO();
        response.setMobile(mobile);
        response.setUserName(userName);
        return response;
    }

    @Test
    void resolve_found_passes_identity_verbatim() {
        when(memberProfileResolveApi.resolve(any())).thenAnswer(invocation -> {
            MemberProfileResolveReqDTO request = invocation.getArgument(0);
            assertThat(request.getSourceSystem()).isEqualTo("AIFULI_MALL");
            assertThat(request.getExternalTenantId()).isEqualTo("100");
            assertThat(request.getBuyerUserId()).isEqualTo("buyer-A9");
            assertThat(TenantContextHolder.getTenantId()).isEqualTo(100L);
            assertThat(TenantContextHolder.isIgnore()).isFalse();
            return CommonResult.success(profile("13800138000", "测试用户"));
        });

        MemberProfileResult result = adapter.resolve(command(validRoute("100"), "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.FOUND);
        assertThat(result.mobile()).isEqualTo("13800138000");
        assertThat(result.userName()).isEqualTo("测试用户");
    }

    @Test
    void resolve_not_found_when_data_null() {
        when(memberProfileResolveApi.resolve(any())).thenReturn(CommonResult.success(null));

        MemberProfileResult result = adapter.resolve(command(validRoute("100"), "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.NOT_FOUND);
    }

    @Test
    void resolve_not_found_when_mobile_blank() {
        when(memberProfileResolveApi.resolve(any()))
                .thenReturn(CommonResult.success(profile(" ", "测试用户")));

        MemberProfileResult result = adapter.resolve(command(validRoute("100"), "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.NOT_FOUND);
    }

    @Test
    void resolve_unavailable_when_envelope_error() {
        when(memberProfileResolveApi.resolve(any()))
                .thenReturn(CommonResult.error(1_004_001_005, "会员资料解析路由无效"));

        MemberProfileResult result = adapter.resolve(command(validRoute("100"), "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.UNAVAILABLE);
    }

    @Test
    void resolve_unavailable_when_envelope_null() {
        when(memberProfileResolveApi.resolve(any())).thenReturn(null);

        MemberProfileResult result = adapter.resolve(command(validRoute("100"), "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.UNAVAILABLE);
    }

    @Test
    void resolve_unavailable_when_rpc_throws() {
        when(memberProfileResolveApi.resolve(any())).thenThrow(new RuntimeException("连接超时"));

        MemberProfileResult result = adapter.resolve(command(validRoute("100"), "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.UNAVAILABLE);
    }

    @Test
    void resolve_unavailable_when_external_tenant_id_not_numeric() {
        MemberProfileResult result = adapter.resolve(command(validRoute("tenant-abc"), "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.UNAVAILABLE);
        verifyNoInteractions(memberProfileResolveApi);
    }

    @Test
    void resolve_unavailable_when_external_tenant_id_zero() {
        MemberProfileResult result = adapter.resolve(command(validRoute("0"), "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.UNAVAILABLE);
        verifyNoInteractions(memberProfileResolveApi);
    }

    @Test
    void resolve_unavailable_when_external_tenant_id_negative() {
        MemberProfileResult result = adapter.resolve(command(validRoute("-1"), "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.UNAVAILABLE);
        verifyNoInteractions(memberProfileResolveApi);
    }

    @Test
    void resolve_unavailable_when_external_tenant_id_overflows_long() {
        MemberProfileResult result =
                adapter.resolve(command(validRoute("9223372036854775808"), "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.UNAVAILABLE);
        verifyNoInteractions(memberProfileResolveApi);
    }

    @Test
    void resolve_unavailable_when_external_tenant_id_has_leading_zero() {
        MemberProfileResult result = adapter.resolve(command(validRoute("0100"), "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.UNAVAILABLE);
        verifyNoInteractions(memberProfileResolveApi);
    }

    @Test
    void resolve_unavailable_when_source_system_mismatch() {
        BuyerIntegrationRoute mismatch =
                route("OTHER_SYSTEM", "100", "RPC", "SAAS_MEMBER_RPC", "v1");

        MemberProfileResult result = adapter.resolve(command(mismatch, "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.UNAVAILABLE);
        verifyNoInteractions(memberProfileResolveApi);
    }

    @Test
    void resolve_unavailable_when_transport_mode_mismatch() {
        BuyerIntegrationRoute mismatch =
                route("AIFULI_MALL", "100", "HTTP", "SAAS_MEMBER_RPC", "v1");

        MemberProfileResult result = adapter.resolve(command(mismatch, "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.UNAVAILABLE);
        verifyNoInteractions(memberProfileResolveApi);
    }

    @Test
    void resolve_unavailable_when_adapter_code_mismatch() {
        BuyerIntegrationRoute mismatch =
                route("AIFULI_MALL", "100", "RPC", "OTHER_ADAPTER", "v1");

        MemberProfileResult result = adapter.resolve(command(mismatch, "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.UNAVAILABLE);
        verifyNoInteractions(memberProfileResolveApi);
    }

    @Test
    void resolve_unavailable_when_contract_version_mismatch() {
        BuyerIntegrationRoute mismatch =
                route("AIFULI_MALL", "100", "RPC", "SAAS_MEMBER_RPC", "v2");

        MemberProfileResult result = adapter.resolve(command(mismatch, "buyer-A9"));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.UNAVAILABLE);
        verifyNoInteractions(memberProfileResolveApi);
    }

    @Test
    void resolve_restores_tenant_context_after_success() {
        TenantContextHolder.setTenantId(999L);
        TenantContextHolder.setIgnore(true);

        when(memberProfileResolveApi.resolve(any()))
                .thenReturn(CommonResult.success(profile("13800138000", "测试用户")));

        adapter.resolve(command(validRoute("100"), "buyer-A9"));

        assertThat(TenantContextHolder.getTenantId()).isEqualTo(999L);
        assertThat(TenantContextHolder.isIgnore()).isTrue();
    }

    @Test
    void resolve_restores_tenant_context_after_exception() {
        TenantContextHolder.setTenantId(999L);
        TenantContextHolder.setIgnore(true);

        when(memberProfileResolveApi.resolve(any())).thenThrow(new RuntimeException("连接超时"));

        adapter.resolve(command(validRoute("100"), "buyer-A9"));

        assertThat(TenantContextHolder.getTenantId()).isEqualTo(999L);
        assertThat(TenantContextHolder.isIgnore()).isTrue();
    }

    @Test
    void resolve_success_logs_do_not_leak_identity_or_profile() {
        when(memberProfileResolveApi.resolve(any())).thenAnswer(invocation -> {
            MemberProfileResolveReqDTO request = invocation.getArgument(0);
            assertThat(request.getBuyerUserId()).isEqualTo(BUYER_USER_SENTINEL);
            return CommonResult.success(profile(MOBILE_SENTINEL, USER_NAME_SENTINEL));
        });

        MemberProfileResult result =
                adapter.resolve(command(validRoute(NUMERIC_TENANT_SENTINEL), BUYER_USER_SENTINEL));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.FOUND);
        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list).allSatisfy(event -> {
            String combined = combinedLogText(event);
            assertThat(combined).doesNotContain(NUMERIC_TENANT_SENTINEL);
            assertThat(combined).doesNotContain(BUYER_USER_SENTINEL);
            assertThat(combined).doesNotContain(MOBILE_SENTINEL);
            assertThat(combined).doesNotContain(USER_NAME_SENTINEL);
            assertThat(event.getThrowableProxy()).as("成功路径日志不得携带异常代理").isNull();
        });
    }

    @Test
    void resolve_failure_log_does_not_leak_exception_message_or_identity() {
        RuntimeException failure =
                new RuntimeException(FAILURE_MESSAGE_SENTINEL, new IllegalStateException("nested-cause"));
        when(memberProfileResolveApi.resolve(any())).thenThrow(failure);

        MemberProfileResult result =
                adapter.resolve(command(validRoute(NUMERIC_TENANT_SENTINEL), BUYER_USER_SENTINEL));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.UNAVAILABLE);
        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list).allSatisfy(event -> {
            String combined = combinedLogText(event);
            assertThat(combined).doesNotContain(NUMERIC_TENANT_SENTINEL);
            assertThat(combined).doesNotContain(BUYER_USER_SENTINEL);
            assertThat(combined).doesNotContain(FAILURE_MESSAGE_SENTINEL);
            assertThat(combined).doesNotContain("nested-cause");
            assertThat(event.getThrowableProxy()).as("异常路径日志不得携带异常堆栈").isNull();
        });

        String failureLog = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("failed"))
                .findFirst()
                .orElseThrow();
        assertThat(failureLog).contains(RuntimeException.class.getName());
        assertThat(failureLog).contains(IllegalStateException.class.getName());
    }

    @Test
    void resolve_route_mismatch_log_does_not_leak_external_tenant() {
        BuyerIntegrationRoute mismatch =
                route("OTHER_SYSTEM", TENANT_SENTINEL, "RPC", "SAAS_MEMBER_RPC", "v1");

        MemberProfileResult result = adapter.resolve(command(mismatch, BUYER_USER_SENTINEL));

        assertThat(result.status()).isEqualTo(MemberResolutionStatus.UNAVAILABLE);
        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list).allSatisfy(event -> {
            String combined = combinedLogText(event);
            assertThat(combined).doesNotContain(TENANT_SENTINEL);
            assertThat(combined).doesNotContain(BUYER_USER_SENTINEL);
        });
    }

    /**
     * 合并日志事件的消息、参数与异常代理文本，防止敏感值仅通过 argumentArray 泄漏。
     */
    private static String combinedLogText(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        if (event.getArgumentArray() != null) {
            for (Object argument : event.getArgumentArray()) {
                if (argument != null) {
                    text.append(' ').append(argument);
                }
            }
        }
        if (event.getThrowableProxy() != null) {
            text.append(' ').append(event.getThrowableProxy().getMessage());
        }
        return text.toString();
    }
}
