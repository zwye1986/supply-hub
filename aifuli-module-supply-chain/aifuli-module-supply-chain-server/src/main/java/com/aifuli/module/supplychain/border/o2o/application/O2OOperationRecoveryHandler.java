package com.aifuli.module.supplychain.border.o2o.application;

import com.aifuli.module.supplychain.border.o2o.dal.dataobject.O2OChannelDetailDO;
import com.aifuli.module.supplychain.border.o2o.dal.dataobject.O2OOperationDO;
import com.aifuli.module.supplychain.border.o2o.dal.mysql.O2OChannelDetailMapper;
import com.aifuli.module.supplychain.border.o2o.dal.mysql.O2OOperationMapper;
import com.aifuli.module.supplychain.border.o2o.domain.O2OOperationStatus;
import com.aifuli.module.supplychain.border.o2o.domain.O2OOperationType;
import com.aifuli.module.supplychain.integration.buyer.BuyerCashierPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * O2O 操作恢复处理器：PersistentTimer 用原 outTradeNo/invocationId + expectedVersion 重新进入 Lifecycle。
 *
 * <p>禁止生成新支付单号；网络超时保持原 outTradeNo/invocationId 和冻结，由 Timer 查单收敛。
 * 详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6-关键编排与幂等}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class O2OOperationRecoveryHandler {

    private final O2OOperationMapper operationMapper;
    private final O2OChannelDetailMapper detailMapper;
    private final BuyerCashierPort buyerCashierPort;
    private final O2OChannelLifecycle lifecycle;

    /** 按操作号恢复：对结果未知的 PREORDER 操作按原 outTradeNo 查支付结果。 */
    public void recover(String operationNo) {
        log.info("[O2O][recover] 入口 operationNo={}", operationNo);
        O2OOperationDO operation = operationMapper.selectByOperationNo(operationNo);
        if (operation == null) {
            log.warn("[O2O][recover] operation not found: {}", operationNo);
            return;
        }
        if (operation.getStatus() != O2OOperationStatus.UNKNOWN.getCode()
                && operation.getStatus() != O2OOperationStatus.RUNNING.getCode()) {
            log.info("[O2O][recover] 跳过非未知状态 operationNo={}, status={}", operationNo, operation.getStatus());
            return;
        }
        O2OChannelDetailDO detail = detailMapper.selectById(operation.getO2oDetailId());
        if (detail == null) {
            log.warn("[O2O][recover] 详情缺失 operationNo={}, detailId={}", operationNo, operation.getO2oDetailId());
            return;
        }
        if (O2OOperationType.PREORDER.name().equals(operation.getOperationType())) {
            long startMs = System.currentTimeMillis();
            log.info("[O2O][cashier] queryPayment 请求 outTradeNo={}, operationNo={}",
                    detail.getOutTradeNo(), operationNo);
            BuyerCashierPort.QueryPaymentResult result = buyerCashierPort.queryLegacyPayment(detail.getOutTradeNo());
            log.info("[O2O][cashier] queryPayment 响应 outTradeNo={}, status={}, confirmedAmount={}, cost={}ms",
                    detail.getOutTradeNo(), result.status(), result.confirmedAmountCny(),
                    System.currentTimeMillis() - startMs);
            // TODO: 按 QueryPaymentResult 终态收敛 Operation/Detail，需与 confirmBuyerPayment 共用收敛路径。
            // 详见 docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#18.6-关键编排与幂等
        }
    }
}
