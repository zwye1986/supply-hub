package com.aifuli.module.supplychain.infrastructure.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三位业务编码格式与父段校验。
 * 设计见 docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#2-编码结构与容量。
 */
class ScopedBusinessCodeFormatTest {

    @Test
    void formatsRootAndChildWithThreeDigitSegments() {
        assertThat(ScopedBusinessCodeFormat.root("SPU", 1)).isEqualTo("SPU_001");
        assertThat(ScopedBusinessCodeFormat.child("SKU", 2, 1)).isEqualTo("SKU_002_001");
        assertThat(ScopedBusinessCodeFormat.child("PRJ", 999, 999)).isEqualTo("PRJ_999_999");
    }

    @Test
    void rejectsZeroOverflowAndWrongParentSegmentWithSpecificHint() {
        assertThatThrownBy(() -> ScopedBusinessCodeFormat.parseRoot("SPU", "SPU_000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPU_001");
        assertThatThrownBy(() -> ScopedBusinessCodeFormat.parseRoot("SPU", "spu_001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPU_001");
        assertThatThrownBy(() -> ScopedBusinessCodeFormat.parseChild("SKU", 1, "SKU_002_001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("父段 002")
                .hasMessageContaining("内部短序号 001");
        assertThatThrownBy(() -> ScopedBusinessCodeFormat.child("SKU", 1, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    void legacyRootCandidateIsParsedWithoutUsingExceptionsForOldFormats() {
        assertThat(ScopedBusinessCodeFormat.preferredRoot("SPU", "SPU_100")).isEqualTo(100);
        assertThat(ScopedBusinessCodeFormat.preferredRoot("SPU", "SPU_LEGACY")).isNull();
        assertThat(ScopedBusinessCodeFormat.preferredRoot("SPU", null)).isNull();
    }
}
