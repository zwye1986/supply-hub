package com.aifuli.module.supplychain.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 独立供应链进程专用身份密钥配置；显式密钥端口存在时默认解析器退位，缺密钥失败关闭。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#2-现有实现与接入边界}。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "aifuli.supply-chain", name = "enabled", havingValue = "true")
public class RechargeFrequencyIdentityConfiguration {
    @Bean
    @ConditionalOnMissingBean(RechargeFrequencyIdentityKeyProvider.class)
    RechargeFrequencyIdentityKeyProvider rechargeFrequencyIdentityKeyProvider(
            @Value("${aifuli.supply-chain.recharge-frequency.identity-key-ref:}") String reference) {
        return new ConfiguredRechargeFrequencyIdentityKeyProvider(reference);
    }
}
