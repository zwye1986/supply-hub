package com.aifuli.module.supplychain.infrastructure.identity;

import static org.assertj.core.api.Assertions.*;

import com.aifuli.module.supplychain.border.instore.application.*;
import com.aifuli.module.supplychain.border.o2o.application.*;
import com.aifuli.module.supplychain.border.order.domain.*;
import com.aifuli.module.supplychain.infrastructure.configuration.*;
import com.aifuli.module.supplychain.infrastructure.identity.dal.mysql.*;
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.account.application.*;
import com.aifuli.module.supplychain.tradecore.ledger.application.AccountLedgerWriter;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/** 渠道建单真实MySQL/Redis门禁，未调用渠道或收银台网络，不替代供应商实机验收。 */
class ChannelOrderNumberIntegrationTest {
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("supply_chain")
                    .withUsername("supply_chain")
                    .withPassword("supply_chain");
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7.4").withExposedPorts(6379);
    static ConfigurableApplicationContext context;
    static RedissonClient client;
    static JdbcTemplate jdbc;
    static final AtomicInteger IDS = new AtomicInteger(9000);
    static SwitchableRedis redis;

    @BeforeAll
    static void start() {
        MYSQL.start();
        REDIS.start();
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        client = Redisson.create(config);
        redis = new SwitchableRedis(new RedisOrderNoOperations(client));
        context =
                new SpringApplicationBuilder(Fixture.class)
                        .web(WebApplicationType.NONE)
                        .properties(
                                Map.of(
                                        "aifuli.supply-chain.enabled",
                                        true,
                                        "aifuli.supply-chain.datasource.url",
                                        MYSQL.getJdbcUrl(),
                                        "aifuli.supply-chain.datasource.username",
                                        MYSQL.getUsername(),
                                        "aifuli.supply-chain.datasource.password",
                                        MYSQL.getPassword(),
                                        "aifuli.supply-chain.datasource.driver-class-name",
                                        "com.mysql.cj.jdbc.Driver"))
                        .run();
        jdbc = new JdbcTemplate(context.getBean("supplyChainDataSource", DataSource.class));
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        if (client != null) client.shutdown();
        REDIS.stop();
        MYSQL.stop();
    }

    @BeforeEach
    void resetFailure() {
        redis.unavailable = false;
    }

    int seed() {
        int id = IDS.incrementAndGet();
        jdbc.update(
                "INSERT INTO"
                    + " sc_buyer(id,buyer_code,buyer_name,short_name,buyer_type,status,version,created_at,updated_at)"
                    + " VALUES (?,?,'测试采购商','测试','SAAS_MALL',10,0,NOW(3),NOW(3))",
                id,
                "B" + id);
        jdbc.update(
                "INSERT INTO"
                    + " sc_buyer_account(account_no,buyer_id,currency,balance,frozen,status,version,created_at,updated_at)"
                    + " VALUES (?,?,'CNY',0,0,10,0,NOW(3),NOW(3))",
                "ACC" + id,
                id);
        jdbc.update(
                "INSERT INTO"
                    + " sc_supplier(id,supplier_code,supplier_name,short_name,supplier_type,status,version,created_at,updated_at)"
                    + " VALUES (?,?,'测试商户','商户','O2O_MERCHANT',10,0,NOW(3),NOW(3))",
                id,
                "S" + id);
        for (String channel : List.of("ELEME", "RT_MART"))
            jdbc.update(
                    "INSERT INTO"
                        + " sc_supplier_channel_binding(channel_code,external_merchant_id,supplier_id,mapping_version,valid_from,current_flag,status,version,created_at,updated_at)"
                        + " VALUES (?,?,?,1,NOW(3),1,10,0,NOW(3),NOW(3))",
                    channel,
                    "M" + id,
                    id);
        jdbc.update(
                "INSERT INTO"
                    + " sc_o2o_channel_user_binding(binding_no,buyer_id,buyer_user_id,channel_code,channel_user_id,status,version,created_at,updated_at)"
                    + " VALUES (?,?,'user','ELEME',?,10,0,NOW(3),NOW(3))",
                "OUB" + id,
                id,
                "CU" + id);
        jdbc.update(
                "INSERT INTO"
                    + " sc_instore_channel_user_binding(binding_no,buyer_id,project_id,source_system,external_tenant_id,buyer_user_id,channel_code,status,version,created_at,updated_at)"
                    + " VALUES (?,?,?,'AIFULI_MALL','tenant','user','RT_MART',10,0,NOW(3),NOW(3))",
                "IUB" + id,
                id,
                id);
        return id;
    }

    O2OChannelLifecycle.PreparePaymentCommand o2o(int id, long amount, String subject) {
        return new O2OChannelLifecycle.PreparePaymentCommand(
                "ELEME",
                "T" + id,
                "CU" + id,
                amount,
                "M" + id,
                "store",
                "门店",
                subject,
                "trace-test");
    }

    InstoreChannelLifecycle.CreatePaymentOrderCommand instore(int id, long amount, Long project) {
        return new InstoreChannelLifecycle.CreatePaymentOrderCommand(
                "RT_MART",
                "O" + id,
                "T" + id,
                (long) id,
                project,
                1L,
                "AIFULI_MALL",
                "tenant",
                "user",
                "user",
                amount,
                1,
                amount,
                "M" + id,
                "M" + id,
                "shop",
                "门店",
                "测试",
                "trace-test");
    }

    @Test
    void o2oNumericCreationAndAmountOnlyReplayRemainCompatibleWhenRedisFails() {
        int id = seed();
        var coordinator = context.getBean(O2OOrderCreationCoordinator.class);
        var first = coordinator.create(o2o(id, 100, "首次"));
        assertThat(first.orderNo()).matches("[0-9]{24}");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT order_no FROM sc_supply_chain_order WHERE id=?",
                                String.class,
                                first.orderId()))
                .isEqualTo(first.orderNo());
        int consumed = redis.calls.get();
        redis.unavailable = true;
        var replay = coordinator.create(o2o(id, 100, "修改非金额字段"));
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(replay.outTradeNo()).isEqualTo(first.outTradeNo());
        assertThat(redis.calls.get()).isEqualTo(consumed);
        assertThatThrownBy(() -> coordinator.create(o2o(id, 101, "异参")))
                .isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT frozen FROM sc_buyer_account WHERE buyer_id=?",
                                Long.class,
                                id))
                .isEqualTo(100L);
    }

    @Test
    void instoreNumericCreationReplayAndProjectScopeKeepOriginalRules() {
        int id = seed();
        var coordinator = context.getBean(InstoreOrderCreationCoordinator.class);
        var first = coordinator.create(instore(id, 100, (long) id));
        assertThat(first.orderNo()).matches("[0-9]{24}");
        int consumed = redis.calls.get();
        redis.unavailable = true;
        var replay = coordinator.create(instore(id, 100, (long) id));
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.orderId()).isEqualTo(first.orderId());
        assertThat(replay.outTradeNo()).isEqualTo(first.outTradeNo());
        assertThat(redis.calls.get()).isEqualTo(consumed);
        assertThatThrownBy(() -> coordinator.create(instore(id, 100, (long) id + 1)))
                .isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sc_instore_payment_flow WHERE order_id=?",
                                Integer.class,
                                first.orderId()))
                .isEqualTo(1);
    }

    @Test
    void o2oStillRequiresEnabledBindingBeforeReplay() {
        int id = seed();
        var coordinator = context.getBean(O2OOrderCreationCoordinator.class);
        coordinator.create(o2o(id, 100, "首次"));
        jdbc.update("UPDATE sc_o2o_channel_user_binding SET status=20 WHERE buyer_id=?", id);
        int consumed = redis.calls.get();
        assertThatThrownBy(() -> coordinator.create(o2o(id, 100, "同参")))
                .isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class);
        assertThat(redis.calls.get()).isEqualTo(consumed);
    }

    @Test
    void missingAccountRollsBackOrderButNeverReclaimsAllocatedSequence() {
        int id = seed();
        jdbc.update("DELETE FROM sc_buyer_account WHERE buyer_id=?", id);
        var coordinator = context.getBean(O2OOrderCreationCoordinator.class);
        int consumed = redis.calls.get();
        assertThatThrownBy(() -> coordinator.create(o2o(id, 100, "失败")))
                .isInstanceOf(com.aifuli.framework.common.exception.ServiceException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sc_supply_chain_order WHERE buyer_id=?",
                                Integer.class,
                                id))
                .isZero();
        assertThat(redis.calls.get()).isEqualTo(consumed + 1);
        int next = seed();
        assertThat(coordinator.create(o2o(next, 100, "新单")).orderNo()).matches("[0-9]{24}");
    }

    @Test
    void concurrentO2oPermanentKeyRaceCreatesOneOrderAndOneFreeze() throws Exception {
        int id = seed();
        var coordinator = context.getBean(O2OOrderCreationCoordinator.class);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var gate = new CountDownLatch(1);
            var futures = new ArrayList<Future<O2OChannelLifecycle.PreparedPayment>>();
            for (int i = 0; i < 8; i++)
                futures.add(
                        workers.submit(
                                () -> {
                                    gate.await();
                                    return coordinator.create(o2o(id, 100, "同参"));
                                }));
            gate.countDown();
            var orderIds = new HashSet<Long>();
            for (var f : futures) orderIds.add(f.get(30, TimeUnit.SECONDS).orderId());
            assertThat(orderIds).hasSize(1);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sc_supply_chain_order WHERE buyer_id=?",
                                Integer.class,
                                id))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT frozen FROM sc_buyer_account WHERE buyer_id=?",
                                Long.class,
                                id))
                .isEqualTo(100L);
    }

    @Test
    void concurrentInstorePermanentKeyRaceCreatesOneOrderAndOneFreeze() throws Exception {
        int id = seed();
        var coordinator = context.getBean(InstoreOrderCreationCoordinator.class);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var gate = new CountDownLatch(1);
            var futures = new ArrayList<Future<InstoreChannelLifecycle.PreparedPayment>>();
            for (int i = 0; i < 8; i++) futures.add(workers.submit(() -> {
                gate.await(); return coordinator.create(instore(id, 100, (long) id));
            }));
            gate.countDown(); var ids = new HashSet<Long>();
            for (var result : futures) ids.add(result.get(30, TimeUnit.SECONDS).orderId());
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sc_supply_chain_order WHERE buyer_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT frozen FROM sc_buyer_account WHERE buyer_id=?", Long.class, id)).isEqualTo(100L);
    }

    static class SwitchableRedis implements OrderNoRedisOperations {
        final OrderNoRedisOperations delegate;
        volatile boolean unavailable;
        final AtomicInteger calls = new AtomicInteger();

        SwitchableRedis(OrderNoRedisOperations delegate) {
            this.delegate = delegate;
        }

        public Instant time(Duration timeout) {
            if (unavailable) throw new IllegalStateException("Redis不可用");
            return delegate.time(timeout);
        }

        public Result consume(
                String key, OrderNoSegment s, int floor, int count, Duration timeout) {
            if (unavailable) throw new IllegalStateException("Redis不可用");
            calls.incrementAndGet();
            return delegate.consume(key, s, floor, count, timeout);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
        SupplyChainDataSourceConfiguration.class,
        UlidGenerator.class,
        BusinessNoGenerator.class,
        AccountLedgerWriter.class,
        FundReservationService.class,
        BuyerAccountReturnService.class,
        OutboxEventRepository.class,
        OrderSummaryPolicyRegistry.class,
        CardOrderSummaryPolicy.class,
        O2OOrderSummaryPolicy.class,
        InstoreOrderSummaryPolicy.class,
        O2OChannelLifecycle.class,
        O2ORefundSequencer.class,
        O2OOrderCreationCoordinator.class,
        InstoreChannelLifecycle.class,
        InstoreRefundSequencer.class,
        InstoreOrderCreationCoordinator.class
    })
    static class Fixture {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        OrderNoAllocationService allocator(
                OrderNoSequenceMapper seq,
                OrderNoSegmentMapper segment,
                SupplyChainDatabaseClock clock,
                @Qualifier("supplyChainDataSource") DataSource ds,
                @Qualifier("supplyChainTransactionManager") PlatformTransactionManager tm) {
            var properties = new OrderNoProperties();
            properties.setEnv("test");
            properties.setScope("channels");
            properties.setLockWait(Duration.ofSeconds(10));
            properties.setCommandTimeout(Duration.ofSeconds(3));
            return new OrderNoAllocationService(
                    new MySqlOrderNoSegmentStore(seq, segment, clock, ds, tm),
                    redis,
                    properties,
                    new OrderNoFormatter(),
                    ds);
        }
    }
}
