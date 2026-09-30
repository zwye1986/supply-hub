package com.aifuli.module.supplychain.infrastructure.identity;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.random.RandomGenerator;

/**
 * 生成符合 ULID 二进制布局的 26 位大写 Crockford Base32 标识。
 */
@Component
public class UlidGenerator {

    private static final char[] CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final BigInteger BASE = BigInteger.valueOf(32);

    private final Clock clock;
    private final RandomGenerator random;

    public UlidGenerator() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    UlidGenerator(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
    }

    public synchronized String next() {
        long timestamp = clock.millis();
        if (timestamp < 0 || timestamp > 0xFFFF_FFFF_FFFFL) {
            throw new IllegalStateException("ULID timestamp is outside 48-bit range");
        }

        byte[] value = new byte[16];
        value[0] = (byte) (timestamp >>> 40);
        value[1] = (byte) (timestamp >>> 32);
        value[2] = (byte) (timestamp >>> 24);
        value[3] = (byte) (timestamp >>> 16);
        value[4] = (byte) (timestamp >>> 8);
        value[5] = (byte) timestamp;
        byte[] randomness = new byte[10];
        random.nextBytes(randomness);
        System.arraycopy(randomness, 0, value, 6, randomness.length);

        BigInteger remaining = new BigInteger(1, value);
        char[] encoded = new char[26];
        for (int index = encoded.length - 1; index >= 0; index--) {
            BigInteger[] quotientAndRemainder = remaining.divideAndRemainder(BASE);
            encoded[index] = CROCKFORD_BASE32[quotientAndRemainder[1].intValue()];
            remaining = quotientAndRemainder[0];
        }
        return new String(encoded);
    }
}
