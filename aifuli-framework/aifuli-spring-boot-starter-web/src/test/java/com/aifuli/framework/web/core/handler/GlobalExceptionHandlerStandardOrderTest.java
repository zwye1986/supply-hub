package com.aifuli.framework.web.core.handler;

import com.aifuli.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import com.aifuli.framework.common.biz.infra.logger.dto.ApiErrorLogCreateReqDTO;
import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 标准订单异常处理安全与脱敏保护测试。
 * 设计依据：{@code docs/superpowers/specs/2026-10-02-standard-project-http-transport-design.md#4-共享web保护与影响范围}。
 */
class GlobalExceptionHandlerStandardOrderTest {

    private ApiErrorLogCommonApi apiErrorLogApi;
    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        apiErrorLogApi = mock(ApiErrorLogCommonApi.class);
        exceptionHandler = new GlobalExceptionHandler("supply-chain-server", apiErrorLogApi);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void standardOrderParameterErrorMaskedAndSetsNoStore() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/open-api/supply-chain/project/v1/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("buyerOrderNo", "String");
        CommonResult<?> result = exceptionHandler.allExceptionHandler(request, ex);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMsg()).isEqualTo("请求参数不正确");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void standardOrderMethodNotAllowedSetsNoStore() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/open-api/supply-chain/project/v1/orders/query");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("GET");
        CommonResult<?> result = exceptionHandler.allExceptionHandler(request, ex);

        assertThat(result.getCode()).isEqualTo(405);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void standardOrderServiceExceptionSetsNoStoreAndPreservesCode() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/open-api/supply-chain/project/v1/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        ServiceException ex = new ServiceException(1005001001, "订单号冲突");
        CommonResult<?> result = exceptionHandler.allExceptionHandler(request, ex);

        assertThat(result.getCode()).isEqualTo(1005001001);
        assertThat(result.getMsg()).isEqualTo("订单号冲突");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @ParameterizedTest
    @ValueSource(strings = {"v1", "v2"})
    void standardOrderDefaultExceptionSanitizesUrlAndOmitsParamsInLog(String version) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/open-api/supply-chain/project/" + version + "/orders/13800138000");
        request.setParameter("secret", "123456");
        request.setContent("{\"sensitive\":\"data\"}".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        RuntimeException ex = new RuntimeException("internal failure with secret");
        CommonResult<?> result = exceptionHandler.allExceptionHandler(request, ex);

        assertThat(result.getCode()).isEqualTo(500);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");

        ArgumentCaptor<ApiErrorLogCreateReqDTO> captor = ArgumentCaptor.forClass(ApiErrorLogCreateReqDTO.class);
        verify(apiErrorLogApi).createApiErrorLogAsync(captor.capture());
        ApiErrorLogCreateReqDTO logDto = captor.getValue();

        assertThat(logDto.getRequestUrl()).isEqualTo("/open-api/supply-chain/project/" + version + "/orders/[protected]");
        assertThat(logDto.getRequestParams()).isEqualTo("{}");
        assertThat(logDto.getExceptionMessage()).doesNotContain("internal failure with secret");
    }

    @Test
    void nonStandardOrderPathPreservesOriginalMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin-api/system/user/get");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("userId", "Long");
        CommonResult<?> result = exceptionHandler.allExceptionHandler(request, ex);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMsg()).isEqualTo("请求参数缺失:userId");
        assertThat(response.getHeader("Cache-Control")).isNull();
    }

    @Test
    void huashengCallbackParameterErrorMaskedAndSetsNoStore() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/callbacks/suppliers/huasheng/101");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("rawBody", "String");
        CommonResult<?> result = exceptionHandler.allExceptionHandler(request, ex);

        assertThat(result.getCode()).isEqualTo(400);
        assertThat(result.getMsg()).isEqualTo("请求参数不正确");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void huashengCallbackMethodNotAllowedSetsNoStore() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/callbacks/suppliers/huasheng/101");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("GET");
        CommonResult<?> result = exceptionHandler.allExceptionHandler(request, ex);

        assertThat(result.getCode()).isEqualTo(405);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void huashengCallbackServiceExceptionSetsNoStoreAndPreservesCode() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/callbacks/suppliers/huasheng/101");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));

        ServiceException ex = new ServiceException(1002003001, "测试业务拒绝");
        CommonResult<?> result = exceptionHandler.allExceptionHandler(request, ex);

        assertThat(result.getCode()).isEqualTo(1002003001);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
