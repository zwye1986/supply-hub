package com.aifuli.module.supplychain.testsupport;

import com.aifuli.module.supplychain.infrastructure.identity.*;
import com.aifuli.module.supplychain.infrastructure.identity.dal.mysql.*;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/**
 * 既有业务测试显式隔离Redis基础设施：真实MySQL永久预留+低层Redis双替身。
 * 不属于生产装配，也不作为真实Redis故障门禁证据；后者由OrderNoRedisIntegrationTest独立覆盖。
 */
@Configuration(proxyBeanMethods = false)
public class OrderNoTestConfiguration {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    public static PreparedOrderNumbers numbers() {
        int first = SEQUENCE.addAndGet(2) - 1;
        return new PreparedOrderNumbers(
                String.format(java.util.Locale.ROOT, "2026101312000000%08d", first),
                String.format(java.util.Locale.ROOT, "2026101312000000%08d", first + 1));
    }

    @Bean
    OrderNoAllocationService orderNoAllocationService(
            OrderNoSequenceMapper sequence,
            OrderNoSegmentMapper segment,
            SupplyChainDatabaseClock clock,
            @Qualifier("supplyChainDataSource") DataSource ds,
            @Qualifier("supplyChainTransactionManager") PlatformTransactionManager tm) {
        var properties = new OrderNoProperties();
        properties.setEnv("test");
        properties.setScope("business-fixture");
        properties.setLockWait(Duration.ofSeconds(5));
        OrderNoRedisOperations redis =
                new OrderNoRedisOperations() {
                    public Instant time(Duration timeout) {
                        return Instant.now();
                    }

                    public Result consume(
                            String key, OrderNoSegment s, int floor, int count, Duration timeout) {
                        Instant now = time(timeout);
                        if (!s.date().equals(now.atZone(OrderNoFormatter.ZONE).toLocalDate()))
                            return new Result(Status.DATE_CHANGED, now, 0);
                        return new Result(Status.SUCCESS, now, floor + count);
                    }
                };
        return new OrderNoAllocationService(
                new MySqlOrderNoSegmentStore(sequence, segment, clock, ds, tm),
                redis,
                properties,
                new OrderNoFormatter(),
                ds);
    }
}
