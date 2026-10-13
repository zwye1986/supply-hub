package com.aifuli.module.supplychain.infrastructure.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aifuli.module.supplychain.infrastructure.configuration.OrderNoConfiguration;
import com.aifuli.module.supplychain.infrastructure.identity.dal.mysql.*;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

class OrderNoConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(OrderNoConfiguration.class)
          .withBean("supplyChainDataSource", DataSource.class, () -> mock(DataSource.class))
          .withBean(
              "supplyChainTransactionManager",
              PlatformTransactionManager.class,
              () -> mock(PlatformTransactionManager.class))
          .withBean(OrderNoSequenceMapper.class, () -> mock(OrderNoSequenceMapper.class))
          .withBean(OrderNoSegmentMapper.class, () -> mock(OrderNoSegmentMapper.class))
          .withBean(SupplyChainDatabaseClock.class, () -> mock(SupplyChainDatabaseClock.class));

  @Test
  void disabledDoesNotRequireRedis() {
    runner
        .withPropertyValues("aifuli.supply-chain.enabled=false")
        .run(c -> assertThat(c).doesNotHaveBean(OrderNoAllocationService.class));
  }

  @Test
  void enabledWithoutRedisFailsClosed() {
    configured().run(c -> assertThat(c).hasFailed());
  }

  @Test
  void invalidDomainFailsBeforeAnyRedisCall() {
    var redis = mock(RedissonClient.class);
    configured()
        .withBean(RedissonClient.class, () -> redis)
        .withPropertyValues("aifuli.supply-chain.order-no.scope=bad:scope")
        .run(
            c -> {
              assertThat(c).hasFailed();
              verifyNoInteractions(redis);
            });
  }

  @Test
  void validConfigurationReusesClientWithoutStartupAllocation() {
    var redis = mock(RedissonClient.class);
    configured()
        .withBean(RedissonClient.class, () -> redis)
        .run(
            c -> {
              assertThat(c).hasSingleBean(OrderNoAllocationService.class);
              verifyNoInteractions(redis);
            });
  }

  private ApplicationContextRunner configured() {
    return runner.withPropertyValues(
        "aifuli.supply-chain.enabled=true",
        "aifuli.supply-chain.order-no.env=test",
        "aifuli.supply-chain.order-no.scope=isolated");
  }
}
