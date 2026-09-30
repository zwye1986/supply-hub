package com.aifuli.module.supplychain.infrastructure.security;

public interface EnvelopeCryptoPort {

    EncryptedPayload encrypt(byte[] plaintext, byte[] associatedData);

    byte[] decrypt(byte[] ciphertext, String keyVersion, byte[] associatedData);

    record EncryptedPayload(byte[] ciphertext, String keyVersion) {
        public EncryptedPayload {
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }
}
