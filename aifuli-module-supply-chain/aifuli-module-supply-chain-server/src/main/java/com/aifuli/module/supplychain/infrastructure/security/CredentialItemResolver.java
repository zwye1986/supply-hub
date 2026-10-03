package com.aifuli.module.supplychain.infrastructure.security;

import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;

/**
 * 单个凭据项（AD-3）的秘密解析端口；按 {@code providerType} 分派。
 *
 * <p>契约（不变量 11）：返回的 {@code char[]} 是秘密明文，<b>调用方不得记录返回值</b>，
 * 用毕应 {@code Arrays.fill} 擦除。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#52-ad-3-凭据集变更集-054-envelope-provider}。</p>
 */
public interface CredentialItemResolver {
    boolean supports(String providerType);

    char[] resolve(CredentialItemDO item);
}
