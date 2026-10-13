package com.aifuli.module.supplychain.infrastructure.configuration;

import com.aifuli.module.supplychain.infrastructure.identity.*;
import com.aifuli.module.supplychain.infrastructure.identity.dal.mysql.OrderNoSegmentMapper;
import com.aifuli.module.supplychain.infrastructure.identity.dal.mysql.OrderNoSequenceMapper;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;

/**
 * 数字订单号仅复用供应链数据源、事务管理器和已有Redis客户端；启用供应链时必要配置缺失即失败关闭。
 * 设计见 {@code docs/superpowers/specs/2026-10-13-supply-chain-order-number-design.md#10-评审关注点与本轮交付}。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderNoProperties.class)
@ConditionalOnProperty(prefix = "aifuli.supply-chain", name = "enabled", havingValue = "true")
public class OrderNoConfiguration {
    @Bean
    OrderNoAllocationService orderNoAllocationService(OrderNoProperties properties, RedissonClient redis,
            OrderNoSequenceMapper sequenceMapper, OrderNoSegmentMapper segmentMapper, SupplyChainDatabaseClock clock,
            @Qualifier("supplyChainDataSource") DataSource dataSource,
            @Qualifier("supplyChainTransactionManager") PlatformTransactionManager transactionManager) {
        properties.validate();
        return new OrderNoAllocationService(
                new MySqlOrderNoSegmentStore(sequenceMapper, segmentMapper, clock, dataSource, transactionManager),
                new RedisOrderNoOperations(redis), properties, new OrderNoFormatter(), dataSource);
    }
}
