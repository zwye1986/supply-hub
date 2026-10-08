package com.aifuli.module.supplychain.infrastructure.token.huasheng;

import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCredentialIdentity;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCredentialIdentity.CredentialItemSnapshot;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengTokenCachePort;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengTokenEntry;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengTokenIdentity;
import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengTokenPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 华昇基础设施层内存 Token 缓存契约测试。
 *
 * <p>职责：验证 {@link HuashengInMemoryTokenCache} 的键级单飞锁、CAS generation 条件失效、
 * 双时钟条目判定、1024 键容量上限溢出守卫、锁安全退休及脱敏与拒绝序列化契约。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#183-内存缓存实现与单飞锁（ad-a5-05）。
 */
class HuashengTokenCacheTest {

    private HuashengTokenIdentity createTestIdentity(String suffix) {
        List<CredentialItemSnapshot> items = List.of(
                new CredentialItemSnapshot(10L, "hs_item_key_" + suffix, "HS_TYPE", 1),
                new CredentialItemSnapshot(2L, "hs_item_key_" + suffix, "HS_TYPE", 1),
                new CredentialItemSnapshot(5L, "alpha_key_" + suffix, "HS_TYPE", 2)
        );
        return HuashengTokenIdentity.of(
                "HS_SUPPLIER",
                1001L,
                "https://api.huasheng.com",
                "HS_APP_ID_" + suffix,
                5001L,
                items,
                "testSecret123" + suffix,
                ("fakeDerBytes" + suffix).getBytes(StandardCharsets.UTF_8)
        );
    }

    @Test
    @DisplayName("基本 put 与 get：条目写入后可正确读出")
    void testBasicPutAndGet() {
        HuashengInMemoryTokenCache cache = new HuashengInMemoryTokenCache();
        HuashengTokenIdentity identity = createTestIdentity("basic");

        assertTrue(cache.get(identity).isEmpty());

        Instant expiresAt = Instant.now().plusSeconds(7200);
        long leaseDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(7200);
        HuashengTokenEntry entry = new HuashengTokenEntry("test_access_token_1", 1L, expiresAt, leaseDeadlineNanos, 300L);

        cache.put(identity, entry);

        Optional<HuashengTokenEntry> retrieved = cache.get(identity);
        assertTrue(retrieved.isPresent());
        assertEquals("test_access_token_1", retrieved.get().accessToken());
        assertEquals(1L, retrieved.get().generation());
    }

    @Test
    @DisplayName("键级单飞锁：相同 Identity 串行执行，不同 Identity 并行执行")
    void testSingleFlightLease() throws Exception {
        HuashengInMemoryTokenCache cache = new HuashengInMemoryTokenCache();
        HuashengTokenIdentity identity1 = createTestIdentity("same");
        HuashengTokenIdentity identity2 = createTestIdentity("diff");

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch enteredLatch = new CountDownLatch(1);
            CountDownLatch releaseLatch = new CountDownLatch(1);
            AtomicInteger activeSameCount = new AtomicInteger(0);
            AtomicInteger maxConcurrentSame = new AtomicInteger(0);

            // 任务 1：持有 identity1 锁
            Future<String> f1 = executor.submit(() -> cache.withIdentityLease(identity1, () -> {
                int count = activeSameCount.incrementAndGet();
                maxConcurrentSame.updateAndGet(curr -> Math.max(curr, count));
                enteredLatch.countDown();
                try {
                    releaseLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                activeSameCount.decrementAndGet();
                return "done1";
            }));

            enteredLatch.await(5, TimeUnit.SECONDS);

            // 任务 2：对同一 identity1 请求锁，必须等待任务 1 释放
            Future<String> f2 = executor.submit(() -> cache.withIdentityLease(identity1, () -> {
                int count = activeSameCount.incrementAndGet();
                maxConcurrentSame.updateAndGet(curr -> Math.max(curr, count));
                activeSameCount.decrementAndGet();
                return "done2";
            }));

            // 任务 3：对不同 identity2 请求锁，应能立即并行执行完毕，不受 identity1 阻塞
            Future<String> f3 = executor.submit(() -> cache.withIdentityLease(identity2, () -> "diffDone"));
            assertEquals("diffDone", f3.get(2, TimeUnit.SECONDS));

            // 释放任务 1
            releaseLatch.countDown();

            assertEquals("done1", f1.get(2, TimeUnit.SECONDS));
            assertEquals("done2", f2.get(2, TimeUnit.SECONDS));
            assertEquals(1, maxConcurrentSame.get(), "同一 Identity 单飞锁在任何时刻只允许至多 1 个线程执行");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("CAS generation 条件失效：代次匹配时失效成功，过期旧代次不覆盖新代次")
    void testCasGenerationInvalidation() {
        HuashengInMemoryTokenCache cache = new HuashengInMemoryTokenCache();
        HuashengTokenIdentity identity = createTestIdentity("cas");

        Instant expiresAt = Instant.now().plusSeconds(7200);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7200);
        HuashengTokenEntry entryGen1 = new HuashengTokenEntry("token_gen1", 1L, expiresAt, deadline, 300L);
        cache.put(identity, entryGen1);

        // 尝试用旧代次 0 失效代次 1，应返回 false 且 entry 保持存在
        assertFalse(cache.invalidateIfGeneration(identity, 0L));
        assertTrue(cache.get(identity).isPresent());
        assertEquals(1L, cache.get(identity).get().generation());

        // 此时并发刷新了代次 2
        HuashengTokenEntry entryGen2 = new HuashengTokenEntry("token_gen2", 2L, expiresAt, deadline, 300L);
        cache.put(identity, entryGen2);

        // 迟到的旧业务 61 携带代次 1 尝试失效，应返回 false，代次 2 的 Token 不被误删
        assertFalse(cache.invalidateIfGeneration(identity, 1L));
        assertTrue(cache.get(identity).isPresent());
        assertEquals(2L, cache.get(identity).get().generation());

        // 携带匹配的代次 2 失效，成功移除
        assertTrue(cache.invalidateIfGeneration(identity, 2L));
        assertTrue(cache.get(identity).isEmpty());
    }

    @Test
    @DisplayName("双时钟判定：Wall Clock 与 Monotonic Ticker 任一时钟到期均判定不可用或过期")
    void testDualClockUsabilityAndExpiration() {
        AtomicLong wallMillis = new AtomicLong(1000000000L);
        Clock controllableClock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(wallMillis.get()); }
        };
        AtomicLong monotonicNanos = new AtomicLong(50000000000L);

        // TTL = 7200s, refreshAhead = 300s, usableUntil = instant + 6900s
        Instant startInstant = controllableClock.instant();
        long startNano = monotonicNanos.get();
        Instant expiresAt = startInstant.plusSeconds(7200);
        long leaseDeadlineNanos = startNano + TimeUnit.SECONDS.toNanos(7200);

        HuashengTokenEntry entry = new HuashengTokenEntry("valid_token", 1L, expiresAt, leaseDeadlineNanos, 300L);

        // 刚生成时：可用且未过期
        assertTrue(entry.isUsable(controllableClock, monotonicNanos::get));
        assertFalse(entry.isExpired(controllableClock, monotonicNanos::get));

        // 时间推进 6800s：未达 refreshAhead，仍可用
        wallMillis.addAndGet(6800L * 1000L);
        monotonicNanos.addAndGet(TimeUnit.SECONDS.toNanos(6800L));
        assertTrue(entry.isUsable(controllableClock, monotonicNanos::get));
        assertFalse(entry.isExpired(controllableClock, monotonicNanos::get));

        // 时间推进到 6901s：进入 refreshAhead 窗口，变为不可用（需要刷新），但尚未绝对过期
        wallMillis.addAndGet(101L * 1000L);
        monotonicNanos.addAndGet(TimeUnit.SECONDS.toNanos(101L));
        assertFalse(entry.isUsable(controllableClock, monotonicNanos::get));
        assertFalse(entry.isExpired(controllableClock, monotonicNanos::get));

        // 时钟回拨反例验证：Wall Clock 恶意倒退回 100s 前，但单调 Ticker 已经达到 7205s（绝对超期）
        wallMillis.set(startInstant.toEpochMilli() + 100L * 1000L);
        monotonicNanos.set(startNano + TimeUnit.SECONDS.toNanos(7205L));
        // 单调 Ticker 已过，双时钟守卫彻底拦截时钟倒退攻击
        assertFalse(entry.isUsable(controllableClock, monotonicNanos::get));
        assertTrue(entry.isExpired(controllableClock, monotonicNanos::get));
    }

    @Test
    @DisplayName("容量超限守卫：满额 1024 优先清理过期项；无法腾挪时抛异常安全失败，绝不驱逐活跃锁")
    void testCapacityLimitAndSafeFailure() {
        AtomicLong wallMillis = new AtomicLong(1000000000L);
        Clock controllableClock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(wallMillis.get()); }
        };
        AtomicLong monotonicNanos = new AtomicLong(1000000000L);

        // 创建容量为 3 的小缓存供严格边界验证
        HuashengInMemoryTokenCache smallCache = new HuashengInMemoryTokenCache(controllableClock, monotonicNanos::get, 3);

        HuashengTokenIdentity id1 = createTestIdentity("cap1");
        HuashengTokenIdentity id2 = createTestIdentity("cap2");
        HuashengTokenIdentity id3 = createTestIdentity("cap3");

        // 插入 3 条，id1 设置为已过期 (expiresAt 已过)
        Instant past = controllableClock.instant().minusSeconds(10);
        long pastNano = monotonicNanos.get() - TimeUnit.SECONDS.toNanos(10);
        smallCache.put(id1, new HuashengTokenEntry("tok1", 1L, past, pastNano, 0L));

        Instant future = controllableClock.instant().plusSeconds(3600);
        long futureNano = monotonicNanos.get() + TimeUnit.SECONDS.toNanos(3600);
        smallCache.put(id2, new HuashengTokenEntry("tok2", 2L, future, futureNano, 300L));
        smallCache.put(id3, new HuashengTokenEntry("tok3", 3L, future, futureNano, 300L));

        // 插入第 4 条：由于 id1 已过期，淘汰 id1 后成功放入 id4
        HuashengTokenIdentity id4 = createTestIdentity("cap4");
        smallCache.put(id4, new HuashengTokenEntry("tok4", 4L, future, futureNano, 300L));
        assertTrue(smallCache.get(id1).isEmpty(), "已过期条目应被清理");
        assertTrue(smallCache.get(id4).isPresent());

        // 现在 id2, id3, id4 均为未过期有效条目。尝试插入第 5 条 id5：无法腾挪，必须安全失败抛出 IllegalStateException
        HuashengTokenIdentity id5 = createTestIdentity("cap5");
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                smallCache.put(id5, new HuashengTokenEntry("tok5", 5L, future, futureNano, 300L)));
        assertTrue(ex.getMessage().contains("容量超限"));
    }

    @Test
    @DisplayName("凭据项排序与脱敏：排序规则为 itemKey 升序且同 key 按 itemId 升序；toString 脱敏")
    void testCredentialIdentitySortingAndRedaction() {
        CredentialItemSnapshot i1 = new CredentialItemSnapshot(20L, "beta", "RSA", 1);
        CredentialItemSnapshot i2 = new CredentialItemSnapshot(10L, "beta", "RSA", 1);
        CredentialItemSnapshot i3 = new CredentialItemSnapshot(5L, "alpha", "AES", 2);

        HuashengCredentialIdentity credId = new HuashengCredentialIdentity(888L, List.of(i1, i2, i3));

        List<CredentialItemSnapshot> sorted = credId.sortedItems();
        assertEquals(3, sorted.size());
        assertEquals("alpha", sorted.get(0).itemKey());
        assertEquals(5L, sorted.get(0).itemId());
        assertEquals("beta", sorted.get(1).itemKey());
        assertEquals(10L, sorted.get(1).itemId());
        assertEquals("beta", sorted.get(2).itemKey());
        assertEquals(20L, sorted.get(2).itemId());

        String str = credId.toString();
        assertTrue(str.contains("888"));
        assertTrue(str.contains("3"));
        assertFalse(str.contains("alpha"));
        assertFalse(str.contains("beta"));
        assertFalse(str.contains("RSA"));
    }

    @Test
    @DisplayName("TokenPolicy 策略：MAX_ACCEPTED_TTL_SECONDS = 7200，refreshAhead 计算与 8192 字符守卫")
    void testTokenPolicy() {
        assertEquals(7200L, HuashengTokenPolicy.MAX_ACCEPTED_TTL_SECONDS);

        // refreshAhead = min(300, ttl / 10)
        assertEquals(300L, HuashengTokenPolicy.calculateRefreshAheadSeconds(7200L));
        assertEquals(300L, HuashengTokenPolicy.calculateRefreshAheadSeconds(3000L));
        assertEquals(100L, HuashengTokenPolicy.calculateRefreshAheadSeconds(1000L));
        assertEquals(5L, HuashengTokenPolicy.calculateRefreshAheadSeconds(50L));
        assertEquals(0L, HuashengTokenPolicy.calculateRefreshAheadSeconds(9L));
        assertEquals(0L, HuashengTokenPolicy.calculateRefreshAheadSeconds(1L));

        // validateTtl
        assertDoesNotThrow(() -> HuashengTokenPolicy.validateTtl(7200L));
        assertDoesNotThrow(() -> HuashengTokenPolicy.validateTtl(1L));
        assertThrows(IllegalArgumentException.class, () -> HuashengTokenPolicy.validateTtl(0L));
        assertThrows(IllegalArgumentException.class, () -> HuashengTokenPolicy.validateTtl(-10L));

        // validateAccessToken
        assertDoesNotThrow(() -> HuashengTokenPolicy.validateAccessToken("a".repeat(8192)));
        assertThrows(IllegalArgumentException.class, () -> HuashengTokenPolicy.validateAccessToken("a".repeat(8193)));
        assertThrows(IllegalArgumentException.class, () -> HuashengTokenPolicy.validateAccessToken("tok\n123"));
        assertThrows(IllegalArgumentException.class, () -> HuashengTokenPolicy.validateAccessToken("tok\r123"));
        assertThrows(IllegalArgumentException.class, () -> HuashengTokenPolicy.validateAccessToken("tok\0123"));
        assertThrows(IllegalArgumentException.class, () -> HuashengTokenPolicy.validateAccessToken(null));
        assertThrows(IllegalArgumentException.class, () -> HuashengTokenPolicy.validateAccessToken(""));
    }

    @Test
    @DisplayName("安全边界：TokenEntry 与 TokenIdentity 拒绝序列化且 toString 绝无秘密")
    void testSecurityRedactionAndSerializationRejection() {
        HuashengTokenIdentity identity = createTestIdentity("sec");
        HuashengTokenEntry entry = new HuashengTokenEntry("super_secret_token_val", 1L,
                Instant.now().plusSeconds(3600), System.nanoTime() + 1000000000L, 300L);

        assertEquals("HuashengTokenEntry[redacted]", entry.toString());
        assertFalse(entry.toString().contains("super_secret_token_val"));

        String idStr = identity.toString();
        assertFalse(idStr.contains("testSecret123sec"));
        assertFalse(idStr.contains("fakeDerBytes"));

        ObjectMapper mapper = new ObjectMapper();
        assertThrows(IOException.class, () -> mapper.writeValueAsString(entry));
        assertThrows(IOException.class, () -> mapper.writeValueAsString(identity));
    }
}
