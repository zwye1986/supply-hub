package com.aifuli.module.supplychain.infrastructure.ledger;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Q-R3-01-01：只使用安全的小展开反例，巨大指数待保护生效后才测试。 */
class LedgerJsonBudgetTest {
    @Test void inputUtf8BoundaryIncludesMultibyteAndRejectsAbove() {
        String equal = "\"" + "é".repeat(524287) + "\"";
        assertEquals(1048576, LedgerJsonEncoding.canonicalBytes(equal).length);
        budget("INPUT_UTF8", "\"" + "é".repeat(524288) + "\"");
    }
    @Test void depthAndEveryTokenHaveInclusiveBoundaries() {
        assertEquals(128 + 1, LedgerJsonEncoding.canonicalBytes("[".repeat(64) + "0" + "]".repeat(64)).length);
        budget("DEPTH", "[".repeat(65) + "0" + "]".repeat(65));
    }
    @Test void allTokensHaveInclusiveBoundary() {
        String equal = "[" + "0,".repeat(65533) + "0]";
        assertEquals(131069, LedgerJsonEncoding.canonicalBytes(equal).length);
        budget("TOKENS", "[" + "0,".repeat(65534) + "0]");
    }
    @Test void originalNumberLexemeCountsSignAndExponent() {
        assertEquals(1000, LedgerJsonEncoding.canonicalBytes("1".repeat(1000)).length);
        budget("NUMBER_LEXEME", "-" + "1".repeat(1000));
        budget("NUMBER_LEXEME", "1." + "0".repeat(995) + "e+00");
    }
    private static void budget(String reason, String input) {
        var e = assertThrows(SystemIntegrityException.class, () -> LedgerJsonEncoding.canonicalBytes(input));
        assertNotNull(e.getCause());
        assertEquals("JSON预算超限: " + reason, e.getCause().getMessage());
    }
    @Test void totalOutputIsCheckedBeforeAnyExpansion() {
        String prefix = "[" + "1e4095,".repeat(1023);
        String equal = prefix + "\"" + "a".repeat(3069) + "\"]";
        assertEquals(4194304, LedgerJsonEncoding.canonicalBytes(equal).length);
        budget("OUTPUT_UTF8", prefix + "\"" + "a".repeat(3070) + "\"]");
    }
    @Test void hugeExponentsAndScaleExtremesAreRejectedWithoutExpansion() {
        long start = System.nanoTime();
        for (String input : new String[]{"1e1000000000", "1e-1000000000", "-1e2147483647",
                "10e2147483647", "1e-2147483647"}) budget("NUMBER_OUTPUT", input);
        for (String input : new String[]{"0e1000000000", "-0e-1000000000", "0.00"})
            assertEquals("0", new String(LedgerJsonEncoding.canonicalBytes(input), java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("Q-R3-01-01 guarded exponent nanos=" + (System.nanoTime() - start));
    }
    @Test void signedFractionalNumberBoundariesAndEscapesRemainExact() {
        assertEquals(4096, LedgerJsonEncoding.canonicalBytes("-1e4094").length);
        budget("NUMBER_OUTPUT", "-1e4095");
        assertEquals(4096, LedgerJsonEncoding.canonicalBytes("1e-4094").length);
        budget("NUMBER_OUTPUT", "1e-4095");
        assertEquals("[-1.23,0.001,100,0]", new String(LedgerJsonEncoding.canonicalBytes("[-1.2300,1e-3,100.00,-0.0]"), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("\"\\u000a\\u0001é😀\\\"\\\\\"", new String(LedgerJsonEncoding.canonicalBytes("\"\\n\\u0001é😀\\\"\\\\\""), java.nio.charset.StandardCharsets.UTF_8));
        String object = "{\"é😀\":\"\\n\"}";
        assertEquals(19, LedgerJsonEncoding.canonicalBytes(object).length);
    }
    @Test void fieldNamesAndContainerEndsAreTokens() {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < 32767; i++) { if (i > 0) b.append(','); b.append('"').append(i).append("\":0"); }
        b.append('}');
        assertNotNull(LedgerJsonEncoding.canonicalBytes(b.toString()));
        budget("TOKENS", b.substring(0, b.length() - 1) + ",\"extra\":0}");
    }
    @Test void budgetReasonsDoNotContainPayload() {
        var e = assertThrows(SystemIntegrityException.class, () -> LedgerJsonEncoding.canonicalBytes("{\"PRIVATE_SENTINEL\":1e4096}"));
        assertFalse(e.getMessage().contains("PRIVATE_SENTINEL"));
        assertEquals("JSON预算超限: NUMBER_OUTPUT", e.getCause().getMessage());
    }
    @Test void plainNumberBoundaryIsCheckedBeforeExpansion() {
        assertEquals(4096, LedgerJsonEncoding.canonicalBytes("1e4095").length);
        var error = assertThrows(SystemIntegrityException.class,
                () -> LedgerJsonEncoding.canonicalBytes("1e4096"));
        assertNotNull(error.getCause());
        assertEquals("JSON预算超限: NUMBER_OUTPUT", error.getCause().getMessage());
    }
}
