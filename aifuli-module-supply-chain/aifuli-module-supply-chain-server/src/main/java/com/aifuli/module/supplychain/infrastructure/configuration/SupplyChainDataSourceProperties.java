package com.aifuli.module.supplychain.infrastructure.configuration;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 供应链中台独立数据源配置。
 */
@ConfigurationProperties(prefix = "aifuli.supply-chain.datasource")
public class SupplyChainDataSourceProperties extends DataSourceProperties {

    /**
     * 新建物理连接超时毫秒数，R02获审仅允许1500。
     */
    private int connectTimeoutMillis = 1500;

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        if (connectTimeoutMillis != 1500) {
            throw new IllegalArgumentException("aifuli.supply-chain.datasource.connect-timeout-millis 只允许配置为 1500，实际: " + connectTimeoutMillis);
        }
        this.connectTimeoutMillis = connectTimeoutMillis;
    }
}
