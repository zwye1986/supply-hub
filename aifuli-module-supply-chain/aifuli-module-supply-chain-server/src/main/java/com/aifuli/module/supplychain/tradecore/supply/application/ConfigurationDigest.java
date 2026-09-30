package com.aifuli.module.supplychain.tradecore.supply.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class ConfigurationDigest {
    private ConfigurationDigest() {
    }

    static byte[] sha256(String... parts) {
        String canonical = String.join("\u001f", parts);
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
