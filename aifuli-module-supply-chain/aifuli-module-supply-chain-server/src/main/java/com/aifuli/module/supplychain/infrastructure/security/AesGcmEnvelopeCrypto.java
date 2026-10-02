package com.aifuli.module.supplychain.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** 目标与资产的 AES-GCM 信封加密，以及不可逆 HMAC 指纹。 */
@Primary
@Component
public final class AesGcmEnvelopeCrypto implements EnvelopeCryptoPort, HmacFingerprintPort {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final String keyVersion;
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec fingerprintKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmEnvelopeCrypto(
            @Value("${aifuli.supply-chain.security.key-version}") String keyVersion,
            @Value("${aifuli.supply-chain.security.envelope-key-base64:}") String envelopeKeyBase64,
            @Value("${aifuli.supply-chain.security.fingerprint-key-base64:}") String fingerprintKeyBase64) {
        this.keyVersion = requireKeyVersion(keyVersion);
        this.encryptionKey = new SecretKeySpec(decodeKey(envelopeKeyBase64, "envelope", 32), "AES");
        this.fingerprintKey = new SecretKeySpec(decodeKey(fingerprintKeyBase64, "fingerprint", 32), "HmacSHA256");
    }

    @Override
    public EncryptedPayload encrypt(byte[] plaintext, byte[] associatedData) {
        if (plaintext == null || plaintext.length == 0 || associatedData == null) {
            throw new IllegalArgumentException("Envelope plaintext and associated data are required");
        }
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(associatedData);
            byte[] encrypted = cipher.doFinal(plaintext);
            return new EncryptedPayload(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array(), keyVersion);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Target envelope encryption failed", exception);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext, String requestedKeyVersion, byte[] associatedData) {
        if (!keyVersion.equals(requestedKeyVersion) || ciphertext == null
                || ciphertext.length <= IV_LENGTH || associatedData == null) {
            throw new IllegalArgumentException("Target envelope key version or ciphertext is invalid");
        }
        byte[] iv = java.util.Arrays.copyOfRange(ciphertext, 0, IV_LENGTH);
        byte[] encrypted = java.util.Arrays.copyOfRange(ciphertext, IV_LENGTH, ciphertext.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Target envelope decryption failed", exception);
        }
    }

    @Override
    public byte[] fingerprint(String purpose, byte[] canonicalPlaintext) {
        if (purpose == null || purpose.isBlank() || canonicalPlaintext == null) {
            throw new IllegalArgumentException("HMAC purpose and plaintext are required");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(fingerprintKey);
            mac.update(purpose.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(canonicalPlaintext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC fingerprint generation failed", exception);
        }
    }

    private static String requireKeyVersion(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalStateException("Supply-chain security key version is invalid");
        }
        return value;
    }

    private static byte[] decodeKey(String encoded, String purpose, int requiredLength) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length != requiredLength) {
                throw new IllegalStateException("Supply-chain " + purpose + " key must be 256 bits");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Supply-chain " + purpose + " key is not valid Base64", exception);
        }
    }
}
