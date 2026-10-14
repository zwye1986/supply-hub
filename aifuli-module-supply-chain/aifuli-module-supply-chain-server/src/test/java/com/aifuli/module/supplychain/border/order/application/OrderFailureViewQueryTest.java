package com.aifuli.module.supplychain.border.order.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.*;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.domain.FundReferenceType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class OrderFailureViewQueryTest {
    @Test
    void cancelledPriceMismatchKeepsOriginalAmountsAndMissingReservationUnknown() {
        var cases = mock(FulfillmentExceptionCaseMapper.class);
        var requests = mock(FulfillmentRequestMapper.class);
        var funds = mock(FundReservationService.class);
        var observations = mock(RawStatusObservationMapper.class);
        var order = BOrderDO.builder().id(1L).fulfillmentRequestId(2L)
                .expectedSupplierSettlementAmount(9500L).reportedSupplierSettlementAmount(20000L)
                .terminalReasonCode("HUASHENG_SUPPLIER_CANCELLED").build();
        when(requests.selectById(2L)).thenReturn(FulfillmentRequestDO.builder()
                .buyerId(3L).requestNo("FR_1").build());
        when(cases.selectList(any())).thenReturn(List.of(FulfillmentExceptionCaseDO.builder().caseNo("FC_1").build()));
        when(observations.selectList(any())).thenReturn(List.of(RawStatusObservationDO.builder().externalStatus("CANCEL").build()));
        var result = new OrderFailureViewQuery(cases, requests, funds, observations).read(order);
        assertThat(result.fundDisposition()).isNull();
        assertThat(result.supplierSettlementDifferenceAmount()).isEqualTo(10500L);
        assertThat(result.expectedSupplierSettlementAmount()).isEqualTo(9500L);
        assertThat(result.reportedSupplierSettlementAmount()).isEqualTo(20000L);
        assertThat(result.externalSupplierStatus()).isEqualTo("CANCEL");
        assertThat(result.caseNo()).isEqualTo("FC_1");
        verify(funds).findSnapshotByReference(3L, FundReferenceType.CARD_ORDER.code(), "FR_1");
        verifyNoMoreInteractions(funds);
    }
    @Test
    void independentSettlementAccountDrivesFailureSummary() {
        var cases = mock(FulfillmentExceptionCaseMapper.class);
        var requests = mock(FulfillmentRequestMapper.class);
        var funds = mock(FundReservationService.class);
        var observations = mock(RawStatusObservationMapper.class);
        var order = BOrderDO.builder().id(1L).fulfillmentRequestId(2L)
                .expectedSupplierSettlementAmount(9500L).reportedSupplierSettlementAmount(20000L)
                .terminalReasonCode("HUASHENG_SUPPLIER_CANCELLED").build();
        when(requests.selectById(2L)).thenReturn(FulfillmentRequestDO.builder()
                .buyerId(3L).settlementBuyerId(4L).requestNo("FR_1").build());
        when(cases.selectList(any())).thenReturn(List.of(FulfillmentExceptionCaseDO.builder().caseNo("FC_1").build()));
        when(observations.selectList(any())).thenReturn(List.of(RawStatusObservationDO.builder().externalStatus("CANCEL").build()));
        var result = new OrderFailureViewQuery(cases, requests, funds, observations).read(order);
        assertThat(result.fundDisposition()).isNull();
        assertThat(result.supplierSettlementDifferenceAmount()).isEqualTo(10500L);
        assertThat(result.expectedSupplierSettlementAmount()).isEqualTo(9500L);
        assertThat(result.reportedSupplierSettlementAmount()).isEqualTo(20000L);
        assertThat(result.externalSupplierStatus()).isEqualTo("CANCEL");
        assertThat(result.caseNo()).isEqualTo("FC_1");
        verify(funds).findSnapshotByReference(4L, FundReferenceType.CARD_ORDER.code(), "FR_1");
        verifyNoMoreInteractions(funds);
    }
}
