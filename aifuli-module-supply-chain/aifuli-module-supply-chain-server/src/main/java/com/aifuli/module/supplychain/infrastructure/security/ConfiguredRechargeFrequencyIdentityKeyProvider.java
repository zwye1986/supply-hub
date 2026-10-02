package com.aifuli.module.supplychain.infrastructure.security;

import java.util.Base64;
import java.util.function.Function;

/**
 * 独立供应链进程启动时解析环境密钥并冻结快照；密钥更新需离线迁移，不允许动态更换身份。
 * 默认实现只有供应链启用时注册，其他独立进程不受影响；测试和未来密钥系统可显式替换端口。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#2-现有实现与接入边界}。
 */
public class ConfiguredRechargeFrequencyIdentityKeyProvider implements RechargeFrequencyIdentityKeyProvider {
    private static final int KEY_LENGTH = 32;
    private final byte[] snapshot;

    public ConfiguredRechargeFrequencyIdentityKeyProvider(String reference) {
        this(reference, System::getenv);
    }

    ConfiguredRechargeFrequencyIdentityKeyProvider(String reference, Function<String, String> environment) {
        this.snapshot = resolve(reference, environment);
    }

    /** 只暴露副本，防止调用方改变进程固定身份。 */
    @Override
    public byte[] identityKey() { return snapshot.clone(); }

    /** 解析错误不输出环境值，保留Base64解析异常的因果关系。 */
    static byte[] resolve(String reference, Function<String, String> environment) {
        if (reference == null || !reference.matches("env:[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("直充限频身份密钥引用必须为 env:环境变量名");
        }
        String encoded = environment.apply(reference.substring("env:".length()));
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("直充限频身份密钥环境变量缺失");
        }
        byte[] key;
        try { key = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("直充限频身份密钥不是合法Base64", invalid);
        }
        if (key.length != KEY_LENGTH) {
            throw new IllegalStateException("直充限频身份密钥必须为32字节");
        }
        return key;
    }
}
