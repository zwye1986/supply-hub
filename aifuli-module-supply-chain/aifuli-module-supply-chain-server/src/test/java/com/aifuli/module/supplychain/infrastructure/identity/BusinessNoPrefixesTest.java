package com.aifuli.module.supplychain.infrastructure.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端分销商/项目商品编号前缀：4 字符（含下划线），保证 prefix+ULID≤32。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md}。
 */
class BusinessNoPrefixesTest {

    @Test
    void adminFrontendPrefixesAreFourCharsAndRegistered() {
        assertThat(BusinessNoPrefixes.BYR).isEqualTo("BYR_").hasSize(4);
        assertThat(BusinessNoPrefixes.BPD).isEqualTo("BPD_").hasSize(4);
        assertThat(BusinessNoPrefixes.PRJ).isEqualTo("PRJ_").hasSize(4);
        assertThat(BusinessNoPrefixes.PLT).isEqualTo("PLT_").hasSize(4);
        assertThat(BusinessNoPrefixes.SPU).isEqualTo("SPU_").hasSize(4);
        assertThat(BusinessNoPrefixes.SKU).isEqualTo("SKU_").hasSize(4);
        assertThat(BusinessNoPrefixes.BRD).isEqualTo("BRD_").hasSize(4);
        assertThat(BusinessNoPrefixes.CAT).isEqualTo("CAT_").hasSize(4);
        assertThat(BusinessNoPrefixes.PT).isEqualTo("PT_").hasSize(3);
        assertThat(BusinessNoPrefixes.ALL).contains(
                BusinessNoPrefixes.BYR, BusinessNoPrefixes.BPD,
                BusinessNoPrefixes.PRJ, BusinessNoPrefixes.PLT,
                BusinessNoPrefixes.SPU, BusinessNoPrefixes.SKU, BusinessNoPrefixes.BRD,
                BusinessNoPrefixes.CAT, BusinessNoPrefixes.PT);
    }
}
