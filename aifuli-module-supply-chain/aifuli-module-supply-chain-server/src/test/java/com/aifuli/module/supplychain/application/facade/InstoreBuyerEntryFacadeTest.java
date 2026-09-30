package com.aifuli.module.supplychain.application.facade;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.api.instore.dto.InstoreEntryUrlRespDTO;
import com.aifuli.module.supplychain.application.facade.InstoreBuyerEntryFacade.DetailEntryCommand;
import com.aifuli.module.supplychain.application.facade.InstoreBuyerEntryFacade.HomeEntryCommand;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryAccessService;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryAccessService.DetailEntrySnapshot;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryCredentialService;
import com.aifuli.module.supplychain.border.instore.application.InstoreEntryCredentialService.IssuedCredential;
import com.aifuli.module.supplychain.border.instore.application.InstoreHomeEntryProperties;
import com.aifuli.module.supplychain.border.instore.domain.InstoreCredentialRevokeReason;
import com.aifuli.module.supplychain.border.instore.domain.InstoreEntryCredentialPurpose;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationCapabilityCode;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRoute;
import com.aifuli.module.supplychain.integration.buyer.BuyerIntegrationRouteResolver;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryUrlPort;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryUrlPort.DetailUrlCommand;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryUrlPort.EntryUrlResult;
import com.aifuli.module.supplychain.integration.rtmart.RtmartEntryUrlPort.HomeUrlCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 买家 HOME/DETAIL 入口编排单元测试：锁定 项目校验 → 配置 companyCode 校验/订单快照 →
 * resolveCurrent(MEMBER_PROFILE) → 凭证签发 → URL Port 的固定顺序；URL 外呼失败先撤销
 * 刚签发凭证再返回 046 并保留原因；校验失败不签发、不外呼。HOME 不读取请求体 companyCode。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#9-业务流程}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreBuyerEntryFacadeTest {

    private static final Long BUYER_ID = 1001L;
    private static final Long PROJECT_ID = 101L;
    private static final String BUYER_USER_ID = "opaque-user";
    private static final String COMPANY_CODE = "COMPANY_A";
    private static final String LONGITUDE = "121.4737";
    private static final String LATITUDE = "31.2304";
    private static final String ORDER_NO = "SCO_1";
    private static final Long INSTORE_ORDER_ID = 77L;
    private static final String TRADE_ID = "TRADE_1";
    private static final String CHANNEL_CODE = "RT_MART";
    private static final String HOME_URL = "https://rtmart.example/home";
    private static final String DETAIL_URL = "https://rtmart.example/detail";

    @Mock
    private BuyerProjectAccessResolver projectAccessResolver;
    @Mock
    private InstoreEntryAccessService entryAccessService;
    @Mock
    private BuyerIntegrationRouteResolver routeResolver;
    @Mock
    private InstoreEntryCredentialService credentialService;
    @Mock
    private RtmartEntryUrlPort entryUrlPort;

    private InstoreBuyerEntryFacade facade;
    private InstoreHomeEntryProperties homeEntryProperties;

    @BeforeEach
    void setUp() {
        homeEntryProperties = new InstoreHomeEntryProperties();
        homeEntryProperties.setCompanyCode(COMPANY_CODE);
        homeEntryProperties.validate();
        facade = new InstoreBuyerEntryFacade(
                projectAccessResolver, entryAccessService, routeResolver, credentialService,
                entryUrlPort, homeEntryProperties);
    }

    @Test
    void getHomeEntryFollowsValidationIssueAndUrlOrder() {
        BuyerIntegrationRoute route = memberRoute();
        IssuedCredential issued = issued(66L, "IEC_1", 3L, "tk-home-1",
                InstoreEntryCredentialPurpose.HOME, null);
        when(routeResolver.resolveCurrent(PROJECT_ID, BuyerIntegrationCapabilityCode.MEMBER_PROFILE))
                .thenReturn(route);
        when(credentialService.issueHome(BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE,
                route, COMPANY_CODE))
                .thenReturn(issued);
        when(entryUrlPort.getHomeUrl(any(HomeUrlCommand.class)))
                .thenReturn(new EntryUrlResult(HOME_URL));

        InstoreEntryUrlRespDTO response = facade.getHomeEntry(homeCommand());

        assertThat(response.getEntryUrl()).isEqualTo(HOME_URL);

        InOrder inOrder = inOrder(projectAccessResolver, entryAccessService, routeResolver,
                credentialService, entryUrlPort);
        inOrder.verify(projectAccessResolver).requireActive(PROJECT_ID, BUYER_ID);
        inOrder.verify(entryAccessService).requireEnabledCompany(CHANNEL_CODE, COMPANY_CODE);
        inOrder.verify(routeResolver).resolveCurrent(PROJECT_ID, BuyerIntegrationCapabilityCode.MEMBER_PROFILE);
        inOrder.verify(credentialService).issueHome(BUYER_ID, PROJECT_ID, BUYER_USER_ID,
                CHANNEL_CODE, route, COMPANY_CODE);
        inOrder.verify(entryUrlPort).getHomeUrl(any(HomeUrlCommand.class));

        ArgumentCaptor<HomeUrlCommand> captor = ArgumentCaptor.forClass(HomeUrlCommand.class);
        verify(entryUrlPort).getHomeUrl(captor.capture());
        HomeUrlCommand command = captor.getValue();
        assertThat(command.projectId()).isEqualTo(PROJECT_ID);
        assertThat(command.channelCode()).isEqualTo(CHANNEL_CODE);
        assertThat(command.companyCode()).isEqualTo(COMPANY_CODE);
        assertThat(command.longitude()).isEqualTo(LONGITUDE);
        assertThat(command.latitude()).isEqualTo(LATITUDE);
        assertThat(command.token()).isEqualTo("tk-home-1");
    }

    @Test
    void getOrderDetailEntryUsesOrderSnapshotForUrlCommand() {
        BuyerIntegrationRoute route = memberRoute();
        IssuedCredential issued = issued(67L, "IEC_2", 0L, "tk-detail-1",
                InstoreEntryCredentialPurpose.DETAIL, INSTORE_ORDER_ID);
        when(entryAccessService.requireDetail(ORDER_NO, PROJECT_ID, BUYER_ID, BUYER_USER_ID))
                .thenReturn(new DetailEntrySnapshot(INSTORE_ORDER_ID, CHANNEL_CODE, COMPANY_CODE, TRADE_ID));
        when(routeResolver.resolveCurrent(PROJECT_ID, BuyerIntegrationCapabilityCode.MEMBER_PROFILE))
                .thenReturn(route);
        when(credentialService.issueDetail(BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE,
                route, COMPANY_CODE, INSTORE_ORDER_ID))
                .thenReturn(issued);
        when(entryUrlPort.getDetailUrl(any(DetailUrlCommand.class)))
                .thenReturn(new EntryUrlResult(DETAIL_URL));

        InstoreEntryUrlRespDTO response = facade.getOrderDetailEntry(
                new DetailEntryCommand(PROJECT_ID, BUYER_ID, BUYER_USER_ID, ORDER_NO));

        assertThat(response.getEntryUrl()).isEqualTo(DETAIL_URL);

        InOrder inOrder = inOrder(projectAccessResolver, entryAccessService, routeResolver,
                credentialService, entryUrlPort);
        inOrder.verify(projectAccessResolver).requireActive(PROJECT_ID, BUYER_ID);
        inOrder.verify(entryAccessService).requireDetail(ORDER_NO, PROJECT_ID, BUYER_ID, BUYER_USER_ID);
        inOrder.verify(routeResolver).resolveCurrent(PROJECT_ID, BuyerIntegrationCapabilityCode.MEMBER_PROFILE);
        inOrder.verify(credentialService).issueDetail(BUYER_ID, PROJECT_ID, BUYER_USER_ID,
                CHANNEL_CODE, route, COMPANY_CODE, INSTORE_ORDER_ID);
        inOrder.verify(entryUrlPort).getDetailUrl(any(DetailUrlCommand.class));

        ArgumentCaptor<DetailUrlCommand> captor = ArgumentCaptor.forClass(DetailUrlCommand.class);
        verify(entryUrlPort).getDetailUrl(captor.capture());
        DetailUrlCommand command = captor.getValue();
        assertThat(command.projectId()).isEqualTo(PROJECT_ID);
        assertThat(command.channelCode()).isEqualTo(CHANNEL_CODE);
        assertThat(command.companyCode()).isEqualTo(COMPANY_CODE);
        assertThat(command.tradeId()).isEqualTo(TRADE_ID);
        assertThat(command.token()).isEqualTo("tk-detail-1");
    }

    @Test
    void getHomeEntryRevokesCredentialAndReturns046WhenUrlPortFails() {
        BuyerIntegrationRoute route = memberRoute();
        IssuedCredential issued = issued(66L, "IEC_1", 3L, "tk-home-1",
                InstoreEntryCredentialPurpose.HOME, null);
        when(routeResolver.resolveCurrent(PROJECT_ID, BuyerIntegrationCapabilityCode.MEMBER_PROFILE))
                .thenReturn(route);
        when(credentialService.issueHome(BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE,
                route, COMPANY_CODE))
                .thenReturn(issued);
        RuntimeException portFailure = new IllegalStateException("rtmart timeout");
        when(entryUrlPort.getHomeUrl(any(HomeUrlCommand.class))).thenThrow(portFailure);

        ServiceException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                ServiceException.class, () -> facade.getHomeEntry(homeCommand()));
        assertThat(failure.getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_ENTRY_URL_UNAVAILABLE.getCode());
        assertThat(failure.getCause()).isSameAs(portFailure);

        verify(credentialService).revoke(66L, 3L, InstoreCredentialRevokeReason.ENTRY_URL_CALL_FAILED);
    }

    @Test
    void getOrderDetailEntryRevokesCredentialOnUrlPortFailure() {
        BuyerIntegrationRoute route = memberRoute();
        IssuedCredential issued = issued(67L, "IEC_2", 0L, "tk-detail-1",
                InstoreEntryCredentialPurpose.DETAIL, INSTORE_ORDER_ID);
        when(entryAccessService.requireDetail(ORDER_NO, PROJECT_ID, BUYER_ID, BUYER_USER_ID))
                .thenReturn(new DetailEntrySnapshot(INSTORE_ORDER_ID, CHANNEL_CODE, COMPANY_CODE, TRADE_ID));
        when(routeResolver.resolveCurrent(PROJECT_ID, BuyerIntegrationCapabilityCode.MEMBER_PROFILE))
                .thenReturn(route);
        when(credentialService.issueDetail(BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE,
                route, COMPANY_CODE, INSTORE_ORDER_ID))
                .thenReturn(issued);
        RuntimeException portFailure = new IllegalStateException("rtmart 5xx");
        when(entryUrlPort.getDetailUrl(any(DetailUrlCommand.class))).thenThrow(portFailure);

        ServiceException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                ServiceException.class, () -> facade.getOrderDetailEntry(detailCommand()));
        assertThat(failure.getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_ENTRY_URL_UNAVAILABLE.getCode());
        assertThat(failure.getCause()).isSameAs(portFailure);

        verify(credentialService).revoke(67L, 0L, InstoreCredentialRevokeReason.ENTRY_URL_CALL_FAILED);
    }

    @Test
    void secondBusinessCallAfterUrlFailureIssuesNewToken() {
        BuyerIntegrationRoute route = memberRoute();
        IssuedCredential first = issued(66L, "IEC_1", 3L, "tk-home-1",
                InstoreEntryCredentialPurpose.HOME, null);
        IssuedCredential second = issued(68L, "IEC_3", 0L, "tk-home-2",
                InstoreEntryCredentialPurpose.HOME, null);
        when(routeResolver.resolveCurrent(PROJECT_ID, BuyerIntegrationCapabilityCode.MEMBER_PROFILE))
                .thenReturn(route);
        when(credentialService.issueHome(BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE,
                route, COMPANY_CODE))
                .thenReturn(first, second);
        when(entryUrlPort.getHomeUrl(any(HomeUrlCommand.class)))
                .thenThrow(new IllegalStateException("rtmart timeout"))
                .thenReturn(new EntryUrlResult(HOME_URL));

        assertThatThrownBy(() -> facade.getHomeEntry(homeCommand()))
                .isInstanceOf(ServiceException.class);
        InstoreEntryUrlRespDTO response = facade.getHomeEntry(homeCommand());

        assertThat(response.getEntryUrl()).isEqualTo(HOME_URL);
        verify(credentialService).revoke(66L, 3L, InstoreCredentialRevokeReason.ENTRY_URL_CALL_FAILED);

        ArgumentCaptor<HomeUrlCommand> captor = ArgumentCaptor.forClass(HomeUrlCommand.class);
        verify(entryUrlPort, org.mockito.Mockito.times(2)).getHomeUrl(captor.capture());
        assertThat(captor.getAllValues().get(0).token()).isEqualTo("tk-home-1");
        assertThat(captor.getAllValues().get(1).token()).isEqualTo("tk-home-2");
    }

    @Test
    void getHomeEntrySkipsIssueAndPortWhenProjectInvalid() {
        doThrow(new ServiceException(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID))
                .when(projectAccessResolver).requireActive(PROJECT_ID, BUYER_ID);

        assertThatThrownBy(() -> facade.getHomeEntry(homeCommand()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID.getCode());

        verifyNoInteractions(entryAccessService, routeResolver, credentialService, entryUrlPort);
    }

    @Test
    void getHomeEntrySkipsIssueAndPortWhenCompanyUnbound() {
        doThrow(new ServiceException(SupplyChainErrorCodeConstants.INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND))
                .when(entryAccessService).requireEnabledCompany(CHANNEL_CODE, COMPANY_CODE);

        assertThatThrownBy(() -> facade.getHomeEntry(homeCommand()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants
                        .INSTORE_SUPPLIER_CHANNEL_BINDING_NOT_FOUND.getCode());

        verifyNoInteractions(routeResolver, credentialService, entryUrlPort);
    }

    @Test
    void getOrderDetailEntrySkipsIssueAndPortWhenOrderMissing() {
        doThrow(new ServiceException(SupplyChainErrorCodeConstants.INSTORE_ORDER_NOT_FOUND))
                .when(entryAccessService).requireDetail(ORDER_NO, PROJECT_ID, BUYER_ID, BUYER_USER_ID);

        assertThatThrownBy(() -> facade.getOrderDetailEntry(detailCommand()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.INSTORE_ORDER_NOT_FOUND.getCode());

        verifyNoInteractions(routeResolver, credentialService, entryUrlPort);
    }

    @Test
    void getOrderDetailEntrySkipsIssueAndPortWhenProjectInvalid() {
        doThrow(new ServiceException(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID))
                .when(projectAccessResolver).requireActive(PROJECT_ID, BUYER_ID);

        assertThatThrownBy(() -> facade.getOrderDetailEntry(detailCommand()))
                .isInstanceOf(ServiceException.class);

        verifyNoInteractions(entryAccessService, routeResolver, credentialService, entryUrlPort);
    }

    @Test
    void getHomeEntryDoesNotRevokeOnSuccess() {
        when(routeResolver.resolveCurrent(PROJECT_ID, BuyerIntegrationCapabilityCode.MEMBER_PROFILE))
                .thenReturn(memberRoute());
        when(credentialService.issueHome(any(), any(), any(), any(), any(), any()))
                .thenReturn(issued(66L, "IEC_1", 3L, "tk-home-1",
                        InstoreEntryCredentialPurpose.HOME, null));
        when(entryUrlPort.getHomeUrl(any(HomeUrlCommand.class)))
                .thenReturn(new EntryUrlResult(HOME_URL));

        facade.getHomeEntry(homeCommand());

        verify(credentialService, never()).revoke(any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void getHomeEntryUsesConfiguredCompanyCodeInsteadOfRequest() {
        homeEntryProperties.setCompanyCode(InstoreHomeEntryProperties.DEFAULT_COMPANY_CODE);
        homeEntryProperties.validate();
        BuyerIntegrationRoute route = memberRoute();
        IssuedCredential issued = issued(66L, "IEC_1", 3L, "tk-home-1",
                InstoreEntryCredentialPurpose.HOME, null);
        when(routeResolver.resolveCurrent(PROJECT_ID, BuyerIntegrationCapabilityCode.MEMBER_PROFILE))
                .thenReturn(route);
        when(credentialService.issueHome(BUYER_ID, PROJECT_ID, BUYER_USER_ID, CHANNEL_CODE,
                route, InstoreHomeEntryProperties.DEFAULT_COMPANY_CODE))
                .thenReturn(issued);
        when(entryUrlPort.getHomeUrl(any(HomeUrlCommand.class)))
                .thenReturn(new EntryUrlResult(HOME_URL));

        facade.getHomeEntry(homeCommand());

        verify(entryAccessService).requireEnabledCompany(
                CHANNEL_CODE, InstoreHomeEntryProperties.DEFAULT_COMPANY_CODE);
        ArgumentCaptor<HomeUrlCommand> captor = ArgumentCaptor.forClass(HomeUrlCommand.class);
        verify(entryUrlPort).getHomeUrl(captor.capture());
        assertThat(captor.getValue().companyCode())
                .isEqualTo(InstoreHomeEntryProperties.DEFAULT_COMPANY_CODE);
    }

    private HomeEntryCommand homeCommand() {
        return new HomeEntryCommand(PROJECT_ID, BUYER_ID, BUYER_USER_ID, LONGITUDE, LATITUDE);
    }

    private DetailEntryCommand detailCommand() {
        return new DetailEntryCommand(PROJECT_ID, BUYER_ID, BUYER_USER_ID, ORDER_NO);
    }

    private BuyerIntegrationRoute memberRoute() {
        return new BuyerIntegrationRoute(PROJECT_ID, 901L,
                BuyerIntegrationCapabilityCode.MEMBER_PROFILE, "AIFULI_MALL", "tenant-a",
                "HTTP", "SAAS_MEMBER_ADAPTER", "v1", 55L);
    }

    private IssuedCredential issued(Long credentialId, String credentialNo, long version,
                                    String token, InstoreEntryCredentialPurpose purpose,
                                    Long instoreOrderId) {
        return new IssuedCredential(credentialId, credentialNo, version, token, purpose,
                instoreOrderId, LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
    }
}
