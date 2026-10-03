package com.aifuli.module.supplychain.infrastructure.security;

import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSetDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialItemMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSetMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 凭据集解析器（AD-3，F1-hybrid）：把一个可用凭据集内的全部可用项，按 {@code providerType} 分派到对应
 * {@link CredentialItemResolver}（ENV / ENVELOPE）解析为 {@code itemKey -> 秘密明文}。
 *
 * <p>不变量：集不存在或非可用（status≠10）→ 拒绝；集内无可用项 → 拒绝；项 provider_type 无匹配解析器
 * （如一期未实现的 KMS）→ 拒绝并回显类型名（非秘密）。<b>本类绝不记录任何秘密明文或 {@code secret_ref}</b>，
 * 仅记录集主键与项键名（不变量 11）。返回的 {@code char[]} 调用方不得记录、用毕擦除。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#52-ad-3-凭据集变更集-054-envelope-provider}。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DefaultCredentialSetResolver implements CredentialSetResolver {

    private final CredentialSetMapper credentialSetMapper;
    private final CredentialItemMapper credentialItemMapper;
    private final List<CredentialItemResolver> itemResolvers;

    @Override
    public Map<String, char[]> resolveSet(Long credentialSetId) {
        CredentialSetDO set = credentialSetMapper.selectById(credentialSetId);
        if (set == null || !isActive(set.getStatus())) {
            throw new IllegalStateException("Credential set is not active: " + credentialSetId);
        }
        List<CredentialItemDO> items = credentialItemMapper.selectListBySetId(credentialSetId);
        Map<String, char[]> resolved = new LinkedHashMap<>();
        for (CredentialItemDO item : items) {
            if (!isActive(item.getStatus())) {
                continue;
            }
            resolved.put(item.getItemKey(), resolveItem(item));
        }
        if (resolved.isEmpty()) {
            throw new IllegalStateException("Credential set has no active items: " + credentialSetId);
        }
        // 仅记录集主键与项键名（非秘密），绝不记录 secret_ref 或明文（不变量 11）。
        log.debug("[resolveSet] credentialSetId={} resolvedItemKeys={}", credentialSetId, resolved.keySet());
        return resolved;
    }

    private char[] resolveItem(CredentialItemDO item) {
        return itemResolvers.stream()
                .filter(resolver -> resolver.supports(item.getProviderType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Unsupported credential provider type: " + item.getProviderType()))
                .resolve(item);
    }

    private static boolean isActive(Short status) {
        return status != null && status == CredentialStatus.ACTIVE.getCode();
    }
}
