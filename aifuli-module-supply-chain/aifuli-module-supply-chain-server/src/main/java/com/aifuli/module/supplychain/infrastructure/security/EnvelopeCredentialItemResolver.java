package com.aifuli.module.supplychain.infrastructure.security;

import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * ENVELOPE 凭据项解析器（AD-3，F1-hybrid）：用 {@link EnvelopeCryptoPort} 的 KMS DEK 解密
 * {@code sc_credential_item.secret_ref} 中的信封密文。
 *
 * <p><b>AAD 绑定凭据项身份</b>：解密时 AAD = {@code credentialSetId|itemKey}，与写入加密时一致；
 * 密文被搬到其它凭据项（set 或 key 不同）时 GCM 认证标签校验失败，抛 {@link IllegalStateException}，
 * 从而防止密文搬移（不变量 11）。{@code secret_ref} 格式 {@code envelope:<keyVersion>:<base64(iv||ciphertext)>}，
 * keyVersion 供 KMS 主密钥版本化+轮换。秘密明文以 {@code char[]} 返回，调用方不得记录、用毕擦除。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#52-ad-3-凭据集变更集-054-envelope-provider}。</p>
 */
@Component
@RequiredArgsConstructor
public class EnvelopeCredentialItemResolver implements CredentialItemResolver {

    static final String PROVIDER_TYPE = "ENVELOPE";
    private static final String PREFIX = "envelope:";

    private final EnvelopeCryptoPort envelopeCryptoPort;

    @Override
    public boolean supports(String providerType) {
        return PROVIDER_TYPE.equals(providerType);
    }

    @Override
    public char[] resolve(CredentialItemDO item) {
        if (!supports(item.getProviderType())) {
            throw new IllegalStateException("Credential item is not an ENVELOPE reference");
        }
        String ref = item.getSecretRef();
        if (ref == null || !ref.startsWith(PREFIX)) {
            throw new IllegalStateException("ENVELOPE credential reference is malformed");
        }
        String body = ref.substring(PREFIX.length());
        int separator = body.indexOf(':');
        if (separator <= 0 || separator == body.length() - 1) {
            throw new IllegalStateException("ENVELOPE credential reference is malformed");
        }
        String keyVersion = body.substring(0, separator);
        byte[] ciphertext = Base64.getDecoder().decode(body.substring(separator + 1));
        byte[] plaintext = envelopeCryptoPort.decrypt(ciphertext, keyVersion,
                aad(item.getCredentialSetId(), item.getItemKey()));
        try {
            return new String(plaintext, StandardCharsets.UTF_8).toCharArray();
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /** 写入侧加密与读取侧解密共用的 AAD：绑定凭据项身份 {@code credentialSetId|itemKey}，防密文搬移。 */
    public static byte[] aad(Long credentialSetId, String itemKey) {
        return (credentialSetId + "|" + itemKey).getBytes(StandardCharsets.UTF_8);
    }

    /** 把信封加密结果编码为入库 {@code secret_ref}（{@code envelope:<keyVersion>:<base64>}），供凭据写端点复用。 */
    public static String encodeSecretRef(EnvelopeCryptoPort.EncryptedPayload payload) {
        return PREFIX + payload.keyVersion() + ":" + Base64.getEncoder().encodeToString(payload.ciphertext());
    }
}
