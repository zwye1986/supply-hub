package com.aifuli.module.supplychain.infrastructure.auth;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地信封加解密单测：验证 AES-256-GCM 往返、随机 IV、AAD 绑定、篡改检测与格式门禁。
 *
 * <p>详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#63-appkeysecretresolverspi-与本地信封实现}。</p>
 */
class LocalEnvelopeAppKeyCodecTest {

    private static final byte[] MASTER_KEY =
            Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZmdoaWprbG1ub3BxcnN0dXY=");
    private static final byte[] OTHER_MASTER_KEY =
            Base64.getDecoder().decode("MDEyMzQ1Njc4OWFiY2RlZmdoaWprbG1ub3BxcnN0dXc=");

    @Test
    void encryptDecryptRoundTrip() {
        String secretRef = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 2, "plain-app-key");

        assertThat(secretRef).startsWith("v1:");
        assertThat(secretRef.split(":", -1)).hasSize(3);
        assertThat(LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 2, secretRef))
                .isEqualTo("plain-app-key");
    }

    @Test
    void encryptTwiceProducesDifferentSecretRef() {
        String first = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 1, "k");
        String second = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 1, "k");

        assertThat(first).isNotEqualTo(second);
        assertThat(LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, first)).isEqualTo("k");
        assertThat(LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, second)).isEqualTo("k");
    }

    @Test
    void decryptRejectsAadAppIdMismatchWithoutLeakingSecret() {
        String appKey = "never-leak-app-key";
        String secretRef = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 1, appKey);

        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-2", 1, secretRef))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(Exception.class)
                .hasMessageNotContaining(appKey)
                .hasMessageNotContaining(secretRef);
    }

    @Test
    void decryptRejectsAadKeyVersionMismatch() {
        String secretRef = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 1, "k");

        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 2, secretRef))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void decryptRejectsWrongMasterKey() {
        String secretRef = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 1, "k");

        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(OTHER_MASTER_KEY, "app-1", 1, secretRef))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void decryptRejectsInvalidMasterKeyLength() {
        String secretRef = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 1, "k");

        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(new byte[16], "app-1", 1, secretRef))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void decryptRejectsTamperedCiphertext() {
        String secretRef = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 1, "k");
        String[] parts = secretRef.split(":", -1);
        byte[] ciphertext = Base64.getDecoder().decode(parts[2]);
        ciphertext[0] ^= 1;
        String tampered = parts[0] + ":" + parts[1] + ":"
                + Base64.getEncoder().encodeToString(ciphertext);

        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void decryptRejectsNullPlaintextAndWrongSegmentCount() {
        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, "plain-key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, "v1:onlyonepart"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, "v1:a:b:c"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptRejectsInvalidBase64AndPreservesCause() {
        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, "v1:***:***"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptRejectsEmptyOrWrongLengthIv() {
        String ciphertext = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1,
                "v1::" + ciphertext))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1,
                "v1:" + Base64.getEncoder().encodeToString(new byte[11]) + ":" + ciphertext))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decryptRejectsCiphertextShorterThanGcmTag() {
        String iv = Base64.getEncoder().encodeToString(new byte[12]);

        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1, "v1:" + iv + ":"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocalEnvelopeAppKeyCodec.decrypt(MASTER_KEY, "app-1", 1,
                "v1:" + iv + ":" + Base64.getEncoder().encodeToString(new byte[15])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolverClonesMasterKeyAndDoesNotDependOnCallerArray() {
        byte[] callerKey = MASTER_KEY.clone();
        LocalEnvelopeAppKeySecretResolver resolver = new LocalEnvelopeAppKeySecretResolver(callerKey);
        String secretRef = LocalEnvelopeAppKeyCodec.encrypt(MASTER_KEY, "app-1", 1, "k");
        callerKey[0] ^= 1;

        assertThat(resolver.resolveAppKey("app-1", 1, secretRef)).isEqualTo("k");
    }
}
