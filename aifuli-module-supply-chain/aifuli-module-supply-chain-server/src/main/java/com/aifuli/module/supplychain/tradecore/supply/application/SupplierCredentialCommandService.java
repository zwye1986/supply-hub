package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCredentialItemResolver;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSetDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialItemMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSetMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 供应商凭据集/项写端点（F1-hybrid，§6.3）：建集与建项，ENVELOPE 明文入参立即信封加密。
 *
 * <p>凭据护栏（不变量 11，强制且不受 H2 权限收敛影响）：</p>
 * <ul>
 *   <li><b>录入只写不回显</b>：{@code secretValue} 仅作为入参，服务端立即经 {@link EnvelopeCryptoPort}
 *       信封加密为 {@code secret_ref}（格式 {@code envelope:<keyVersion>:<base64>}），明文绝不入库、绝不回显；</li>
 *   <li><b>AAD 绑定凭据项身份</b>：加密 AAD = {@code credentialSetId|itemKey}，密文被搬到其它项时解密失败，防密文搬移；</li>
 *   <li><b>永不入日志</b>：审计日志只记录 {@code supplierCode/setCode/itemKey/providerType/maskedHint/activeSecretVersion/operatorId}，
 *       绝不记录 {@code secretValue} 或 {@code secret_ref}；</li>
 *   <li><b>itemKey 封闭集</b>：须落在所有已注册适配器 {@code credentialKeys()} 的并集内（AGENTS.md §7，杜绝魔法值）；</li>
 *   <li><b>ENV 只接受 {@code env:} 引用</b>：密钥不入库，且拒绝携带明文值。</li>
 * </ul>
 *
 * <p>设计见
 * docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#63-凭据写f1-hybrid。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierCredentialCommandService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String PROVIDER_ENV = "ENV";
    private static final String PROVIDER_ENVELOPE = "ENVELOPE";
    private static final String ENV_REF_PREFIX = "env:";

    private final SupplierMapper supplierMapper;
    private final CredentialSetMapper credentialSetMapper;
    private final CredentialItemMapper credentialItemMapper;
    private final AdapterMetadataRegistry metadataRegistry;
    private final EnvelopeCryptoPort envelopeCryptoPort;
    private final SupplyChainDatabaseClock databaseClock;

    /**
     * 建凭据集：供应商内 {@code setCode} 唯一（uk_sc_credential_set），初始状态可用。
     *
     * @return 新建凭据集主键
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public Long createCredentialSet(CreateCredentialSetCommand command) {
        SupplierDO supplier = requireActiveSupplier(command.supplierCode());
        if (credentialSetMapper.selectBySupplierAndCode(supplier.getId(), command.setCode()) != null) {
            throw new IllegalArgumentException("Credential set already exists: " + command.setCode());
        }
        LocalDateTime now = databaseClock.now();
        CredentialSetDO set = CredentialSetDO.builder()
                .supplierId(supplier.getId()).setCode(command.setCode())
                .status(CredentialStatus.ACTIVE.getCode())
                .createdAt(now).updatedAt(now).build();
        credentialSetMapper.insert(set);
        // 脱敏审计：仅非秘密字段（不变量 11）。
        log.info("[createCredentialSet] supplierCode={} setCode={} setId={} operatorId={}",
                command.supplierCode(), command.setCode(), set.getId(), command.operatorId());
        return set.getId();
    }

    /**
     * 建凭据项：按 {@code providerType} 分派——ENV 存 {@code env:} 引用，ENVELOPE 立即信封加密明文。
     *
     * <p>{@code itemKey} 须落在所有已注册适配器 {@code credentialKeys()} 的并集内；同一集内 {@code itemKey}
     * 唯一（uk_sc_credential_item）。ENVELOPE 的 {@code secretValue} 用毕擦除其字节副本，绝不入日志。</p>
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void addCredentialItem(AddCredentialItemCommand command) {
        SupplierDO supplier = requireActiveSupplier(command.supplierCode());
        CredentialSetDO set = credentialSetMapper.selectBySupplierAndCode(supplier.getId(), command.setCode());
        if (set == null || !isActive(set.getStatus())) {
            throw new IllegalArgumentException("Active credential set does not exist: " + command.setCode());
        }
        if (!allowedItemKeys().contains(command.itemKey())) {
            throw new IllegalArgumentException(
                    "Credential item key is outside the closed set: " + command.itemKey());
        }
        if (credentialItemMapper.selectBySetAndKey(set.getId(), command.itemKey()) != null) {
            throw new IllegalArgumentException(
                    "Credential item already exists in set: " + command.itemKey());
        }
        String secretRef = resolveSecretRef(set.getId(), command);
        LocalDateTime now = databaseClock.now();
        credentialItemMapper.insert(CredentialItemDO.builder()
                .credentialSetId(set.getId()).itemKey(command.itemKey())
                .providerType(command.providerType()).secretRef(secretRef)
                .maskedHint(command.maskedHint()).activeSecretVersion(command.activeSecretVersion())
                .status(CredentialStatus.ACTIVE.getCode())
                .createdAt(now).updatedAt(now).build());
        // 脱敏审计：绝不记录 secretValue 或 secret_ref（不变量 11）。
        log.info("[addCredentialItem] supplierCode={} setCode={} setId={} itemKey={} providerType={} "
                        + "maskedHint={} activeSecretVersion={} operatorId={}",
                command.supplierCode(), command.setCode(), set.getId(), command.itemKey(),
                command.providerType(), command.maskedHint(), command.activeSecretVersion(),
                command.operatorId());
    }

    private String resolveSecretRef(Long setId, AddCredentialItemCommand command) {
        if (PROVIDER_ENV.equals(command.providerType())) {
            if (hasText(command.secretValue())) {
                throw new IllegalArgumentException(
                        "ENV credential item must not carry a plaintext secret value");
            }
            String ref = command.secretRef();
            if (ref == null || !ref.startsWith(ENV_REF_PREFIX)) {
                throw new IllegalArgumentException("ENV credential item requires an env: secret reference");
            }
            return ref;
        }
        if (PROVIDER_ENVELOPE.equals(command.providerType())) {
            if (command.secretRef() != null) {
                throw new IllegalArgumentException(
                        "ENVELOPE credential item must not carry a secret reference; pass secretValue only");
            }
            if (!hasText(command.secretValue())) {
                throw new IllegalArgumentException("ENVELOPE credential item requires a plaintext secret value");
            }
            byte[] plaintext = command.secretValue().getBytes(StandardCharsets.UTF_8);
            try {
                EnvelopeCryptoPort.EncryptedPayload payload = envelopeCryptoPort.encrypt(
                        plaintext, EnvelopeCredentialItemResolver.aad(setId, command.itemKey()));
                return EnvelopeCredentialItemResolver.encodeSecretRef(payload);
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
        throw new IllegalArgumentException("Unsupported credential provider type: " + command.providerType());
    }

    /** 所有已注册适配器 {@code credentialKeys()} 的并集（封闭集，§6.3 定案）。 */
    private Set<String> allowedItemKeys() {
        Set<String> keys = new LinkedHashSet<>();
        metadataRegistry.all().forEach(metadata -> keys.addAll(metadata.credentialKeys()));
        return keys;
    }

    private SupplierDO requireActiveSupplier(String supplierCode) {
        SupplierDO supplier = supplierMapper.selectByCode(supplierCode);
        if (supplier == null || supplier.getStatus() == null
                || supplier.getStatus() != SupplierStatus.ACTIVE.getCode()) {
            throw new IllegalArgumentException("Active supplier does not exist: " + supplierCode);
        }
        return supplier;
    }

    private static boolean isActive(Short status) {
        return status != null && status == CredentialStatus.ACTIVE.getCode();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record CreateCredentialSetCommand(String supplierCode, String setCode, Long operatorId) {
    }

    /**
     * 建凭据项命令。ENV 用 {@code secretRef}（{@code env:} 前缀），ENVELOPE 用 {@code secretValue}（明文，仅入参）。
     * 二者互斥：ENV 拒绝 {@code secretValue}，ENVELOPE 拒绝 {@code secretRef}。
     */
    public record AddCredentialItemCommand(String supplierCode, String setCode, String itemKey,
                                           String providerType, String secretRef, String secretValue,
                                           String maskedHint, Integer activeSecretVersion, Long operatorId) {
    }
}
