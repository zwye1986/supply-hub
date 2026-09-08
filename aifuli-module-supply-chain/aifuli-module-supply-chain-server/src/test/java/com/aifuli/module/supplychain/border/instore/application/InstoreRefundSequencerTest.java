package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreRefundCaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 到店购退款顺序号：同一支付流水内从 1 递增，按 payment_flow_id 而非订单头排序。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#42-表结构ddl-新增-012-instore-channelsql}。</p>
 */
@ExtendWith(MockitoExtension.class)
class InstoreRefundSequencerTest {

    @Mock
    private InstoreRefundCaseMapper refundCaseMapper;

    private InstoreRefundSequencer sequencer;

    @BeforeEach
    void setUp() {
        sequencer = new InstoreRefundSequencer(refundCaseMapper);
    }

    @Test
    void startsFromOneWhenFlowHasNoRefundCases() {
        when(refundCaseMapper.countByFlow(88L)).thenReturn(0L);
        assertThat(sequencer.nextSequence(88L)).isEqualTo(1);
    }

    @Test
    void incrementsByExistingCaseCount() {
        when(refundCaseMapper.countByFlow(88L)).thenReturn(3L);
        assertThat(sequencer.nextSequence(88L)).isEqualTo(4);
    }
}
