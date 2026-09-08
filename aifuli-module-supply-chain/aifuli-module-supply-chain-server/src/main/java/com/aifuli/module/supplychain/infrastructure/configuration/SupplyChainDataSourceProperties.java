package com.aifuli.module.supplychain.infrastructure.configuration;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 供应链中台独立数据源配置。
 */
@ConfigurationProperties(prefix = "aifuli.supply-chain.datasource")
public class SupplyChainDataSourceProperties extends DataSourceProperties {
}
