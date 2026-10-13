package com.aifuli.module.supplychain.infrastructure.identity;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.redisson.Redisson;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.testcontainers.containers.*;

class OrderNoRedisIntegrationTest {
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("supply_chain")
          .withUsername("supply_chain")
          .withPassword("supply_chain");
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4").withExposedPorts(6379);
  static OrderNoIntegrationSupport db;
  static RedissonClient client;
  static RedisOrderNoOperations redis;
  static final Duration TIMEOUT = Duration.ofSeconds(3);

  @BeforeAll
  static void start() {
    MYSQL.start();
    REDIS.start();
    db = new OrderNoIntegrationSupport(MYSQL);
    var c = new Config();
    c.useSingleServer()
        .setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379))
        .setRetryAttempts(0);
    client = Redisson.create(c);
    redis = new RedisOrderNoOperations(client);
  }

  @AfterAll
  static void stop() {
    if (client != null) client.shutdown();
    if (db != null) db.close();
    REDIS.stop();
    MYSQL.stop();
  }

  @BeforeEach
  void clear() {
    db.clear();
    client.getKeys().flushdb();
  }

  OrderNoSegment segment(int end) {
    return new OrderNoSegment(
        redis.time(TIMEOUT).atZone(OrderNoFormatter.ZONE).toLocalDate(), 1, end, "a".repeat(32));
  }

  @Test
  void luaDeletionAndRollbackRepairFromFloorWithoutReissuing() {
    var s = segment(10);
    String key = "test:{segment}:cursor";
    assertThat(redis.consume(key, s, 0, 2, TIMEOUT).endValue()).isEqualTo(2);
    client.getKeys().delete(key);
    assertThat(redis.consume(key, s, 2, 2, TIMEOUT).endValue()).isEqualTo(4);
    client.getBucket(key, StringCodec.INSTANCE).set("1");
    assertThat(redis.consume(key, s, 4, 1, TIMEOUT).endValue()).isEqualTo(5);
    assertThat(client.getBucket(key, StringCodec.INSTANCE).get()).isEqualTo("5");
  }

  @Test
  void luaReturnsItsOwnTimeAndFixedExpiry() {
    var s = segment(10);
    String key = "test:{expiry}:cursor";
    var r = redis.consume(key, s, 0, 2, TIMEOUT);
    assertThat(r.status()).isEqualTo(OrderNoRedisOperations.Status.SUCCESS);
    assertThat(r.instant().atZone(OrderNoFormatter.ZONE).toLocalDate()).isEqualTo(s.date());
    long expiry =
        s.date().plusDays(8).atStartOfDay(OrderNoFormatter.ZONE).toInstant().toEpochMilli();
    long ttl = client.getBucket(key).remainTimeToLive();
    assertThat(ttl)
        .isBetween(
            expiry - System.currentTimeMillis() - 2000, expiry - System.currentTimeMillis() + 2000);
  }

  @Test
  void dateMismatchNeverWritesCursor() {
    var s = segment(10);
    s = new OrderNoSegment(s.date().minusDays(1), 1, 10, s.ownerId());
    assertThat(redis.consume("test:{day}:cursor", s, 0, 2, TIMEOUT).status())
        .isEqualTo(OrderNoRedisOperations.Status.DATE_CHANGED);
    assertThat(client.getKeys().count()).isZero();
  }

  @Test
  void invalidCursorAndBoundsNeverIssueNumbers() {
    var s = segment(10);
    String key = "test:{invalid}:cursor";
    for (String value : List.of("garbage", "11", "1.5", "-1", "+1", "0001")) {
      client.getBucket(key, StringCodec.INSTANCE).set(value);
      assertThat(redis.consume(key, s, 0, 1, TIMEOUT).status())
          .isEqualTo(OrderNoRedisOperations.Status.INVALID);
      assertThat(client.getBucket(key, StringCodec.INSTANCE).get()).isEqualTo(value);
    }
    client.getKeys().delete(key);
    assertThat(redis.consume(key, s, 0, 3, TIMEOUT).status())
        .isEqualTo(OrderNoRedisOperations.Status.INVALID);
    assertThat(client.getKeys().count()).isZero();
  }

  @Test
  void luaPairCapacityDoesNotPartiallyConsume() {
    var s = segment(3);
    String key = "test:{capacity}:cursor";
    assertThat(redis.consume(key, s, 0, 2, TIMEOUT).endValue()).isEqualTo(2);
    assertThat(redis.consume(key, s, 2, 2, TIMEOUT).status())
        .isEqualTo(OrderNoRedisOperations.Status.EXHAUSTED);
    assertThat(client.getBucket(key, StringCodec.INSTANCE).get()).isEqualTo("2");
  }

  @Test
  void scriptCacheFlushReloadsExactlyUnexecutedScript() {
    var s = segment(10);
    String key = "test:{noscript}:cursor";
    assertThat(redis.consume(key, s, 0, 1, TIMEOUT).endValue()).isEqualTo(1);
    client.getScript().scriptFlush();
    assertThat(redis.consume(key, s, 1, 2, TIMEOUT).endValue()).isEqualTo(3);
  }

  @Test
  void fiveHundredPairsPerformOneDatabaseReserve() {
    var a = db.allocator(redis, 1000);
    var values = new HashSet<String>();
    for (int i = 0; i < 500; i++) {
      var n = a.prepare(OrderNoPurpose.ORDER_AND_BILL);
      values.add(n.orderNo());
      values.add(n.billno());
    }
    assertThat(values).hasSize(1000);
    assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sc_order_no_segment", Integer.class))
        .isEqualTo(1);
    assertThat(
            db.jdbc.queryForObject(
                "SELECT last_reserved_value FROM sc_order_no_sequence", Integer.class))
        .isEqualTo(1000);
  }

  @Test
  void concurrentMixedBatchesAcrossInstancesAreDisjoint() throws Exception {
    var allocators =
        List.of(db.allocator(redis, 1000), db.allocator(redis, 1000), db.allocator(redis, 1000));
    try (var workers = Executors.newFixedThreadPool(12)) {
      var gate = new CountDownLatch(1);
      List<Future<List<String>>> futures =
          IntStream.range(0, 12)
              .mapToObj(
                  i ->
                      workers.submit(
                          () -> {
                            gate.await();
                            var values = new ArrayList<String>();
                            for (int j = 0; j < 30; j++) {
                              var n =
                                  allocators
                                      .get(i % 3)
                                      .prepare(
                                          j % 2 == 0
                                              ? OrderNoPurpose.ORDER_ONLY
                                              : OrderNoPurpose.ORDER_AND_BILL);
                              values.add(n.orderNo());
                              if (n.billno() != null) values.add(n.billno());
                            }
                            return (List<String>) values;
                          }))
              .toList();
      gate.countDown();
      var values = new ArrayList<String>();
      for (var f : futures) values.addAll(f.get(30, TimeUnit.SECONDS));
      assertThat(values).hasSize(540).doesNotHaveDuplicates();
      assertThat(
              db.jdbc.queryForObject(
                  "SELECT COUNT(DISTINCT owner_id) FROM sc_order_no_segment", Integer.class))
          .isEqualTo(3);
    }
  }

  @Test
  void allocatorCursorDeletionAndRestartNeverReissue() {
    var a = db.allocator(redis, 10);
    assertThat(a.prepare(OrderNoPurpose.ORDER_AND_BILL).billno()).endsWith("00000002");
    client.getKeys().flushdb();
    assertThat(a.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000003");
    var b = db.allocator(redis, 10);
    assertThat(b.prepare(OrderNoPurpose.ORDER_ONLY).orderNo()).endsWith("00000011");
    assertThat(
            db.jdbc.queryForObject(
                "SELECT COUNT(DISTINCT owner_id) FROM sc_order_no_segment", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void responseLostAfterRealLuaAbandonsPermanentRange() {
    AtomicBoolean fail = new AtomicBoolean(true);
    OrderNoRedisOperations lossy =
        new OrderNoRedisOperations() {
          public Instant time(Duration timeout) {
            return redis.time(timeout);
          }

          public Result consume(
              String key, OrderNoSegment s, int floor, int count, Duration timeout) {
            var r = redis.consume(key, s, floor, count, timeout);
            if (fail.getAndSet(false)) throw new IllegalStateException("response lost after Lua");
            return r;
          }
        };
    var a = db.allocator(lossy, 10);
    assertThatThrownBy(() -> a.prepare(OrderNoPurpose.ORDER_AND_BILL))
        .hasMessageContaining("response lost");
    assertThat(
            client
                .getKeys()
                .getKeysStream()
                .map(k -> client.getBucket(k, StringCodec.INSTANCE).get())
                .toList())
        .containsExactly("2");
    assertThat(a.prepare(OrderNoPurpose.ORDER_AND_BILL).orderNo()).endsWith("00000011");
    assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sc_order_no_segment", Integer.class))
        .isEqualTo(2);
  }
}
