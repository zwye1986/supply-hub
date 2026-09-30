package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreOperationDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstorePaymentFlowDO;
import com.aifuli.module.supplychain.border.instore.dal.dataobject.InstoreRefundCaseDO;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreOperationMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstorePaymentFlowMapper;
import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreRefundCaseMapper;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOperationStatus;
import com.aifuli.module.supplychain.border.instore.domain.InstoreOperationType;
import com.aifuli.module.supplychain.border.instore.domain.InstoreRefundStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 到店购操作恢复：终态跳过；非终态按原 outTradeNo/trade_id/refundCaseNo 决策查单，禁止生成新单号。
 *
 * <p>退款操作（BUYER_REFUND/REFUND_QUERY）只接受数据库固化的原 refundCaseNo；
 * Case 已终态时跳过权威查询。详见
 * {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#4-中台侧场景详情模块}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreOperationRecoveryHandlerTest {

    @Mock
    private InstoreOperationMapper operationMapper;
    @Mock
    private InstorePaymentFlowMapper paymentFlowMapper;
    @Mock
    private InstoreRefundCaseMapper refundCaseMapper;
    @Mock
    private InstorePaymentConvergenceService convergenceService;
    @Mock
    private InstoreRefundConvergenceService refundConvergenceService;

    private InstoreOperationRecoveryHandler handler;

    @BeforeEach
    void setUp() {
        handler = new InstoreOperationRecoveryHandler(operationMapper, paymentFlowMapper,
                refundCaseMapper, convergenceService, refundConvergenceService);
    }

    @Test
    void rejectsMissingOperation() {
        when(operationMapper.selectByOperationNo("IOP_missing")).thenReturn(null);
        assertThatThrownBy(() -> handler.recover("IOP_missing"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Instore operation not found");
        verifyNoInteractions(convergenceService, refundConvergenceService);
    }

    @ParameterizedTest
    @EnumSource(value = InstoreOperationStatus.class, names = {"SUCCESS", "FAILED"})
    void skipsTerminalOperations(InstoreOperationStatus terminal) {
        when(operationMapper.selectByOperationNo("IOP_done")).thenReturn(operation(
                InstoreOperationType.PREORDER, terminal, 3L));
        InstoreOperationRecoveryHandler.RecoveryDecision decision = handler.recover("IOP_done");
        assertThat(decision.action()).isEqualTo("ALREADY_TERMINAL");
        assertThat(decision.operationVersion()).isEqualTo(3L);
        verifyNoInteractions(paymentFlowMapper, refundCaseMapper, convergenceService, refundConvergenceService);
    }

    @Test
    void preorderQueriesOriginalOutTradeNo() {
        when(operationMapper.selectByOperationNo("IOP_1")).thenReturn(operation(
                InstoreOperationType.PREORDER, InstoreOperationStatus.UNKNOWN, 1L));
        when(paymentFlowMapper.selectById(300L)).thenReturn(flow());
        InstoreOperationRecoveryHandler.RecoveryDecision decision = handler.recover("IOP_1");
        assertThat(decision.action()).isEqualTo("QUERY_PAYMENT:OTN_1");
        assertThat(decision.operationType()).isEqualTo(InstoreOperationType.PREORDER.name());
        verify(convergenceService).queryAndConvergePaymentByOutTradeNo(
                "OTN_1", "TIMER_RECOVERY:IOP_1");
        verifyNoInteractions(refundCaseMapper, refundConvergenceService);
    }

    @Test
    void cancelPayQueriesOriginalTradeId() {
        when(operationMapper.selectByOperationNo("IOP_2")).thenReturn(operation(
                InstoreOperationType.CANCEL_PAY, InstoreOperationStatus.RUNNING, 2L));
        when(paymentFlowMapper.selectById(300L)).thenReturn(flow());
        assertThat(handler.recover("IOP_2").action()).isEqualTo("QUERY_CANCEL:TRADE001");
        verifyNoInteractions(refundCaseMapper, refundConvergenceService);
    }

    @ParameterizedTest
    @EnumSource(value = InstoreOperationType.class, names = {"BUYER_REFUND", "REFUND_QUERY"})
    void refundOperationsConvergeByOriginalRefundCaseNo(InstoreOperationType type) {
        when(operationMapper.selectByOperationNo("IOP_3")).thenReturn(refundOperation(
                type, InstoreOperationStatus.PENDING, 0L, 500L));
        when(refundCaseMapper.selectById(500L)).thenReturn(refundCase(
                InstoreRefundStatus.BUYER_REFUNDING));
        InstoreOperationRecoveryHandler.RecoveryDecision decision = handler.recover("IOP_3");
        assertThat(decision.action()).isEqualTo("QUERY_REFUND:RCN_500");
        assertThat(decision.operationType()).isEqualTo(type.name());
        verify(refundConvergenceService).queryAndConvergeRefundByRefundCaseNo(
                "RCN_500", "TIMER_RECOVERY:IOP_3");
        verifyNoInteractions(paymentFlowMapper, convergenceService);
    }

    @ParameterizedTest
    @EnumSource(value = InstoreRefundStatus.class, names = {"SUCCESS", "FAILED", "COMPLETED", "MANUAL"})
    void refundOperationsSkipTerminalRefundCase(InstoreRefundStatus terminal) {
        when(operationMapper.selectByOperationNo("IOP_4")).thenReturn(refundOperation(
                InstoreOperationType.BUYER_REFUND, InstoreOperationStatus.PENDING, 0L, 500L));
        when(refundCaseMapper.selectById(500L)).thenReturn(refundCase(terminal));
        InstoreOperationRecoveryHandler.RecoveryDecision decision = handler.recover("IOP_4");
        assertThat(decision.action()).isEqualTo("ALREADY_CONVERGED:RCN_500");
        verifyNoInteractions(refundConvergenceService, paymentFlowMapper, convergenceService);
    }

    @Test
    void refundOperationRejectsMissingRefundCaseId() {
        when(operationMapper.selectByOperationNo("IOP_5")).thenReturn(refundOperation(
                InstoreOperationType.BUYER_REFUND, InstoreOperationStatus.PENDING, 0L, null));
        assertThatThrownBy(() -> handler.recover("IOP_5"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refund operation missing refundCaseId");
        verifyNoInteractions(refundCaseMapper, refundConvergenceService);
    }

    @Test
    void refundOperationRejectsDisappearedRefundCase() {
        when(operationMapper.selectByOperationNo("IOP_6")).thenReturn(refundOperation(
                InstoreOperationType.REFUND_QUERY, InstoreOperationStatus.UNKNOWN, 1L, 500L));
        when(refundCaseMapper.selectById(500L)).thenReturn(null);
        assertThatThrownBy(() -> handler.recover("IOP_6"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refund case disappeared during recovery");
        verifyNoInteractions(refundConvergenceService);
    }

    @Test
    void rejectsMissingPaymentFlow() {
        when(operationMapper.selectByOperationNo("IOP_orphan")).thenReturn(operation(
                InstoreOperationType.PAYMENT_QUERY, InstoreOperationStatus.UNKNOWN, 1L));
        when(paymentFlowMapper.selectById(300L)).thenReturn(null);
        assertThatThrownBy(() -> handler.recover("IOP_orphan"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Payment flow disappeared");
    }

    private static InstoreOperationDO operation(InstoreOperationType type,
                                                 InstoreOperationStatus status,
                                                 long version) {
        return refundOperation(type, status, version, null);
    }

    private static InstoreOperationDO refundOperation(InstoreOperationType type,
                                                       InstoreOperationStatus status,
                                                       long version,
                                                       Long refundCaseId) {
        return InstoreOperationDO.builder()
                .id(40L)
                .operationNo("IOP_x")
                .paymentFlowId(300L)
                .refundCaseId(refundCaseId)
                .operationType(type.name())
                .status(status.getCode())
                .version(version)
                .build();
    }

    private static InstorePaymentFlowDO flow() {
        return InstorePaymentFlowDO.builder()
                .id(300L)
                .outTradeNo("OTN_1")
                .tradeId("TRADE001")
                .build();
    }

    private static InstoreRefundCaseDO refundCase(InstoreRefundStatus status) {
        return InstoreRefundCaseDO.builder()
                .id(500L)
                .refundCaseNo("RCN_500")
                .status(status.getCode())
                .build();
    }
}
