package com.aifuli.module.supplychain.infrastructure.identity;

import static org.assertj.core.api.Assertions.*;

import com.aifuli.module.supplychain.infrastructure.configuration.BudgetContext;

import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.testcontainers.containers.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** 真实节点故障与预算门禁；不修改宿主Redis/MySQL，不替代部署域Sentinel/Cluster验收。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderNoFaultIntegrationTest {
    static final Network NETWORK = Network.newNetwork();
    static final GenericContainer<?> PRIMARY =
            new GenericContainer<>("redis:7.4")
                    .withNetwork(NETWORK)
                    .withNetworkAliases("order-no-primary")
                    .withExposedPorts(6379);
    static final GenericContainer<?> REPLICA =
            new GenericContainer<>("redis:7.4")
                    .withNetwork(NETWORK)
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--replicaof", "order-no-primary", "6379");
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("supply_chain")
                    .withUsername("supply_chain")
                    .withPassword("supply_chain");
    static RedissonClient client, replicaClient;
    static RedisOrderNoOperations redis;
    static OrderNoIntegrationSupport db;

    @BeforeAll
    static void start() {
        MYSQL.start();
        PRIMARY.start();
        REPLICA.start();
        db = new OrderNoIntegrationSupport(MYSQL);
        client = connect(PRIMARY);
        replicaClient = connect(REPLICA);
        redis = new RedisOrderNoOperations(client);
    }

    static RedissonClient connect(GenericContainer<?> node) {
        var config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + node.getHost() + ":" + node.getMappedPort(6379))
                .setRetryAttempts(0);
        return Redisson.create(config);
    }

    @BeforeEach
    void clear() {
        BudgetContext.clear();
        db.clear();
        client.getKeys().flushdb();
    }

    @AfterEach
    void clearBudget() {
        BudgetContext.clear();
    }

    @AfterAll
    static void stop() {
        if (client != null) client.shutdown();
        if (replicaClient != null && replicaClient != client) replicaClient.shutdown();
        if (db != null) db.close();
        REPLICA.stop();
        PRIMARY.stop();
        MYSQL.stop();
        NETWORK.close();
    }

    @Test
    @Order(1)
    void realCommandTimeoutRetiresRangeAndLateResultCannotReturnOldNumber() throws Exception {
        var p = OrderNoPropertiesTest.valid();
        p.setSegmentSize(10);
        p.setCommandTimeout(Duration.ofMillis(100));
        var a =
                new OrderNoAllocationService(
                        db.store, redis, p, new OrderNoFormatter(() -> 0), db.ds);
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000001");
        PRIMARY.execInContainer("redis-cli", "CLIENT", "PAUSE", "600", "WRITE");
        long started = System.nanoTime();
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_AND_BILL))
                .isInstanceOf(RuntimeException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        // 允许已经发送的旧段命令迟到完成；本实例等待已经终结，不能采纳它的结果。
        Thread.sleep(800);
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000011");
        assertThat(
                        db.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sc_order_no_segment", Integer.class))
                .isEqualTo(2);
    }

    @Test
    @Order(2)
    void cancelledBudgetStopsWaitingAndAbandonsCurrentSegment() throws Exception {
        var a = db.allocator(redis, 10);
        a.prepare(OrderNoPurpose.ORDER_ONLY);
        PRIMARY.execInContainer("redis-cli", "CLIENT", "PAUSE", "600", "WRITE");
        long now = System.nanoTime();
        BudgetContext.set(BudgetContext.create(now, now + Duration.ofMillis(80).toNanos()));
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_ONLY))
                .isInstanceOf(RuntimeException.class);
        BudgetContext.clear();
        Thread.sleep(800);
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000011");
    }

    @Test
    @Order(3)
    void pausedOwnerAndNewOwnerNeverShareRemainingRange() {
        var old = db.allocator(redis, 10);
        assertThat(old.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000001");
        var fresh = db.allocator(redis, 10);
        assertThat(fresh.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000011");
        assertThat(old.prepare(OrderNoPurpose.ORDER_AND_BILL).billno()).endsWith("00000003");
    }

    @Test
    @Order(4)
    void redisUnavailableDoesNotUseMysqlPerNumberFallback() throws Exception {
        var p = OrderNoPropertiesTest.valid();
        p.setSegmentSize(10);
        p.setCommandTimeout(Duration.ofMillis(100));
        var a =
                new OrderNoAllocationService(
                        db.store, redis, p, new OrderNoFormatter(() -> 0), db.ds);
        a.prepare(OrderNoPurpose.ORDER_ONLY);
        PRIMARY.execInContainer("redis-cli", "CLIENT", "PAUSE", "900", "ALL");
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_ONLY))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_ONLY))
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        db.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sc_order_no_segment", Integer.class))
                .isEqualTo(1);
        Thread.sleep(1100);
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000011");
    }

    @Test
    @Order(5)
    void promotedStaleReplicaRepairsAcknowledgedWriteLossFromLiveOwnerFloor() throws Exception {
        AtomicReference<OrderNoRedisOperations> target = new AtomicReference<>(redis);
        OrderNoRedisOperations switching =
                new OrderNoRedisOperations() {
                    public Instant time(Duration t) {
                        return target.get().time(t);
                    }

                    public Result consume(String k, OrderNoSegment s, int f, int c, Duration t) {
                        return target.get().consume(k, s, f, c, t);
                    }
                };
        var a = db.allocator(switching, 10);
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000001");
        String key = client.getKeys().getKeysStream().findFirst().orElseThrow();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!"1".equals(replicaClient.getBucket(key, StringCodec.INSTANCE).get())) {
            if (System.nanoTime() > deadline) throw new AssertionError("replica未同步初始游标");
            Thread.sleep(50);
        }
        // 将已同步的从库独立提升，随后旧主确认写2但不再复制；切到从库确实会看到旧游标1。
        REPLICA.execInContainer("redis-cli", "REPLICAOF", "NO", "ONE");
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000002");
        assertThat(replicaClient.getBucket(key, StringCodec.INSTANCE).get()).isEqualTo("1");
        PRIMARY.stop();
        var promoted = new RedisOrderNoOperations(replicaClient);
        target.set(promoted);
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000003");
        assertThat(replicaClient.getBucket(key, StringCodec.INSTANCE).get()).isEqualTo("3");
        client.shutdown();
        client = replicaClient;
        redis = promoted;
    }

    @Test
    @Order(6)
    void confirmedRangeConsumesWithoutMysqlButNextReservationFails() {
        var a = db.allocator(redis, 2);
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000001");
        MYSQL.stop();
        assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000002");
        assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_ONLY))
                .isInstanceOf(RuntimeException.class);
    }
}
