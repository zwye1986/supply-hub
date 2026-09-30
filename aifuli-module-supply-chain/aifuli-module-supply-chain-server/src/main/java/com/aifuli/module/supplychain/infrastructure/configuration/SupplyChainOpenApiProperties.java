package com.aifuli.module.supplychain.infrastructure.configuration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 供应链接口文档开关。生产环境默认关闭。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "aifuli.supply-chain.openapi")
public class SupplyChainOpenApiProperties {

    private boolean enabled;
}
