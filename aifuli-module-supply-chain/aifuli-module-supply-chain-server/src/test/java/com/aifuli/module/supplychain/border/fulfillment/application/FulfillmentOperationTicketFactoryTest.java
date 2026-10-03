package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.infrastructure.security.CredentialProvider;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.CredentialSlotMapper;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FulfillmentOperationTicketFactoryTest {
    @Test void queryTicketKeepsFrozenReferencesWithoutAccountAndFallsBackToBOrderBillno() {
        var factory=new FulfillmentOperationTicketFactory(mock(CredentialSlotMapper.class),List.of(),new FulfillmentCallbackProperties());
        var operation=FulfillmentOperationDO.builder().operationNo("OP_ORIGINAL").operationKind("FULFILL")
                .adapterStepCode("SEND").sequenceNo(2).idempotencyKey("ORIGINAL_KEY").build();
        var bo=BOrderDO.builder().bOrderNo("BO_FROZEN").connectorVersionId(71L).offerVersionId(81L).requestedQuantity(1L).externalOrderNo("EXT").build();
        var request=FulfillmentRequestDO.builder().targetSchemaCode("MOBILE_RECHARGE").targetCiphertext(new byte[]{0}).build();
        var config=new PublishedConnectorConfiguration("CON",71L,1,"ADAPTER","https://supplier.invalid","MERCHANT",null,null,Set.of());
        var ticket=factory.create(operation,bo,request,SupplierDO.builder().supplierCode("SUP").build(),SupplierOfferVersionDO.builder().externalSkuCode("SKU_FROZEN").build(),
                config,FulfillmentOperationKind.QUERY_ORDER,null,"secret", "TRACE");
        assertThat(ticket.operationNo()).isEqualTo("OP_ORIGINAL"); assertThat(ticket.operationKind()).isEqualTo(FulfillmentOperationKind.QUERY_ORDER);
        assertThat(ticket.rechargeAccount()).isNull(); assertThat(ticket.billno()).isEqualTo("BO_FROZEN");
        assertThat(ticket.connectorVersionId()).isEqualTo(71L); assertThat(ticket.offerVersionId()).isEqualTo(81L);
        assertThat(ticket.externalSkuCode()).isEqualTo("SKU_FROZEN"); assertThat(ticket.externalOrderNo()).isEqualTo("EXT");
        assertThat(ticket.idempotencyKey()).isEqualTo("ORIGINAL_KEY"); assertThat(ticket.secretKey()).isEqualTo("secret");
    }
    @Test void ticketFreezesIndependentTimeoutsAndOutboundMaterial() {
        assertThat(Arrays.stream(OperationTicket.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .contains("credentialMaterial", "connectTimeoutMs", "readTimeoutMs");
        assertThat(Arrays.stream(PublishedConnectorConfiguration.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                .contains("credentialSetId", "credentialBindingMode", "connectTimeoutMs", "readTimeoutMs", "extraParameters");
    }
    @Test void ticketRedactsSensitiveFieldsAndCopiesExtras() {
        Map<String,String> extras=new HashMap<>(); extras.put("token", "sensitive-extra");
        var ticket=OperationTicket.builder().operationNo("OP").billno("BILL").adapterType("TEST")
                .secretKey("sensitive-key").rechargeAccount("sensitive-account")
                .checkpointPayload("sensitive-checkpoint".getBytes()).extraParameters(extras).build();
        extras.put("token", "changed");
        assertThat(ticket.extraParameters().get("token")).isEqualTo("sensitive-extra");
        assertThat(ticket.toString()).doesNotContain("sensitive-key", "sensitive-account", "sensitive-extra", "secretKey=", "checkpointPayload=");
    }
    @Test void legacyCredentialArrayIsWiped() {
        var slots=mock(CredentialSlotMapper.class); var provider=mock(CredentialProvider.class);
        when(slots.selectById(9L)).thenReturn(CredentialSlotDO.builder().id(9L).providerType("TEST").status((short)10).build());
        when(provider.supports("TEST")).thenReturn(true);
        char[] chars="secret-material".toCharArray(); when(provider.resolve(any())).thenReturn(chars);
        var factory=new FulfillmentOperationTicketFactory(slots,List.of(provider),new FulfillmentCallbackProperties());
        var config=new PublishedConnectorConfiguration("CON",71L,1,"ADAPTER","https://supplier.invalid","MERCHANT",9L,null,Set.of());
        assertThat(factory.resolveCredential(config)).isEqualTo("secret-material");
        assertThat(chars).containsOnly('\0');
    }
    @Test void legacyTwentyFourArgumentConstructorPreservesSecondsDefaults() throws Exception {
        var constructor=Arrays.stream(OperationTicket.class.getConstructors()).filter(row->row.getParameterCount()==24).findFirst();
        assertThat(constructor).isPresent();
        var ticket=(OperationTicket)constructor.orElseThrow().newInstance("OP","BO","BILL",null,"SUP","QNM",1L,2L,
                FulfillmentOperationKind.QUERY_ORDER,AdapterStepCode.SEND,1,"ID",null,1L,null,null,null,null,null,"secret",7,"TRACE",null,Map.of());
        assertThat(ticket.connectTimeoutMs()).isEqualTo(7000);assertThat(ticket.readTimeoutMs()).isEqualTo(7000);
        assertThat(ticket.timeoutSeconds()).isEqualTo(7);assertThat(ticket.credentialMaterial()).isNull();
    }
    @Test void emptyLegacyMaterialFailsClosed() {
        var slots=mock(CredentialSlotMapper.class);var provider=mock(CredentialProvider.class);
        when(slots.selectById(9L)).thenReturn(CredentialSlotDO.builder().id(9L).providerType("TEST").status((short)10).build());
        when(provider.supports("TEST")).thenReturn(true);when(provider.resolve(any())).thenReturn(new char[0]);
        var factory=new FulfillmentOperationTicketFactory(slots,List.of(provider),new FulfillmentCallbackProperties());
        var config=new PublishedConnectorConfiguration("CON",71L,1,"ADAPTER","https://supplier.invalid","MERCHANT",9L,null,Set.of());
        assertThatThrownBy(()->factory.resolveCredential(config)).isInstanceOf(IllegalStateException.class);
    }
    @Test void existingCreateEntryBuildsSetMaterialAndWipesAllItemsIncludingCallbackKey() {
        var resolver=mock(com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver.class);
        Map<String,char[]> values=new LinkedHashMap<>(); values.put("appSecret","sensitive-app".toCharArray());
        values.put("privateKey","sensitive-private".toCharArray());values.put("callbackPublicKey","sensitive-callback".toCharArray());
        when(resolver.resolveSet(42L)).thenReturn(values);
        var slots=mock(CredentialSlotMapper.class);
        var factory=new FulfillmentOperationTicketFactory(slots,List.of(),new FulfillmentCallbackProperties(),List.of(resolver));
        Map<String,String> extras=new HashMap<>();extras.put("expireTime","60");
        var config=new PublishedConnectorConfiguration("CON",71L,1,"TEST_SET","https://supplier.invalid","MERCHANT",null,null,Set.of(),
                42L,com.aifuli.module.supplychain.supplieradapter.metadata.CredentialBindingMode.CREDENTIAL_SET,1500,2501,extras);
        extras.put("expireTime","changed");
        assertThat(factory.resolveCredential(config)).isNull();verifyNoInteractions(slots);
        var operation=FulfillmentOperationDO.builder().operationNo("OP").adapterStepCode("SEND").build();
        var order=BOrderDO.builder().bOrderNo("BO").connectorVersionId(71L).build();
        var ticket=factory.create(operation,order,FulfillmentRequestDO.builder().build(),SupplierDO.builder().supplierCode("SUP").build(),null,
                config,FulfillmentOperationKind.QUERY_ORDER,null,null,"TRACE");
        assertThat(ticket.secretKey()).isNull();assertThat(ticket.credentialMaterial().appSecret()).isEqualTo("sensitive-app");
        assertThat(ticket.credentialMaterial().privateKeyPem()).isEqualTo("sensitive-private");
        assertThat(ticket.credentialMaterial().toString()).doesNotContain("sensitive-app","sensitive-private","sensitive-callback");
        assertThat(ticket.connectTimeoutMs()).isEqualTo(1500);assertThat(ticket.readTimeoutMs()).isEqualTo(2501);assertThat(ticket.timeoutSeconds()).isEqualTo(3);
        assertThat(ticket.extraParameters()).containsEntry("expireTime","60");
        assertThatThrownBy(()->ticket.extraParameters().put("token","x")).isInstanceOf(UnsupportedOperationException.class);
        values.values().forEach(chars->assertThat(chars).containsOnly('\0'));
        var legacy=OperationTicket.builder().operationNo("OP").billno("BO").adapterType("QNM").timeoutSeconds(7).build();
        assertThat(legacy.connectTimeoutMs()).isEqualTo(7000);assertThat(legacy.readTimeoutMs()).isEqualTo(7000);
    }
    @Test void missingOutboundMaterialFailsAndStillWipesResolvedArrays() {
        var resolver=mock(com.aifuli.module.supplychain.infrastructure.security.CredentialSetResolver.class);char[] app="sensitive-app".toCharArray();
        when(resolver.resolveSet(42L)).thenReturn(Map.of("appSecret",app));
        var factory=new FulfillmentOperationTicketFactory(mock(CredentialSlotMapper.class),List.of(),new FulfillmentCallbackProperties(),List.of(resolver));
        var config=new PublishedConnectorConfiguration("CON",71L,1,"TEST_SET","https://supplier.invalid","MERCHANT",null,null,Set.of(),42L,
                com.aifuli.module.supplychain.supplieradapter.metadata.CredentialBindingMode.CREDENTIAL_SET,1500,2501,Map.of());
        assertThatThrownBy(()->factory.create(FulfillmentOperationDO.builder().operationNo("OP").adapterStepCode("SEND").build(),BOrderDO.builder().bOrderNo("BO").build(),
                FulfillmentRequestDO.builder().build(),SupplierDO.builder().supplierCode("SUP").build(),null,config,FulfillmentOperationKind.QUERY_ORDER,null,null,"TRACE"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(app).containsOnly('\0');
    }
    @Test void credentialFailureIsNotHiddenOrReplacedByEmptySecret() {
        var slots=mock(CredentialSlotMapper.class); var provider=mock(CredentialProvider.class);
        when(slots.selectById(9L)).thenReturn(CredentialSlotDO.builder().id(9L).providerType("TEST").status((short)10).build());
        when(provider.supports("TEST")).thenReturn(true);
        var failure=new IllegalStateException("provider unavailable"); when(provider.resolve(any())).thenThrow(failure);
        var factory=new FulfillmentOperationTicketFactory(slots,List.of(provider),new FulfillmentCallbackProperties());
        var config=new PublishedConnectorConfiguration("CON",71L,1,"ADAPTER","https://supplier.invalid","MERCHANT",9L,null,Set.of());
        assertThatThrownBy(()->factory.resolveCredential(config)).isSameAs(failure);
    }
}
