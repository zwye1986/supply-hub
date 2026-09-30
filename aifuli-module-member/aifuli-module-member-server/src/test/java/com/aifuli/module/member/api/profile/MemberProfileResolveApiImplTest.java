package com.aifuli.module.member.api.profile;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.tenant.core.context.TenantContextHolder;
import com.aifuli.module.member.api.profile.dto.MemberProfileResolveReqDTO;
import com.aifuli.module.member.api.profile.dto.MemberProfileResolveRespDTO;
import com.aifuli.module.member.dal.dataobject.user.MemberUserDO;
import com.aifuli.module.member.service.user.MemberUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static com.aifuli.module.member.api.profile.MemberProfileSourceSystemConstants.AIFULI_MALL;
import static com.aifuli.module.member.enums.ErrorCodeConstants.MEMBER_PROFILE_ROUTE_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link MemberProfileResolveApiImpl} 单元测试，验证三元身份仅在 member-server 内部解释，
 * 且诊断日志不泄漏个人资料或外部身份原文。
 *
 * <p>详细设计：docs/superpowers/specs/2026-08-26-saas-member-profile-resolver-design.md#32-member-server</p>
 */
@ExtendWith(MockitoExtension.class)
class MemberProfileResolveApiImplTest {

    private static final Long TENANT_ID = 100L;
    private static final String MOBILE = "13800138000";
    private static final String REAL_NAME = "真实姓名";
    private static final String NICKNAME = "昵称";
    private static final String BUYER_USER_ID = "1024";

    @Mock
    private MemberUserService userService;

    private MemberProfileResolveApiImpl api;
    private Long previousTenantId;
    private Logger implLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        previousTenantId = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(TENANT_ID);
        api = new MemberProfileResolveApiImpl(userService);
        implLogger = (Logger) LoggerFactory.getLogger(MemberProfileResolveApiImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        implLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        implLogger.detachAppender(logAppender);
        TenantContextHolder.clear();
        if (previousTenantId != null) {
            TenantContextHolder.setTenantId(previousTenantId);
        }
    }

    @Test
    void resolveReturnsRealNameWhenMemberIsFound() {
        when(userService.getUser(1024L)).thenReturn(member(MOBILE, REAL_NAME, NICKNAME));

        CommonResult<MemberProfileResolveRespDTO> result = api.resolve(request(AIFULI_MALL, "100", BUYER_USER_ID));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).satisfies(profile -> {
            assertThat(profile.getMobile()).isEqualTo(MOBILE);
            assertThat(profile.getUserName()).isEqualTo(REAL_NAME);
        });
        verify(userService).getUser(1024L);
    }

    @Test
    void resolveFallsBackToNicknameWhenRealNameIsBlank() {
        when(userService.getUser(1024L)).thenReturn(member(MOBILE, " ", NICKNAME));

        CommonResult<MemberProfileResolveRespDTO> result = api.resolve(request(AIFULI_MALL, "100", BUYER_USER_ID));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().getUserName()).isEqualTo(NICKNAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"opaque-user", "9223372036854775808"})
    void resolveReturnsNotFoundWithoutQueryingForInvalidMemberUserId(String buyerUserId) {
        CommonResult<MemberProfileResolveRespDTO> result = api.resolve(request(AIFULI_MALL, "100", buyerUserId));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isNull();
        verifyNoInteractions(userService);
    }

    @Test
    void resolveReturnsNotFoundWhenMemberDoesNotExist() {
        when(userService.getUser(1024L)).thenReturn(null);

        CommonResult<MemberProfileResolveRespDTO> result = api.resolve(request(AIFULI_MALL, "100", BUYER_USER_ID));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void resolveReturnsNotFoundWhenMobileIsBlank(String mobile) {
        when(userService.getUser(1024L)).thenReturn(member(mobile, REAL_NAME, NICKNAME));

        CommonResult<MemberProfileResolveRespDTO> result = api.resolve(request(AIFULI_MALL, "100", BUYER_USER_ID));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isNull();
    }

    @Test
    void resolveReturnsNotFoundWhenMobileIsNull() {
        when(userService.getUser(1024L)).thenReturn(member(null, REAL_NAME, NICKNAME));

        CommonResult<MemberProfileResolveRespDTO> result = api.resolve(request(AIFULI_MALL, "100", BUYER_USER_ID));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isNull();
    }

    @Test
    void resolveRejectsUnsupportedSourceSystemWithoutQuerying() {
        assertRouteInvalid(request("OTHER", "100", BUYER_USER_ID));
    }

    @Test
    void resolveRejectsMissingTenantContextWithoutQuerying() {
        TenantContextHolder.clear();

        assertRouteInvalid(request(AIFULI_MALL, "100", BUYER_USER_ID));
    }

    @Test
    void resolveRejectsTenantTextThatDoesNotExactlyMatchContextWithoutQuerying() {
        assertRouteInvalid(request(AIFULI_MALL, "0100", BUYER_USER_ID));
    }

    @Test
    void resolvePropagatesServiceFailureWithoutMaskingIt() {
        RuntimeException failure = new RuntimeException("service-error-sentinel");
        when(userService.getUser(1024L)).thenThrow(failure);

        assertThatThrownBy(() -> api.resolve(request(AIFULI_MALL, "100", BUYER_USER_ID)))
                .isSameAs(failure);
        assertThat(logAppender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("service-error-sentinel");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void resolveLogsOnlySafeFieldsAndNeverAttachesThrowable() {
        String externalTenantId = "987654321";
        TenantContextHolder.setTenantId(Long.valueOf(externalTenantId));
        when(userService.getUser(1024L)).thenReturn(member(MOBILE, REAL_NAME, NICKNAME));

        api.resolve(request(AIFULI_MALL, externalTenantId, BUYER_USER_ID));

        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .doesNotContain(BUYER_USER_ID, externalTenantId, MOBILE, REAL_NAME,
                            NICKNAME, "service-error-sentinel");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private void assertRouteInvalid(MemberProfileResolveReqDTO request) {
        assertThatThrownBy(() -> api.resolve(request))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(MEMBER_PROFILE_ROUTE_INVALID.getCode());
        verifyNoInteractions(userService);
    }

    private static MemberUserDO member(String mobile, String name, String nickname) {
        return MemberUserDO.builder().id(1024L).mobile(mobile).name(name).nickname(nickname).build();
    }

    private static MemberProfileResolveReqDTO request(String sourceSystem, String externalTenantId, String buyerUserId) {
        MemberProfileResolveReqDTO request = new MemberProfileResolveReqDTO();
        request.setSourceSystem(sourceSystem);
        request.setExternalTenantId(externalTenantId);
        request.setBuyerUserId(buyerUserId);
        return request;
    }
}
