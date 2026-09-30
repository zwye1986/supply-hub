package com.aifuli.module.supplychain.interfaceapi.rpc;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.dto.InstoreMemberRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.ResolveInstoreMemberReqDTO;
import com.aifuli.module.supplychain.application.facade.InstoreChannelMemberFacade;
import com.aifuli.module.supplychain.application.facade.InstoreChannelMemberFacade.ResolveMemberCommand;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.ChannelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 旅通会员解析 RPC Adapter 单元测试：渠道身份只来自 ProjectCallContextAccessor 的
 * ChannelContext，请求 DTO 只提供 token；入口与出口日志不得打印 token；
 * userCode 以不透明字符串原样透传（含前导零），不做数值化转换。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#41-api-层}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreChannelMemberApiImplTest {

    private static final Long PROJECT_ID = 101L;
    private static final String CHANNEL_CODE = "RT_MART";
    private static final String TOKEN = "tk-opaque";
    private static final String BUYER_USER_CODE = "001234";

    @Mock
    private ProjectCallContextAccessor contextAccessor;
    @Mock
    private InstoreChannelMemberFacade facade;

    private InstoreChannelMemberApiImpl apiImpl;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger implLogger;

    @BeforeEach
    void setUp() {
        apiImpl = new InstoreChannelMemberApiImpl(contextAccessor, facade);
        implLogger = (Logger) LoggerFactory.getLogger(InstoreChannelMemberApiImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        implLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        implLogger.detachAppender(logAppender);
    }

    @Test
    void resolveMemberPropagatesAuthFailureWhenContextMissing() {
        when(contextAccessor.requireChannelContext())
                .thenThrow(new ServiceException(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID));

        assertThatThrownBy(() -> apiImpl.resolveMember(memberRequest()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID.getCode());

        verifyNoInteractions(facade);
    }

    @Test
    void resolveMemberConvertsChannelContextAndTokenToCommand() {
        when(contextAccessor.requireChannelContext())
                .thenReturn(new ChannelContext(PROJECT_ID, CHANNEL_CODE));
        InstoreMemberRespDTO memberProfile = new InstoreMemberRespDTO();
        memberProfile.setUserCode(BUYER_USER_CODE);
        memberProfile.setMobile("13800000000");
        memberProfile.setUserName("测试会员");
        memberProfile.setCompanyCode("COMPANY_A");
        when(facade.resolveMember(org.mockito.ArgumentMatchers.any(ResolveMemberCommand.class)))
                .thenReturn(memberProfile);

        CommonResult<InstoreMemberRespDTO> result = apiImpl.resolveMember(memberRequest());

        assertThat(result.getData().getUserCode()).isEqualTo(BUYER_USER_CODE);
        assertThat(result.getData().getMobile()).isEqualTo("13800000000");
        assertThat(result.getData().getUserName()).isEqualTo("测试会员");
        assertThat(result.getData().getCompanyCode()).isEqualTo("COMPANY_A");

        ArgumentCaptor<ResolveMemberCommand> captor = ArgumentCaptor.forClass(ResolveMemberCommand.class);
        verify(facade).resolveMember(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(PROJECT_ID);
        assertThat(captor.getValue().channelCode()).isEqualTo(CHANNEL_CODE);
        assertThat(captor.getValue().token()).isEqualTo(TOKEN);

        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list)
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(TOKEN));
    }

    private ResolveInstoreMemberReqDTO memberRequest() {
        ResolveInstoreMemberReqDTO request = new ResolveInstoreMemberReqDTO();
        request.setToken(TOKEN);
        return request;
    }
}
