package com.aifuli.module.supplychain.infrastructure.security;

import java.util.Map;

/**
 * 凭据集（AD-3）解析端口：把一个可用凭据集内的全部可用项解析为 {@code itemKey -> 秘密明文}。
 *
 * <p>契约（不变量 11）：返回的 {@code char[]} 均为秘密明文，<b>调用方不得记录返回值</b>，
 * 用毕应逐项 {@code Arrays.fill} 擦除。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#52-ad-3-凭据集变更集-054-envelope-provider}。</p>
 */
public interface CredentialSetResolver {
    Map<String, char[]> resolveSet(Long credentialSetId);
}
