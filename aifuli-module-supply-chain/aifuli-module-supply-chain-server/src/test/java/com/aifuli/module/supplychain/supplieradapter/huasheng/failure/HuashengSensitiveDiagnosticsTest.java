package com.aifuli.module.supplychain.supplieradapter.huasheng.failure;

import com.aifuli.module.supplychain.supplieradapter.huasheng.HuashengCrypto;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.util.*;
import static com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.*;
import static org.junit.jupiter.api.Assertions.*;

/** 两个独立边界：内部保真与外部安全。见设计 §9.1、§14。 */
class HuashengSensitiveDiagnosticsTest {
    @Test void safeExpressionsOmitPayloadAndRetainEmptyEvidenceValues() throws Exception {
        var price=((HuashengInternalAttempt.Parsed<HuashengPrice>)HuashengMoneyParser.parsePrice("0009.9900")).value();
        var face=((HuashengInternalAttempt.Parsed<HuashengFaceAmount>)HuashengMoneyParser.parseFaceAmount("9".repeat(32))).value();
        assertEquals("HuashengPrice[redacted]",price.toString());
        assertEquals("HuashengFaceAmount[redacted]",face.toString());
        assertEquals("HuashengInternalAttempt[parsed]",HuashengInternalAttempt.parsed(SECRET).toString());
        assertEquals("HuashengInternalAttempt[failed]",HuashengInternalAttempt.failed(capture(new IOException(SECRET))).toString());
        assertFalse(capture(new IOException(SECRET)).toString().contains(SECRET));
        var evidence=new HuashengExchangeEvidence(HuashengEndpoint.TOKEN,OptionalInt.empty(),OptionalInt.empty(),0,0,0,0,false);
        for (ObjectMapper mapper : new ObjectMapper[] {new ObjectMapper(),new ObjectMapper().setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)}) {
            var tree=mapper.readTree(mapper.writeValueAsString(evidence));
            assertEquals(8,tree.size()); assertTrue(tree.get("httpStatus").isNull()); assertTrue(tree.get("rawBusinessCode").isNull());
            assertEquals(0,tree.get("physicalSendCount").intValue()); assertFalse(tree.get("wasBusinessCallStarted").booleanValue());
        }
        assertEquals(List.of("TOKEN","PLACE","CONFIRM","QUERY","TAKE"),Arrays.stream(HuashengEndpoint.values()).map(Enum::name).toList());
    }
    @Test void factoriesRejectNullReferencesAndEvidenceRejectsNegativeMetrics() {
        assertThrows(RuntimeException.class, () -> HuashengInternalAttempt.parsed(null));
        assertThrows(RuntimeException.class, () -> HuashengInternalAttempt.failed(null));
        assertThrows(RuntimeException.class, () -> HuashengProtectedFailure.capture(null,new IOException(SECRET),Optional.empty()));
        assertThrows(RuntimeException.class, () -> HuashengProtectedFailure.capture(HuashengFailureKind.MONEY_INVALID,null,Optional.empty()));
        assertThrows(RuntimeException.class, () -> HuashengProtectedFailure.capture(HuashengFailureKind.MONEY_INVALID,new IOException(SECRET),null));
        assertThrows(RuntimeException.class, () -> HuashengDiagnosticProjector.toSafeDiagnostics(null));
        assertThrows(RuntimeException.class, () -> com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengProtocolException.fromDiagnostics(null));
        assertThrows(RuntimeException.class, () -> new HuashengFailureDiagnostics(null,HuashengFailureReasonCode.MONEY_INVALID,HuashengCauseKind.IO,Optional.empty()));
        assertThrows(RuntimeException.class, () -> new HuashengFailureDiagnostics(HuashengFailureKind.MONEY_INVALID,null,HuashengCauseKind.IO,Optional.empty()));
        assertThrows(RuntimeException.class, () -> new HuashengFailureDiagnostics(HuashengFailureKind.MONEY_INVALID,HuashengFailureReasonCode.MONEY_INVALID,null,Optional.empty()));
        assertThrows(RuntimeException.class, () -> new HuashengFailureDiagnostics(HuashengFailureKind.MONEY_INVALID,HuashengFailureReasonCode.MONEY_INVALID,HuashengCauseKind.IO,null));
        for (int missing=0;missing<3;missing++) {
            int absent=missing;
            assertThrows(RuntimeException.class, () -> new HuashengExchangeEvidence(absent==0?null:HuashengEndpoint.TOKEN,absent==1?null:OptionalInt.empty(),absent==2?null:OptionalInt.empty(),0,0,0,0,false));
        }
        for (int negative=0;negative<4;negative++) {
            int metric=negative;
            assertThrows(IllegalArgumentException.class, () -> new HuashengExchangeEvidence(HuashengEndpoint.TOKEN,OptionalInt.empty(),OptionalInt.empty(),metric==0?-1:0,metric==1?-1:0,metric==2?-1:0,metric==3?-1:0,false));
        }
    }
    @Test void publicExceptionCannotAttachRawCauseOrSuppressedAndSerializesDiagnostics() throws Exception {
        Throwable raw=new IOException(SECRET);
        var safe=HuashengDiagnosticProjector.toSafeDiagnostics(capture(raw));
        var exception=com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengProtocolException.fromDiagnostics(safe);
        assertSame(safe,exception.diagnostics());
        assertNull(exception.getCause());
        assertThrows(IllegalStateException.class, () -> exception.initCause(raw));
        exception.addSuppressed(raw);
        assertEquals(0,exception.getSuppressed().length);
        assertFalse(exception.getMessage().contains(SECRET)); assertFalse(exception.toString().contains(SECRET));
        for (ObjectMapper mapper : new ObjectMapper[] {new ObjectMapper(), new ObjectMapper().setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD, com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)}) {
            String text=mapper.writeValueAsString(exception);
            assertFalse(text.contains(SECRET));
            assertEquals("IO",mapper.readTree(text).get("diagnostics").get("causeKind").asText());
        }
        StringWriter stack=new StringWriter(); exception.printStackTrace(new PrintWriter(stack)); assertFalse(stack.toString().contains(SECRET));
    }
    @Test void initCauseRejectionNeverEchoesRawThrowableAndKeepsSafeState() throws Exception {
        Throwable raw=new IOException(SECRET);
        var safe=HuashengDiagnosticProjector.toSafeDiagnostics(capture(raw));
        var exception=com.aifuli.module.supplychain.border.fulfillment.application.huasheng.HuashengProtocolException.fromDiagnostics(safe);
        // 拒绝挂载原链时，新公开拒绝异常文本不得复制原Throwable.toString（Q-SEC-01）
        IllegalStateException rejection=assertThrows(IllegalStateException.class, () -> exception.initCause(raw));
        assertFalse(rejection.getMessage().contains(SECRET));
        assertEquals("不允许覆盖安全异常的原始原因", rejection.getMessage());
        assertFalse(rejection.toString().contains(SECRET));
        StringWriter rejectionStack=new StringWriter(); rejection.printStackTrace(new PrintWriter(rejectionStack));
        assertFalse(rejectionStack.toString().contains(SECRET));
        // 恶意Throwable.toString在拒绝路径上真实不被调用
        Throwable hostile=new HostileInitCauseException();
        IllegalStateException hostileRejection=assertThrows(IllegalStateException.class, () -> exception.initCause(hostile));
        assertEquals("不允许覆盖安全异常的原始原因", hostileRejection.getMessage());
        assertThrows(IllegalStateException.class, () -> exception.initCause(null));
        // 原始安全异常状态不变：cause==null、suppressed空、安全序列化行为不变
        assertNull(exception.getCause());
        assertEquals(0,exception.getSuppressed().length);
        assertSame(safe,exception.diagnostics());
        for (ObjectMapper mapper : new ObjectMapper[] {new ObjectMapper(), new ObjectMapper().setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD, com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)}) {
            String text=mapper.writeValueAsString(exception);
            assertFalse(text.contains(SECRET));
            assertEquals("IO",mapper.readTree(text).get("diagnostics").get("causeKind").asText());
        }
        StringWriter safeStack=new StringWriter(); exception.printStackTrace(new PrintWriter(safeStack)); assertFalse(safeStack.toString().contains(SECRET));
    }
    @Test void internalSerializationRejectsBeforeAnyFieldOrSecretIsWritten() throws Exception {
        var failure=capture(new IOException(SECRET));
        Object[] objects={failure, HuashengInternalAttempt.parsed(SECRET), HuashengInternalAttempt.failed(failure)};
        for (ObjectMapper mapper : new ObjectMapper[] {new ObjectMapper(), new ObjectMapper().setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD, com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)}) {
            for (Object value : objects) {
                StringWriter out=new StringWriter();
                Throwable rejection=assertThrows(Exception.class, () -> mapper.writeValue(out,value));
                assertTrue(rejection.getMessage().contains("内部协议对象禁止序列化"));
                for (Throwable current=rejection; current!=null; current=current.getCause()) {
                    assertFalse(String.valueOf(current.getMessage()).contains(SECRET));
                    assertFalse(current.toString().contains(SECRET));
                }
                assertEquals("",out.toString());
            }
        }
    }
    @Test void safeDiagnosticsSerializeFourRealFieldsWithEmptyAndPresentEvidence() throws Exception {
        var evidence = new HuashengExchangeEvidence(HuashengEndpoint.QUERY, OptionalInt.of(299), OptionalInt.of(987654), 7, 11, 13, 2, true);
        for (ObjectMapper mapper : new ObjectMapper[] {new ObjectMapper(), new ObjectMapper().setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD, com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)}) {
            for (Optional<HuashengExchangeEvidence> supplied : java.util.List.of(Optional.<HuashengExchangeEvidence>empty(), Optional.of(evidence))) {
                var failure = HuashengProtectedFailure.capture(HuashengFailureKind.MONEY_INVALID, new IOException(SECRET), supplied);
                var safe = HuashengDiagnosticProjector.toSafeDiagnostics(failure);
                assertEquals(supplied, safe.evidence());
                String text = assertDoesNotThrow(() -> mapper.writeValueAsString(safe));
                assertFalse(text.contains(SECRET));
                JsonNode tree = mapper.readTree(text);
                assertEquals(java.util.Set.of("failureKind", "reasonCode", "causeKind", "evidence"), fieldNames(tree));
                assertEquals("MONEY_INVALID", tree.get("failureKind").asText());
                assertEquals("MONEY_INVALID", tree.get("reasonCode").asText());
                assertEquals("IO", tree.get("causeKind").asText());
                if (supplied.isEmpty()) assertTrue(tree.get("evidence").isNull());
                else {
                    JsonNode e=tree.get("evidence");
                    assertEquals(java.util.Set.of("endpoint", "httpStatus", "rawBusinessCode", "durationMs", "requestBytes", "responseBytes", "physicalSendCount", "wasBusinessCallStarted"), fieldNames(e));
                    assertEquals("QUERY",e.get("endpoint").asText()); assertEquals(299,e.get("httpStatus").intValue());
                    assertEquals(987654,e.get("rawBusinessCode").intValue()); assertEquals(7,e.get("durationMs").longValue());
                    assertEquals(11,e.get("requestBytes").longValue()); assertEquals(13,e.get("responseBytes").longValue());
                    assertEquals(2,e.get("physicalSendCount").intValue()); assertTrue(e.get("wasBusinessCallStarted").booleanValue());
                }
            }
        }
    }
    private static java.util.Set<String> fieldNames(JsonNode tree) {
        java.util.Set<String> names=new java.util.HashSet<>(); tree.fieldNames().forEachRemaining(names::add); return names;
    }
    private static final String SECRET = "HS_SENTINEL_APPSECRET_PRIVATEKEY_TOKEN_PHONE_BODY_HEX_SIGN_CODE_PASS_CRC_URL_URLPASS_VALIDTIME";
    private static HuashengProtectedFailure capture(Throwable cause) {
        return HuashengProtectedFailure.capture(HuashengFailureKind.MONEY_INVALID, cause, Optional.empty());
    }
    @Test void preservesActualArithmeticCauseAndSuppressedByIdentity() {
        ArithmeticException raw = assertThrows(ArithmeticException.class,
                () -> new java.math.BigDecimal("92233720368547758.0750").movePointRight(2)
                        .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact());
        Throwable nested = new IOException(SECRET);
        raw.initCause(nested);
        Throwable suppressed = new IllegalArgumentException(SECRET);
        raw.addSuppressed(suppressed);
        var protectedFailure = capture(raw);
        assertSame(raw, protectedFailure.originalCauseForInternalInspection());
        HuashengDiagnosticProjector.toSafeDiagnostics(protectedFailure);
        assertSame(raw, protectedFailure.originalCauseForInternalInspection());
        assertSame(nested, raw.getCause());
        assertSame(suppressed, raw.getSuppressed()[0]);
        var result = (HuashengInternalAttempt.Failed<HuashengPrice>) HuashengMoneyParser.parsePrice("92233720368547758.0750");
        assertInstanceOf(ArithmeticException.class, result.failure().originalCauseForInternalInspection());
    }
    @Test void classifiesDirectTypesWithoutReadingMessagesOrTraversingCauses() throws Exception {
        Throwable json;
        try (JsonParser parser = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
                .createParser("{\""+SECRET+"\":1,\""+SECRET+"\":2}")) {
            json = assertThrows(JsonProcessingException.class, () -> { while (parser.nextToken()!=null) { } });
        }
        assertTrue(json.getMessage().contains(SECRET));
        Throwable crypto = assertThrows(IllegalArgumentException.class, () -> HuashengCrypto.loadPkcs8PrivateKey("AQID"));
        assertInstanceOf(GeneralSecurityException.class, crypto.getCause());
        Throwable[] raw = {json, new SocketTimeoutException(SECRET), new IOException(SECRET),
                new GeneralSecurityException(SECRET), new IllegalArgumentException(SECRET), new ArithmeticException(SECRET),
                crypto, new RuntimeException(SECRET, new SocketTimeoutException(SECRET)), new HostileException()};
        HuashengCauseKind[] expected = {HuashengCauseKind.JSON_PARSE, HuashengCauseKind.TIMEOUT, HuashengCauseKind.IO,
                HuashengCauseKind.CRYPTO, HuashengCauseKind.VALIDATION, HuashengCauseKind.VALIDATION,
                HuashengCauseKind.VALIDATION, HuashengCauseKind.OTHER, HuashengCauseKind.OTHER};
        for (int i=0; i<raw.length; i++) {
            var failure = capture(raw[i]);
            var safe = HuashengDiagnosticProjector.toSafeDiagnostics(failure);
            assertEquals(expected[i], safe.causeKind());
            assertSame(raw[i], failure.originalCauseForInternalInspection());
            assertFalse(safe.toString().contains(SECRET));
        }
    }
    private static class HostileException extends RuntimeException {
        @Override public String getMessage() { throw new AssertionError("不得读取原message"); }
        @Override public String toString() { throw new AssertionError("不得读取原toString"); }
        @Override public synchronized Throwable getCause() { throw new AssertionError("不得遍历原cause"); }
        @Override public StackTraceElement[] getStackTrace() { throw new AssertionError("不得读取原stack"); }
    }
    /** 恶意原因夹具：任何读取（message/toString/cause/stack）都立即失败，证明 initCause 拒绝路径不调用入参。 */
    private static class HostileInitCauseException extends RuntimeException {
        @Override public String getMessage() { throw new AssertionError("不得读取入参message"); }
        @Override public String toString() { throw new AssertionError("不得读取入参toString"); }
        @Override public synchronized Throwable getCause() { throw new AssertionError("不得读取入参cause"); }
        @Override public StackTraceElement[] getStackTrace() { throw new AssertionError("不得读取入参stack"); }
    }
}
