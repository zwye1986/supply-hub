package com.aifuli.module.supplychain.supplieradapter.huasheng.failure;

import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengCauseKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureKind;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureReasonCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationTicket;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengCallContext;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengEndpoint;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengTransportContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 华昇传输与失败因果保留契约测试。
 *
 * <p>职责：
 * 验证华昇失败诊断体系在 A4 本地传输扩展后的因果保留与单向安全投影能力：
 * <ul>
 *   <li>核对 {@link HuashengFailureKind} 与 {@link HuashengFailureReasonCode} 枚举追加常量的基数与次序保真；</li>
 *   <li>核对 {@link HuashengProtectedFailure} 对原始异常引用的同一性保留（assertSame）、因果链保留及序列化拒绝；</li>
 *   <li>核对 {@link HuashengDiagnosticProjector} 单向投影对异常类型的确定性映射与原异常文本零读取安全边界；</li>
 *   <li>核对 {@link HuashengFailureDiagnostics} 及其安全序列化器在包含与缺失通信证据时的正确编码。</li>
 * </ul>
 *
 * <p>不变量：
 * <ul>
 *   <li>原始 Throwable 引用在内部受保护对象中恒定保留同一实例，不丢失 cause 与 suppressed；</li>
 *   <li>公开诊断对象不保留任何原始异常引用，序列化不泄漏秘密堆栈；</li>
 *   <li>旧 8 个枚举常量名称与序号完全固定，新增 3 个常量必须追加于末尾。</li>
 * </ul>
 *
 * <p>关键决策：
 * 测试端双盲验证异常类型映射，使用自定义哨兵异常断言投影逻辑绝不调用 {@link Throwable#getMessage()} 或 {@link Throwable#toString()}。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#17-a4-真实本地-https-传输技术附表（最小方案已确认，待针对性复审）、§17.1、§17.6、§9.1、§14.2。
 */
public class HuashengTransportCauseContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("N-A4-R2-02/§17.1: 失败类别与原因码枚举必须包含11个常量且新增3项追加于末尾")
    void enumExtensionMaintainsOrdinalsAndAppendsThreeNewConstants() {
        // 验证旧 8 个常量名称与序数不变
        String[] expectedOldKinds = {
                "MONEY_INVALID", "ENVELOPE_INVALID", "DATA_MISSING", "IDENTITY_MISMATCH",
                "QUANTITY_MISMATCH", "STATE_CONFLICT", "CARD_PAYLOAD_INVALID", "RESPONSE_TOO_LARGE"
        };
        for (int i = 0; i < expectedOldKinds.length; i++) {
            assertEquals(expectedOldKinds[i], HuashengFailureKind.valueOf(expectedOldKinds[i]).name());
            assertEquals(i, HuashengFailureKind.valueOf(expectedOldKinds[i]).ordinal());
            assertEquals(expectedOldKinds[i], HuashengFailureReasonCode.valueOf(expectedOldKinds[i]).name());
            assertEquals(i, HuashengFailureReasonCode.valueOf(expectedOldKinds[i]).ordinal());
        }

        // 验证新增 3 个常量存在且顺序为 PREFLIGHT, HTTP_ERROR, TRANSPORT_FAILURE
        HuashengFailureKind preflightKind = HuashengFailureKind.valueOf("PREFLIGHT");
        HuashengFailureKind httpErrorKind = HuashengFailureKind.valueOf("HTTP_ERROR");
        HuashengFailureKind transportFailureKind = HuashengFailureKind.valueOf("TRANSPORT_FAILURE");

        assertEquals(8, preflightKind.ordinal());
        assertEquals(9, httpErrorKind.ordinal());
        assertEquals(10, transportFailureKind.ordinal());
        assertEquals(11, HuashengFailureKind.values().length);

        HuashengFailureReasonCode preflightReason = HuashengFailureReasonCode.valueOf("PREFLIGHT");
        HuashengFailureReasonCode httpErrorReason = HuashengFailureReasonCode.valueOf("HTTP_ERROR");
        HuashengFailureReasonCode transportFailureReason = HuashengFailureReasonCode.valueOf("TRANSPORT_FAILURE");

        assertEquals(8, preflightReason.ordinal());
        assertEquals(9, httpErrorReason.ordinal());
        assertEquals(10, transportFailureReason.ordinal());
        assertEquals(11, HuashengFailureReasonCode.values().length);
    }

    @Test
    @DisplayName("§17.6: HuashengProtectedFailure 必须完整保留原始异常同一引用与因果链")
    void protectedFailureRetainsExactCauseReferenceAndSuppressedChain() {
        IllegalStateException rootCause = new IllegalStateException("底层根因");
        IllegalArgumentException middleCause = new IllegalArgumentException("中间层因果", rootCause);
        IOException suppressedError = new IOException("被抑制的异常");
        middleCause.addSuppressed(suppressedError);

        HuashengFailureKind kind = HuashengFailureKind.valueOf("TRANSPORT_FAILURE");
        HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                HuashengEndpoint.TOKEN,
                OptionalInt.of(503),
                OptionalInt.empty(),
                120L,
                100L,
                0L,
                0,
                false
        );

        HuashengProtectedFailure failure = HuashengProtectedFailure.capture(kind, middleCause, Optional.of(evidence));

        assertSame(middleCause, failure.originalCauseForInternalInspection(), "原始异常必须保持同一引用");
        assertSame(rootCause, failure.originalCauseForInternalInspection().getCause(), "原始因果链必须完整保留");
        assertEquals(1, failure.originalCauseForInternalInspection().getSuppressed().length);
        assertSame(suppressedError, failure.originalCauseForInternalInspection().getSuppressed()[0], "被抑制异常引用必须完整保留");
        assertEquals(kind, failure.failureKind());
        assertTrue(failure.safeEvidence().isPresent());
        assertSame(evidence, failure.safeEvidence().get());

        // toString 必须脱敏，不输出异常 message 或 stack
        assertEquals("HuashengProtectedFailure[TRANSPORT_FAILURE]", failure.toString());
    }

    @Test
    @DisplayName("§17.6: HuashengProtectedFailure 内部类型严禁 Jackson 序列化")
    void protectedFailureRejectsJacksonSerialization() {
        HuashengProtectedFailure failure = HuashengProtectedFailure.capture(
                HuashengFailureKind.valueOf("PREFLIGHT"),
                new IllegalArgumentException("敏感参数详情"),
                Optional.empty()
        );

        IOException ex = assertThrows(IOException.class, () -> OBJECT_MAPPER.writeValueAsString(failure));
        assertTrue(ex.getMessage().contains("内部协议对象禁止序列化"), "必须抛出固定安全拒绝说明: " + ex.getMessage());
    }

    @Test
    @DisplayName("§17.6: 验证投影器对新增失败类别与各类异常的类型映射且不读取异常文本")
    void diagnosticProjectorMapsFailureTypesWithoutCallingMessageOrToString() {
        // 使用禁止调用 getMessage() 和 toString() 的哨兵异常
        class SentinelThrowable extends RuntimeException {
            @Override
            public String getMessage() {
                fail("安全防护违例：禁止在安全诊断投影时读取原始异常 getMessage()");
                return null;
            }

            @Override
            public String toString() {
                fail("安全防护违例：禁止在安全诊断投影时读取原始异常 toString()");
                return null;
            }
        }

        // 1. JsonProcessingException -> JSON_PARSE
        JsonProcessingException jsonEx = new JsonProcessingException("json syntax error") {};
        HuashengProtectedFailure f1 = HuashengProtectedFailure.capture(HuashengFailureKind.valueOf("HTTP_ERROR"), jsonEx, Optional.empty());
        HuashengFailureDiagnostics d1 = HuashengDiagnosticProjector.toSafeDiagnostics(f1);
        assertEquals(HuashengFailureKind.valueOf("HTTP_ERROR"), d1.failureKind());
        assertEquals(HuashengFailureReasonCode.valueOf("HTTP_ERROR"), d1.reasonCode());
        assertEquals(HuashengCauseKind.JSON_PARSE, d1.causeKind());

        // 2. SocketTimeoutException -> TIMEOUT
        SocketTimeoutException timeoutEx = new SocketTimeoutException("connect timed out");
        HuashengProtectedFailure f2 = HuashengProtectedFailure.capture(HuashengFailureKind.valueOf("TRANSPORT_FAILURE"), timeoutEx, Optional.empty());
        HuashengFailureDiagnostics d2 = HuashengDiagnosticProjector.toSafeDiagnostics(f2);
        assertEquals(HuashengCauseKind.TIMEOUT, d2.causeKind());

        // 3. IOException -> IO
        IOException ioEx = new IOException("broken pipe");
        HuashengProtectedFailure f3 = HuashengProtectedFailure.capture(HuashengFailureKind.valueOf("TRANSPORT_FAILURE"), ioEx, Optional.empty());
        HuashengFailureDiagnostics d3 = HuashengDiagnosticProjector.toSafeDiagnostics(f3);
        assertEquals(HuashengCauseKind.IO, d3.causeKind());

        // 4. GeneralSecurityException -> CRYPTO
        GeneralSecurityException cryptoEx = new GeneralSecurityException("bad signature");
        HuashengProtectedFailure f4 = HuashengProtectedFailure.capture(HuashengFailureKind.valueOf("PREFLIGHT"), cryptoEx, Optional.empty());
        HuashengFailureDiagnostics d4 = HuashengDiagnosticProjector.toSafeDiagnostics(f4);
        assertEquals(HuashengCauseKind.CRYPTO, d4.causeKind());

        // 5. IllegalArgumentException -> VALIDATION
        IllegalArgumentException iae = new IllegalArgumentException("invalid arg");
        HuashengProtectedFailure f5 = HuashengProtectedFailure.capture(HuashengFailureKind.valueOf("PREFLIGHT"), iae, Optional.empty());
        HuashengFailureDiagnostics d5 = HuashengDiagnosticProjector.toSafeDiagnostics(f5);
        assertEquals(HuashengCauseKind.VALIDATION, d5.causeKind());

        // 6. 哨兵异常 -> OTHER（证明不调用 getMessage / toString）
        SentinelThrowable sentinel = new SentinelThrowable();
        HuashengProtectedFailure f6 = HuashengProtectedFailure.capture(HuashengFailureKind.valueOf("TRANSPORT_FAILURE"), sentinel, Optional.empty());
        HuashengFailureDiagnostics d6 = HuashengDiagnosticProjector.toSafeDiagnostics(f6);
        assertEquals(HuashengCauseKind.OTHER, d6.causeKind());
    }

    @Test
    @DisplayName("§9.1/§17.1: HuashengFailureDiagnostics 安全序列化四字段且证据可选")
    void failureDiagnosticsSafeSerializationFormatsFourFieldsProperly() throws Exception {
        HuashengFailureDiagnostics dWithoutEvidence = new HuashengFailureDiagnostics(
                HuashengFailureKind.valueOf("PREFLIGHT"),
                HuashengFailureReasonCode.valueOf("PREFLIGHT"),
                HuashengCauseKind.VALIDATION,
                Optional.empty()
        );

        String json1 = OBJECT_MAPPER.writeValueAsString(dWithoutEvidence);
        JsonNode node1 = OBJECT_MAPPER.readTree(json1);
        assertEquals("PREFLIGHT", node1.get("failureKind").asText());
        assertEquals("PREFLIGHT", node1.get("reasonCode").asText());
        assertEquals("VALIDATION", node1.get("causeKind").asText());
        assertTrue(node1.get("evidence").isNull());

        HuashengExchangeEvidence evidence = new HuashengExchangeEvidence(
                HuashengEndpoint.PLACE,
                OptionalInt.of(200),
                OptionalInt.empty(),
                50L,
                256L,
                1024L,
                1,
                true
        );
        HuashengFailureDiagnostics dWithEvidence = new HuashengFailureDiagnostics(
                HuashengFailureKind.valueOf("TRANSPORT_FAILURE"),
                HuashengFailureReasonCode.valueOf("TRANSPORT_FAILURE"),
                HuashengCauseKind.IO,
                Optional.of(evidence)
        );

        String json2 = OBJECT_MAPPER.writeValueAsString(dWithEvidence);
        JsonNode node2 = OBJECT_MAPPER.readTree(json2);
        assertEquals("TRANSPORT_FAILURE", node2.get("failureKind").asText());
        assertEquals("TRANSPORT_FAILURE", node2.get("reasonCode").asText());
        assertEquals("IO", node2.get("causeKind").asText());
        assertFalse(node2.get("evidence").isNull());
        assertEquals("PLACE", node2.get("evidence").get("endpoint").asText());
        assertEquals(200, node2.get("evidence").get("httpStatus").asInt());
        assertEquals(1, node2.get("evidence").get("physicalSendCount").asInt());
        assertTrue(node2.get("evidence").get("wasBusinessCallStarted").asBoolean());
    }

    @Test
    @DisplayName("§17.2: HuashengTransportContext 构造、深复制与拒绝序列化契约")
    void transportContextContractAndRejectsSerialization() throws Exception {
        OperationTicket.CredentialMaterial material = new OperationTicket.CredentialMaterial(
                "1234567890123456", "privateKeyPemSentinel");
        HuashengCallContext callContext = new HuashengCallContext(
                "HUA_SHENG", 1001L, "https://hs.example.com", "1234567890123456",
                material, 3000, 5000, "OP_20261007_001", "TRACE_HS_001"
        );

        // 1. 边界参数拒绝
        assertThrows(IllegalArgumentException.class, () ->
                HuashengTransportContext.from(null, 1000L, 1));
        assertThrows(IllegalArgumentException.class, () ->
                HuashengTransportContext.from(callContext, 1000L, 0));
        assertThrows(IllegalArgumentException.class, () ->
                HuashengTransportContext.from(callContext, 1000L, 5));

        // 2. 正常构造与字段投影
        long deadline = System.nanoTime() + 10_000_000_000L;
        HuashengTransportContext transportContext = HuashengTransportContext.from(callContext, deadline, 1);

        assertEquals("HUA_SHENG", transportContext.supplierCode());
        assertEquals(1001L, transportContext.connectorVersionId());
        assertEquals("https://hs.example.com", transportContext.frozenBaseUrl());
        assertEquals("1234567890123456", transportContext.appId());
        assertEquals(3000, transportContext.connectTimeoutMs());
        assertEquals(5000, transportContext.readTimeoutMs());
        assertEquals("OP_20261007_001", transportContext.operationNo());
        assertEquals("TRACE_HS_001", transportContext.traceId());
        assertEquals(deadline, transportContext.deadlineNanos());
        assertEquals(1, transportContext.transportAttemptOrdinal());

        // 3. toString 脱敏
        assertEquals("HuashengTransportContext[redacted]", transportContext.toString());

        // 4. 拒绝序列化
        IOException ex = assertThrows(IOException.class, () -> OBJECT_MAPPER.writeValueAsString(transportContext));
        assertTrue(ex.getMessage().contains("内部协议对象禁止序列化"));
    }
}
