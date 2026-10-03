package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式模式、项身份冻结、额外参数与摘要不变量；设计见华盛详细设计 §12.3。 */
class ConnectorCredentialBindingValidatorTest {
    final CredentialSetMapper sets=mock(CredentialSetMapper.class);
    final CredentialItemMapper items=mock(CredentialItemMapper.class);
    final CredentialSlotMapper slots=mock(CredentialSlotMapper.class);
    final ObjectMapper json=new ObjectMapper();
    final ConnectorCredentialBindingValidator guard=new ConnectorCredentialBindingValidator(slots,sets,items,new ClosedJsonSchemaValidator(json),json);
    final Set<String> required=Set.of("appSecret","privateKey","callbackPublicKey");
    AdapterMetadata setMetadata() {
        AdapterMetadata metadata=mock(AdapterMetadata.class);
        when(metadata.credentialBindingMode()).thenReturn(CredentialBindingMode.CREDENTIAL_SET);
        when(metadata.credentialKeys()).thenReturn(required);
        when(metadata.extraConfigSchemaResource()).thenReturn("schema/credential-extra-test.json");
        return metadata;
    }
    List<CredentialItemDO> complete() {
        return List.of(item(3L,"privateKey"),item(1L,"appSecret"),item(2L,"callbackPublicKey"));
    }
    CredentialItemDO item(Long id,String key) {
        return CredentialItemDO.builder().id(id).credentialSetId(9L).itemKey(key).providerType("ENV")
                .activeSecretVersion(1).secretRef("env:PRIVATE_REFERENCE").status((short)10).build();
    }
    void givenSet(List<CredentialItemDO> rows) {
        var set=CredentialSetDO.builder().id(9L).supplierId(7L).setCode("SET").status((short)10).build();
        when(sets.selectBySupplierAndCode(7L,"SET")).thenReturn(set);
        when(sets.selectByIdForUpdate(9L)).thenReturn(set);when(sets.selectById(9L)).thenReturn(set);
        when(items.selectListBySetIdForUpdate(9L)).thenReturn(rows);when(items.selectListBySetId(9L)).thenReturn(rows);
    }
    @Test void singleSlotDefaultIsExplicitAndPreservesLegacyReference() {
        var metadata=new QnmGameOrderV1Metadata();
        assertThat(metadata.credentialBindingMode()).isEqualTo(CredentialBindingMode.SINGLE_SLOT);
        assertThat(metadata.extraConfigSchemaResource()).isEqualTo(metadata.connectorSchemaResource());
        when(slots.selectByNo("SLOT")).thenReturn(CredentialSlotDO.builder().id(4L).status((short)10).build());
        var binding=guard.bindDraft(metadata,7L,"SLOT",null);
        assertThat(binding.credentialSlotId()).isEqualTo(4L);assertThat(binding.credentialSetId()).isNull();assertThat(binding.itemSnapshot()).isNull();
    }
    @Test void modeRequiresExactlyOneCorrectReference() {
        var metadata=setMetadata();
        for(String[] references:List.of(new String[]{"SLOT","SET"},new String[]{"SLOT",null},new String[]{null,null})) {
            assertThatThrownBy(()->guard.bindDraft(metadata,7L,references[0],references[1])).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->guard.bindDraft(new QnmGameOrderV1Metadata(),7L,null,"SET")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void setRequiresSupplierOwnershipAndActiveStatus() {
        var metadata=setMetadata();givenSet(complete());
        when(sets.selectBySupplierAndCode(7L,"SET")).thenReturn(CredentialSetDO.builder().id(9L).supplierId(8L).status((short)10).build());
        assertThatThrownBy(()->guard.bindDraft(metadata,7L,null,"SET")).isInstanceOf(IllegalArgumentException.class);
        when(sets.selectBySupplierAndCode(7L,"SET")).thenReturn(CredentialSetDO.builder().id(9L).supplierId(7L).status((short)10).build());
        when(sets.selectByIdForUpdate(9L)).thenReturn(CredentialSetDO.builder().id(9L).supplierId(7L).status((short)20).build());
        assertThatThrownBy(()->guard.bindDraft(metadata,7L,null,"SET")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingAdditionalOrInactiveRequiredKeysAreRejected() {
        var metadata=setMetadata();
        for(var rows:List.of(List.of(item(1L,"appSecret")),List.of(item(1L,"appSecret"),item(2L,"privateKey"),item(3L,"callbackPublicKey"),item(4L,"extra")),
                List.of(item(1L,"appSecret"),item(2L,"privateKey"),item(3L,"callbackPublicKey").toBuilder().status((short)20).build()))) {
            givenSet(rows);assertThatThrownBy(()->guard.bindDraft(metadata,7L,null,"SET")).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void snapshotIsSortedAndContainsOnlyIdentityAndVersion() throws Exception {
        givenSet(complete());var binding=guard.bindDraft(setMetadata(),7L,null,"SET");
        assertThat(binding.credentialSetId()).isEqualTo(9L);assertThat(binding.credentialSlotId()).isNull();
        assertThat(json.readTree(binding.itemSnapshot()).get(0).path("itemKey").asText()).isEqualTo("appSecret");
        assertThat(binding.itemSnapshot()).contains("itemId","providerType","activeSecretVersion").doesNotContain("secretRef","PRIVATE_REFERENCE");
    }
    @Test void verifyRejectsIdentityProviderOrVersionDriftAndMissingSnapshot() {
        givenSet(complete());var metadata=setMetadata();var binding=guard.bindDraft(metadata,7L,null,"SET");
        var version=SupplierConnectorVersionDO.builder().credentialSetId(9L).credentialItemSnapshot(binding.itemSnapshot()).build();
        guard.verify(metadata,7L,version,false);
        for(var changed:List.of(item(30L,"privateKey"),item(3L,"privateKey").toBuilder().providerType("ENVELOPE").build(),
                item(3L,"privateKey").toBuilder().activeSecretVersion(2).build())) {
            when(items.selectListBySetId(9L)).thenReturn(List.of(item(1L,"appSecret"),item(2L,"callbackPublicKey"),changed));
            assertThatThrownBy(()->guard.verify(metadata,7L,version,false)).isInstanceOf(IllegalStateException.class);
        }
        version.setCredentialItemSnapshot(null);when(items.selectListBySetId(9L)).thenReturn(complete());
        assertThatThrownBy(()->guard.verify(metadata,7L,version,false)).isInstanceOf(IllegalStateException.class);
    }
    @Test void publicationCurrentReadCannotReuseAnActiveSlotSnapshot() {
        when(slots.selectById(4L)).thenReturn(CredentialSlotDO.builder().id(4L).status((short)10).build());
        when(slots.selectOne(org.mockito.ArgumentMatchers.any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(CredentialSlotDO.builder().id(4L).status((short)20).build());
        var version=SupplierConnectorVersionDO.builder().credentialSlotId(4L).build();
        assertThatThrownBy(()->guard.verify(new QnmGameOrderV1Metadata(),7L,version,true)).isInstanceOf(IllegalStateException.class);
    }
    @Test void currentReadUsesLockedSetInsteadOfOldSnapshot() {
        givenSet(complete());var metadata=setMetadata();var binding=guard.bindDraft(metadata,7L,null,"SET");
        var version=SupplierConnectorVersionDO.builder().credentialSetId(9L).credentialItemSnapshot(binding.itemSnapshot()).build();
        when(sets.selectByIdForUpdate(9L)).thenReturn(CredentialSetDO.builder().id(9L).supplierId(7L).status((short)20).build());
        assertThatThrownBy(()->guard.verify(metadata,7L,version,true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void extraConfigProjectsDeclaredScalarsIntoImmutableMap() {
        var metadata=setMetadata();var parameters=guard.extraParameters(metadata,"{\"outMobile\":false,\"expireTime\":60,\"label\":\"非秘密\"}");
        assertThat(parameters).containsEntry("outMobile","false").containsEntry("expireTime","60").containsEntry("label","非秘密");
        assertThatThrownBy(()->parameters.put("token","secret")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(guard.normalizeExtra(metadata,"{\"outMobile\":false,\"expireTime\":60}"))
                .isEqualTo(guard.normalizeExtra(metadata,"{\"expireTime\":60,\"outMobile\":false}"));
        for(String unsafe:List.of("{\"unknown\":1}","{\"token\":\"secret\"}","{\"nested\":{}}","{\"expireTime\":\"60\"}","[]")) {
            assertThatThrownBy(()->guard.extraParameters(metadata,unsafe)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->guard.extraParameters(new QnmGameOrderV1Metadata(),"{\"expireTime\":60}")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void digestIncludesModeSetIdentitySnapshotAndCanonicalExtras() throws Exception {
        var command=new SupplierConfigurationCommandService.CreateConnectorDraftCommand("SUP","CON","TEST","https://supplier.invalid","merchant",null,"V1",null,1000,2000,null,null,"{}",1L,"SET","{}");
        var digest=SupplierConfigurationCommandService.class.getDeclaredMethod("connectorDigest",SupplierConfigurationCommandService.CreateConnectorDraftCommand.class,
                Long.class,ConnectorCredentialBindingValidator.Binding.class,CredentialBindingMode.class,int.class,String.class,String.class);
        digest.setAccessible(true);
        var binding=new ConnectorCredentialBindingValidator.Binding(null,9L,"[]");
        byte[] initial=(byte[])digest.invoke(null,command,1L,binding,CredentialBindingMode.CREDENTIAL_SET,1,"{}","{}");
        assertThat((byte[])digest.invoke(null,command,1L,new ConnectorCredentialBindingValidator.Binding(null,10L,"[]"),CredentialBindingMode.CREDENTIAL_SET,1,"{}","{}")).isNotEqualTo(initial);
        assertThat((byte[])digest.invoke(null,command,1L,new ConnectorCredentialBindingValidator.Binding(null,9L,"[{\"itemId\":1}]"),CredentialBindingMode.CREDENTIAL_SET,1,"{}","{}")).isNotEqualTo(initial);
        assertThat((byte[])digest.invoke(null,command,1L,binding,CredentialBindingMode.CREDENTIAL_SET,1,"{}","{\"expireTime\":60}")).isNotEqualTo(initial);
        assertThat((byte[])digest.invoke(null,command,1L,new ConnectorCredentialBindingValidator.Binding(9L,null,null),CredentialBindingMode.SINGLE_SLOT,1,"{}","{}")).isNotEqualTo(initial);
    }
}
