package com.aifuli.module.supplychain.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 供应商 URL 白名单与基础 SSRF 防护单测。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#17-测试与发版门禁}。</p>
 */
class SupplierEndpointPolicyTest {

    private SupplierEndpointPolicy policy;

    @BeforeEach
    void setUp() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("aifuli.supply-chain.connector.allowed-hosts", "qinameng.com,example.com");
        policy = new SupplierEndpointPolicy(environment);
    }

    @Test
    void acceptsHttpsHostOnAllowlist() {
        assertThatCode(() -> policy.validate("https://api.qinameng.com/v1"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("https://example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsHttpQueryUserInfoAndPrivateAddresses() {
        assertThatThrownBy(() -> policy.validate("http://api.qinameng.com/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("https://api.qinameng.com/v1?token=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("https://user:pass@api.qinameng.com/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("https://127.0.0.1/admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("https://192.168.1.8/internal"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("https://169.254.169.254/latest/meta-data"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate("https://localhost/secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsHostOutsideAllowlist() {
        assertThatThrownBy(() -> policy.validate("https://evil.example.net/callback"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");
    }
}
