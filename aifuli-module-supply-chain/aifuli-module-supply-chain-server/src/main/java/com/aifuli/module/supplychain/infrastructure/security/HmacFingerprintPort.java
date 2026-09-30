package com.aifuli.module.supplychain.infrastructure.security;

public interface HmacFingerprintPort {
    byte[] fingerprint(String purpose, byte[] canonicalPlaintext);
}
