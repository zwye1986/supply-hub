package com.aifuli.module.supplychain.interfaceapi.open.project;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.api.fulfillment.dto.ProjectStandardOrderCreateRespDTO;
import com.aifuli.module.supplychain.application.facade.ProjectStandardOrderCommandFacade;
import com.aifuli.module.supplychain.application.facade.ProjectStandardOrderQueryFacade;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor;
import com.aifuli.module.supplychain.infrastructure.security.ProjectCallContextAccessor.StandardProductContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 项目标准订单开放控制器契约与边界测试。
 * 验证端点路径、方法、consumes 白名单、禁止 query string 与上下文委派。
 * 详见 {@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#1-controller与严格请求解析}。
 */
class ProjectStandardOrderHttpContractTest {

    private ProjectCallContextAccessor contextAccessor;
    private ProjectStandardOrderCommandFacade commandFacade;
    private ProjectStandardOrderQueryFacade queryFacade;
    private ProjectStandardHttpRequestParser parser;
    private ProjectStandardOrderController controller;

    @BeforeEach
    void setUp() {
        contextAccessor = mock(ProjectCallContextAccessor.class);
        commandFacade = mock(ProjectStandardOrderCommandFacade.class);
        queryFacade = mock(ProjectStandardOrderQueryFacade.class);
        parser = new ProjectStandardHttpRequestParser();
        controller = new ProjectStandardOrderController(contextAccessor, commandFacade, queryFacade, parser);
    }

    @Test
    void controllerHasExactThreePostMappingsAndStrictConsumes() throws NoSuchMethodException {
        assertThat(ProjectStandardOrderController.class.isAnnotationPresent(RestController.class)).isTrue();
        RequestMapping rootMapping = ProjectStandardOrderController.class.getAnnotation(RequestMapping.class);
        assertThat(rootMapping.value()).containsExactly(ProjectAuthOperations.STANDARD_ORDERS_PATH);

        Method createMethod = ProjectStandardOrderController.class.getMethod("createOrder", HttpServletRequest.class, byte[].class);
        PostMapping createPost = createMethod.getAnnotation(PostMapping.class);
        assertThat(createPost.value()).isEmpty();
        assertThat(createPost.consumes()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
        assertThat(Arrays.stream(createMethod.getParameters()).anyMatch(p -> p.isAnnotationPresent(RequestBody.class))).isTrue();

        Method queryMethod = ProjectStandardOrderController.class.getMethod("queryOrder", HttpServletRequest.class, byte[].class);
        PostMapping queryPost = queryMethod.getAnnotation(PostMapping.class);
        assertThat(queryPost.value()).containsExactly("/query");
        assertThat(queryPost.consumes()).containsExactly(MediaType.APPLICATION_JSON_VALUE);

        Method assetsMethod = ProjectStandardOrderController.class.getMethod("queryAssets", HttpServletRequest.class, byte[].class);
        PostMapping assetsPost = assetsMethod.getAnnotation(PostMapping.class);
        assertThat(assetsPost.value()).containsExactly("/assets/query");
        assertThat(assetsPost.consumes()).containsExactly(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    void rejectsAnyRequestWithNonEmptyQueryString() {
        HttpServletRequest reqWithQuery = mock(HttpServletRequest.class);
        when(reqWithQuery.getQueryString()).thenReturn("foo=bar");

        byte[] body = "{\"buyerOrderNo\":\"ORD_1\"}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> controller.createOrder(reqWithQuery, body))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> controller.queryOrder(reqWithQuery, body))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> controller.queryAssets(reqWithQuery, body))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void createOrderDelegatesToCommandFacadeWithStandardProductContext() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getQueryString()).thenReturn(null);

        StandardProductContext ctx = new StandardProductContext(1L, 2L, "app1", 1, ProjectAuthOperations.STANDARD_ORDER_CREATE);
        when(contextAccessor.requireStandardProductContext(ProjectAuthOperations.STANDARD_ORDER_CREATE)).thenReturn(ctx);

        ProjectStandardOrderCreateRespDTO respDto = new ProjectStandardOrderCreateRespDTO();
        respDto.setRequestId("AR_1");
        respDto.setBuyerOrderNo("ORD_1");
        when(commandFacade.create(eq(ctx), any())).thenReturn(respDto);

        String json = """
                {
                    "buyerOrderNo": "ORD_1",
                    "buyerSkuCode": "SKU_1",
                    "quantity": 1,
                    "target": {"k": "v"}
                }
                """;
        CommonResult<ProjectStandardOrderCreateRespDTO> result = controller.createOrder(req, json.getBytes(StandardCharsets.UTF_8));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().getRequestId()).isEqualTo("AR_1");
        verify(commandFacade).create(eq(ctx), any());
    }
}
