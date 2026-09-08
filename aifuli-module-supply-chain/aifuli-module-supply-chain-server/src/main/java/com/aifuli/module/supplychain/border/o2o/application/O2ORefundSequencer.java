package com.aifuli.module.supplychain.border.o2o.application;

import com.aifuli.module.supplychain.border.o2o.dal.mysql.O2ORefundCaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * O2O 退款顺序号生成器：同一详情内退款顺序号从 1 递增。
 *
 * <p>同一详情最多一个 BUYER_REFUNDING，其余按 refund_sequence 排队。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#9.3-O2ORefundCase}。</p>
 */
@Service
@RequiredArgsConstructor
public class O2ORefundSequencer {

    private final O2ORefundCaseMapper refundCaseMapper;

    /** 返回下一个退款顺序号；基于已有退款 Case 数量 + 1。 */
    public int nextSequence(Long o2oDetailId) {
        long existing = refundCaseMapper.countByDetail(o2oDetailId);
        return (int) existing + 1;
    }
}
