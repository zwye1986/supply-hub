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

    @Test
    void createForHuashengSuccessfullyBuildsTicketWithPlaintextCheckpointAndPermitGeneration() {
        var crypto = new com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto("test-key",
                Base64.getEncoder().encodeToString(new byte[32]), Base64.getEncoder().encodeToString(new byte[32]));
        var codec = new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpointCodec(crypto);
        var factory = new FulfillmentOperationTicketFactory(mock(CredentialSlotMapper.class), List.of(),
                new FulfillmentCallbackProperties(), List.of(), codec);

        byte[] configDigest = new byte[32];
        Arrays.fill(configDigest, (byte) 1);
        var binding = new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint.Binding(
                100L, "BO_100", "OP_100", 1, AdapterStepCode.PLACE_ORDER, 71L, 81L, configDigest);
        var placeParams = new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint.PlaceParameters(
                "BILL_100", 9999L, 1, 300, "13800138000", "https://api.aifuli.cn/callbacks/suppliers/huasheng/71");
        var checkpoint = new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint(
                binding, placeParams, null, null,
                new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint.Progress(
                        0, 0, 0, null, null, "TRACE_ORIGIN"), null);
        var stored = codec.encrypt(checkpoint);

        var operation = FulfillmentOperationDO.builder()
                .operationNo("OP_100")
                .bOrderId(100L)
                .sequenceNo(1)
                .adapterStepCode("PLACE_ORDER")
                .operationKind("FULFILL")
                .idempotencyKey("OP_KEY_1")
                .configDigest(configDigest)
                .executionGeneration(5L)
                .checkpointSchemaVersion(stored.schemaVersion())
                .checkpointKeyVersion(stored.keyVersion())
                .checkpointCiphertext(stored.ciphertext())
                .build();

        var bOrder = BOrderDO.builder()
                .id(100L)
                .bOrderNo("BO_100")
                .billno("BILL_100")
                .connectorVersionId(71L)
                .offerVersionId(81L)
                .requestedQuantity(1L)
                .build();

        var request = FulfillmentRequestDO.builder().targetSchemaCode("CARD_CODE_SECRET").build();
        var supplier = SupplierDO.builder().supplierCode("SUP_HS").build();
        var offerVersion = SupplierOfferVersionDO.builder().externalSkuCode("9999").build();

        var frozenItem = new FrozenCredentialItemIdentity(1L, "appSecret", "DB_AES_GCM", 1);
        var config = new PublishedConnectorConfiguration("CON_HS", 71L, 1,
                com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata.TYPE,
                "https://huasheng.api", "APP_ID_16_BYTES_", null, "/callbacks/suppliers/huasheng",
                Set.of(), 42L, com.aifuli.module.supplychain.supplieradapter.metadata.CredentialBindingMode.CREDENTIAL_SET,
                3000, 5000, Map.of("expireTime", "300"), List.of(frozenItem));

        var permit = new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengExecutionPermit(
                "OP_100", 100L, 5L, AdapterStepCode.PLACE_ORDER,
                com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengFlowTrigger.EXECUTE,
                java.time.LocalDateTime.now().plusMinutes(5));

        var credentialMaterial = new OperationTicket.CredentialMaterial("secret-123", "pem-456");

        var ticket = factory.createForHuasheng(operation, bOrder, request, supplier, offerVersion,
                config, permit, credentialMaterial, "TRACE_CURR");

        assertThat(ticket.operationNo()).isEqualTo("OP_100");
        assertThat(ticket.bOrderNo()).isEqualTo("BO_100");
        assertThat(ticket.billno()).isEqualTo("BILL_100");
        assertThat(ticket.adapterType()).isEqualTo("HUA_SHENG_ORDER_V1");
        assertThat(ticket.adapterStepCode()).isEqualTo(AdapterStepCode.PLACE_ORDER);
        assertThat(ticket.operationKind()).isEqualTo(FulfillmentOperationKind.FULFILL);
        assertThat(ticket.callbackUrl()).isEqualTo("https://api.aifuli.cn/callbacks/suppliers/huasheng/71");
        assertThat(ticket.executionGeneration()).isEqualTo(5L);
        assertThat(ticket.credentialSetId()).isEqualTo(42L);
        assertThat(ticket.frozenCredentialItems()).containsExactly(frozenItem);
        assertThat(ticket.credentialMaterial()).isEqualTo(credentialMaterial);
        assertThat(ticket.secretKey()).isNull();

        // Checkpoint payload is plaintext bytes, decodable by codec.decodePlaintext
        assertThat(ticket.checkpointPayload()).isNotNull();
        var decodedFromTicket = codec.decodePlaintext(ticket.checkpointPayload());
        assertThat(decodedFromTicket.binding().bOrderNo()).isEqualTo("BO_100");
        assertThat(decodedFromTicket.placeParameters().outOrderSn()).isEqualTo("BILL_100");
    }

    @Test
    void createForHuashengFailsWhenOutOrderSnDoesNotMatchBOrderBillno() {
        var crypto = new com.aifuli.module.supplychain.infrastructure.security.AesGcmEnvelopeCrypto("test-key",
                Base64.getEncoder().encodeToString(new byte[32]), Base64.getEncoder().encodeToString(new byte[32]));
        var codec = new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpointCodec(crypto);
        var factory = new FulfillmentOperationTicketFactory(mock(CredentialSlotMapper.class), List.of(),
                new FulfillmentCallbackProperties(), List.of(), codec);

        byte[] configDigest = new byte[32];
        var binding = new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint.Binding(
                100L, "BO_100", "OP_100", 1, AdapterStepCode.PLACE_ORDER, 71L, 81L, configDigest);
        var placeParams = new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint.PlaceParameters(
                "BILL_ORIGINAL", 9999L, 1, 300, null, "https://api.aifuli.cn/callbacks/suppliers/huasheng/71");
        var checkpoint = new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint(
                binding, placeParams, null, null,
                new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengCheckpoint.Progress(
                        0, 0, 0, null, null, null), null);
        var stored = codec.encrypt(checkpoint);

        var operation = FulfillmentOperationDO.builder()
                .operationNo("OP_100").bOrderId(100L).sequenceNo(1).adapterStepCode("PLACE_ORDER")
                .configDigest(configDigest).executionGeneration(1L)
                .checkpointSchemaVersion(stored.schemaVersion()).checkpointKeyVersion(stored.keyVersion())
                .checkpointCiphertext(stored.ciphertext()).build();

        var bOrder = BOrderDO.builder().id(100L).bOrderNo("BO_100").billno("BILL_TAMPERED")
                .connectorVersionId(71L).offerVersionId(81L).build();

        var config = new PublishedConnectorConfiguration("CON_HS", 71L, 1,
                com.aifuli.module.supplychain.supplieradapter.metadata.HuashengOrderV1Metadata.TYPE,
                "https://huasheng.api", "APP_ID_16_BYTES_", null, "/callbacks/suppliers/huasheng",
                Set.of(), 42L, com.aifuli.module.supplychain.supplieradapter.metadata.CredentialBindingMode.CREDENTIAL_SET,
                3000, 5000, Map.of(), List.of());

        var permit = new com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengExecutionPermit(
                "OP_100", 100L, 1L, AdapterStepCode.PLACE_ORDER,
                com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengFlowTrigger.EXECUTE,
                java.time.LocalDateTime.now().plusMinutes(5));

        assertThatThrownBy(() -> factory.createForHuasheng(operation, bOrder, FulfillmentRequestDO.builder().build(),
                SupplierDO.builder().supplierCode("SUP").build(), SupplierOfferVersionDO.builder().build(),
                config, permit, new OperationTicket.CredentialMaterial("sec", "pem"), "TRACE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("billno");
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
