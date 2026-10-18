package com.aifuli.module.supplychain.border.fulfillment.application.singlestep;

import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentOperationTicketFactory;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.*;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.io.IOException;
import java.util.HexFormat;

/**
 * 所有单步执行/补查触发的事务外编排入口；秘密在认领前准备并由try(ticket)统一释放。
 * 只对Metadata显式启用的类型工作，通用频控查询与原单Timer竞争同一Lifecycle资格。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#47-t03本地执行与恢复接线2026-10-18。
 */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
@Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.NOT_SUPPORTED)
public class SingleStepFulfillmentRunner {
    private final FulfillmentOperationMapper operations;
    private final BOrderMapper bOrders;
    private final FulfillmentRequestMapper requests;
    private final SupplyChainOrderMapper orders;
    private final SupplierMapper suppliers;
    private final PublishedConnectorConfigurationResolver connectors;
    private final FulfillmentOperationTicketFactory tickets;
    private final SingleStepFulfillmentLifecycle lifecycle;
    private final OperationExecutorPort executor;
    private final com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaService merchantQuota;

    public boolean supports(Long connectorVersionId) {
        return connectors.singleStepRecoveryContract(connectorVersionId) != null;
    }

    public String runExecute(String operationNo, String originTraceId) {
        return run(operationNo, SingleStepAction.CREATE, null, originTraceId);
    }

    public String runRecovery(String operationNo, Long expectedGeneration, String originTraceId) {
        return run(operationNo, SingleStepAction.QUERY, expectedGeneration, originTraceId);
    }

    /** 成功保护独立提交后才动原预留；本入口不持外层事务或锁。设计见详细设计§7.6。 */
    public String applyResult(SingleStepExecutionPermit permit, OperationOutcome outcome, String traceId) {
        lifecycle.observeSuccess(permit, outcome, traceId);
        return lifecycle.apply(permit, outcome, traceId);
    }

    private String run(String operationNo, SingleStepAction action, Long expectedGeneration, String originTraceId) {
        var op = operations.selectOne(FulfillmentOperationDO::getOperationNo, operationNo);
        if (op == null) return "NOT_FOUND";
        var bo = bOrders.selectById(op.getBOrderId());
        if (bo == null || !supports(bo.getConnectorVersionId())) throw new IllegalStateException("非单步骤执行原单");
        if (op.getStatus() == SupplierOrderOperationStatus.MANUAL.getCode()
                || op.getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode()
                || op.getStatus() == SupplierOrderOperationStatus.FAILED.getCode()) return "ALREADY_COMPLETED";
        if (expectedGeneration != null && expectedGeneration.longValue() != op.getExecutionGeneration()) return "STALE";
        if (action == SingleStepAction.CREATE && op.getBusinessSendPossibleAt() != null) return "CREATE_ALREADY_MARKED";
        if (action == SingleStepAction.QUERY) {
            // QUERY任务也先补交独立成功；进度已落未知时不能绕过保护再次外呼。设计见§7.6。
            var localResult = lifecycle.watchdog(operationNo, op.getExecutionGeneration(), originTraceId);
            if (op.getStatus() == SupplierOrderOperationStatus.EXECUTING.getCode() || !"STALE".equals(localResult)) return localResult;
        }
        if (lifecycle.capacityPending(operationNo, action, op.getExecutionGeneration())) return "CAPACITY_WAITING";
        OperationTicket ticket;
        try {
            ticket = tickets.createForSingleStep(op, bo, requests.selectById(bo.getFulfillmentRequestId()), orders.selectById(bo.getOrderId()),
                    suppliers.selectById(bo.getSupplierId()), action == SingleStepAction.CREATE ? FulfillmentOperationKind.FULFILL : FulfillmentOperationKind.QUERY_ORDER,
                    originTraceId, Math.incrementExact(op.getExecutionGeneration()));
        } catch (RuntimeException failure) {
            lifecycle.preparationFailed(operationNo, op.getExecutionGeneration(), false);
            throw safeFailure(failure, 0);
        }
        try (ticket) {
            var binding = new SingleStepCheckpoint.Binding(operationNo, bo.getId(), ticket.adapterType(), AdapterStepCode.SEND,
                    bo.getSupplierId(), bo.getConnectorVersionId(), bo.getOfferVersionId(), HexFormat.of().formatHex(op.getConfigDigest()));
            com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepExecutionPermit permit;
            try {
                permit = lifecycle.claim(operationNo, action, op.getExecutionGeneration(), binding, originTraceId);
            } catch (com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaService.CapacityUnavailable waiting) {
                return lifecycle.waitForCapacity(operationNo, action, op.getExecutionGeneration(), waiting.retryAt(), originTraceId);
            } catch (com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaService.MissingConfiguration missing) {
                lifecycle.preparationFailed(operationNo, op.getExecutionGeneration(), false);
                return "MANUAL";
            }
            if (permit == null) return "CLAIM_SKIPPED";
            OperationOutcome outcome;
            try {
                // 资源释放包在实际执行的内层finally，网络/程序故障分类和结果事务都在释放之后。
                try {
                    if (!lifecycle.canDispatch(permit)) return "DISPATCH_SKIPPED";
                    outcome = executor.execute(ticket, permit.deadlineNanos());
                } finally {
                    releaseQuota(operationNo, permit.generation());
                }
            } catch (RuntimeException failure) {
                // 仅明确I/O因果按网络未知；鉴权/协议及程序异常不能借普通网络分支继续自动查。
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof IOException) return lifecycle.networkUnknown(permit, originTraceId);
                }
                lifecycle.executionFailed(permit);
                throw safeFailure(failure, 0);
            }
            // 结果持久化失败必须让Timer重试；已提交认领和watchdog不回滚、不重发CREATE。
            return applyResult(permit, outcome, originTraceId);
        }
    }

    /**
     * HTTP结束后幂等回收；不再读取可变Metadata，避免finally配置读取失败掩盖可信结果。
     * 无额度的隔离旧单步返回空租约即可；诊断只在此降级边界记录，租约过期仍可回收。
     * 设计：docs/superpowers/specs/2026-10-18-single-step-merchant-quota-design.md#63-锁序与释放。
     */
    private void releaseQuota(String operationNo, long generation) {
        try { merchantQuota.release(operationNo, generation); }
        catch (RuntimeException releaseFailure) {
            log.error("[merchantQuotaRelease][商户调用占用回收降级] operationNo={}, generation={}",
                    operationNo, generation, safeFailure(releaseFailure, 0));
        }
    }

    private static IllegalStateException safeFailure(Throwable failure, int depth) {
        var safe = new IllegalStateException("单步骤执行故障: " + failure.getClass().getName(),
                failure.getCause() == null || depth >= 8 ? null : safeFailure(failure.getCause(), depth + 1));
        safe.setStackTrace(failure.getStackTrace());
        return safe;
    }
}
