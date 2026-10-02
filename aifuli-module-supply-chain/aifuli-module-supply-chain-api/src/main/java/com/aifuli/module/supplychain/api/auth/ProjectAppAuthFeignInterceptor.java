package com.aifuli.module.supplychain.api.auth;

import feign.RequestInterceptor;
import feign.RequestTemplate;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Feign 请求拦截器：为受保护操作应用 Signer 生成的项目认证字段。
 *
 * <p>按模板最终方法与路径在 {@link ProjectAuthOperations} 注册表定位操作，
 * requestSha256 取 Feign 已序列化 body 原始字节；凭证以供应器注入，支持调用方
 * 按请求上下文动态切换项目身份。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#7-签名客户端}。</p>
 */
public final class ProjectAppAuthFeignInterceptor implements RequestInterceptor {

    private final Supplier<ProjectAppAuthCredentials> credentialsSupplier;

    public ProjectAppAuthFeignInterceptor(Supplier<ProjectAppAuthCredentials> credentialsSupplier) {
        this.credentialsSupplier = credentialsSupplier;
    }

    /**
     * 以本次 Signer 结果替换模板中的全部认证头，避免其它拦截器预置值或重复 apply 累积多值。
     *
     * <p>Feign Core 会省略值为 Java 空字符串的物理头；签名串仍以空串覆盖不适用的 scope，
     * 服务端 Task 9 的 {@code header()} 再把缺失头归一为空串。详见
     * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#43-签名串对上游设计-52-的补强-delta}
     * 与 {@code docs/superpowers/plans/2026-08-23-project-app-auth.md#task-9-入站验签过滤器与装配含安全放行配置}。</p>
     */
    @Override
    public void apply(RequestTemplate template) {
        String url = template.url();
        int queryIndex = url.indexOf('?');
        String path = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        Map<String, String> headers = ProjectAppAuthSigner.sign(
                credentialsSupplier.get(), template.method(), path, template.body());
        // 标准签名结果省略 scope 头，必须同时移除模板预置的旧身份，避免残留未签名声明。
        // 详见 docs/superpowers/specs/2026-09-30-project-signed-standard-order-http-design.md#21-认证实施边界。
        template.removeHeader(ProjectAppAuthSigner.HEADER_BUYER_USER_ID);
        template.removeHeader(ProjectAppAuthSigner.HEADER_CHANNEL_CODE);
        headers.forEach((name, value) -> {
            template.removeHeader(name);
            template.header(name, value);
        });
    }
}
