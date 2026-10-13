package com.aifuli.module.supplychain.infrastructure.identity;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainDataSourceConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.dal.mysql.*;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.MySQLContainer;

class OrderNoIntegrationSupport implements AutoCloseable {
  final ConfigurableApplicationContext context;
  final DataSource ds;
  final JdbcTemplate jdbc;
  final PlatformTransactionManager tm;
  final MySqlOrderNoSegmentStore store;

  OrderNoIntegrationSupport(MySQLContainer<?> mysql) {
    context =
        new SpringApplicationBuilder(Fixture.class)
            .web(WebApplicationType.NONE)
            .properties(
                Map.of(
                    "aifuli.supply-chain.enabled",
                    true,
                    "aifuli.supply-chain.datasource.url",
                    mysql.getJdbcUrl(),
                    "aifuli.supply-chain.datasource.username",
                    mysql.getUsername(),
                    "aifuli.supply-chain.datasource.password",
                    mysql.getPassword(),
                    "aifuli.supply-chain.datasource.driver-class-name",
                    "com.mysql.cj.jdbc.Driver"))
            .run();
    ds = context.getBean("supplyChainDataSource", DataSource.class);
    tm = context.getBean("supplyChainTransactionManager", PlatformTransactionManager.class);
    jdbc = new JdbcTemplate(ds);
    store = store(context.getBean(OrderNoSegmentMapper.class), tm);
  }

  MySqlOrderNoSegmentStore store(OrderNoSegmentMapper segment, PlatformTransactionManager manager) {
    return new MySqlOrderNoSegmentStore(
        context.getBean(OrderNoSequenceMapper.class),
        segment,
        context.getBean(SupplyChainDatabaseClock.class),
        ds,
        manager);
  }

  void clear() {
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND"
                + " TABLE_NAME='sc_order_no_sequence'",
            Integer.class)
        > 0) {
      jdbc.update("DELETE FROM sc_order_no_segment");
      jdbc.update("DELETE FROM sc_order_no_sequence");
    }
  }

  OrderNoAllocationService allocator(OrderNoRedisOperations redis, int size) {
    var p = OrderNoPropertiesTest.valid();
    p.setSegmentSize(size);
    p.setCommandTimeout(Duration.ofSeconds(3));
    p.setLockWait(Duration.ofSeconds(5));
    return new OrderNoAllocationService(store, redis, p, new OrderNoFormatter(() -> 0), ds);
  }

  public void close() {
    context.close();
  }

  @Configuration(proxyBeanMethods = false)
  @Import(SupplyChainDataSourceConfiguration.class)
  static class Fixture {
    /** 观察真实共享数据源借用，不替换JDBC、事务或Mapper行为。 */
    @org.springframework.context.annotation.Bean
    static org.springframework.beans.factory.config.BeanPostProcessor observeDataSource() {
      return new org.springframework.beans.factory.config.BeanPostProcessor() {
        public Object postProcessAfterInitialization(Object bean, String name) {
          return "supplyChainDataSource".equals(name) ? org.mockito.Mockito.spy(bean) : bean;
        }
      };
    }
  }
}
