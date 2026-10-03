package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCredentialItemResolver;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.metadata.AdapterMetadataRegistry;
import com.aifuli.module.supplychain.supplieradapter.metadata.QnmGameOrderV1Metadata;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialItemDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.CredentialSetDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialItemMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSetMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import com.aifuli.module.supplychain.tradecore.supply.domain.CredentialStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 凭据写端点（F1-hybrid，§6.3）的纯逻辑单测。
 *
 * <p>锁定不变量 11 与护栏：ENVELOPE 项的 {@code secretValue} 仅入参、服务端立即经
 * {@link EnvelopeCryptoPort} 信封加密（AAD 绑定 {@code setId|itemKey}）落 {@code secret_ref}，
 * 明文绝不入库；{@code itemKey} 须落在所有已注册适配器 {@code credentialKeys()} 的并集内；
 * ENV 只接受 {@code env:} 引用且拒绝明文值；不支持的 provider_type 拒绝。用真实
 * {@link AesGcmEnvelopeCrypto} 验证加密可被 {@link EnvelopeCredentialItemResolver} 原样解回。设计见
 * docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#63-凭据写f1-hybrid。</p>
 */
@ExtendWith(MockitoExtension.class)
class SupplierCredentialCommandServiceTest {

    private static final String KEY_VERSION = "v1";
    private static final String KEY_32_BYTES = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String SUPPLIER_CODE = "SPL_QNM";
    private static final Long SUPPLIER_ID = 7L;
    private static final String SET_CODE = "QNM_SET";
    private static final Long SET_ID = 55L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 9, 0);
    private static final String PLAINTEXT = "super-secret-qnm-key";

    @Mock
    private SupplierMapper supplierMapper;
    @Mock
    private CredentialSetMapper credentialSetMapper;
    @Mock
    private CredentialItemMapper credentialItemMapper;
    @Mock
    private SupplyChainDatabaseClock databaseClock;

    private AesGcmEnvelopeCrypto crypto;
    private SupplierCredentialCommandService service;

    @BeforeEach
    void setUp() {
        crypto = new AesGcmEnvelopeCrypto(KEY_VERSION, KEY_32_BYTES, KEY_32_BYTES);
        AdapterMetadataRegistry registry =
                new AdapterMetadataRegistry(List.of(new QnmGameOrderV1Metadata()));
        service = new SupplierCredentialCommandService(supplierMapper, credentialSetMapper,
                credentialItemMapper, registry, crypto, databaseClock, org.mockito.Mockito.mock(com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierConnectorVersionMapper.class));
    }

    @Test
    void envelopeRejectsCharacterAndUtf8ByteOverflowBeforePersistence() {
        givenActiveSet();
        for (String tooLarge : List.of("x".repeat(8193), "密".repeat(6000))) {
            assertThatThrownBy(() -> service.addCredentialItem(new SupplierCredentialCommandService.AddCredentialItemCommand(
                    SUPPLIER_CODE, SET_CODE, "secretKey", "ENVELOPE", null, tooLarge, "masked", 1, 1L)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
        }
        verify(credentialItemMapper, never()).insert(any(CredentialItemDO.class));
    }

    @Test
    void createCredentialSetPersistsActiveSetAndReturnsId() {
        givenActiveSupplier();
        when(credentialSetMapper.selectBySupplierAndCode(SUPPLIER_ID, SET_CODE)).thenReturn(null);
        when(databaseClock.now()).thenReturn(NOW);
        // 模拟 MyBatis-Plus 对 @TableId(AUTO) 的回填：insert 后实体带上自增主键。
        when(credentialSetMapper.insert(any(CredentialSetDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, CredentialSetDO.class).setId(SET_ID);
            return 1;
        });

        Long id = service.createCredentialSet(
                new SupplierCredentialCommandService.CreateCredentialSetCommand(SUPPLIER_CODE, SET_CODE, 1L));

        ArgumentCaptor<CredentialSetDO> captor = ArgumentCaptor.forClass(CredentialSetDO.class);
        verify(credentialSetMapper).insert(captor.capture());
        assertThat(captor.getValue().getSetCode()).isEqualTo(SET_CODE);
        assertThat(captor.getValue().getSupplierId()).isEqualTo(SUPPLIER_ID);
        assertThat(captor.getValue().getStatus()).isEqualTo(CredentialStatus.ACTIVE.getCode());
        assertThat(id).isEqualTo(SET_ID);
    }

    @Test
    void createCredentialSetRejectsDuplicateSetCode() {
        givenActiveSupplier();
        when(credentialSetMapper.selectBySupplierAndCode(SUPPLIER_ID, SET_CODE))
                .thenReturn(CredentialSetDO.builder().id(SET_ID).build());

        assertThatThrownBy(() -> service.createCredentialSet(
                new SupplierCredentialCommandService.CreateCredentialSetCommand(SUPPLIER_CODE, SET_CODE, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(credentialSetMapper, never()).insert(any(CredentialSetDO.class));
    }

    @Test
    void addEnvelopeItemEncryptsSecretValueAndNeverStoresPlaintext() {
        givenActiveSet();

        when(databaseClock.now()).thenReturn(NOW);

        service.addCredentialItem(new SupplierCredentialCommandService.AddCredentialItemCommand(
                SUPPLIER_CODE, SET_CODE, "secretKey", "ENVELOPE", null, PLAINTEXT,
                "qnm-****", 1, 1L));

        ArgumentCaptor<CredentialItemDO> captor = ArgumentCaptor.forClass(CredentialItemDO.class);
        verify(credentialItemMapper).insert(captor.capture());
        CredentialItemDO stored = captor.getValue();
        assertThat(stored.getProviderType()).isEqualTo("ENVELOPE");
        assertThat(stored.getStatus()).isEqualTo(CredentialStatus.ACTIVE.getCode());
        assertThat(stored.getMaskedHint()).isEqualTo("qnm-****");
        // 明文绝不入库：secret_ref 为信封密文引用，且不含明文子串。
        assertThat(stored.getSecretRef()).startsWith("envelope:");
        assertThat(stored.getSecretRef()).doesNotContain(PLAINTEXT);
        // 加密正确且 AAD 绑定 setId|itemKey：用解析器可原样解回明文。
        char[] resolved = new EnvelopeCredentialItemResolver(crypto).resolve(stored);
        assertThat(new String(resolved)).isEqualTo(PLAINTEXT);
    }

    @Test
    void addEnvItemStoresEnvReferenceWithoutPlaintext() {
        givenActiveSet();

        when(databaseClock.now()).thenReturn(NOW);

        service.addCredentialItem(new SupplierCredentialCommandService.AddCredentialItemCommand(
                SUPPLIER_CODE, SET_CODE, "secretKey", "ENV", "env:QNM_USERKEY", null,
                "qnm-****", 1, 1L));

        ArgumentCaptor<CredentialItemDO> captor = ArgumentCaptor.forClass(CredentialItemDO.class);
        verify(credentialItemMapper).insert(captor.capture());
        assertThat(captor.getValue().getProviderType()).isEqualTo("ENV");
        assertThat(captor.getValue().getSecretRef()).isEqualTo("env:QNM_USERKEY");
    }

    @Test
    void addCredentialItemRejectsItemKeyOutsideClosedUnion() {
        givenActiveSet();

        assertThatThrownBy(() -> service.addCredentialItem(
                new SupplierCredentialCommandService.AddCredentialItemCommand(
                        SUPPLIER_CODE, SET_CODE, "unknownKey", "ENVELOPE", null, PLAINTEXT,
                        "x-****", 1, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(credentialItemMapper, never()).insert(any(CredentialItemDO.class));
    }

    @Test
    void addEnvItemRejectsPlaintextSecretValue() {
        givenActiveSet();

        assertThatThrownBy(() -> service.addCredentialItem(
                new SupplierCredentialCommandService.AddCredentialItemCommand(
                        SUPPLIER_CODE, SET_CODE, "secretKey", "ENV", "env:QNM_USERKEY", PLAINTEXT,
                        "qnm-****", 1, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(credentialItemMapper, never()).insert(any(CredentialItemDO.class));
    }

    @Test
    void addEnvItemRejectsReferenceWithoutEnvPrefix() {
        givenActiveSet();

        assertThatThrownBy(() -> service.addCredentialItem(
                new SupplierCredentialCommandService.AddCredentialItemCommand(
                        SUPPLIER_CODE, SET_CODE, "secretKey", "ENV", "plain-ref", null,
                        "qnm-****", 1, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(credentialItemMapper, never()).insert(any(CredentialItemDO.class));
    }

    @Test
    void addEnvelopeItemRejectsCallerSuppliedSecretRef() {
        givenActiveSet();

        assertThatThrownBy(() -> service.addCredentialItem(
                new SupplierCredentialCommandService.AddCredentialItemCommand(
                        SUPPLIER_CODE, SET_CODE, "secretKey", "ENVELOPE", "envelope:v1:abc", PLAINTEXT,
                        "qnm-****", 1, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(credentialItemMapper, never()).insert(any(CredentialItemDO.class));
    }

    @Test
    void addCredentialItemRejectsUnsupportedProviderType() {
        givenActiveSet();

        assertThatThrownBy(() -> service.addCredentialItem(
                new SupplierCredentialCommandService.AddCredentialItemCommand(
                        SUPPLIER_CODE, SET_CODE, "secretKey", "KMS", "kms:ref", null,
                        "qnm-****", 1, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(credentialItemMapper, never()).insert(any(CredentialItemDO.class));
    }

    private void givenActiveSupplier() {
        when(supplierMapper.selectByCode(SUPPLIER_CODE)).thenReturn(SupplierDO.builder()
                .id(SUPPLIER_ID).supplierCode(SUPPLIER_CODE).supplierType("STANDARD_SUPPLIER")
                .status(SupplierStatus.ACTIVE.getCode()).build());
    }

    private void givenActiveSet() {
        when(credentialSetMapper.selectByIdForUpdate(SET_ID)).thenReturn(CredentialSetDO.builder().id(SET_ID).supplierId(SUPPLIER_ID).status((short)10).build());
        givenActiveSupplier();
        when(credentialSetMapper.selectBySupplierAndCode(SUPPLIER_ID, SET_CODE))
                .thenReturn(CredentialSetDO.builder().id(SET_ID).supplierId(SUPPLIER_ID)
                        .setCode(SET_CODE).status(CredentialStatus.ACTIVE.getCode()).build());
    }
}
