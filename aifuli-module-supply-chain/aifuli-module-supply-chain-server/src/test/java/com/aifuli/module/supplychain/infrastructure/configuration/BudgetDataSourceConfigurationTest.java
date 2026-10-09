package com.aifuli.module.supplychain.infrastructure.configuration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 R02 获审 connect-timeout-millis: 1500 配置校验
 * 与统一资源键 StandardBudgetRoutingDataSource 包装实例共享。
 * 设计见 docs/superpowers/specs/2026-10-09-druid-to-hikari-migration-design.md#41-数据源装配与生命周期管理supplychaindatasourceconfiguration
 */
class BudgetDataSourceConfigurationTest {

    @Test
    void connectTimeoutPropertiesDefaultAndValidation() {
        SupplyChainDataSourceProperties props = new SupplyChainDataSourceProperties();
        assertThat(props.getConnectTimeoutMillis()).isEqualTo(1500);

        // 允许显式设置为 1500
        props.setConnectTimeoutMillis(1500);
        assertThat(props.getConnectTimeoutMillis()).isEqualTo(1500);

        // 显式其它值（含0、负数、其它正数）必须严格拒绝
        assertThatThrownBy(() -> props.setConnectTimeoutMillis(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1500");
        assertThatThrownBy(() -> props.setConnectTimeoutMillis(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> props.setConnectTimeoutMillis(3000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supplyChainDataSourceIsStandardBudgetRoutingAndSharedAcrossComponents() {
        SupplyChainDataSourceProperties properties = new SupplyChainDataSourceProperties();
        properties.setUrl("jdbc:mysql://localhost:3306/test?useSSL=false");
        properties.setUsername("root");
        properties.setPassword("root");
        properties.setDriverClassName("com.mysql.cj.jdbc.Driver");
        properties.setConnectTimeoutMillis(1500);

        SupplyChainDataSourceConfiguration config = new SupplyChainDataSourceConfiguration();
        DataSource dsBean = config.supplyChainDataSource(properties);

        assertThat(dsBean).isInstanceOf(StandardBudgetRoutingDataSource.class);
        StandardBudgetRoutingDataSource routingDs = (StandardBudgetRoutingDataSource) dsBean;
        HikariDataSource rawHikari = routingDs.getRawDataSource();
        assertThat(rawHikari).isNotNull();
        assertThat(rawHikari.getPoolName()).isEqualTo("supply-chain-pool");
        assertThat(rawHikari.getConnectionTimeout()).isEqualTo(3000L);
        // 驱动级 connectTimeout 以数据源属性注入（Connector/J 生效路径）
        assertThat(rawHikari.getDataSourceProperties().getProperty("connectTimeout")).isEqualTo("1500");

        // 验证事务管理器绑定统一包装实例资源键
        SupplyChainTransactionObservationManager txManager = config.supplyChainTransactionManager(dsBean);
        assertThat(txManager.getDataSource()).isSameAs(dsBean);

        routingDs.close();
    }
}
