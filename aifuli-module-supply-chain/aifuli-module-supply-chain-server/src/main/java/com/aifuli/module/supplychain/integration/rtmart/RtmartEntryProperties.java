package com.aifuli.module.supplychain.integration.rtmart;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * 旅通 HOME/DETAIL 入口适配器的私密与非私密配置。
 *
 * <p>启用时在 Bean 初始化阶段 fail-fast，阻止无效配置注册服务发现；HTTP 仅限 MockWebServer 使用的
 * loopback 地址。私钥和 appKey 永远排除在诊断字符串之外。设计见
 * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#7-配置与秘密管理}。</p>
 */
@Data
@ConfigurationProperties(prefix = "aifuli.supply-chain.instore.rtmart")
public class RtmartEntryProperties {

    /** 防止异常响应占用无界内存的配置硬上限：1 MiB。 */
    public static final int MAX_REASONABLE_RESPONSE_BYTES = 1024 * 1024;

    private static final String LOOPBACK_IPV4_LITERAL = "127.0.0.1";
    private static final String LOOPBACK_IPV6_LITERAL = "::1";
    private static final Duration MINIMUM_OKHTTP_TIMEOUT = Duration.ofMillis(1L);
    private static final Duration MAXIMUM_OKHTTP_TIMEOUT = Duration.ofMillis(Integer.MAX_VALUE);

    private boolean enabled = false;
    private String baseUrl = "https://test.ltsw.cn";
    private String homePath = "/pay_center/rt-pos-api/getRtPosUrl/home";
    private String detailPath = "/pay_center/rt-pos-api/getRtPosUrl/detail";

    /** 旅通 app_key，只能在出站请求内存中使用，禁止诊断输出。 */
    @ToString.Exclude
    private String appKey;

    /** PKCS#8 RSA 私钥，只能由 {@link RtmartPrivateKeyParser} 解析，禁止诊断输出。 */
    @ToString.Exclude
    private String privateKey;

    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration writeTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);
    private int maxResponseBytes = 65536;

    /**
     * Spring 配置绑定完成后执行启用态安全校验。
     *
     * <p>未启用的失败关闭模式无需秘密；启用后任何错误均阻止应用以错误配置启动。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#33-bean-启用与失败关闭}
     * 与 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#7-配置与秘密管理}。</p>
     */
    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        requireText(appKey);
        requireText(privateKey);
        validateBaseUrl();
        validatePath(homePath);
        validatePath(detailPath);
        validatePositive(connectTimeout);
        validatePositive(writeTimeout);
        validatePositive(readTimeout);
        if (maxResponseBytes <= 0 || maxResponseBytes > MAX_REASONABLE_RESPONSE_BYTES) {
            throw RtmartEntryException.configuration();
        }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw RtmartEntryException.configuration();
        }
    }

    /**
     * 校验旅通 base URL 为不含凭证、查询或片段的绝对地址，并拒绝生产 HTTP。
     *
     * <p>HTTP 仅允许精确的 {@code 127.0.0.1}/{@code ::1} 字面量，绝不触发 DNS 解析，避免 localhost
     * 或可重绑定域名在校验后解析到其他地址。显式端口只允许 1..65535；URI 解析失败通过
     * {@link RtmartSafeCause} 保留类型与栈帧，不保留可能回显配置原文的 message/原对象。
     * 设计见 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#41-http-约定}
     * 与 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#7-配置与秘密管理}。</p>
     */
    private void validateBaseUrl() {
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw RtmartEntryException.configuration();
            }
            int port = uri.getPort();
            if (port != -1 && (port < 1 || port > 65535)) {
                throw RtmartEntryException.configuration();
            }
            if ("https".equalsIgnoreCase(scheme)) {
                return;
            }
            if ("http".equalsIgnoreCase(scheme) && isLoopbackLiteral(uri.getHost())) {
                return;
            }
            throw RtmartEntryException.configuration();
        } catch (RtmartEntryException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw RtmartEntryException.configuration(exception);
        }
    }

    /**
     * 仅比较 URI 已解析出的规范化回环字面量；不使用 {@code InetAddress}，因此不执行 DNS 查询。
     *
     * <p>JDK 对 IPv6 host 可能保留方括号，先只去除该 URI 表达层包装，再与精确字面量比较。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#41-http-约定}
     * 与 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#7-配置与秘密管理}。</p>
     */
    private static boolean isLoopbackLiteral(String host) {
        String normalizedHost = host != null && host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        return LOOPBACK_IPV4_LITERAL.equals(normalizedHost) || LOOPBACK_IPV6_LITERAL.equals(normalizedHost);
    }

    /**
     * 校验 HOME/DETAIL 端点只是一条绝对路径，不能暗含 scheme、host、查询或片段。
     *
     * <p>URI 解析异常经 {@link RtmartSafeCause} 转换后进入 cause 链，保留类型/栈帧但不保留
     * 可能泄漏非法配置原文的 message 或原对象。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#41-http-约定}
     * 与 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#7-配置与秘密管理}。</p>
     */
    private static void validatePath(String path) {
        try {
            URI uri = URI.create(path);
            if (!path.startsWith("/") || uri.isAbsolute() || uri.getRawAuthority() != null
                    || uri.getHost() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw RtmartEntryException.configuration();
            }
        } catch (RtmartEntryException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw RtmartEntryException.configuration(exception);
        }
    }

    /**
     * 校验 timeout 是可无损映射为 OkHttp int 的整数毫秒，避免任何亚毫秒尾数被静默截断。
     *
     * <p>connect/write/read 三项均复用本校验，先按 Duration 精确比较 1ms 至 {@link Integer#MAX_VALUE} ms，
     * 再要求与毫秒换算结果完全相等；超界、亚毫秒精度或毫秒换算溢出必须 fail-closed。设计见
     * {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#6-超时重试与事务}
     * 与 {@code docs/superpowers/specs/2026-08-24-rtmart-entry-adapter-pro143-deployment-design.md#7-配置与秘密管理}。</p>
     */
    private static void validatePositive(Duration timeout) {
        if (timeout == null) {
            throw RtmartEntryException.configuration();
        }
        if (timeout.compareTo(MINIMUM_OKHTTP_TIMEOUT) < 0) {
            throw RtmartEntryException.configuration();
        }
        if (timeout.compareTo(MAXIMUM_OKHTTP_TIMEOUT) > 0) {
            validateOutOfRangeTimeoutConversion(timeout);
            throw RtmartEntryException.configuration();
        }
        try {
            long timeoutMillis = timeout.toMillis();
            if (timeoutMillis < 1L || timeoutMillis > Integer.MAX_VALUE
                    || !timeout.equals(Duration.ofMillis(timeoutMillis))) {
                throw RtmartEntryException.configuration();
            }
        } catch (ArithmeticException exception) {
            throw RtmartEntryException.configuration(exception);
        }
    }

    /**
     * 超过精确上界时探测毫秒换算，使极大 Duration 的安全 ArithmeticException 保留在配置失败因果链中。
     */
    private static void validateOutOfRangeTimeoutConversion(Duration timeout) {
        try {
            timeout.toMillis();
        } catch (ArithmeticException exception) {
            throw RtmartEntryException.configuration(exception);
        }
    }
}
