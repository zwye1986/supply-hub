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
