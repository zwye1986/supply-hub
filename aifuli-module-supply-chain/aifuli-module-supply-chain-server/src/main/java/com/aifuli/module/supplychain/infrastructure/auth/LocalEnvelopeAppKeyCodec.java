package com.aifuli.module.supplychain.infrastructure.auth;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 本地 appKey 信封编解码器：使用 AES-256-GCM，并通过 AAD 绑定 appId 与 keyVersion。
 *
 * <p>{@code secret_ref} 固定为 {@code v1:{base64(iv)}:{base64(ciphertext)}}，其中 IV 为随机
 * 12 字节，GCM tag 为 128 bit。{@link #encrypt(byte[], String, int, String)} 只供运维和测试
 * 生成可入库的 secret_ref；认证请求仅调用解密。详见
 * {@code docs/superpowers/specs/2026-08-23-project-app-auth-design.md#63-appkeysecretresolverspi-与本地信封实现}。</p>
 */
public final class LocalEnvelopeAppKeyCodec {

    private static final String VERSION = "v1";
    private static final String SEPARATOR = ":";
    private static final int MASTER_KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_TAG_BYTES = GCM_TAG_BITS / Byte.SIZE;
    private static final SecureRandom RANDOM = new SecureRandom();

    private LocalEnvelopeAppKeyCodec() {
    }

    /**
     * 使用随机 IV 加密 appKey，生成协议格式的 secret_ref。
     *
     * <p>本方法不记录主密钥、appKey 或生成结果，并在 {@code finally} 清零额外创建的 UTF-8
     * 明文字节数组。调用方传入的 {@link String} 无法由本方法可靠擦除，应限制其生命周期且避免日志输出。</p>
     */
    public static String encrypt(byte[] masterKey, String appId, int keyVersion, String appKey) {
        requireEncryptArguments(masterKey, appId, appKey);
        byte[] plaintext = appKey.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(appId, keyVersion));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return VERSION + SEPARATOR + Base64.getEncoder().encodeToString(iv) + SEPARATOR
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("appKey 信封加密失败", exception);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * 解密协议格式的 secret_ref。
     *
     * <p>格式、Base64、主密钥、AAD 或 GCM tag 任一校验失败均统一抛出
     * {@link IllegalArgumentException}。异常仅携带固定摘要，并保留原始 cause 供内部诊断，
     * 不暴露主密钥、secret_ref 或 appKey。解密产生的临时 UTF-8 明文字节会在返回前清零；
     * 返回的 {@link String} 受 JVM 不可变对象约束，无法可靠擦除，调用方仍须限制其生命周期。</p>
     */
    public static String decrypt(byte[] masterKey, String appId, int keyVersion, String secretRef) {
        byte[] plaintext = null;
        try {
            requireMasterKey(masterKey);
            if (appId == null) {
                throw new IllegalArgumentException("appId is required");
            }
            String[] parts = splitSecretRef(secretRef);
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[2]);
            validateEnvelopeLengths(iv, ciphertext);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(appId, keyVersion));
            plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("secret_ref 解密失败", exception);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private static String[] splitSecretRef(String secretRef) {
        if (secretRef == null) {
            throw new IllegalArgumentException("secret_ref is required");
        }
        String[] parts = secretRef.split(SEPARATOR, -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new IllegalArgumentException("secret_ref format is invalid");
        }
        return parts;
    }

    private static void validateEnvelopeLengths(byte[] iv, byte[] ciphertext) {
        if (iv.length != IV_BYTES || ciphertext.length < GCM_TAG_BYTES) {
            throw new IllegalArgumentException("secret_ref envelope length is invalid");
        }
    }

    private static void requireEncryptArguments(byte[] masterKey, String appId, String appKey) {
        requireMasterKey(masterKey);
        if (appId == null || appKey == null) {
            throw new IllegalArgumentException("appKey 信封加密参数非法");
        }
    }

    private static void requireMasterKey(byte[] masterKey) {
        if (masterKey == null || masterKey.length != MASTER_KEY_BYTES) {
            throw new IllegalArgumentException("appKey 信封主密钥非法");
        }
    }

    private static byte[] aad(String appId, int keyVersion) {
        return (appId + "\n" + keyVersion).getBytes(StandardCharsets.UTF_8);
    }
}
