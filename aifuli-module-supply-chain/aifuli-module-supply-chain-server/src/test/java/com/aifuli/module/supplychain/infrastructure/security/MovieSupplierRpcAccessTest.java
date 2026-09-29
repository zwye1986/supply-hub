package com.aifuli.module.supplychain.infrastructure.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/** 电影专用服务令牌及传输要求必须失败关闭，不接受无配置或错误令牌。 */
class MovieSupplierRpcAccessTest {
    /** 纯测试令牌，不用于部署。 */
    private static final String TOKEN = "movie-config-test-token-1234567890";
    private final MockEnvironment environment = new MockEnvironment();
    private final MovieSupplierRpcAccess access = new MovieSupplierRpcAccess(environment);

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void missingShortAndWrongTokensAreDenied() {
        environment.setProperty("movie.supply.config-require-https", "false");
        assertThat(access.allowed(TOKEN)).isFalse();
        environment.setProperty("movie.supply.config-token", "short");
        assertThat(access.allowed("short")).isFalse();
        environment.setProperty("movie.supply.config-token", TOKEN);
        assertThat(access.allowed(null)).isFalse();
        assertThat(access.allowed("wrong-token")).isFalse();
        assertThat(access.allowed(TOKEN)).isTrue();
    }

    @Test
    void httpsIsRequiredUnlessExplicitlyDisabledForLocalIntegration() {
        environment.setProperty("movie.supply.config-token", TOKEN);
        assertThat(access.allowed(TOKEN)).isFalse();
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThat(access.allowed(TOKEN)).isFalse();
        request.setSecure(true);
        assertThat(access.allowed(TOKEN)).isTrue();
        request.setSecure(false);
        environment.setProperty("movie.supply.config-require-https", "false");
        assertThat(access.allowed(TOKEN)).isTrue();
    }
}
