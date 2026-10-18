package com.aifuli.framework.web.core.handler;

import com.aifuli.framework.apilog.core.filter.ApiAccessLogFilter;
import com.aifuli.framework.common.biz.infra.logger.ApiAccessLogCommonApi;
import com.aifuli.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import com.aifuli.framework.common.biz.infra.logger.dto.ApiAccessLogCreateReqDTO;
import com.aifuli.framework.common.biz.infra.logger.dto.ApiErrorLogCreateReqDTO;
import com.aifuli.framework.common.util.json.JsonUtils;
import com.aifuli.framework.web.config.WebProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仁跃临时合同共享日志保护，见 docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#513-临时本地认证与http闭环合同2026-10-18用户批准。 */
class RenyueCallbackLoggingTest {
    private static final String SECRET = "SYNTHETIC_RENYUE_SECRET";
    private static final String ROOT = "/callbacks/suppliers/renyue";

    @AfterEach
    void tearDown() { RequestContextHolder.resetRequestAttributes(); }

    private MockHttpServletRequest request(String suffix) {
        var request = new MockHttpServletRequest("POST", "/sc" + ROOT + "/" + SECRET + suffix) {
            @Override public Map<String, String[]> getParameterMap() { throw new AssertionError("不能读取查询参数"); }
            @Override public jakarta.servlet.ServletInputStream getInputStream() { throw new AssertionError("不能读取正文"); }
        };
        request.setContextPath("/sc");
        request.setContentType("application/x-www-form-urlencoded");
        request.setQueryString("ReturnSign=" + SECRET);
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/extra"})
    void accessLogDoesNotReadBodyOrQueryAndMasksVersionOnSuccessAndFailure(String suffix) throws Exception {
        for (boolean failure : new boolean[]{false, true}) {
            var api = mock(ApiAccessLogCommonApi.class);
            var request = request(suffix);
            var response = new MockHttpServletResponse();
            var filter = new ApiAccessLogFilter(new WebProperties(), "supply-chain-server", api);
            jakarta.servlet.FilterChain chain = (req, res) -> {
                if (failure) { throw new IllegalStateException(SECRET); }
                res.getWriter().write("success");
            };
            if (failure) {
                assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isInstanceOf(IllegalStateException.class).hasMessage(SECRET);
            } else {
                filter.doFilter(request, response, chain);
                assertThat(response.getContentAsString()).isEqualTo("success");
            }
            var captor = ArgumentCaptor.forClass(ApiAccessLogCreateReqDTO.class);
            verify(api).createApiAccessLogAsync(captor.capture());
            assertThat(captor.getValue().getRequestUrl()).isEqualTo(ROOT + "/[version]");
            assertThat(captor.getValue().getRequestParams()).isNull();
            assertThat(captor.getValue().getResponseBody()).isNull();
            assertThat(JsonUtils.toJsonString(captor.getValue())).doesNotContain(SECRET);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/extra"})
    void systemExceptionLogUsesSafeUrlAndMessageFreeCauseAndStack(String suffix) {
        var api = mock(ApiErrorLogCommonApi.class);
        var request = request(suffix);
        var response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        var exception = new IllegalStateException(SECRET, new IllegalArgumentException(SECRET));
        exception.addSuppressed(new RuntimeException(SECRET));
        var result = new GlobalExceptionHandler("supply-chain-server", api).allExceptionHandler(request, exception);
        assertThat(result.getCode()).isEqualTo(500);
        var captor = ArgumentCaptor.forClass(ApiErrorLogCreateReqDTO.class);
        verify(api).createApiErrorLogAsync(captor.capture());
        assertThat(captor.getValue().getRequestUrl()).isEqualTo(ROOT + "/[version]");
        assertThat(captor.getValue().getRequestParams()).isEqualTo("{}");
        assertThat(JsonUtils.toJsonString(captor.getValue())).doesNotContain(SECRET);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
