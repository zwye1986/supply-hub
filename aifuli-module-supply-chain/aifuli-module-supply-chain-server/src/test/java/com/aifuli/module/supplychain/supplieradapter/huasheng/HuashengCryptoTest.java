package com.aifuli.module.supplychain.supplieradapter.huasheng;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * HUA_SHENG 通信密码协议测试，以独立 JCA 操作核对算法、字节编码和密钥格式。
 * 设计见 docs/superpowers/specs/2026-10-02-huasheng-fulfillment-adapter-design.md#121-第一切片通信密码协议。
 */
class HuashengCryptoTest {

    private static final String SECRET = "0123456789abcdef";
    private static final String APP_ID = "abcdef0123456789";
    private static final String PLAIN = "{\"商品\":\"电影兑换券🎬\",\"数量\":1}";
    private static final HexFormat HEX = HexFormat.of();
    private static final String SECRET_SECURITY_MARKER = "SYNTHETIC_SECRET_SECURITY_MARKER_INVALID_LENGTH";
    private static final String BODY_SECURITY_MARKER = "SYNTHETIC_BODY_SECURITY_MARKER_47";
    private static final String CIPHER_SECURITY_MARKER = "SYNTHETIC_CIPHER_SECURITY_MARKER_58";
    private static final String PRIVATE_SECURITY_MARKER = "SYNTHETIC_PRIVATE_SECURITY_MARKER_69";
    private static final String PUBLIC_SECURITY_MARKER = "SYNTHETIC_PUBLIC_SECURITY_MARKER_70";
    private static final String SIGNATURE_SECURITY_MARKER = "SYNTHETIC_SIGNATURE_SECURITY_MARKER_81";
    private static KeyPair keyPair;
    private static KeyPair wrongKeyPair;

    @BeforeAll
    static void generateTestKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        wrongKeyPair = generator.generateKeyPair();
    }

    @ParameterizedTest
    @ValueSource(strings = {SECRET, "0123456789abcdefghijklmn", "0123456789abcdefghijklmnopqrstuv", "中文密钥abcd", "中文密钥abcdefghijkl", "中文密钥abcdefghijklmnopqrst"})
    @DisplayName("AES 密钥按 UTF-8 字节计长，独立 JCA 核对中英文正文及三种密钥长度")
    void shouldMatchIndependentAesEncryptionAndDecryption(String secret) throws Exception {
        String expectedHex = independentEncrypt(PLAIN, secret, APP_ID);
        assertThat(HuashengCrypto.encryptBodyHex(PLAIN, secret, APP_ID)).isEqualTo(expectedHex)
                .matches("[0-9a-f]+");
        assertThat(HuashengCrypto.decryptBodyHex(expectedHex, secret, APP_ID)).isEqualTo(PLAIN);
        assertThat(HuashengCrypto.decryptBodyHex(expectedHex.toUpperCase(Locale.ROOT), secret, APP_ID))
                .isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("AES IV 按 appId UTF-8 的十六字节处理")
    void shouldUseUtf8AppIdAsIv() throws Exception {
        String appId = "中文应用abcd";
        String expectedHex = independentEncrypt(PLAIN, SECRET, appId);
        assertThat(HuashengCrypto.encryptBodyHex(PLAIN, SECRET, appId)).isEqualTo(expectedHex);
        assertThat(HuashengCrypto.decryptBodyHex(expectedHex, SECRET, appId)).isEqualTo(PLAIN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "0123456789abcde", "0123456789abcdefg", "中文密钥ab"})
    @DisplayName("非法 AES 密钥长度在加解密入口拒绝且错误不暴露密钥")
    void shouldRejectInvalidAesSecret(String secret) {
        assertThatThrownBy(() -> HuashengCrypto.encryptBodyHex(PLAIN, secret, APP_ID))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("HUA_SHENG 正文加密失败").hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HuashengCrypto.decryptBodyHex("0011", secret, APP_ID))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("HUA_SHENG 正文解密失败").hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0123456789abcde", "0123456789abcdefg", "中文应用"})
    @DisplayName("非法 appId 字节长度在加解密入口拒绝")
    void shouldRejectInvalidIv(String appId) {
        assertThatThrownBy(() -> HuashengCrypto.encryptBodyHex(PLAIN, SECRET, appId))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("HUA_SHENG 正文加密失败").hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HuashengCrypto.decryptBodyHex("0011", SECRET, appId))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("HUA_SHENG 正文解密失败").hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    @DisplayName("空加解密正文拒绝且固定错误保留因果")
    void shouldRejectEmptyBodies(String body) {
        assertThatThrownBy(() -> HuashengCrypto.encryptBodyHex(body, SECRET, APP_ID))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("HUA_SHENG 正文加密失败").hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HuashengCrypto.decryptBodyHex(body, SECRET, APP_ID))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("HUA_SHENG 正文解密失败").hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "001", "gg", "0x00", "00 11", "\n0011", "0011\n", "００"})
    @DisplayName("奇数、非 ASCII hex 及包含空白的密文均拒绝")
    void shouldRejectMalformedCiphertext(String body) {
        assertThatThrownBy(() -> HuashengCrypto.decryptBodyHex(body, SECRET, APP_ID))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("HUA_SHENG 正文解密失败").hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("非法 AES 块长度保留 JCA 错误因果")
    void shouldPreserveCipherFailureCause() {
        assertThatThrownBy(() -> HuashengCrypto.decryptBodyHex("0011", SECRET, APP_ID))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("HUA_SHENG 正文解密失败")
                .hasCauseInstanceOf(javax.crypto.IllegalBlockSizeException.class);
    }

    @Test
    @DisplayName("SHA512withRSA 签原始密文 hex，输出小写 hex，与独立 JCA 一致")
    void shouldSignCiphertextHexBytes() throws Exception {
        String bodyHex = independentEncrypt(PLAIN, SECRET, APP_ID);
        String actual = HuashengCrypto.signBodyHex(bodyHex, keyPair.getPrivate());
        assertThat(actual).isEqualTo(independentSign(bodyHex)).matches("[0-9a-f]+");
        Signature verifier = Signature.getInstance("SHA512withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(bodyHex.getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(HEX.parseHex(actual))).isTrue();
        assertThat(HuashengCrypto.verifyBodyHex(bodyHex, independentSign(bodyHex), keyPair.getPublic())).isTrue();
    }

    @Test
    @DisplayName("独立 JCA 对明文误签后不得通过密文验签")
    void shouldRejectSignatureOverPlaintext() throws Exception {
        String bodyHex = independentEncrypt(PLAIN, SECRET, APP_ID);
        assertThat(HuashengCrypto.verifyBodyHex(bodyHex, independentSign(PLAIN), keyPair.getPublic())).isFalse();
    }

    @Test
    @DisplayName("正文大小写和前后空白参与原始签名字节，不做归一化")
    void shouldPreserveOriginalBodyBytes() throws Exception {
        String body = "aabbcc001122";
        String uppercaseBody = body.toUpperCase(Locale.ROOT);
        String spacedBody = " \n" + body + "\r\n";
        String originalSign = independentSign(body);
        assertThat(HuashengCrypto.verifyBodyHex(uppercaseBody, originalSign, keyPair.getPublic())).isFalse();
        assertThat(HuashengCrypto.verifyBodyHex(spacedBody, originalSign, keyPair.getPublic())).isFalse();
        assertThat(HuashengCrypto.signBodyHex(uppercaseBody, keyPair.getPrivate())).isEqualTo(independentSign(uppercaseBody));
        assertThat(HuashengCrypto.signBodyHex(spacedBody, keyPair.getPrivate())).isEqualTo(independentSign(spacedBody));
        assertThat(HuashengCrypto.verifyBodyHex(spacedBody, independentSign(spacedBody), keyPair.getPublic())).isTrue();
        assertThat(HuashengCrypto.verifyBodyHex(body, originalSign.toUpperCase(Locale.ROOT), keyPair.getPublic())).isTrue();
    }

    @Test
    @DisplayName("正文篡改、签名篡改及错误公钥验签失败")
    void shouldRejectTamperingAndWrongKey() throws Exception {
        String body = "aabbcc001122";
        String sign = independentSign(body);
        String tamperedSign = (sign.startsWith("0") ? "1" : "0") + sign.substring(1);
        assertThat(HuashengCrypto.verifyBodyHex("aabbcc001123", sign, keyPair.getPublic())).isFalse();
        assertThat(HuashengCrypto.verifyBodyHex(body, tamperedSign, keyPair.getPublic())).isFalse();
        assertThat(HuashengCrypto.verifyBodyHex(body, sign, wrongKeyPair.getPublic())).isFalse();
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        assertThat(HuashengCrypto.verifyBodyHex(body, sign, ec.generateKeyPair().getPublic())).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "0", "zz", "0011", "00 11", "\n0011", "0011\n"})
    @DisplayName("缺失或非法签名统一返回 false")
    void shouldRejectMissingOrMalformedSignature(String sign) {
        assertThat(HuashengCrypto.verifyBodyHex("aabb", sign, keyPair.getPublic())).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    @DisplayName("缺失正文不可签名或验签")
    void shouldRejectMissingSigningBody(String body) throws Exception {
        assertThat(HuashengCrypto.verifyBodyHex(body, independentSign("aabb"), keyPair.getPublic())).isFalse();
        assertThatThrownBy(() -> HuashengCrypto.signBodyHex(body, keyPair.getPrivate()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("HUA_SHENG 正文签名失败").hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("缺失或非 RSA 私钥拒绝签名，缺失公钥验签失败")
    void shouldRejectInvalidSigningKeys() throws Exception {
        assertThatThrownBy(() -> HuashengCrypto.signBodyHex("aabb", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("HUA_SHENG 正文签名失败").hasCauseInstanceOf(IllegalArgumentException.class);
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        assertThatThrownBy(() -> HuashengCrypto.signBodyHex("aabb", ec.generateKeyPair().getPrivate()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("HUA_SHENG 正文签名失败")
                .hasCauseInstanceOf(java.security.InvalidKeyException.class);
        assertThat(HuashengCrypto.verifyBodyHex("aabb", independentSign("aabb"), null)).isFalse();
    }

    @Test
    @DisplayName("加载 PKCS8 私钥支持标准 PEM 及裸 Base64")
    void shouldLoadPkcs8PrivateKey() {
        byte[] encoded = keyPair.getPrivate().getEncoded();
        assertThat(HuashengCrypto.loadPkcs8PrivateKey(pem("PRIVATE KEY", encoded)).getEncoded()).isEqualTo(encoded);
        assertThat(HuashengCrypto.loadPkcs8PrivateKey(Base64.getEncoder().encodeToString(encoded)).getEncoded()).isEqualTo(encoded);
    }

    @Test
    @DisplayName("加载 X509 公钥支持标准 PEM 及裸 Base64")
    void shouldLoadX509PublicKey() {
        byte[] encoded = keyPair.getPublic().getEncoded();
        assertThat(HuashengCrypto.loadPublicKey(pem("PUBLIC KEY", encoded)).getEncoded()).isEqualTo(encoded);
        assertThat(HuashengCrypto.loadPublicKey(Base64.getEncoder().encodeToString(encoded)).getEncoded()).isEqualTo(encoded);
    }

    @Test
    @DisplayName("独立 DER 编码 PKCS1 公钥可包装为 X509 并完成验签")
    void shouldLoadPkcs1PublicKey() throws Exception {
        RSAPublicKey rsa = (RSAPublicKey) keyPair.getPublic();
        byte[] pkcs1 = sequence(der(0x02, rsa.getModulus().toByteArray()), der(0x02, rsa.getPublicExponent().toByteArray()));
        var loaded = HuashengCrypto.loadPublicKey(pem("RSA PUBLIC KEY", pkcs1));
        assertThat(loaded.getEncoded()).isEqualTo(rsa.getEncoded());
        assertThat(HuashengCrypto.verifyBodyHex("aabb", independentSign("aabb"), loaded)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "invalid-key-sensitive-text", "***", "AA==", "-----BEGIN RSA PRIVATE KEY-----\nAA==\n-----END RSA PRIVATE KEY-----", "-----BEGIN PRIVATE KEY-----\nAA==\n-----END PUBLIC KEY-----"})
    @DisplayName("非法私钥编码统一安全异常并保留因果，PKCS1 私钥不得伪装成 PKCS8")
    void shouldRejectInvalidPrivateKeyEncoding(String key) {
        assertThatThrownBy(() -> HuashengCrypto.loadPkcs8PrivateKey(key))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("HUA_SHENG 私钥加载失败").hasCauseInstanceOf(Exception.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "invalid-key-sensitive-text", "***", "AA==", "-----BEGIN RSA PUBLIC KEY-----\nAA==\n-----END RSA PUBLIC KEY-----", "-----BEGIN PUBLIC KEY-----\nAA==\n-----END RSA PUBLIC KEY-----"})
    @DisplayName("非法公钥编码统一安全异常并保留因果")
    void shouldRejectInvalidPublicKeyEncoding(String key) {
        assertThatThrownBy(() -> HuashengCrypto.loadPublicKey(key))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("HUA_SHENG 公钥加载失败").hasCauseInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("错误 PEM 类型、附加内容及非 RSA 编码拒绝加载")
    void shouldRejectWrongPemTypeAndNonRsaKey() throws Exception {
        assertThatThrownBy(() -> HuashengCrypto.loadPkcs8PrivateKey(pem("RSA PRIVATE KEY", keyPair.getPrivate().getEncoded())))
                .hasMessage("HUA_SHENG 私钥加载失败");
        assertThatThrownBy(() -> HuashengCrypto.loadPublicKey(pem("PRIVATE KEY", keyPair.getPrivate().getEncoded())))
                .hasMessage("HUA_SHENG 公钥加载失败");
        assertThatThrownBy(() -> HuashengCrypto.loadPublicKey(pem("PUBLIC KEY", keyPair.getPublic().getEncoded()) + "extra"))
                .hasMessage("HUA_SHENG 公钥加载失败");
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        KeyPair ecKeys = ec.generateKeyPair();
        assertThatThrownBy(() -> HuashengCrypto.loadPkcs8PrivateKey(pem("PRIVATE KEY", ecKeys.getPrivate().getEncoded())))
                .hasMessage("HUA_SHENG 私钥加载失败");
        assertThatThrownBy(() -> HuashengCrypto.loadPublicKey(pem("PUBLIC KEY", ecKeys.getPublic().getEncoded())))
                .hasMessage("HUA_SHENG 公钥加载失败");
    }

    @Test
    @DisplayName("非法 AES 参数的真实完整异常堆栈不含密钥、正文或密文且保留 cause")
    void shouldKeepAesFailureStackTraceFreeOfSensitiveInputs() {
        assertSafeFailure(catchThrowable(() -> HuashengCrypto.encryptBodyHex(
                BODY_SECURITY_MARKER, SECRET_SECURITY_MARKER, APP_ID)),
                "HUA_SHENG 正文加密失败", IllegalArgumentException.class);
        assertSafeFailure(catchThrowable(() -> HuashengCrypto.decryptBodyHex(
                CIPHER_SECURITY_MARKER, SECRET_SECURITY_MARKER, APP_ID)),
                "HUA_SHENG 正文解密失败", IllegalArgumentException.class);
    }

    @Test
    @DisplayName("非法 hex 与非法 AES 块的真实完整异常堆栈不回显密文")
    void shouldKeepCipherFailureStackTraceFreeOfSensitiveInputs() {
        assertSafeFailure(catchThrowable(() -> HuashengCrypto.decryptBodyHex(
                CIPHER_SECURITY_MARKER, SECRET, APP_ID)),
                "HUA_SHENG 正文解密失败", IllegalArgumentException.class);
        String malformedBlockHex = HEX.formatHex(CIPHER_SECURITY_MARKER.getBytes(StandardCharsets.UTF_8));
        assertSafeFailure(catchThrowable(() -> HuashengCrypto.decryptBodyHex(
                malformedBlockHex, SECRET, APP_ID)),
                "HUA_SHENG 正文解密失败", javax.crypto.IllegalBlockSizeException.class, malformedBlockHex, SECRET);
    }

    @Test
    @DisplayName("非法 Base64 与 DER 密钥的真实完整异常堆栈不含合成 PEM 或编码材料")
    void shouldKeepKeyLoadingFailureStackTraceFreeOfSensitiveInputs() {
        String invalidPrivateBase64Pem = "-----BEGIN PRIVATE KEY-----\n" + PRIVATE_SECURITY_MARKER + "\n-----END PRIVATE KEY-----";
        String invalidPublicBase64Pem = "-----BEGIN PUBLIC KEY-----\n" + PUBLIC_SECURITY_MARKER + "\n-----END PUBLIC KEY-----";
        assertSafeFailure(catchThrowable(() -> HuashengCrypto.loadPkcs8PrivateKey(invalidPrivateBase64Pem)),
                "HUA_SHENG 私钥加载失败", IllegalArgumentException.class, invalidPrivateBase64Pem);
        assertSafeFailure(catchThrowable(() -> HuashengCrypto.loadPublicKey(invalidPublicBase64Pem)),
                "HUA_SHENG 公钥加载失败", IllegalArgumentException.class, invalidPublicBase64Pem);
        String invalidPrivateDerPem = pem("PRIVATE KEY", PRIVATE_SECURITY_MARKER.getBytes(StandardCharsets.UTF_8));
        String invalidPublicDerPem = pem("PUBLIC KEY", PUBLIC_SECURITY_MARKER.getBytes(StandardCharsets.UTF_8));
        assertSafeFailure(catchThrowable(() -> HuashengCrypto.loadPkcs8PrivateKey(invalidPrivateDerPem)),
                "HUA_SHENG 私钥加载失败", java.security.spec.InvalidKeySpecException.class,
                invalidPrivateDerPem, Base64.getEncoder().encodeToString(PRIVATE_SECURITY_MARKER.getBytes(StandardCharsets.UTF_8)));
        assertSafeFailure(catchThrowable(() -> HuashengCrypto.loadPublicKey(invalidPublicDerPem)),
                "HUA_SHENG 公钥加载失败", java.security.spec.InvalidKeySpecException.class,
                invalidPublicDerPem, Base64.getEncoder().encodeToString(PUBLIC_SECURITY_MARKER.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("签名无效私钥的真实完整异常堆栈保留原因且不含正文或私钥材料")
    void shouldKeepSigningFailureStackTraceFreeOfSensitiveInputs() {
        PrivateKey invalidKey = new PrivateKey() {
            @Override
            public String getAlgorithm() {
                return "RSA";
            }

            @Override
            public String getFormat() {
                return "PKCS#8";
            }

            @Override
            public byte[] getEncoded() {
                return PRIVATE_SECURITY_MARKER.getBytes(StandardCharsets.UTF_8);
            }
        };
        assertSafeFailure(catchThrowable(() -> HuashengCrypto.signBodyHex(BODY_SECURITY_MARKER, invalidKey)),
                "HUA_SHENG 正文签名失败", java.security.InvalidKeyException.class);
        assertThat(HuashengCrypto.verifyBodyHex(BODY_SECURITY_MARKER, SIGNATURE_SECURITY_MARKER, keyPair.getPublic())).isFalse();
    }

    /**
     * 对真实 Throwable 调用 printStackTrace，包含所有 cause 与 suppressed，不仅检查包装异常消息。
     * 原始异常链必须保留；所有合成敏感标记、PEM 与调用方补充的编码材料不得出现在日志文本中。
     */
    private static void assertSafeFailure(Throwable failure, String expectedMessage, Class<? extends Throwable> expectedCause,
                                          String... extraSensitiveValues) {
        assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessage(expectedMessage).hasCauseInstanceOf(expectedCause);
        StringWriter stackTrace = new StringWriter();
        failure.printStackTrace(new PrintWriter(stackTrace));
        assertThat(stackTrace.toString()).contains("Caused by:")
                .doesNotContain(SECRET_SECURITY_MARKER, BODY_SECURITY_MARKER, CIPHER_SECURITY_MARKER,
                        PRIVATE_SECURITY_MARKER, PUBLIC_SECURITY_MARKER, SIGNATURE_SECURITY_MARKER);
        if (extraSensitiveValues.length > 0) {
            assertThat(stackTrace.toString()).doesNotContain(extraSensitiveValues);
        }
    }

    private static String independentEncrypt(String plain, String secret, String appId) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(appId.getBytes(StandardCharsets.UTF_8)));
        return HEX.formatHex(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private static String independentSign(String body) throws Exception {
        Signature signer = Signature.getInstance("SHA512withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(body.getBytes(StandardCharsets.UTF_8));
        return HEX.formatHex(signer.sign());
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded)
                + "\n-----END " + type + "-----";
    }

    // 测试独立编码 ASN.1 DER，仅使用 modulus/exponent，不调用生产包装方法。
    private static byte[] sequence(byte[]... values) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] value : values) {
            content.writeBytes(value);
        }
        return der(0x30, content.toByteArray());
    }

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(tag);
        if (content.length < 128) {
            encoded.write(content.length);
        } else if (content.length <= 255) {
            encoded.write(0x81);
            encoded.write(content.length);
        } else {
            encoded.write(0x82);
            encoded.write(content.length >>> 8);
            encoded.write(content.length);
        }
        encoded.writeBytes(content);
        return encoded.toByteArray();
    }
}
