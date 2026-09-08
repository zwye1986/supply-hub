package com.aifuli.module.supplychain.integration.rtmart;

import com.aifuli.module.supplychain.integration.configuration.InstoreEntryPortConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import java.security.PrivateKey;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * 旅通 HOME/DETAIL 入口的真实出站 Adapter 自动配置。
 *
 * <p>仅在显式启用且不存在用户端口实现时装配；类级缺失条件必须覆盖属性、HTTP client 与签名器，确保用户
 * 自定义 {@link RtmartEntryUrlPort} 时不会读取或校验无关旅通秘密。真实端口优先于失败关闭默认端口。
 * 设计见 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#33-bean-启用与失败关闭}
 * 与 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#7-配置与秘密管理}。</p>
 */
@AutoConfiguration(before = InstoreEntryPortConfiguration.class)
@EnableConfigurationProperties(RtmartEntryProperties.class)
@ConditionalOnProperty(prefix = "aifuli.supply-chain.instore.rtmart", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean(RtmartEntryUrlPort.class)
public class RtmartEntryAdapterConfiguration {

    /**
     * 注册仅供旅通入口调用的单例 HTTP client，禁止复用或修改聚合服务中的通用 client。
     *
     * <p>该 Bean 不是未限定 {@link OkHttpClient} 注入的默认候选，Adapter 只能通过 Qualifier 使用它；即使开启
     * 全局 lazy initialization 也必须在启动期创建，从而驱动配置校验。此处以毫秒构造并关闭连接失败重试与两类
     * 重定向，确保请求失败或跳转时不会重放包含 token/sign 的请求体。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#33-bean-启用与失败关闭}
     * 与 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#6-超时重试与事务}。</p>
     *
     * @return 已按旅通安全不变量配置的独立连接池客户端
     */
    @Bean(value = "rtmartEntryOkHttpClient", defaultCandidate = false)
    @Lazy(false)
    public OkHttpClient rtmartEntryOkHttpClient(RtmartEntryProperties properties) {
        return new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .writeTimeout(properties.getWriteTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    /**
     * 在启动期解析 PKCS#8 私钥并注册单例签名器，禁止将延迟解析推迟到首个业务请求。
     *
     * <p>即使全局启用 lazy initialization，本 Bean 仍须在应用就绪前创建；私钥解析失败必须阻止服务注册，且
     * 配置异常不会携带秘密原文；统一使用 UTC 时钟生成请求时间戳。
     * 设计见 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#33-bean-启用与失败关闭}
     * 与 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#7-配置与秘密管理}。</p>
     *
     * @return 已持有可用 RSA 私钥的生产签名器
     */
    @Bean("rtmartRequestSigner")
    @Lazy(false)
    public RtmartRequestSigner rtmartRequestSigner(RtmartEntryProperties properties) {
        PrivateKey privateKey = RtmartPrivateKeyParser.parsePkcs8(properties.getPrivateKey());
        return RtmartRequestSigner.production(properties.getAppKey(), privateKey, Clock.systemUTC());
    }

    /**
     * 注册真实旅通端口，显式注入本配置创建的专用 client 与签名器，避免按类型误接聚合容器其它 Bean。
     *
     * <p>Bean 名刻意不同于失败关闭 Bean；自动配置排序使本真实端口先注册，从而令 fallback 的缺失条件退位。
     * 即使开启全局 lazy initialization 也必须在应用就绪前实例化，确保无效私钥不被推迟至首个业务调用。
     * 设计见 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#33-bean-启用与失败关闭}。</p>
     *
     * @return 不重试、严格响应校验的旅通 HTTP 入口端口
     */
    @Bean("rtmartEntryUrlHttpAdapter")
    @Lazy(false)
    public RtmartEntryUrlPort rtmartEntryUrlHttpAdapter(RtmartEntryProperties properties,
                                                        @Qualifier("rtmartEntryOkHttpClient") OkHttpClient client,
                                                        ObjectMapper objectMapper,
                                                        @Qualifier("rtmartRequestSigner") RtmartRequestSigner signer) {
        return new RtmartEntryUrlHttpAdapter(properties, client, objectMapper, signer);
    }
}
