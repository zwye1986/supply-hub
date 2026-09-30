package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.supplieradapter.metadata.*;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RechargeFrequencyQueryServiceTest {
    private final PublishedConnectorConfigurationResolver connectors=mock(PublishedConnectorConfigurationResolver.class);
    private final SupplierMapper suppliers=mock(SupplierMapper.class);
    private final SupplierOfferVersionMapper offers=mock(SupplierOfferVersionMapper.class);
    private final OperationExecutorPort executor=mock(OperationExecutorPort.class);
    private final SupplierOrderLifecycle lifecycle=mock(SupplierOrderLifecycle.class);
    private final RechargeFrequencyQueryService service=new RechargeFrequencyQueryService(connectors,suppliers,offers,
            new FulfillmentOperationTicketFactory(mock(CredentialSlotMapper.class),List.of(),new FulfillmentCallbackProperties()),executor,lifecycle);
    private final RechargeFrequencyRecoveryService.QueryWork work;
    RechargeFrequencyQueryServiceTest() {
        var operation=FulfillmentOperationDO.builder().operationNo("OP_ORIGINAL").operationKind("FULFILL").adapterStepCode("SEND").build();
        var bo=BOrderDO.builder().bOrderNo("BO_ORIGINAL").supplierId(9L).offerVersionId(8L).connectorVersionId(7L).build();
        work=new RechargeFrequencyRecoveryService.QueryWork(new RechargeFrequencyLifecycle.LockedExecution(null,null,
                FulfillmentRequestDO.builder().targetCiphertext(new byte[]{0}).build(),SupplyChainOrderDO.builder().build(),bo,operation,List.of(bo),List.of(operation),null),
                new RechargeFrequencyExecutionPermit(1L,2L,"OP_ORIGINAL",3L,4L,5L));
        when(connectors.resolve(7L)).thenReturn(new PublishedConnectorConfigurationResolver.PublishedConnectorConfiguration("CON",7L,1,"ADAPTER","https://supplier.invalid","merchant",null,null,Set.of(Capability.QUERY_ORDER)));
        when(executor.supports("ADAPTER")).thenReturn(true); when(suppliers.selectById(9L)).thenReturn(SupplierDO.builder().supplierCode("SUP").build());
    }
    @Test void nullAdapterResponsePreservesSafeFailureWithoutWritingResult() {
        assertThatThrownBy(()->service.query(work,"NULL")).hasMessage("FREQUENCY_QUERY_FAILED"); verifyNoInteractions(lifecycle);
    }
    @Test void timeoutTransportCannotSupplyTrustedSuccess() {
        when(executor.execute(any())).thenReturn(OperationOutcome.builder().outcomeClass(OperationOutcomeClass.SUCCEEDED)
                .callDigest(CallDigestFact.builder().build()).rawObservation(RawObservationFact.builder().sourceType("QUERY_RESPONSE").externalStatus("TIMEOUT").build()).build());
        service.query(work,"TIMEOUT"); assertThat(fact().outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
    }
    @Test void unsupportedConfiguredAdapterCannotFallbackToSend() {
        when(executor.supports("ADAPTER")).thenReturn(false); service.query(work,"UNSUPPORTED");
        verify(executor,never()).execute(any()); verifyNoInteractions(lifecycle);
    }
    @Test void notFoundObservationKeepsUnknownWithoutInventingTerminal() {
        var observation=RawObservationFact.builder().sourceType("QUERY_RESPONSE").externalStatus("NOT_FOUND").build();
        when(executor.execute(any())).thenReturn(OperationOutcome.builder().outcomeClass(OperationOutcomeClass.NOT_FOUND).rawObservation(observation).build());
        service.query(work,"NOT_FOUND"); var fact=fact(); assertThat(fact.outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
        assertThat(fact.rawObservation()).isSameAs(observation);
    }
    @Test void terminalClassificationOnHttp404IsNotTrustedFailure() { httpFailure(OperationOutcomeClass.REJECTED,404); }
    @Test void terminalClassificationOnHttp401IsNotTrustedSuccess() { httpFailure(OperationOutcomeClass.SUCCEEDED,401); }
    private void httpFailure(OperationOutcomeClass outcome,int status) {
        when(executor.execute(any())).thenReturn(OperationOutcome.builder().outcomeClass(outcome).callDigest(CallDigestFact.builder().httpStatus(status).build()).build());
        service.query(work,"HTTP_FAILURE"); assertThat(fact().outcomeClass()).isEqualTo(OperationOutcomeClass.RESULT_UNKNOWN);
    }
    @Test void credibleQueryOutcomeRetainsOriginalPermitAndFrozenQueryTicket() {
        when(executor.execute(any())).thenReturn(OperationOutcome.builder().outcomeClass(OperationOutcomeClass.SUCCEEDED).callDigest(CallDigestFact.builder().httpStatus(200).build())
                .rawObservation(RawObservationFact.builder().sourceType("QUERY_RESPONSE").externalStatus("SUCCESS").build()).build());
        service.query(work,"QUERY"); assertThat(fact().outcomeClass()).isEqualTo(OperationOutcomeClass.SUCCEEDED);
        var ticket=ArgumentCaptor.forClass(OperationTicket.class); verify(executor).execute(ticket.capture());
        assertThat(ticket.getValue().operationKind()).isEqualTo(FulfillmentOperationKind.QUERY_ORDER);
        assertThat(ticket.getValue().operationNo()).isEqualTo("OP_ORIGINAL"); assertThat(ticket.getValue().rechargeAccount()).isNull();
    }
    @Test void networkExceptionPropagatesSafeCausalFailureForTimerWithoutChangingHolder() {
        when(executor.execute(any())).thenThrow(new IllegalStateException("mobile=13800138000 secret=evil",new java.io.IOException("raw token")));
        assertThatThrownBy(()->service.query(work,"ERROR")).hasMessage("FREQUENCY_QUERY_FAILED")
                .satisfies(error -> assertThat(error.getCause().getMessage()).isEqualTo(IllegalStateException.class.getName()));
        verifyNoInteractions(lifecycle);
    }
    private OperationOutcome fact() {
        var outcome=ArgumentCaptor.forClass(OperationOutcome.class);
        verify(lifecycle).confirmTrustedOutcome(eq("OP_ORIGINAL"),outcome.capture(),anyString(),same(work.permit()));
        return outcome.getValue();
    }
}
