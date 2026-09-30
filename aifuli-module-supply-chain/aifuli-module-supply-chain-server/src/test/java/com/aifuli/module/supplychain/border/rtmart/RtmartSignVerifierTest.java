package com.aifuli.module.supplychain.border.rtmart;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旅通入站 SHA1withRSA 验签器测试；密钥均在测试运行时生成，禁止使用真实渠道秘密。
 *
 * <p>验签与出站 {@code RtmartRequestSigner} 互为逆运算：规范串同为字典序 key+value 拼接。
 * 覆盖范围见 {@code docs/instore-channel-member-resolve-api.html#3-签名规则}。</p>
 */
class RtmartSignVerifierTest {

    private KeyPair keyPair;
    private KeyPair otherKeyPair;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        otherKeyPair = generator.generateKeyPair();
    }

    @Test
    void verifiesSignatureSignedOverSortedKeyValueCanonical() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("token", "CXB_tk-abc");
        fields.put("app_key", "1G23d45P6");
        fields.put("version", "1");
        fields.put("timestamp", "1787727794");
        fields.put("nonce_str", "nonce-value");

        String sign = sign(fields, keyPair);

        assertThat(RtmartSignVerifier.verify(fields, sign, keyPair.getPublic())).isTrue();
    }

    @Test
    void verifiesSignatureOverEmptyCanonical() throws Exception {
        String sign = sign(Map.of(), keyPair);

        assertThat(RtmartSignVerifier.verify(Map.of(), sign, keyPair.getPublic())).isTrue();
    }

    @Test
    void excludesNullFieldValuesFromCanonical() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("token", "tk-abc");
        fields.put("ignored", null);

        String sign = sign(Map.of("token", "tk-abc"), keyPair);

        assertThat(RtmartSignVerifier.verify(fields, sign, keyPair.getPublic())).isTrue();
    }

    @Test
    void rejectsSignatureSignedWithDifferentKeyPair() throws Exception {
        Map<String, String> fields = Map.of("token", "tk-abc");

        String sign = sign(fields, otherKeyPair);

        assertThat(RtmartSignVerifier.verify(fields, sign, keyPair.getPublic())).isFalse();
    }

    @Test
    void rejectsTamperedFieldValue() throws Exception {
        Map<String, String> fields = new LinkedHashMap<>(Map.of("token", "tk-original"));
        String sign = sign(fields, keyPair);
        fields.put("token", "tk-tampered");

        assertThat(RtmartSignVerifier.verify(fields, sign, keyPair.getPublic())).isFalse();
    }

    @Test
    void rejectsBlankNullOrMissingSign() throws Exception {
        Map<String, String> fields = Map.of("token", "tk-abc");

        assertThat(RtmartSignVerifier.verify(fields, null, keyPair.getPublic())).isFalse();
        assertThat(RtmartSignVerifier.verify(fields, "  ", keyPair.getPublic())).isFalse();
        assertThat(RtmartSignVerifier.verify(fields, "", keyPair.getPublic())).isFalse();
    }

    @Test
    void rejectsNullPublicKey() throws Exception {
        Map<String, String> fields = Map.of("token", "tk-abc");

        assertThat(RtmartSignVerifier.verify(fields, sign(fields, keyPair), null)).isFalse();
    }

    @Test
    void rejectsNonBase64SignWithoutThrowing() {
        Map<String, String> fields = Map.of("token", "tk-abc");

        assertThat(RtmartSignVerifier.verify(fields, "not-base64-!!!", keyPair.getPublic())).isFalse();
    }

    @Test
    void rejectsBase64EncodedBytesThatAreNotAValidSignature() {
        Map<String, String> fields = Map.of("token", "tk-abc");
        String notSignature = Base64.getEncoder().encodeToString("garbage".repeat(64)
                .getBytes(StandardCharsets.UTF_8));

        assertThat(RtmartSignVerifier.verify(fields, notSignature, keyPair.getPublic())).isFalse();
    }

    private String sign(Map<String, String> fields, KeyPair signingKeyPair) throws Exception {
        StringBuilder canonical = new StringBuilder();
        fields.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.append(entry.getKey()).append(entry.getValue()));

        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(signingKeyPair.getPrivate());
        signer.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}
