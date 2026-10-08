package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengDiagnosticProjector;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengCauseKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureReasonCode;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalOnlySerializer;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengProtectedFailure;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 华昇应用层脱敏与安全诊断契约测试。
 *
 * <p>职责：验证 {@link HuashengCallResult} 密封类族与零 Throwable 暴露、
 * {@link HuashengCallBudget} 有界预算与截止点守卫、
 * {@link HuashengSafeLog} 分钟桶限频与 1024 键容量上限/闲置退休、以及
 * 关键领域对象的脱敏 toString 与 Jackson 序列化拦截。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#184-有界-61-重试编排预算与调用门面ad-a5-06--ad-a5-08、§9。
 */
class HuashengApplicationSensitiveDiagnosticsTest {

    private HuashengExchangeEvidence createMockEvidence() {
        return new HuashengExchangeEvidence(
                HuashengEndpoint.PLACE,
                OptionalInt.of(200),
                OptionalInt.of(0),
                50L,
                120L,
                240L,
                1,
                true
        );
    }

    private HuashengFailureDiagnostics createMockDiagnostics(HuashengFailureKind kind) {
        HuashengProtectedFailure prot = HuashengProtectedFailure.capture(
                kind,
                new IllegalArgumentException("test"),
                Optional.of(createMockEvidence())
        );
        return HuashengDiagnosticProjector.toSafeDiagnostics(prot);
    }

    @Test
    @DisplayName("HuashengCallResult 密封契约：六分支互斥密封，类级与字段绝对零 Throwable 与零 ProtectedFailure")
    void testCallResultSealedPermitsAndZeroThrowable() {
        assertTrue(HuashengCallResult.class.isSealed(), "HuashengCallResult 必须为 sealed 接口");

        Class<?>[] permitted = HuashengCallResult.class.getPermittedSubclasses();
        assertNotNull(permitted);
        assertEquals(6, permitted.length, "必须恰好有六个互斥分支");

        Set<String> simpleNames = Arrays.stream(permitted).map(Class::getSimpleName).collect(Collectors.toSet());
        assertEquals(Set.of("Parsed", "BusinessError", "HttpFailure", "TransportFailure", "InvalidResponse", "LocalFailure"), simpleNames);

        for (Class<?> sub : permitted) {
            JsonSerialize jsonAnn = sub.getAnnotation(JsonSerialize.class);
            assertNotNull(jsonAnn, sub.getSimpleName() + " 必须标注 @JsonSerialize");
            assertEquals(HuashengInternalOnlySerializer.class, jsonAnn.using());

            for (Field f : sub.getDeclaredFields()) {
                assertFalse(Throwable.class.isAssignableFrom(f.getType()), sub.getSimpleName() + "." + f.getName() + " 严禁为 Throwable");
                assertFalse(HuashengProtectedFailure.class.isAssignableFrom(f.getType()), sub.getSimpleName() + "." + f.getName() + " 严禁包含 ProtectedFailure");
            }

            for (Method m : sub.getDeclaredMethods()) {
                assertFalse(Throwable.class.isAssignableFrom(m.getReturnType()), sub.getSimpleName() + "." + m.getName() + " 返回类型严禁为 Throwable");
            }
        }
    }

    @Test
    @DisplayName("脱敏与序列化拦截：所有 CallResult 分支 toString 绝无秘密，Jackson 序列化直接抛 IOException 拒绝")
    void testCallResultSerializationAndToStringZeroSecrets() {
        String secretSentinel = "MY_SUPER_SECRET_PAYLOAD_SENTINEL";
        HuashengExchangeEvidence ev = createMockEvidence();
        List<HuashengExchangeEvidence> attempts = List.of(ev);

        HuashengFailureDiagnostics diag = createMockDiagnostics(HuashengFailureKind.PREFLIGHT);

        List<HuashengCallResult<?>> allResults = List.of(
                new HuashengCallResult.Parsed<>("safe_data", attempts, true),
                new HuashengCallResult.HttpFailure<>(diag, attempts, true),
                new HuashengCallResult.TransportFailure<>(diag, attempts, true),
                new HuashengCallResult.InvalidResponse<>(diag, attempts, true),
                new HuashengCallResult.LocalFailure<>(diag, attempts, true)
        );

        ObjectMapper mapper = new ObjectMapper();

        for (HuashengCallResult<?> res : allResults) {
            String str = res.toString();
            assertFalse(str.contains(secretSentinel));
            assertThrows(IOException.class, () -> mapper.writeValueAsString(res));
            assertEquals(attempts, res.attempts());
            assertTrue(res.wasBusinessCallStarted());
            assertTrue(res.latestEvidence().isPresent());
        }
    }

    @Test
    @DisplayName("HuashengCallBudget 有界预算：业务发送次数严格限定为 [1, 2]，单调截止点原子检查")
    void testCallBudgetBoundsAndDeadline() {
        assertThrows(IllegalArgumentException.class, () -> new HuashengCallBudget(0, 1000000000L));
        assertThrows(IllegalArgumentException.class, () -> new HuashengCallBudget(3, 1000000000L));

        long deadline = 1000000000L;
        HuashengCallBudget budget = new HuashengCallBudget(2, deadline);

        assertEquals(2, budget.maxBusinessAttempts());
        assertEquals(2, budget.remainingBusinessAttempts());
        assertEquals(0, budget.usedBusinessAttempts());
        assertEquals(deadline, budget.deadlineNanos());

        // 第 1 次获取成功
        assertTrue(budget.tryAcquireBusinessAttempt(deadline - 500L));
        assertEquals(1, budget.remainingBusinessAttempts());
        assertEquals(1, budget.usedBusinessAttempts());

        // 第 2 次获取成功
        assertTrue(budget.tryAcquireBusinessAttempt(deadline - 200L));
        assertEquals(0, budget.remainingBusinessAttempts());
        assertEquals(2, budget.usedBusinessAttempts());

        // 第 3 次获取：超限失败
        assertFalse(budget.tryAcquireBusinessAttempt(deadline - 100L));

        // 截止点超期检查
        HuashengCallBudget budget2 = new HuashengCallBudget(2, deadline);
        assertTrue(budget2.isDeadlineExceeded(deadline));
        assertTrue(budget2.isDeadlineExceeded(deadline + 1L));
        assertFalse(budget2.isDeadlineExceeded(deadline - 1L));

        // 若已超期，即便剩余次数足额也返回 false
        assertFalse(budget2.tryAcquireBusinessAttempt(deadline + 10L));
    }

    @Test
    @DisplayName("HuashengSafeLog 聚合限频：同类错误按分钟桶聚合抑制，容量上限 1024，闲置 2 分钟惰性退休")
    void testSafeLogRateLimitingAndEviction() {
        AtomicLong wallMillis = new AtomicLong(1000000000L); // 分钟 16666
        Clock controllableClock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(wallMillis.get()); }
        };

        HuashengSafeLog safeLog = new HuashengSafeLog(controllableClock, 1024);

        HuashengFailureDiagnostics diag = createMockDiagnostics(HuashengFailureKind.HTTP_ERROR);

        // 连续记录 10 次同类失败
        for (int i = 0; i < 10; i++) {
            safeLog.recordFailure(diag);
        }

        // 首条记录打印，后续 9 条在当分钟被抑制
        assertEquals(9, safeLog.getSuppressedCount(HuashengEndpoint.PLACE, HuashengFailureKind.HTTP_ERROR, OptionalInt.of(0)));

        // 时间推进 1 分钟（进入下一分钟桶）
        wallMillis.addAndGet(60_000L);
        safeLog.recordFailure(diag);
        // 新分钟首条会输出前一分钟的 9 条抑制计数，当前分钟抑制计数重置为 0
        assertEquals(0, safeLog.getSuppressedCount(HuashengEndpoint.PLACE, HuashengFailureKind.HTTP_ERROR, OptionalInt.of(0)));

        // 时间推进 2 分钟闲置（无日志产生）
        wallMillis.addAndGet(120_000L);
        assertEquals(1, safeLog.activeKeyCount());

        // 再次触发任何记录时，触发惰性退休，2 分钟前的闲置 key 被清理
        HuashengFailureDiagnostics otherDiag = createMockDiagnostics(HuashengFailureKind.TRANSPORT_FAILURE);
        safeLog.recordFailure(otherDiag);

        // 原 HTTP_ERROR 键已被惰性退休移除
        assertEquals(0, safeLog.getSuppressedCount(HuashengEndpoint.PLACE, HuashengFailureKind.HTTP_ERROR, OptionalInt.of(0)));
    }
}
