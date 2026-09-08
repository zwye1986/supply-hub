package com.aifuli.module.supplychain.application.facade;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.instore.dto.InstoreMemberRespDTO;
import com.aifuli.module.supplychain.application.facade.InstoreChannelMemberFacade.ResolveMemberCommand;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryCredentialService;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryCredentialService.ClaimedCredential;
import com.aifuli.module.supplychain.border.instore.domain.InstoreEntryCredentialPurpose;
import com.aifuli.module.supplychain.border.instore.domain.InstoreCredentialRevokeReason;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationProfileRetiredException;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRouteResolver;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort.MemberProfileResult;
import com.aifuli.module.supplychain.integration.buyer.BuyerMemberProfilePort.MemberResolutionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 旅通接口①会员解析编排单元测试：锁定 claim → resolveFrozen → Port → markUsed/release/revoke
 * 的固定顺序与状态矩阵；Port 收到的身份三元组必须来自冻结路由；手机号、姓名、token、
 * userCode 不得进入日志。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#93-旅通接口会员解析}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreChannelMemberFacadeTest {

    private static final Long PROJECT_ID = 101L;
    private static final Long PROFILE_VERSION_ID = 901L;
    private static final String CHANNEL_CODE = "RT_MART";
    private static final String TOKEN = "tk-opaque";
    private static final String BUYER_USER_ID = "001234";
    private static final String EXTERNAL_TENANT_SENTINEL = "TENANT-SECRET-SENTINEL";
    private static final String COMPANY_CODE = "COMPANY_A";
    private static final String MOBILE = "13800000000";
    private static final String USER_NAME = "测试会员";
    private static final Long CREDENTIAL_ID = 66L;
    private static final long PROCESSING_VERSION = 4L;

    @Mock
    private InstoreEntryCredentialService credentialService;
    @Mock
    private BuyerIntegrationRouteResolver routeResolver;
    @Mock
    private BuyerMemberProfilePort memberProfilePort;

    private InstoreChannelMemberFacade facade;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger facadeLogger;

    @BeforeEach
    void setUp() {
        facade = new InstoreChannelMemberFacade(
                credentialService, routeResolver, memberProfilePort);
        facadeLogger = (Logger) LoggerFactory.getLogger(InstoreChannelMemberFacade.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        facadeLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        facadeLogger.detachAppender(logAppender);
    }

    @Test
    void frozenFoundWithMobileMarksUsedAndReturnsProfile() {
        stubClaimAndRoute();
        when(memberProfilePort.resolve(any(BuyerMemberProfilePort.ResolveMemberCommand.class)))
                .thenReturn(new MemberProfileResult(MemberResolutionStatus.FOUND, MOBILE, USER_NAME));

        InstoreMemberRespDTO response = facade.resolveMember(command());

        assertThat(response.getUserCode()).isEqualTo(BUYER_USER_ID);
        assertThat(response.getMobile()).isEqualTo(MOBILE);
        assertThat(response.getUserName()).isEqualTo(USER_NAME);
        assertThat(response.getCompanyCode()).isEqualTo(COMPANY_CODE);

        InOrder inOrder = inOrder(credentialService, routeResolver, memberProfilePort);
        inOrder.verify(credentialService).claimForMemberLookup(PROJECT_ID, CHANNEL_CODE, TOKEN);
        inOrder.verify(routeResolver).resolveFrozen(PROJECT_ID, PROFILE_VERSION_ID,
                BuyerIntegrationCapabilityCode.MEMBER_PROFILE);
        inOrder.verify(memberProfilePort).resolve(any(BuyerMemberProfilePort.ResolveMemberCommand.class));
        inOrder.verify(credentialService).markLookupSucceeded(CREDENTIAL_ID, PROCESSING_VERSION);

        ArgumentCaptor<BuyerMemberProfilePort.ResolveMemberCommand> portCaptor =
                ArgumentCaptor.forClass(BuyerMemberProfilePort.ResolveMemberCommand.class);
        verify(memberProfilePort).resolve(portCaptor.capture());
        assertThat(portCaptor.getValue().route().sourceSystem()).isEqualTo("AIFULI_MALL");
        assertThat(portCaptor.getValue().route().externalTenantId()).isEqualTo(EXTERNAL_TENANT_SENTINEL);
        assertThat(portCaptor.getValue().buyerUserId()).isEqualTo(BUYER_USER_ID);
    }

    @Test
    void foundWithBlankMobileReleasesAndReturns044() {
        stubClaimAndRoute();
        MemberProfileResult blankMobile = mock(MemberProfileResult.class);
        when(blankMobile.status()).thenReturn(MemberResolutionStatus.FOUND);
        when(blankMobile.mobile()).thenReturn(" ");
        when(memberProfilePort.resolve(any(BuyerMemberProfilePort.ResolveMemberCommand.class)))
                .thenReturn(blankMobile);

        assertThatThrownBy(() -> facade.resolveMember(command()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_MEMBER_PROFILE_INVALID.getCode());

        verify(credentialService).releaseAfterLookupFailure(CREDENTIAL_ID, PROCESSING_VERSION);
        verify(credentialService, never()).markLookupSucceeded(any(), anyLong());
    }

    @Test
    void notFoundReleasesAndReturns044() {
        stubClaimAndRoute();
        when(memberProfilePort.resolve(any(BuyerMemberProfilePort.ResolveMemberCommand.class)))
                .thenReturn(new MemberProfileResult(MemberResolutionStatus.NOT_FOUND, null, null));

        assertThatThrownBy(() -> facade.resolveMember(command()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_MEMBER_PROFILE_INVALID.getCode());

        verify(credentialService).releaseAfterLookupFailure(CREDENTIAL_ID, PROCESSING_VERSION);
        verify(credentialService, never()).markLookupSucceeded(any(), anyLong());
    }

    @Test
    void unavailableReleasesAndReturns045() {
        stubClaimAndRoute();
        when(memberProfilePort.resolve(any(BuyerMemberProfilePort.ResolveMemberCommand.class)))
                .thenReturn(new MemberProfileResult(MemberResolutionStatus.UNAVAILABLE, null, null));

        assertThatThrownBy(() -> facade.resolveMember(command()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_MEMBER_PROFILE_UNAVAILABLE.getCode());

        verify(credentialService).releaseAfterLookupFailure(CREDENTIAL_ID, PROCESSING_VERSION);
    }

    @Test
    void nullResultReleasesAndReturns045() {
        stubClaimAndRoute();
        when(memberProfilePort.resolve(any(BuyerMemberProfilePort.ResolveMemberCommand.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> facade.resolveMember(command()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_MEMBER_PROFILE_UNAVAILABLE.getCode());

        verify(credentialService).releaseAfterLookupFailure(CREDENTIAL_ID, PROCESSING_VERSION);
    }

    @Test
    void portFailureReleasesReturns045AndKeepsCause() {
        stubClaimAndRoute();
        RuntimeException portFailure = new IllegalStateException("saas timeout");
        when(memberProfilePort.resolve(any(BuyerMemberProfilePort.ResolveMemberCommand.class)))
                .thenThrow(portFailure);

        ServiceException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                ServiceException.class, () -> facade.resolveMember(command()));
        assertThat(failure.getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_MEMBER_PROFILE_UNAVAILABLE.getCode());
        assertThat(failure.getCause()).isSameAs(portFailure);

        verify(credentialService).releaseAfterLookupFailure(CREDENTIAL_ID, PROCESSING_VERSION);
    }

    @Test
    void retiredRouteRevokesCredentialAndReturns043WithoutCallingPort() {
        when(credentialService.claimForMemberLookup(PROJECT_ID, CHANNEL_CODE, TOKEN))
                .thenReturn(claimed());
        when(routeResolver.resolveFrozen(PROJECT_ID, PROFILE_VERSION_ID,
                BuyerIntegrationCapabilityCode.MEMBER_PROFILE))
                .thenThrow(new BuyerIntegrationProfileRetiredException(PROFILE_VERSION_ID));

        assertThatThrownBy(() -> facade.resolveMember(command()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CREDENTIAL_REVOKED.getCode());

        verify(credentialService).revoke(CREDENTIAL_ID, PROCESSING_VERSION,
                InstoreCredentialRevokeReason.MEMBER_PROFILE_RETIRED);
        verifyNoInteractions(memberProfilePort);
        verify(credentialService, never()).releaseAfterLookupFailure(any(), anyLong());
    }

    @Test
    void routeResolutionFailureReleasesAndReturns045() {
        when(credentialService.claimForMemberLookup(PROJECT_ID, CHANNEL_CODE, TOKEN))
                .thenReturn(claimed());
        when(routeResolver.resolveFrozen(PROJECT_ID, PROFILE_VERSION_ID,
                BuyerIntegrationCapabilityCode.MEMBER_PROFILE))
                .thenThrow(new IllegalStateException("profile not found"));

        assertThatThrownBy(() -> facade.resolveMember(command()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_MEMBER_PROFILE_UNAVAILABLE.getCode());

        verify(credentialService).releaseAfterLookupFailure(CREDENTIAL_ID, PROCESSING_VERSION);
        verifyNoInteractions(memberProfilePort);
    }

    @Test
    void markUsedFailureReturns041WithoutMemberData() {
        stubClaimAndRoute();
        when(memberProfilePort.resolve(any(BuyerMemberProfilePort.ResolveMemberCommand.class)))
                .thenReturn(new MemberProfileResult(MemberResolutionStatus.FOUND, MOBILE, USER_NAME));
        doThrow(new ServiceException(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE))
                .when(credentialService).markLookupSucceeded(CREDENTIAL_ID, PROCESSING_VERSION);

        assertThatThrownBy(() -> facade.resolveMember(command()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE.getCode());

        verify(credentialService, never()).releaseAfterLookupFailure(any(), anyLong());
    }

    @Test
    void releaseFailurePropagates041() {
        stubClaimAndRoute();
        when(memberProfilePort.resolve(any(BuyerMemberProfilePort.ResolveMemberCommand.class)))
                .thenReturn(new MemberProfileResult(MemberResolutionStatus.NOT_FOUND, null, null));
        doThrow(new ServiceException(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE))
                .when(credentialService).releaseAfterLookupFailure(CREDENTIAL_ID, PROCESSING_VERSION);

        assertThatThrownBy(() -> facade.resolveMember(command()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE.getCode());
    }

    @Test
    void revokeFailurePropagates041() {
        when(credentialService.claimForMemberLookup(PROJECT_ID, CHANNEL_CODE, TOKEN))
                .thenReturn(claimed());
        when(routeResolver.resolveFrozen(PROJECT_ID, PROFILE_VERSION_ID,
                BuyerIntegrationCapabilityCode.MEMBER_PROFILE))
                .thenThrow(new BuyerIntegrationProfileRetiredException(PROFILE_VERSION_ID));
        doThrow(new ServiceException(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE))
                .when(credentialService).revoke(CREDENTIAL_ID, PROCESSING_VERSION,
                        InstoreCredentialRevokeReason.MEMBER_PROFILE_RETIRED);

        assertThatThrownBy(() -> facade.resolveMember(command()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_CONCURRENT_UPDATE.getCode());

        verifyNoInteractions(memberProfilePort);
    }

    @Test
    void resolveMemberLogsDoNotLeakPersonalDataOrToken() {
        stubClaimAndRoute();
        when(memberProfilePort.resolve(any(BuyerMemberProfilePort.ResolveMemberCommand.class)))
                .thenReturn(new MemberProfileResult(MemberResolutionStatus.FOUND, MOBILE, USER_NAME));

        facade.resolveMember(command());

        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list)
                .allSatisfy(event -> {
                    String combined = combinedLogText(event);
                    assertThat(combined).doesNotContain(MOBILE);
                    assertThat(combined).doesNotContain(USER_NAME);
                    assertThat(combined).doesNotContain(BUYER_USER_ID);
                    assertThat(combined).doesNotContain(TOKEN);
                    assertThat(combined).doesNotContain(EXTERNAL_TENANT_SENTINEL);
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

    private ResolveMemberCommand command() {
        return new ResolveMemberCommand(PROJECT_ID, CHANNEL_CODE, TOKEN);
    }

    private ClaimedCredential claimed() {
        return new ClaimedCredential(CREDENTIAL_ID, PROCESSING_VERSION, PROJECT_ID,
                PROFILE_VERSION_ID, COMPANY_CODE, BUYER_USER_ID, CHANNEL_CODE,
                InstoreEntryCredentialPurpose.HOME, null);
    }

    private BuyerIntegrationRoute frozenRoute() {
        return new BuyerIntegrationRoute(PROJECT_ID, PROFILE_VERSION_ID,
                BuyerIntegrationCapabilityCode.MEMBER_PROFILE, "AIFULI_MALL", EXTERNAL_TENANT_SENTINEL,
                "HTTP", "SAAS_MEMBER_ADAPTER", "v1", 55L);
    }

    private void stubClaimAndRoute() {
        when(credentialService.claimForMemberLookup(PROJECT_ID, CHANNEL_CODE, TOKEN))
                .thenReturn(claimed());
        when(routeResolver.resolveFrozen(PROJECT_ID, PROFILE_VERSION_ID,
                BuyerIntegrationCapabilityCode.MEMBER_PROFILE))
                .thenReturn(frozenRoute());
    }
}
