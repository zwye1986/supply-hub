package com.aifuli.module.supplychain.infrastructure.security;

import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * ENV 凭据项解析器（AD-3，F1-hybrid）：{@code sc_credential_item.secret_ref} 只存 {@code env:NAME} 引用，
 * 密钥不入库，运行时从 {@link Environment} 读取。与槽位制 {@link EnvironmentCredentialProvider} 并行，
 * 服务于凭据集/项模型。秘密明文以 {@code char[]} 返回，调用方不得记录、用毕擦除。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#52-ad-3-凭据集变更集-054-envelope-provider}。
 */
@Component
@RequiredArgsConstructor
public class EnvCredentialItemResolver implements CredentialItemResolver {

    static final String PROVIDER_TYPE = "ENV";
    private static final String PREFIX = "env:";
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Z][A-Z0-9_]{2,127}");

    private final Environment environment;

    @Override
    public boolean supports(String providerType) {
        return PROVIDER_TYPE.equals(providerType);
    }

    @Override
    public char[] resolve(CredentialItemDO item) {
        if (!supports(item.getProviderType())) {
            throw new IllegalStateException("Credential item is not an ENV reference");
        }
        String ref = item.getSecretRef();
        if (ref == null || !ref.startsWith(PREFIX)) {
            throw new IllegalStateException("ENV credential reference is malformed");
        }
        String name = ref.substring(PREFIX.length());
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalStateException("ENV credential reference name is unsafe");
        }
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("ENV credential reference cannot be resolved");
        }
        return value.toCharArray();
    }
}
