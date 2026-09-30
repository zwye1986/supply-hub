package com.aifuli.module.supplychain.border.instore.application;

import com.aifuli.module.supplychain.border.instore.dal.mysql.InstoreRefundCaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 到店购退款顺序号生成器：同一支付流水内退款顺序号从 1 递增。
 *
 * <p>同一支付流水最多一个活动退款（BUYER_REFUNDING），其余按 refund_sequence 排队。
 * 与 O2O 退款排序器区别：按 payment_flow_id 而非 o2o_detail_id 排序。
 * 详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#42-表结构ddl-新增-012-instore-channelsql}。</p>
 */
@Service
@RequiredArgsConstructor
public class InstoreRefundSequencer {

    private final InstoreRefundCaseMapper refundCaseMapper;

    /** 返回下一个退款顺序号；基于同一支付流水已有退款 Case 数量 + 1。 */
    public int nextSequence(Long paymentFlowId) {
        long existing = refundCaseMapper.countByFlow(paymentFlowId);
        return (int) existing + 1;
    }
}
