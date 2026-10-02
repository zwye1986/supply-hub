package com.aifuli.module.supplychain.infrastructure.auth;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** 项目密钥发码契约，见 docs/superpowers/specs/2026-09-29-access-config-backend-impl-design.md#31-发码材料与安全生命周期。 */
class LocalEnvelopeAppKeySecretIssuerTest {

    @Test
    void issuesDistinctRandomKeysThatAuthenticateWithExistingCodec() {
        byte[] masterKey = new byte[32];
        LocalEnvelopeAppKeySecretIssuer issuer = new LocalEnvelopeAppKeySecretIssuer(masterKey);

        AppKeySecretIssuer.IssueMaterial first = issuer.issue("AP_01", 1);
        AppKeySecretIssuer.IssueMaterial second = issuer.issue("AP_01", 2);

        assertThat(Base64.getUrlDecoder().decode(first.appKey())).hasSize(32);
        assertThat(first.appKey()).isNotEqualTo(second.appKey());
        assertThat(first.maskedHint()).startsWith(first.appKey().substring(0, 4))
                .endsWith(first.appKey().substring(first.appKey().length() - 4));
        assertThat(LocalEnvelopeAppKeyCodec.decrypt(masterKey, "AP_01", 1, first.secretRef()))
                .isEqualTo(first.appKey());
        assertThat(first.toString()).doesNotContain(first.appKey(), first.secretRef());
    }
}
