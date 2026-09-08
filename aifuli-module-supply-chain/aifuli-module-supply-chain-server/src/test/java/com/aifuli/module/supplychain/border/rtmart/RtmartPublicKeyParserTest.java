package com.aifuli.module.supplychain.border.rtmart;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 旅通入站验签公钥解析测试；密钥均在测试运行时生成，禁止使用真实渠道秘密。
 *
 * <p>与出站 {@code RtmartPrivateKeyParser} 对称：严格区分完整 PEM 包装与裸 Base64，
 * 解析失败抛固定摘要异常且不回显配置内容。</p>
 */
class RtmartPublicKeyParserTest {

    private static final String PUBLIC_KEY_SENTINEL = "PUBLIC-KEY-SECRET-SENTINEL";

    private String bareBase64;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        bareBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    @Test
    void parsesBareBase64AndPemWrappedKeysToSameKey() {
        String pem = "-----BEGIN PUBLIC KEY-----\n" + bareBase64 + "\n-----END PUBLIC KEY-----";

        assertThat(RtmartPublicKeyParser.parseX509(pem).getEncoded())
                .isEqualTo(RtmartPublicKeyParser.parseX509(bareBase64).getEncoded());
    }

    @Test
    void toleratesSurroundingAndEmbeddedWhitespace() {
        assertThat(RtmartPublicKeyParser.parseX509(" \n" + bareBase64 + "\t").getEncoded())
                .isEqualTo(RtmartPublicKeyParser.parseX509(bareBase64).getEncoded());
    }

    @Test
    void rejectsBlankConfiguration() {
        assertThatThrownBy(() -> RtmartPublicKeyParser.parseX509(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rtmart inbound publicKey must not be blank");
    }

    @Test
    void rejectsInvalidBase64WithoutLeakingConfiguredValue() {
        String invalid = PUBLIC_KEY_SENTINEL + "-not-base64";

        assertThatThrownBy(() -> RtmartPublicKeyParser.parseX509(invalid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(invalid)
                .hasMessageNotContaining(PUBLIC_KEY_SENTINEL);
    }

    @Test
    void rejectsValidBase64ThatIsNotAnRsaPublicKey() {
        String notAnRsaKey = Base64.getEncoder()
                .encodeToString("not-an-x509-rsa-public-key".getBytes());

        assertThatThrownBy(() -> RtmartPublicKeyParser.parseX509(notAnRsaKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(notAnRsaKey);
    }

    @Test
    void rejectsPartialRepeatedAndMixedPemBoundaries() {
        List<String> malformedPemValues = List.of(
                "-----BEGIN PUBLIC KEY-----\n" + bareBase64,
                bareBase64 + "\n-----END PUBLIC KEY-----",
                "-----BEGIN PUBLIC KEY-----\n-----BEGIN PUBLIC KEY-----\n" + bareBase64
                        + "\n-----END PUBLIC KEY-----\n-----END PUBLIC KEY-----",
                "-----BEGIN PUBLIC KEY-----\n" + bareBase64 + "\n-----END CERTIFICATE-----");

        malformedPemValues.forEach(value -> assertThatThrownBy(() -> RtmartPublicKeyParser.parseX509(value))
                .isInstanceOf(IllegalStateException.class));
    }
}
