package com.aifuli.module.supplychain.border.instore.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HOME 企业组配置默认值与启动期失败关闭。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#91-home-首页入口}。</p>
 */
class InstoreHomeEntryPropertiesTest {

    @Test
    void defaultsToConfiguredTestCompanyCode() {
        InstoreHomeEntryProperties properties = new InstoreHomeEntryProperties();
        properties.validate();
        assertThat(properties.getCompanyCode())
                .isEqualTo(InstoreHomeEntryProperties.DEFAULT_COMPANY_CODE)
                .isEqualTo("L0000A");
    }

    @Test
    void trimsWhitespaceAroundConfiguredCompanyCode() {
        InstoreHomeEntryProperties properties = new InstoreHomeEntryProperties();
        properties.setCompanyCode("  L0000A  ");
        properties.validate();
        assertThat(properties.getCompanyCode()).isEqualTo("L0000A");
    }

    @Test
    void rejectsBlankCompanyCode() {
        InstoreHomeEntryProperties properties = new InstoreHomeEntryProperties();
        properties.setCompanyCode(" ");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("HOME 入口 companyCode 配置无效");
    }

    @Test
    void rejectsOversizedCompanyCode() {
        InstoreHomeEntryProperties properties = new InstoreHomeEntryProperties();
        properties.setCompanyCode("c".repeat(InstoreHomeEntryProperties.MAX_COMPANY_CODE_LENGTH + 1));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("HOME 入口 companyCode 配置无效");
    }
}
