package com.aifuli.module.supplychain.interfaceapi.rpc;

import com.aifuli.module.supplychain.api.movie.*;
import com.aifuli.module.supplychain.infrastructure.security.MovieSupplierRpcAccess;
import com.aifuli.module.supplychain.tradecore.supply.application.MovieSupplierConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 在真实方法安全代理上验证专用令牌，避免仅检查注解文本而遗漏失效的安全配置。 */
class MovieSupplierConfigApiContractTest {
    /** 虚构测试令牌。 */
    private static final String TOKEN = "movie-rpc-contract-test-1234567890";

    @Test
    void contractUsesOneExactPathAndRequiresServiceToken() throws Exception {
        var method = MovieSupplierConfigApi.class.getMethod("accounts", String.class, List.class);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly(MovieSupplierConfigApi.PATH);
        MovieSupplierConfigService service = mock(MovieSupplierConfigService.class);
        when(service.accounts(List.of(MovieSupplierChannel.JC))).thenReturn(List.of());
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment().withProperty("movie.supply.config-token", TOKEN)
                    .withProperty("movie.supply.config-require-https", "false"));
            context.register(SecurityConfiguration.class);
            context.registerBean("movieSupplierRpcAccess", MovieSupplierRpcAccess.class);
            context.registerBean(MovieSupplierConfigService.class, () -> service);
            context.registerBean(MovieSupplierConfigApiImpl.class);
            context.refresh();
            MovieSupplierConfigApi api = context.getBean(MovieSupplierConfigApi.class);
            assertThatThrownBy(() -> api.accounts("wrong", List.of(MovieSupplierChannel.JC)))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(service);
            assertThat(api.accounts(TOKEN, List.of(MovieSupplierChannel.JC)).getData()).isEmpty();
            verify(service).accounts(List.of(MovieSupplierChannel.JC));
        }
    }

    @Test
    void applicationErrorsReturnFailureWithoutEchoingRawCause() {
        MovieSupplierConfigService service = mock(MovieSupplierConfigService.class);
        when(service.accounts(List.of(MovieSupplierChannel.JC)))
                .thenThrow(new IllegalStateException("test-sensitive-config-must-not-escape"));
        var response = new MovieSupplierConfigApiImpl(service).accounts(TOKEN, List.of(MovieSupplierChannel.JC));
        assertThat(response.getCode()).isNotZero();
        assertThat(response.getMsg()).doesNotContain("test-sensitive-config-must-not-escape");
        assertThat(response.getData()).isNull();
    }

    /** 测试仅启用方法鉴权，不依赖真实登录服务。 */
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class SecurityConfiguration {
    }
}
