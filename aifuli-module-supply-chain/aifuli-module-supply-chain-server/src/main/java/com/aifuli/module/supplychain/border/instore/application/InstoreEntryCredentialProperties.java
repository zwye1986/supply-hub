package com.aifuli.module.supplychain.border.instore.application;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 到店购入口凭证配置。
 *
 * <p>默认有效期为 10 分钟。详见
 * {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#7-身份与入口}。</p>
 */
@Data
@ConfigurationProperties(prefix = "aifuli.supply-chain.instore.entry-credential")
public class InstoreEntryCredentialProperties {
    private Duration ttl = Duration.ofMinutes(10);
}
