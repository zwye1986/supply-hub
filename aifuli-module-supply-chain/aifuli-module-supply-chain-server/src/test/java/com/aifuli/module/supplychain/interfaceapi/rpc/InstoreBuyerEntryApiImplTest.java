package com.aifuli.module.supplychain.interfaceapi.rpc;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.instore.dto.HomeEntryReqDTO;
import com.aifuli.module.supplychain.api.instore.dto.InstoreEntryUrlRespDTO;
import com.aifuli.module.supplychain.api.instore.dto.OrderDetailEntryReqDTO;
import com.aifuli.module.supplychain.application.facade.InstoreBuyerEntryFacade;
import com.aifuli.module.supplychain.application.facade.InstoreBuyerEntryFacade.DetailEntryCommand;
import com.aifuli.module.supplychain.application.facade.InstoreBuyerEntryFacade.HomeEntryCommand;
import com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.BuyerContext;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 买家入口 RPC Adapter 单元测试：身份只来自 ProjectCallContextAccessor，
 * 未注入上下文时传播 001；成功时把 BuyerContext 与 DTO 转换为 Facade 命令；
 * 入口与出口日志不打印 buyerUserId、token 或最终 URL。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#42-可信上下文}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreBuyerEntryApiImplTest {

    private static final Long PROJECT_ID = 101L;
    private static final Long BUYER_ID = 1001L;
    private static final String BUYER_USER_ID = "opaque-user";
    private static final String LONGITUDE = "121.4737";
    private static final String LATITUDE = "31.2304";
    private static final String ORDER_NO = "SCO_1";
    private static final String ENTRY_URL = "https://rtmart.example/home?opaque=1";

    @Mock
    private ProjectCallContextAccessor contextAccessor;
    @Mock
    private InstoreBuyerEntryFacade facade;

    private InstoreBuyerEntryApiImpl apiImpl;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger implLogger;

    @BeforeEach
    void setUp() {
        apiImpl = new InstoreBuyerEntryApiImpl(contextAccessor, facade);
        implLogger = (Logger) LoggerFactory.getLogger(InstoreBuyerEntryApiImpl.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        implLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        implLogger.detachAppender(logAppender);
    }

    @Test
    void getHomeEntryPropagatesAuthFailureWhenContextMissing() {
        when(contextAccessor.requireBuyerContext())
                .thenThrow(new ServiceException(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID));

        assertThatThrownBy(() -> apiImpl.getHomeEntry(homeRequest()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID.getCode());

        verifyNoInteractions(facade);
    }

    @Test
    void getOrderDetailEntryPropagatesAuthFailureWhenContextMissing() {
        when(contextAccessor.requireBuyerContext())
                .thenThrow(new ServiceException(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID));

        assertThatThrownBy(() -> apiImpl.getOrderDetailEntry(detailRequest()))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getCode())
                .isEqualTo(SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID.getCode());

        verifyNoInteractions(facade);
    }

    @Test
    void getHomeEntryConvertsContextAndDtoToCommand() {
        when(contextAccessor.requireBuyerContext())
                .thenReturn(new BuyerContext(PROJECT_ID, BUYER_ID, BUYER_USER_ID));
        InstoreEntryUrlRespDTO response = new InstoreEntryUrlRespDTO();
        response.setEntryUrl(ENTRY_URL);
        when(facade.getHomeEntry(org.mockito.ArgumentMatchers.any(HomeEntryCommand.class)))
                .thenReturn(response);

        CommonResult<InstoreEntryUrlRespDTO> result = apiImpl.getHomeEntry(homeRequest());

        assertThat(result.getData().getEntryUrl()).isEqualTo(ENTRY_URL);

        ArgumentCaptor<HomeEntryCommand> captor = ArgumentCaptor.forClass(HomeEntryCommand.class);
        org.mockito.Mockito.verify(facade).getHomeEntry(captor.capture());
        HomeEntryCommand command = captor.getValue();
        assertThat(command.projectId()).isEqualTo(PROJECT_ID);
        assertThat(command.buyerId()).isEqualTo(BUYER_ID);
        assertThat(command.buyerUserId()).isEqualTo(BUYER_USER_ID);
        assertThat(command.longitude()).isEqualTo(LONGITUDE);
        assertThat(command.latitude()).isEqualTo(LATITUDE);

        assertLogsRedacted();
    }

    @Test
    void getOrderDetailEntryConvertsContextAndDtoToCommand() {
        when(contextAccessor.requireBuyerContext())
                .thenReturn(new BuyerContext(PROJECT_ID, BUYER_ID, BUYER_USER_ID));
        InstoreEntryUrlRespDTO response = new InstoreEntryUrlRespDTO();
        response.setEntryUrl(ENTRY_URL);
        when(facade.getOrderDetailEntry(org.mockito.ArgumentMatchers.any(DetailEntryCommand.class)))
                .thenReturn(response);

        CommonResult<InstoreEntryUrlRespDTO> result = apiImpl.getOrderDetailEntry(detailRequest());

        assertThat(result.getData().getEntryUrl()).isEqualTo(ENTRY_URL);

        ArgumentCaptor<DetailEntryCommand> captor = ArgumentCaptor.forClass(DetailEntryCommand.class);
        org.mockito.Mockito.verify(facade).getOrderDetailEntry(captor.capture());
        DetailEntryCommand command = captor.getValue();
        assertThat(command.projectId()).isEqualTo(PROJECT_ID);
        assertThat(command.buyerId()).isEqualTo(BUYER_ID);
        assertThat(command.buyerUserId()).isEqualTo(BUYER_USER_ID);
        assertThat(command.orderNo()).isEqualTo(ORDER_NO);

        assertLogsRedacted();
    }

    /** 入口与出口日志不得包含 buyerUserId、token 或最终入口 URL。 */
    private void assertLogsRedacted() {
        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list)
                .allSatisfy(event -> {
                    String message = event.getFormattedMessage();
                    assertThat(message).doesNotContain(BUYER_USER_ID);
                    assertThat(message).doesNotContain(ENTRY_URL);
                    assertThat(message).doesNotContain("token");
                });
    }

    private HomeEntryReqDTO homeRequest() {
        HomeEntryReqDTO request = new HomeEntryReqDTO();
        request.setLongitude(LONGITUDE);
        request.setLatitude(LATITUDE);
        return request;
    }

    private OrderDetailEntryReqDTO detailRequest() {
        OrderDetailEntryReqDTO request = new OrderDetailEntryReqDTO();
        request.setOrderNo(ORDER_NO);
        return request;
    }
}
