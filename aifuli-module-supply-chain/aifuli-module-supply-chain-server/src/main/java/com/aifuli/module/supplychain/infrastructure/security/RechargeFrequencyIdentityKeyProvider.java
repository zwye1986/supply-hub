package com.aifuli.module.supplychain.infrastructure.security;

/**
 * 直充限频稳定身份专用密钥端口；所有节点必须使用同一32字节快照，返回值必须防御复制。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#2-现有实现与接入边界}。
 */
@FunctionalInterface
public interface RechargeFrequencyIdentityKeyProvider {
    byte[] identityKey();
}
