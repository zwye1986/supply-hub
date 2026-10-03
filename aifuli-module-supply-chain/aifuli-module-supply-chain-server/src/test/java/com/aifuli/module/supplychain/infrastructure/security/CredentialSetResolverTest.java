package com.aifuli.module.supplychain.infrastructure.security;

import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSetDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialItemMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSetMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AD-3 凭据集解析（地基C，F1-hybrid）的纯逻辑单测。
 *
 * <p>锁定不变量 11 与 §5.2：ENVELOPE 项经 {@link EnvelopeCryptoPort} 解密、AAD 绑定
 * {@code credential_set_id|item_key} 防密文搬移；ENV 项只读 {@code env:} 引用；
 * {@link CredentialSetResolver#resolveSet} 只聚合可用集内的可用项；不支持的 provider_type 与
 * 非可用集一律拒绝。设计见
 * {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#52-ad-3-凭据集变更集-054-envelope-provider}。</p>
 */
class CredentialSetResolverTest {

    private static final String KEY_VERSION = "v1";
    private static final String KEY_32_BYTES = Base64.getEncoder().encodeToString(new byte[32]);
    private static final long SET_ID = 42L;

    private AesGcmEnvelopeCrypto crypto;
    private MockEnvironment environment;
    private CredentialSetMapper setMapper;
    private CredentialItemMapper itemMapper;
    private DefaultCredentialSetResolver resolver;

    @BeforeEach
    void setUp() {
        crypto = new AesGcmEnvelopeCrypto(KEY_VERSION, KEY_32_BYTES, KEY_32_BYTES);
        environment = new MockEnvironment();
        setMapper = mock(CredentialSetMapper.class);
        itemMapper = mock(CredentialItemMapper.class);
        resolver = new DefaultCredentialSetResolver(setMapper, itemMapper,
                List.of(new EnvelopeCredentialItemResolver(crypto), new EnvCredentialItemResolver(environment)));
    }

    @Test
    void resolveSetDecryptsEnvelopeItemWithIdentityBoundAad() {
        String secret = "top-secret-value";
        CredentialItemDO item = envelopeItem(SET_ID, "secretKey", secret);
        givenActiveSetWithItems(item);

        Map<String, char[]> resolved = resolver.resolveSet(SET_ID);

        assertThat(new String(resolved.get("secretKey"))).isEqualTo(secret);
    }

    @Test
    void resolveSetRejectsCiphertextRelocatedToAnotherItemKey() {
        // 密文以 itemKey=secretKey 的 AAD 加密，却被搬到 itemKey=stolenKey 的项上：
        // AAD 不匹配 → GCM 认证标签校验失败 → 拒绝解析（防密文搬移）。
        CredentialItemDO relocated = envelopeItem(SET_ID, "secretKey", "top-secret-value").toBuilder()
                .itemKey("stolenKey").build();
        givenActiveSetWithItems(relocated);

        assertThatThrownBy(() -> resolver.resolveSet(SET_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolveSetReadsEnvReferenceItem() {
        environment.setProperty("QNM_TEST_USERKEY", "env-secret-value");
        CredentialItemDO item = CredentialItemDO.builder().credentialSetId(SET_ID).itemKey("secretKey")
                .providerType("ENV").secretRef("env:QNM_TEST_USERKEY")
                .status(CredentialStatus.ACTIVE.getCode()).build();
        givenActiveSetWithItems(item);

        Map<String, char[]> resolved = resolver.resolveSet(SET_ID);

        assertThat(new String(resolved.get("secretKey"))).isEqualTo("env-secret-value");
    }

    @Test
    void resolveSetAggregatesOnlyActiveItems() {
        environment.setProperty("QNM_TEST_USERKEY", "env-secret-value");
        CredentialItemDO activeEnvelope = envelopeItem(SET_ID, "privateKey", "rsa-private");
        CredentialItemDO disabled = CredentialItemDO.builder().credentialSetId(SET_ID).itemKey("callbackPublicKey")
                .providerType("ENV").secretRef("env:QNM_TEST_USERKEY")
                .status(CredentialStatus.DISABLED.getCode()).build();
        givenActiveSetWithItems(activeEnvelope, disabled);

        Map<String, char[]> resolved = resolver.resolveSet(SET_ID);

        assertThat(resolved).containsOnlyKeys("privateKey");
        assertThat(new String(resolved.get("privateKey"))).isEqualTo("rsa-private");
    }

    @Test
    void resolveSetRejectsUnsupportedProviderType() {
        CredentialItemDO kms = CredentialItemDO.builder().credentialSetId(SET_ID).itemKey("secretKey")
                .providerType("KMS").secretRef("kms:ref")
                .status(CredentialStatus.ACTIVE.getCode()).build();
        givenActiveSetWithItems(kms);

        assertThatThrownBy(() -> resolver.resolveSet(SET_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KMS");
    }

    @Test
    void resolveSetRejectsInactiveOrMissingSet() {
        when(setMapper.selectById(SET_ID)).thenReturn(CredentialSetDO.builder().id(SET_ID)
                .status(CredentialStatus.DISABLED.getCode()).build());
        assertThatThrownBy(() -> resolver.resolveSet(SET_ID))
                .isInstanceOf(IllegalStateException.class);

        when(setMapper.selectById(SET_ID)).thenReturn(null);
        assertThatThrownBy(() -> resolver.resolveSet(SET_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    private void givenActiveSetWithItems(CredentialItemDO... items) {
        when(setMapper.selectById(SET_ID)).thenReturn(CredentialSetDO.builder().id(SET_ID)
                .status(CredentialStatus.ACTIVE.getCode()).build());
        when(itemMapper.selectListBySetId(SET_ID)).thenReturn(List.of(items));
    }

    private CredentialItemDO envelopeItem(long setId, String itemKey, String secret) {
        byte[] aad = EnvelopeCredentialItemResolver.aad(setId, itemKey);
        EnvelopeCryptoPort.EncryptedPayload payload =
                crypto.encrypt(secret.getBytes(StandardCharsets.UTF_8), aad);
        return CredentialItemDO.builder().credentialSetId(setId).itemKey(itemKey)
                .providerType("ENVELOPE").secretRef(EnvelopeCredentialItemResolver.encodeSecretRef(payload))
                .status(CredentialStatus.ACTIVE.getCode()).build();
    }
}
