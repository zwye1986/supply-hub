package com.aifuli.module.supplychain.infrastructure.auth;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 项目应用统一认证配置。
 *
 * <p>配置语义及默认值详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#66-配置项}。</p>
 */
@Data
@ConfigurationProperties(prefix = "aifuli.supply-chain.auth")
public class ProjectAppAuthProperties {

    /** 请求体缓存不可提高的安全硬上限：1 MiB。 */
    public static final int HARD_MAX_REQUEST_BODY_BYTES = 1024 * 1024;

    /** 过滤器总开关；false 仅供本地调试，Task 9 才按此开关注册过滤器。 */
    private boolean enabled = true;

    /**
     * 32 字节 Base64 主密钥；默认本地 resolver 生效且 enabled=true 时必填；
     * 自定义/KMS resolver 存在时不读取、不要求。
     *
     * <p>该字段禁止出现在日志或诊断输出中，排除 Lombok 生成的 {@code toString()}；
     * Spring 配置绑定仍通过 getter/setter 正常工作。</p>
     */
    @ToString.Exclude
    private String masterKey;

    /** 时间戳允许偏差，单位：分钟。 */
    private long timestampSkewMinutes = 5;

    /** nonce 过期保留时长，单位：小时；清扫与 expire_at 计算共用。 */
    private long nonceRetentionHours = 1;

    /** 入站验签允许缓存的请求体上限，单位：字节；不得超过 1 MiB 硬上限。 */
    private int maxRequestBodyBytes = HARD_MAX_REQUEST_BODY_BYTES;
}
