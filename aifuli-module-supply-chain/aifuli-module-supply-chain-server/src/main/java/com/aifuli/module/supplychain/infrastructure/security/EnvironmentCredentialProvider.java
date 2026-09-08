package com.aifuli.module.supplychain.infrastructure.security;

import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSlotDO;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 一期环境变量凭证解析器；数据库仅保存 env:NAME 引用。 */
@Component
@RequiredArgsConstructor
public class EnvironmentCredentialProvider implements CredentialProvider {

    private static final String PROVIDER_TYPE = "ENV";
    private static final String PREFIX = "env:";
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Z][A-Z0-9_]{2,127}");

    private final Environment environment;

    @Override
    public boolean supports(String providerType) {
        return PROVIDER_TYPE.equals(providerType);
    }

    @Override
    public boolean isResolvable(CredentialSlotDO slot) {
        String value = read(slot);
        return value != null && !value.isBlank();
    }

    @Override
    public char[] resolve(CredentialSlotDO slot) {
        String value = read(slot);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Credential reference cannot be resolved");
        }
        return value.toCharArray();
    }

    private String read(CredentialSlotDO slot) {
        if (!supports(slot.getProviderType()) || slot.getSecretRef() == null
                || !slot.getSecretRef().startsWith(PREFIX)) {
            return null;
        }
        String name = slot.getSecretRef().substring(PREFIX.length());
        if (!SAFE_NAME.matcher(name).matches()) {
            return null;
        }
        return environment.getProperty(name);
    }
}
