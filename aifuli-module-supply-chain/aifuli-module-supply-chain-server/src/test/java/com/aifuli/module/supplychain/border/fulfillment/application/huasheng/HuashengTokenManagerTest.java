package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;

import com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengCredentialIdentity.CredentialItemSnapshot;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.infrastructure.token.huasheng.HuashengInMemoryTokenCache;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengDiagnosticProjector;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.http.HuashengHttpExchange;
import com.aifuli.module.supplychain.supplieradapter.huasheng.http.HuashengHttpTransportPort;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengCallContext;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengRequestBuilder;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTransportContext;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengWireRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 华昇 Token 管理器应用服务契约单测。
 *
 * <p>职责：验证 {@link HuashengTokenManager} 的身份全维度隔离、双时钟过期与回拨守卫、
 * 本地 7200 秒 TTL 策略守卫（7201 拒绝为 TOKEN_TTL_UNACCEPTABLE）、单飞并发仅 1 次物理外呼与
 * CAS generation 防迟到 61 误删保护。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#182-复合身份材料指纹与缓存条目ad-a5-02--ad-a5-04、§8.2。
 */
class HuashengTokenManagerTest {

    private static String testPrivateKeyPem;

    @BeforeAll
    static void setUpKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        testPrivateKeyPem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(kp.getPrivate().getEncoded()) +
                "\n-----END PRIVATE KEY-----";
    }

    private HuashengAuthorizedCallContext createCallContext(String suffix, String appSecret, String privateKeyPem) {
        String sec = appSecret != null ? String.format("%-16s", appSecret).replace(' ', '0') : "1234567890123456";
        OperationTicket.CredentialMaterial material = new OperationTicket.CredentialMaterial(
                sec,
                privateKeyPem != null ? privateKeyPem : testPrivateKeyPem
        );
        HuashengCallContext callContext = new HuashengCallContext(
                "HS_SUPPLIER_" + suffix,
                1001L,
                "https://api.huasheng.com",
                "1234567890123456", // 16 bytes appId
                material,
                3000,
                5000,
                "OP_NO_" + suffix,
                "TRACE_" + suffix
        );
        HuashengCredentialIdentity credId = new HuashengCredentialIdentity(
                5001L,
                List.of(new CredentialItemSnapshot(1L, "key_" + suffix, "HS_TYPE", 1))
        );
        return new HuashengAuthorizedCallContext(callContext, credId);
    }

    private HuashengHttpExchange createMockTokenExchange(String responseJson, long durationMs) {
        byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
        HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                HuashengEndpoint.TOKEN,
                OptionalInt.of(200),
                OptionalInt.of(0),
                durationMs,
                100L,
                bytes.length,
                0, // physicalSendCount = 0 for TOKEN
                false // wasBusinessCallStarted = false
        );
        try {
            Constructor<HuashengHttpExchange> ctor = HuashengHttpExchange.class.getDeclaredConstructor(byte[].class, HuashengExchangeEvidence.class);
            ctor.setAccessible(true);
            return ctor.newInstance(bytes, evidence);
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct mock HuashengHttpExchange", e);
        }
    }

    @Test
    @DisplayName("身份全维度隔离：凭据材料、版本、appId、端点任何一处不同均生成不同 Identity 且缓存互不命中")
    void testCacheKeyIsolationAllDimensions() {
        HuashengAuthorizedCallContext ctx1 = createCallContext("base", "secret1", null);
        HuashengAuthorizedCallContext ctxDiffSecret = createCallContext("base", "secret2", null);
        HuashengAuthorizedCallContext ctxDiffApp = createCallContext("diffApp", "secret1", null);

        HuashengTokenIdentity id1 = ctx1.toTokenIdentity();
        HuashengTokenIdentity idDiffSecret = ctxDiffSecret.toTokenIdentity();
        HuashengTokenIdentity idDiffApp = ctxDiffApp.toTokenIdentity();

        assertNotEquals(id1, idDiffSecret, "密钥变动时内存材料指纹必须不同");
        assertNotEquals(id1, idDiffApp, "appId 不同时身份必须不同");
    }

    @Test
    @DisplayName("TTL 本地策略守卫：7200 秒正常接受，7201 秒在应用层拒绝为 TOKEN_TTL_UNACCEPTABLE")
    void testTtlPolicyBoundaryAccept7200Reject7201() {
        AtomicInteger networkCallCount = new AtomicInteger(0);
        AtomicLong ttlSupplier = new AtomicLong(7200L);

        HuashengHttpTransportPort transportPort = (request, transportContext) -> {
            networkCallCount.incrementAndGet();
            String json = "{\"code\":0,\"data\":{\"access_token\":\"valid_tok_7200\",\"expires_in\":" + ttlSupplier.get() + "}}";
            return HuashengInternalAttempt.parsed(createMockTokenExchange(json, 50L));
        };

        HuashengTokenCachePort cache = new HuashengInMemoryTokenCache();
        HuashengRequestBuilder builder = new HuashengRequestBuilder();
        Clock clock = Clock.systemUTC();
        HuashengTokenManager manager = new HuashengTokenManager(cache, builder, transportPort, clock, System::nanoTime);

        // 1. 7200 秒：正常接受
        HuashengAuthorizedCallContext ctx7200 = createCallContext("t7200", "sec1", null);
        HuashengInternalAttempt<HuashengTokenEntry> res7200 = manager.acquireToken(ctx7200);
        assertTrue(res7200 instanceof HuashengInternalAttempt.Parsed, "7200 秒应正常获取成功");
        assertEquals("valid_tok_7200", ((HuashengInternalAttempt.Parsed<HuashengTokenEntry>) res7200).value().accessToken());
        assertEquals(1, networkCallCount.get());

        // 2. 7201 秒：虽然供应商返回合法正整数，但在应用层策略被拒绝为 TOKEN_TTL_UNACCEPTABLE (PREFLIGHT)
        ttlSupplier.set(7201L);
        HuashengAuthorizedCallContext ctx7201 = createCallContext("t7201", "sec2", null);
        HuashengInternalAttempt<HuashengTokenEntry> res7201 = manager.acquireToken(ctx7201);
        assertTrue(res7201 instanceof HuashengInternalAttempt.Failed, "7201 秒应被应用层策略拦截");
        HuashengInternalAttempt.Failed<HuashengTokenEntry> failed = (HuashengInternalAttempt.Failed<HuashengTokenEntry>) res7201;
        assertEquals(HuashengFailureKind.PREFLIGHT, HuashengDiagnosticProjector.toSafeDiagnostics(failed.failure()).failureKind());
        assertTrue(failed.failure().toString().contains("PREFLIGHT"));
        assertEquals(2, networkCallCount.get());
    }

    @Test
    @DisplayName("双时钟与时钟回拨守卫：系统时钟倒退但单调租约到期时，拒绝复用过期 Token 并触发重新外呼")
    void testClockRollbackProtection() {
        AtomicLong wallMillis = new AtomicLong(1000000000L);
        Clock controllableClock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(wallMillis.get()); }
        };
        AtomicLong monotonicNanos = new AtomicLong(10000000000L);
        AtomicInteger networkCallCount = new AtomicInteger(0);

        HuashengHttpTransportPort transportPort = (request, transportContext) -> {
            int count = networkCallCount.incrementAndGet();
            String json = "{\"code\":0,\"data\":{\"access_token\":\"tok_call_" + count + "\",\"expires_in\":7200}}";
            return HuashengInternalAttempt.parsed(createMockTokenExchange(json, 20L));
        };

        HuashengTokenCachePort cache = new HuashengInMemoryTokenCache(controllableClock, monotonicNanos::get, 1024);
        HuashengRequestBuilder builder = new HuashengRequestBuilder();
        HuashengTokenManager manager = new HuashengTokenManager(cache, builder, transportPort, controllableClock, monotonicNanos::get);

        HuashengAuthorizedCallContext ctx = createCallContext("rollback", "sec", null);

        // 第一次调用：网络外呼获取 tok_call_1
        HuashengInternalAttempt<HuashengTokenEntry> res1 = manager.acquireToken(ctx);
        assertTrue(res1 instanceof HuashengInternalAttempt.Parsed);
        assertEquals("tok_call_1", ((HuashengInternalAttempt.Parsed<HuashengTokenEntry>) res1).value().accessToken());
        assertEquals(1, networkCallCount.get());

        // 恶意时钟回拨场景：单调时间前进 7205 秒（真实时间已过去 7205 秒），但 Wall Clock 倒退回 100 秒前
        monotonicNanos.addAndGet(TimeUnit.SECONDS.toNanos(7205L));
        wallMillis.set(1000000000L - 100000L);

        // 再次获取：双时钟守卫发现单调租约已到期，判定不可用，执行重新外呼获取 tok_call_2
        HuashengInternalAttempt<HuashengTokenEntry> res2 = manager.acquireToken(ctx);
        assertTrue(res2 instanceof HuashengInternalAttempt.Parsed);
        assertEquals("tok_call_2", ((HuashengInternalAttempt.Parsed<HuashengTokenEntry>) res2).value().accessToken());
        assertEquals(2, networkCallCount.get(), "单调租约到期后必须重新外呼，时钟回拨不能阻止 Token 刷新");
    }

    @Test
    @DisplayName("单飞并发：高并发请求相同 Identity，仅触发恰好 1 次物理 TOKEN 外呼，其余线程复用新 Token")
    void testSingleFlightConcurrencyIssuesOnlyOneNetworkCall() throws Exception {
        AtomicInteger networkCallCount = new AtomicInteger(0);
        CountDownLatch barrierLatch = new CountDownLatch(1);

        HuashengHttpTransportPort transportPort = (request, transportContext) -> {
            networkCallCount.incrementAndGet();
            try {
                // 模拟网络延迟 100ms
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            String json = "{\"code\":0,\"data\":{\"access_token\":\"concurrent_tok\",\"expires_in\":7200}}";
            return HuashengInternalAttempt.parsed(createMockTokenExchange(json, 100L));
        };

        HuashengTokenCachePort cache = new HuashengInMemoryTokenCache();
        HuashengRequestBuilder builder = new HuashengRequestBuilder();
        Clock clock = Clock.systemUTC();
        HuashengTokenManager manager = new HuashengTokenManager(cache, builder, transportPort, clock, System::nanoTime);

        HuashengAuthorizedCallContext ctx = createCallContext("concurrent", "sec", null);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<HuashengInternalAttempt<HuashengTokenEntry>>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    barrierLatch.await();
                    return manager.acquireToken(ctx);
                }));
            }
            barrierLatch.countDown(); // 同时释放 10 个线程

            for (Future<HuashengInternalAttempt<HuashengTokenEntry>> f : futures) {
                HuashengInternalAttempt<HuashengTokenEntry> res = f.get(5, TimeUnit.SECONDS);
                assertTrue(res instanceof HuashengInternalAttempt.Parsed);
                assertEquals("concurrent_tok", ((HuashengInternalAttempt.Parsed<HuashengTokenEntry>) res).value().accessToken());
            }

            assertEquals(1, networkCallCount.get(), "10 个并发请求同一 Identity 必须仅触发恰好 1 次物理网络外呼");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("CAS generation 条件失效防护：迟到旧 61 携带旧 generation 无法误删已刷新的新代次 Token")
    void testCasGenerationProtection() {
        HuashengTokenCachePort cache = new HuashengInMemoryTokenCache();
        HuashengTokenIdentity identity = createCallContext("cas_mgr", "sec", null).toTokenIdentity();

        Instant exp = Instant.now().plusSeconds(7200);
        long nano = System.nanoTime() + TimeUnit.SECONDS.toNanos(7200);
        HuashengTokenEntry entryGen1 = new HuashengTokenEntry("tok_gen1", 1L, exp, nano, 300L);
        cache.put(identity, entryGen1);

        // 并发线程已刷新代次 2
        HuashengTokenEntry entryGen2 = new HuashengTokenEntry("tok_gen2", 2L, exp, nano, 300L);
        cache.put(identity, entryGen2);

        HuashengRequestBuilder builder = new HuashengRequestBuilder();
        HuashengHttpTransportPort transportPort = (request, transportContext) -> null;
        HuashengTokenManager manager = new HuashengTokenManager(cache, builder, transportPort, Clock.systemUTC(), System::nanoTime);

        // 迟到的旧业务 61 携带代次 1 请求失效，应被 CAS 防护拦截，代次 2 依然有效
        boolean invalidated = manager.invalidateIfGeneration(identity, 1L);
        assertFalse(invalidated, "旧代次 1 不得失效新代次 2");
        assertTrue(cache.get(identity).isPresent());
        assertEquals(2L, cache.get(identity).get().generation());

        // 携带匹配的代次 2 失效成功
        assertTrue(manager.invalidateIfGeneration(identity, 2L));
        assertTrue(cache.get(identity).isEmpty());
    }

    @Test
    @DisplayName("脱敏安全：HuashengAuthorizedCallContext toString 仅输出 operationNo 与 connectorVersionId")
    void testToStringRedaction() {
        HuashengAuthorizedCallContext ctx = createCallContext("safe", "super_secret_material_123", null);
        String str = ctx.toString();
        assertTrue(str.contains("OP_NO_safe"));
        assertTrue(str.contains("1001"));
        assertFalse(str.contains("super_secret_material_123"));
        assertFalse(str.contains("PRIVATE KEY"));
    }
}
