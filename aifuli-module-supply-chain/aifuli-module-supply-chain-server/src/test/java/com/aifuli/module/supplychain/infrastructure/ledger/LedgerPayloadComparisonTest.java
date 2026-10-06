package com.aifuli.module.supplychain.infrastructure.ledger;

import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import static org.junit.jupiter.api.Assertions.*;

/** §6.1 LEGACY 比较纯逻辑回归；不证明真实源归属。 */
class LedgerPayloadComparisonTest {
    private final DefaultAppendScheduleIdentityLedgerService service =
            new DefaultAppendScheduleIdentityLedgerService(null);

    private boolean matches(String left, String right) {
        try {
            var method = DefaultAppendScheduleIdentityLedgerService.class.getDeclaredMethod(
                    "isPayloadMatch", String.class, String.class, String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(service, left, right, "LEGACY_JSON_V1");
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void canonicalPayloadBytesAreDetachedAndValuesAreNotNormalized() {
        byte[] bytes = LedgerJsonEncoding.canonicalBytes("{\"a\":[\" é \",null,true,1.0]}");
        byte[] frozen = bytes.clone();
        bytes[0] = 0;
        assertArrayEquals(frozen, LedgerJsonEncoding.canonicalBytes("{\"a\":[\" é \",null,true,1]}"));
        assertFalse(java.util.Arrays.equals(frozen, LedgerJsonEncoding.canonicalBytes("{\"a\":[\" é \",null,true,1]}")));
        assertFalse(java.util.Arrays.equals(LedgerJsonEncoding.canonicalBytes("{\"a\":null}"), LedgerJsonEncoding.canonicalBytes("{}")));
    }

    @Test
    void localCodecDoesNotMutateSharedMapperConfiguration() {
        var shared = com.aifuli.framework.common.util.json.JsonUtils.getObjectMapper();
        var serialization = shared.getSerializationConfig();
        var deserialization = shared.getDeserializationConfig();
        int parserFeatures = shared.getFactory().getParserFeatures();
        LedgerJsonEncoding.canonicalBytes("{\"n\":1.00000000000000000001}");
        assertSame(serialization, shared.getSerializationConfig());
        assertSame(deserialization, shared.getDeserializationConfig());
        assertEquals(parserFeatures, shared.getFactory().getParserFeatures());
    }

    @Test
    void malformedNullOrDuplicateEscapedNamesAreRejected() {
        for (String invalid : new String[]{null, "{\"a\":1,\"\\u0061\":2}", "[\"\\uD800x\"]", "[\"x\\uDC00\"]"}) {
            assertNotNull(assertThrows(SystemIntegrityException.class, () -> matches(invalid, invalid)).getCause());
        }
    }

    @Test
    void baseEncodingUsesCodePointOrderUtf8EscapesAndPlainExactDecimals() throws Exception {
        var method = java.util.Arrays.stream(LedgerJsonEncoding.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("canonicalBytes")).findFirst();
        assertTrue(method.isPresent(), "缺少已冻结的基础规范编码入口");
        method.get().setAccessible(true);
        String input = "{\"\\uD800\\uDC00\":1e0,\"\\uE000\":-0.0,\"a\":\"\\n\\t\\b\\f\\r\\u0001\\\"\\\\/é😀\",\"arr\":[1.2300,9007199254740993,null]}";
        String expected = "{\"a\":\"\\u000a\\u0009\\u0008\\u000c\\u000d\\u0001\\\"\\\\/é😀\",\"arr\":[1.23,9007199254740993,null],\"\uE000\":0,\"𐀀\":1}";
        assertArrayEquals(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                (byte[]) method.get().invoke(null, input));
    }

    @Test
    void duplicateKeysAndUnpairedSurrogatesAreRejectedWithCause() {
        for (String invalid : new String[]{"{\"n\":1,\"n\":1}",
                "{\"nested\":{\"n\":1,\"n\":2}}", "\"\\uD800\"", "\"\\uDC00\"",
                "{\"\\uD800\":1}", "\"" + '\uD800' + "\""}) {
            var error = assertThrows(SystemIntegrityException.class, () -> matches(invalid, invalid));
            assertNotNull(error.getCause());
        }
        assertTrue(matches("\"\\uD83D\\uDE00\"", "\"😀\""));
    }

    @Test
    void identicalInvalidDocumentsAreRejectedWithCause() {
        for (String invalid : new String[]{"{", "", " ", "null null", "1 true", "NaN", "Infinity"}) {
            var error = assertThrows(SystemIntegrityException.class, () -> matches(invalid, invalid), invalid);
            assertNotNull(error.getCause());
        }
    }

    @Test
    void legacyPreservesAllFieldsTypesArraysAndOldTimeText() {
        assertTrue(matches("{\"traceId\":\"x\",\"oldUnknown\":true}", "{\"oldUnknown\":true,\"traceId\":\"x\"}"));
        assertFalse(matches("{\"traceId\":\"x\"}", "{\"traceId\":\"y\"}"));
        assertFalse(matches("[1,2]", "[2,1]"));
        assertFalse(matches("1", "\"1\""));
        assertFalse(matches("true", "\"true\""));
        assertFalse(matches("null", "false"));
        assertFalse(matches("{\"n\":null}", "{}"));
        assertFalse(matches("\"2026-10-05T01:00:00\"", "\"2026-10-05T01:00:00.000\""));
        assertFalse(matches("\"é\"", "\"é\""));
    }

    @Test
    void legacyNumbersUseExactDecimalValue() {
        assertTrue(matches("{\"n\":1}", "{\"n\":1.0}"));
        assertTrue(matches("{\"n\":1.0}", "{\"n\":1e0}"));
        assertTrue(matches("{\"n\":9007199254740993}", "{\"n\":9007199254740993.000}"));
        assertFalse(matches("{\"n\":9007199254740992.0}", "{\"n\":9007199254740993.0}"));
        assertTrue(matches("-0.00", "0"));
    }
}
