package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HuashengMoneyParserTest {
    @Test void packageMoneyConstructorsRejectNullRawWithoutEchoingInput() {
        assertThrows(NullPointerException.class, () -> new HuashengPrice(null,0));
        assertThrows(NullPointerException.class, () -> new HuashengFaceAmount(null));
    }
    @Test void faceRejectsNonAsciiAndOverPrecisionOrLength() {
        for (String raw : new String[] {null, "", "1.234", "-1", "+1", "1e0", "1,000", "NaN", "Infinity", " 1", "1 ", "1\n", "\t1", "１", ".1", "1.", "0".repeat(33)}) {
            var result = assertDoesNotThrow(() -> HuashengMoneyParser.parseFaceAmount(raw));
            assertInstanceOf(HuashengInternalAttempt.Failed.class, result);
            var failure = ((HuashengInternalAttempt.Failed<HuashengFaceAmount>) result).failure();
            var safe = com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengDiagnosticProjector.toSafeDiagnostics(failure);
            assertEquals("MONEY_INVALID", safe.reasonCode().name());
            assertTrue(safe.evidence().isEmpty());
        }
    }
    @Test void facePreservesRawWithoutPriceRangeOrMinorUnits() {
        for (String raw : new String[] {"0", "1", "1.2", "1.23", "0001.20", "9".repeat(32)}) {
            var result = HuashengMoneyParser.parseFaceAmount(raw);
            assertInstanceOf(HuashengInternalAttempt.Parsed.class, result);
            assertEquals(raw, ((HuashengInternalAttempt.Parsed<HuashengFaceAmount>) result).value().rawYuan());
        }
        assertThrows(NoSuchMethodException.class, () -> HuashengFaceAmount.class.getMethod("minorUnits"));
    }
    @Test void overflowReturnsFailedWithoutWrappingOrThrowingInput() {
        for (String raw : new String[] {"92233720368547758.0750", "9".repeat(32)}) {
            var result = assertDoesNotThrow(() -> HuashengMoneyParser.parsePrice(raw));
            assertInstanceOf(HuashengInternalAttempt.Failed.class, result);
            var failure = ((HuashengInternalAttempt.Failed<HuashengPrice>) result).failure();
            var safe = com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengDiagnosticProjector.toSafeDiagnostics(failure);
            assertEquals("MONEY_INVALID", safe.failureKind().name());
            assertEquals("VALIDATION", safe.causeKind().name());
            assertTrue(safe.evidence().isEmpty());
        }
    }
    @Test void enforcesTextLengthBeforeDecimalConversion() {
        var accepted = (HuashengInternalAttempt.Parsed<HuashengPrice>) HuashengMoneyParser.parsePrice("0".repeat(32));
        assertEquals("0".repeat(32), accepted.value().rawYuan());
        assertEquals(0, accepted.value().minorUnits());
        assertInstanceOf(HuashengInternalAttempt.Failed.class, HuashengMoneyParser.parsePrice("0".repeat(33)));
    }
    @Test void rejectsPriceLexicalFormsWithoutCorrection() {
        String[] invalid = {"9.", ".9", "9.00000", "+1", "-0", "-1", "1e0", "1E+3", "1,000", "NaN", "Infinity",
                " 1", "1 ", "1\n", "\t1", "１", null, "", "SECRET_RAW_PRICE"};
        for (String raw : invalid) {
            var result = assertDoesNotThrow(() -> HuashengMoneyParser.parsePrice(raw));
            assertInstanceOf(HuashengInternalAttempt.Failed.class, result);
            var failure = ((HuashengInternalAttempt.Failed<HuashengPrice>) result).failure();
            var safe = com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengDiagnosticProjector.toSafeDiagnostics(failure);
            assertEquals("MONEY_INVALID", safe.failureKind().name());
            assertEquals("MONEY_INVALID", safe.reasonCode().name());
            assertEquals("VALIDATION", safe.causeKind().name());
            assertTrue(safe.evidence().isEmpty());
            assertFalse(safe.toString().contains("SECRET_RAW_PRICE"));
        }
    }
    @Test void roundsWholePriceHalfUpExactlyOnce() {
        String[] raw = {"0.0049", "0.0050", "9.9999", "0.0250", "92233720368547758.0749"};
        long[] cents = {0, 1, 1000, 3, Long.MAX_VALUE};
        for (int i=0; i<raw.length; i++) {
            var price = ((HuashengInternalAttempt.Parsed<HuashengPrice>) HuashengMoneyParser.parsePrice(raw[i])).value();
            assertEquals(cents[i], price.minorUnits());
            assertEquals(raw[i], price.rawYuan());
        }
    }
    @Test void minimalPricesPreserveRawIncludingZeroAndLeadingZeros() {
        String[] raw = {"0", "9.99", "0009.9900"};
        long[] expected = {0, 999, 999};
        for (int i=0; i<raw.length; i++) {
            var result = HuashengMoneyParser.parsePrice(raw[i]);
            assertInstanceOf(HuashengInternalAttempt.Parsed.class, result);
            var price = ((HuashengInternalAttempt.Parsed<HuashengPrice>) result).value();
            assertEquals(raw[i], price.rawYuan());
            assertEquals(expected[i], price.minorUnits());
        }
    }
}
