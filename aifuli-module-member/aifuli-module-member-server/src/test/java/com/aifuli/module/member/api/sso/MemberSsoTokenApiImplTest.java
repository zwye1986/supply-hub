package com.aifuli.module.member.api.sso;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.common.enums.CommonStatusEnum;
import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.tenant.core.context.TenantContextHolder;
import com.aifuli.module.member.api.sso.dto.MemberSsoTokenReqDTO;
import com.aifuli.module.member.api.sso.dto.MemberSsoTokenRespDTO;
import com.aifuli.module.member.controller.app.auth.vo.AppAuthLoginRespVO;
import com.aifuli.module.member.dal.dataobject.user.MemberUserDO;
import com.aifuli.module.member.service.auth.MemberAuthService;
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
 * {@link MemberSsoTokenApiImpl} 单元测试：验证身份边界、会员状态和令牌脱敏。
 *
 * <p>详细设计：docs/superpowers/specs/2026-08-28-buyer-cashier-h5-url-adapter-design.md#32-member-server</p>
 */
@ExtendWith(MockitoExtension.class)
class MemberSsoTokenApiImplTest {

    private static final String ACCESS_TOKEN = "token-secret-sentinel";
    private static final String BUYER_USER_ID = "1024";

    @Mock private MemberUserService userService;
    @Mock private MemberAuthService authService;

    private MemberSsoTokenApiImpl api;
    private Long savedTenantId;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        savedTenantId = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(100L);
        api = new MemberSsoTokenApiImpl(userService, authService);
        logger = (Logger) LoggerFactory.getLogger(MemberSsoTokenApiImpl.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        TenantContextHolder.clear();
        if (savedTenantId != null) {
            TenantContextHolder.setTenantId(savedTenantId);
        }
    }

    @Test
    void createsRtmartSsoTokenForEnabledMember() {
        when(userService.getUser(1024L)).thenReturn(member(CommonStatusEnum.ENABLE.getStatus()));
        when(authService.createTokenAfterSsoLogin(1024L, "rtmart"))
                .thenReturn(AppAuthLoginRespVO.builder().accessToken(ACCESS_TOKEN).build());

        CommonResult<MemberSsoTokenRespDTO> result = api.createToken(request(AIFULI_MALL, "100", BUYER_USER_ID));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().getAccessToken()).isEqualTo(ACCESS_TOKEN);
        verify(authService).createTokenAfterSsoLogin(1024L, "rtmart");
        assertLogsAreSafe();
    }

    @ParameterizedTest
    @ValueSource(strings = {"opaque-user", "9223372036854775808"})
    void returnsEmptySuccessForUnparseableBuyerUserId(String buyerUserId) {
        CommonResult<MemberSsoTokenRespDTO> result = api.createToken(request(AIFULI_MALL, "100", buyerUserId));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isNull();
        verifyNoInteractions(userService, authService);
    }

    @Test
    void returnsEmptySuccessForMissingMember() {
        when(userService.getUser(1024L)).thenReturn(null);

        assertThat(api.createToken(request(AIFULI_MALL, "100", BUYER_USER_ID)).getData()).isNull();
        verifyNoInteractions(authService);
    }

    @Test
    void returnsEmptySuccessForDisabledMember() {
        when(userService.getUser(1024L)).thenReturn(member(CommonStatusEnum.DISABLE.getStatus()));

        assertThat(api.createToken(request(AIFULI_MALL, "100", BUYER_USER_ID)).getData()).isNull();
        verifyNoInteractions(authService);
    }

    @Test
    void returnsEmptySuccessForUnknownMemberStatus() {
        when(userService.getUser(1024L)).thenReturn(member(null));

        assertThat(api.createToken(request(AIFULI_MALL, "100", BUYER_USER_ID)).getData()).isNull();
        verifyNoInteractions(authService);
    }

    @Test
    void rejectsUntrustedRouteBeforeMemberLookup() {
        assertThatThrownBy(() -> api.createToken(request("OTHER", "100", BUYER_USER_ID)))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(MEMBER_PROFILE_ROUTE_INVALID.getCode());
        verifyNoInteractions(userService, authService);
    }

    @Test
    void propagatesTokenServiceFailureAndDoesNotLogSecrets() {
        when(userService.getUser(1024L)).thenReturn(member(CommonStatusEnum.ENABLE.getStatus()));
        RuntimeException failure = new RuntimeException("service-secret-sentinel");
        when(authService.createTokenAfterSsoLogin(1024L, "rtmart")).thenThrow(failure);

        assertThatThrownBy(() -> api.createToken(request(AIFULI_MALL, "100", BUYER_USER_ID)))
                .isSameAs(failure);
        assertLogsAreSafe();
    }

    private void assertLogsAreSafe() {
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .doesNotContain(ACCESS_TOKEN, BUYER_USER_ID, "100", "service-secret-sentinel");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private static MemberUserDO member(Integer status) {
        return MemberUserDO.builder().id(1024L).status(status).build();
    }

    private static MemberSsoTokenReqDTO request(String sourceSystem, String externalTenantId,
                                                String buyerUserId) {
        MemberSsoTokenReqDTO request = new MemberSsoTokenReqDTO();
        request.setSourceSystem(sourceSystem);
        request.setExternalTenantId(externalTenantId);
        request.setBuyerUserId(buyerUserId);
        return request;
    }
}
