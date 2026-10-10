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

    /**
     * Hikari 最大连接数：最终受理含独立新RR视图Reader（REQUIRES_NEW），并发任务需外层+Reader双连接；
     * 默认40仅保障本轮10并发门禁，不承诺任意并发无连接饥饿；部署需核数据库总连接预算。
     * 见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#31-2026-10-10-评审修复边界用户确认方案-3。
     */
    private int maximumPoolSize = 40;

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public void setConnectTimeoutMillis(int connectTimeoutMillis) {
        if (connectTimeoutMillis != 1500) {
            throw new IllegalArgumentException("aifuli.supply-chain.datasource.connect-timeout-millis 只允许配置为 1500，实际: " + connectTimeoutMillis);
        }
        this.connectTimeoutMillis = connectTimeoutMillis;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        if (maximumPoolSize < 10 || maximumPoolSize > 200) {
            throw new IllegalArgumentException("aifuli.supply-chain.datasource.maximum-pool-size 允许 10～200，实际: " + maximumPoolSize);
        }
        this.maximumPoolSize = maximumPoolSize;
    }
}
