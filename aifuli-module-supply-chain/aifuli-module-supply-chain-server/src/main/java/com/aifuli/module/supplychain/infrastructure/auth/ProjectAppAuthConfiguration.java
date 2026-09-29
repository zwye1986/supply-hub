package com.aifuli.module.supplychain.infrastructure.auth;

import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Arrays;
import java.util.Base64;

/**
 * 项目应用统一认证装配：校验本地信封主密钥并注册 appKey 解析 SPI。
 *
 * <p>本类通过 Spring Boot 自动配置 imports 加载，确保用户/KMS resolver 先注册后再判断
 * {@link ConditionalOnMissingBean}，避免普通组件扫描顺序导致默认实现无法可靠退位。enabled 模式
 * 同时在 Filter Bean 创建期校验 body/skew 并挂载四个受保护前缀；任何配置错误都会失败关闭；
 * enabled=false 时跳过解码且不注册 Filter。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#6-服务端组件}。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(ProjectAppAuthProperties.class)
@EnableScheduling
public class ProjectAppAuthConfiguration {

    /**
     * 没有自定义 SPI 时注册默认本地信封解析器。
     *
     * <p>解析器在构造时复制主密钥；构造完成后立即清零本方法持有的额外解码数组，
     * 避免配置装配留下多余敏感副本。接入 KMS 的自定义 resolver 时，本 bean 整体退位且
     * 不要求配置本地主密钥。</p>
     */
    @Bean
    @ConditionalOnMissingBean(AppKeySecretResolver.class)
    public AppKeySecretResolver appKeySecretResolver(ProjectAppAuthProperties properties) {
        byte[] decodedMasterKey = decodeMasterKey(properties);
        try {
            return new LocalEnvelopeAppKeySecretResolver(decodedMasterKey);
        } finally {
            Arrays.fill(decodedMasterKey, (byte) 0);
        }
    }

    /**
     * 默认发码端仅与本地信封解析器配对；KMS/自定义解析器须同时提供自定义发码 SPI。
     * 未配对时开通和轮换失败关闭，已有认证流量不受发码装配影响。
     * 设计见 docs/superpowers/specs/2026-09-29-access-config-backend-impl-design.md#31-发码材料与安全生命周期。
     */
    @Bean
    @ConditionalOnMissingBean(AppKeySecretIssuer.class)
    public AppKeySecretIssuer appKeySecretIssuer(AppKeySecretResolver resolver,
                                                 ProjectAppAuthProperties properties) {
        if (!(resolver instanceof LocalEnvelopeAppKeySecretResolver) || !properties.isEnabled()) {
            return (appId, keyVersion) -> {
                throw new IllegalStateException("项目应用发码端与密钥解析器未配对");
            };
        }
        byte[] decodedMasterKey = decodeMasterKey(properties);
        try {
            return new LocalEnvelopeAppKeySecretIssuer(decodedMasterKey);
        } finally {
            Arrays.fill(decodedMasterKey, (byte) 0);
        }
    }

    /**
     * 注册入站验签过滤器；构造器会在容器启动期校验 body 硬上限与 timestamp skew，配置错误失败关闭。
     * enabled=false 时本 Bean 不存在，供本地调试显式关闭认证。
     */
    @Bean
    @ConditionalOnProperty(prefix = "aifuli.supply-chain.auth", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<ProjectAppAuthFilter> projectAppAuthFilter(
            ProjectAppCredentialService credentialService,
            NonceStore nonceStore,
            ProjectAppAuthProperties properties,
            ObjectMapper objectMapper) {
        return buildFilterRegistration(
                new ProjectAppAuthFilter(credentialService, nonceStore, properties, objectMapper));
    }

    /** 四个 URL 模式由客户端/服务端共用注册表前缀派生，避免配置与保护面漂移。 */
    static FilterRegistrationBean<ProjectAppAuthFilter> buildFilterRegistration(ProjectAppAuthFilter filter) {
        FilterRegistrationBean<ProjectAppAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(filter);
        registration.setName("projectAppAuthFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setAsyncSupported(false);
        registration.addUrlPatterns(ProjectAuthOperations.PROTECTED_PREFIXES.stream()
                .map(prefix -> prefix + "/*")
                .toArray(String[]::new));
        return registration;
    }

    /**
     * 解码并校验 AES-256 主密钥；disabled 时返回空数组且不解析配置值。
     *
     * <p>失败消息只描述配置问题，不回显主密钥。</p>
     */
    static byte[] decodeMasterKey(ProjectAppAuthProperties properties) {
        if (!properties.isEnabled()) {
            return new byte[0];
        }
        String encodedMasterKey = properties.getMasterKey();
        if (encodedMasterKey == null || encodedMasterKey.isBlank()) {
            throw new IllegalStateException("aifuli.supply-chain.auth.master-key 未配置，项目认证失败关闭");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedMasterKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("aifuli.supply-chain.auth.master-key 不是合法 Base64", exception);
        }
        if (decoded.length != 32) {
            int actualLength = decoded.length;
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalStateException("aifuli.supply-chain.auth.master-key 必须是 32 字节 Base64，实际 "
                    + actualLength + " 字节");
        }
        return decoded;
    }
}
