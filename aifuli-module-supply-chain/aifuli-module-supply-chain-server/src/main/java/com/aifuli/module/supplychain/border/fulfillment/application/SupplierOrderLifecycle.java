package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAssetDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentCallLogDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentExceptionCaseDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentRequestDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RawStatusObservationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentAssetMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentCallLogMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentExceptionCaseMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RawStatusObservationMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.CallDigestFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentAssetStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentCallDirection;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentExceptionCaseStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentExceptionReasonCodes;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentRequestStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.fulfillment.domain.RawObservationFact;
import com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.OrderStatusLogMapper;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus;
import com.aifuli.module.supplychain.border.order.domain.BOrderSettlementStatus;
import com.aifuli.module.supplychain.border.order.domain.CardOrderSummaryPolicy;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryDecision;
import com.aifuli.module.supplychain.border.order.domain.OrderSummaryFact;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.supplieradapter.qnm.QnmMaskingUtils;
import com.aifuli.module.supplychain.tradecore.account.application.FundReservationService;
import com.aifuli.module.supplychain.tradecore.account.domain.FundReferenceType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 供应商订单（BOrder）与履约外部操作生命周期服务。
 *
 * <p>核心职责：在短事务内利用 CAS 乐观锁条件推进 Operation 与 BOrder 状态，
 * 同步更新 FulfillmentRequest 交付进度，对齐 {@link CardOrderSummaryPolicy} 推进总订单公共状态与业务阶段，
 * 记录不可变状态变更审计，并在终态时完成资金 Commit 或 Release 闭环。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#732-task-11-任务执行器接入状态机推进与资产资金闭环}
 * 以及 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierOrderLifecycle {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final byte[] EMPTY_DIGEST = new byte[32];
    private static final int CALL_LOG_REDACTION_VERSION = 1;

    private final BOrderMapper bOrderMapper;
    private final FulfillmentOperationMapper operationMapper;
    private final FulfillmentRequestMapper requestMapper;
    private final SupplyChainOrderMapper orderMapper;
    private final FulfillmentAssetMapper assetMapper;
    private final FulfillmentCallLogMapper callLogMapper;
    private final RawStatusObservationMapper observationMapper;
    private final FulfillmentExceptionCaseMapper exceptionCaseMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final FundReservationService fundReservationService;
    private final CardOrderSummaryPolicy cardOrderSummaryPolicy;
    private final EnvelopeCryptoPort envelopeCrypto;
    private final HmacFingerprintPort hmacFingerprint;
    private final BusinessNoGenerator businessNoGenerator;
    private final SupplyChainDatabaseClock databaseClock;
    private final ObjectMapper objectMapper;
    private final RechargeFrequencyLifecycle frequencyLifecycle;

    /**
     * 根据执行结果状态事实推进 BOrder、Operation、Request、Order 及资金/资产闭环。
     *
     * @param operationNo 履约操作编号
     * @param outcome     标准化履约结果事实
     * @param traceId     调用链跟踪标识
     * @return 状态转换结果
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public TransitionResult transition(String operationNo, OperationOutcome outcome, String traceId) {
        return transitionInternal(operationNo, outcome, traceId, false, null);
    }

    /**
     * 查单、可信回调或内部核实的终态事实入口。MANUAL只能由权威SUCCEEDED/REJECTED恢复，不能据此重新发送。
     * 资格非空时验证原占用代次，迟到旧执行者只留观察，不能影响新持有者或资金。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#5-结果窗口和资金。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public TransitionResult confirmTrustedOutcome(String operationNo, OperationOutcome outcome, String traceId,
                                                   RechargeFrequencyExecutionPermit permit) {
        return transitionInternal(operationNo, outcome, traceId, true, permit);
    }

    private TransitionResult transitionInternal(String operationNo, OperationOutcome outcome, String traceId,
                                               boolean trustedRecovery, RechargeFrequencyExecutionPermit permit) {
        log.info("[供应商订单][lifecycle] transition 入口 operationNo={}, outcomeClass={}, traceId={}",
                operationNo, outcome != null ? outcome.outcomeClass() : null, traceId);
        Objects.requireNonNull(operationNo, "operationNo must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");

        var locked = frequencyLifecycle.lockForResult(operationNo);
        FulfillmentOperationDO operation = locked != null ? locked.operation() : operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>()
                        .eq(FulfillmentOperationDO::getOperationNo, operationNo));
        if (operation == null) {
            throw new IllegalArgumentException("FulfillmentOperation does not exist: " + operationNo);
        }
        BOrderDO bOrder = locked != null ? locked.bOrder() : bOrderMapper.selectById(operation.getBOrderId());
        if (bOrder == null) {
            throw new IllegalStateException("BOrder does not exist for operation: " + operationNo);
        }
        FulfillmentRequestDO request = locked != null ? locked.request() : requestMapper.selectById(bOrder.getFulfillmentRequestId());
        if (request == null) {
            throw new IllegalStateException("FulfillmentRequest does not exist: " + bOrder.getFulfillmentRequestId());
        }
        SupplyChainOrderDO order = locked != null ? locked.order() : orderMapper.selectById(bOrder.getOrderId());
        if (order == null) {
            throw new IllegalStateException("SupplyChainOrder does not exist: " + bOrder.getOrderId());
        }

        LocalDateTime now = databaseClock.now();
        // 观察以专用原子SQL去重；RR下的定位预读不能用于判断最新观察存在与否。
        persistCallAndObservation(operation, bOrder, outcome, traceId, now);
        boolean terminal = operation.getStatus() != null && (operation.getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode()
                || operation.getStatus() == SupplierOrderOperationStatus.FAILED.getCode());
        boolean authoritativeTerminal = outcome.outcomeClass() == OperationOutcomeClass.SUCCEEDED
                || outcome.outcomeClass() == OperationOutcomeClass.REJECTED;
        boolean late = locked != null && locked.entry() != null && (!RechargeFrequencyLifecycle.isCurrentHolder(locked)
                || (permit != null && (permit.queueId() != locked.queue().getId() || permit.entryId() != locked.entry().getId()
                || permit.generation() != locked.entry().getOccupancyGeneration()
                || !permit.operationNo().equals(operationNo))));
        if (terminal) {
            if (authoritativeTerminal && ((operation.getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode())
                    != (outcome.outcomeClass() == OperationOutcomeClass.SUCCEEDED))) {
                openContradictoryCase(operation, bOrder, request, order, now);
            }
            if (!late) { frequencyLifecycle.reconcile(locked); }
            return new TransitionResult(operationNo, bOrder.getBOrderNo(), order.getOrderNo(), operation.getStatus(),
                    bOrder.getFulfillmentStatus(), order.getOrderStatus(), true);
        }
        if (late || (operation.getStatus() != null && operation.getStatus() == SupplierOrderOperationStatus.MANUAL.getCode()
                && (!trustedRecovery || !authoritativeTerminal))) {
            return new TransitionResult(operationNo, bOrder.getBOrderNo(), order.getOrderNo(), operation.getStatus(),
                    bOrder.getFulfillmentStatus(), order.getOrderStatus(), false);
        }

        OperationOutcomeClass outcomeClass = outcome.outcomeClass();

        switch (outcomeClass) {
            case SUCCEEDED -> handleSucceeded(operation, bOrder, request, order, outcome, traceId, now);
            case REJECTED -> handleRejected(operation, bOrder, request, order, outcome, traceId, now);
            case RESULT_UNKNOWN -> handleResultUnknown(operation, bOrder, outcome, now);
            case ACCEPTED -> handleAccepted(operation, bOrder, outcome, now);
            default -> handleManualOrOther(operation, bOrder, request, order, outcome, traceId, now);
        }

        frequencyLifecycle.reconcile(locked);
        log.info("[供应商订单][lifecycle] transition 完成 operationNo={}, opStatus={}, bOrderStatus={}, orderStatus={}",
                operation.getOperationNo(), operation.getStatus(), bOrder.getFulfillmentStatus(), order.getOrderStatus());
        boolean isTerminal = operation.getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode()
                || operation.getStatus() == SupplierOrderOperationStatus.FAILED.getCode()
                || operation.getStatus() == SupplierOrderOperationStatus.MANUAL.getCode();
        return new TransitionResult(operation.getOperationNo(), bOrder.getBOrderNo(), order.getOrderNo(),
                operation.getStatus(), bOrder.getFulfillmentStatus(), order.getOrderStatus(), isTerminal);
    }

    /**
     * 短事务认领待执行 Operation：仅 {@code PENDING/CLAIMED} CAS 推进为 {@code EXECUTING}。
     *
     * @return true 表示本节点获得外呼资格
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean claimForExecution(String operationNo) {
        return claimForExecution(operationNo, null);
    }

    /** 普通执行入口必须提供可信实际target类型；频控项只能通过不可变permit及发送marker获得资格。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean claimForExecution(String operationNo, String actualTargetType) {
        if (frequencyLifecycle.requiresFrequency(operationNo, actualTargetType)) { return false; }
        Objects.requireNonNull(operationNo, "operationNo must not be null");
        FulfillmentOperationDO operation = operationMapper.selectOne(
                new LambdaQueryWrapper<FulfillmentOperationDO>()
                        .eq(FulfillmentOperationDO::getOperationNo, operationNo));
        if (operation == null || operation.getStatus() == null) {
            return false;
        }
        if (operation.getStatus() != SupplierOrderOperationStatus.PENDING.getCode()
                && operation.getStatus() != SupplierOrderOperationStatus.CLAIMED.getCode()) {
            log.info("[供应商订单][lifecycle] claim 跳过 operationNo={}, status={}",
                    operationNo, operation.getStatus());
            return false;
        }
        LocalDateTime now = databaseClock.now();
        operation.setStatus(SupplierOrderOperationStatus.EXECUTING.getCode());
        operation.setUpdatedAt(now);
        boolean claimed = operationMapper.updateById(operation) == 1;
        log.info("[供应商订单][lifecycle] claimForExecution operationNo={}, claimed={}", operationNo, claimed);
        return claimed;
    }

    private void handleSucceeded(FulfillmentOperationDO operation, BOrderDO bOrder,
                                 FulfillmentRequestDO request, SupplyChainOrderDO order,
                                 OperationOutcome outcome, String traceId, LocalDateTime now) {
        boolean isCardIssuing = AdapterStepCode.SEND_KM.name().equals(operation.getAdapterStepCode());
        boolean emptyCards = outcome.assets() == null || outcome.assets().isEmpty();

        if (isCardIssuing && emptyCards) {
            log.warn("[供应商订单][lifecycle] 权威成功但缺少卡密资产，进入 MANUAL 人工处理 operationNo={}, bOrderNo={}",
                    operation.getOperationNo(), bOrder.getBOrderNo());
            operation.setStatus(SupplierOrderOperationStatus.SUCCEEDED.getCode());
            operation.setResultClass(outcome.outcomeClass().name());
            if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
                operation.setExternalOperationNo(outcome.externalOrderNo());
            }
            operation.setAttemptCount(operation.getAttemptCount() + 1);
            operation.setCompletedAt(now);
            operation.setUpdatedAt(now);
            if (operationMapper.updateById(operation) != 1) {
                throw new IllegalStateException("Operation CAS update lost optimistic lock: " + operation.getOperationNo());
            }

            bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.SUCCEEDED.getCode());
            bOrder.setFulfilledQuantity(bOrder.getRequestedQuantity());
            bOrder.setActiveFlag(null);
            if (outcome.reportedSettlementAmount() != null) {
                bOrder.setReportedSupplierSettlementAmount(outcome.reportedSettlementAmount());
                bOrder.setConfirmedSupplierSettlementAmount(outcome.reportedSettlementAmount());
            } else {
                bOrder.setReportedSupplierSettlementAmount(bOrder.getExpectedSupplierSettlementAmount());
                bOrder.setConfirmedSupplierSettlementAmount(bOrder.getExpectedSupplierSettlementAmount());
            }
            bOrder.setSettlementStatus(BOrderSettlementStatus.MATCHED.getCode());
            if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
                bOrder.setExternalOrderNo(outcome.externalOrderNo());
            }
            bOrder.setCompletedAt(now);
            bOrder.setUpdatedAt(now);
            if (bOrderMapper.updateById(bOrder) != 1) {
                throw new IllegalStateException("BOrder CAS update lost optimistic lock: " + bOrder.getBOrderNo());
            }

            request.setStatus(FulfillmentRequestStatus.MANUAL.getCode());
            request.setUpdatedAt(now);
            if (requestMapper.updateById(request) != 1) {
                throw new IllegalStateException("FulfillmentRequest CAS update lost optimistic lock: " + request.getRequestNo());
            }

            OrderSummaryFact fact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_CARD,
                    SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT,
                    FulfillmentRequestStatus.MANUAL.getCode(),
                    order.getQuantity(), 0L, "QNM-ASSET-RECOVERY-001");
            OrderSummaryDecision decision = cardOrderSummaryPolicy.decide(fact);
            short fromOrderStatus = order.getOrderStatus();
            order.setOrderStatus(decision.orderStatus());
            order.setAftersaleStatus(decision.aftersaleStatus());
            order.setBusinessStage(decision.businessStage());
            long nextDetailVersion = (order.getDetailVersion() != null ? order.getDetailVersion() : 0L) + 1L;
            order.setDetailVersion(nextDetailVersion);
            order.setUpdatedAt(now);
            if (orderMapper.updateById(order) != 1) {
                throw new IllegalStateException("SupplyChainOrder CAS update lost optimistic lock: " + order.getOrderNo());
            }

            statusLogMapper.insert(OrderStatusLogDO.builder()
                    .orderId(order.getId())
                    .detailVersion(nextDetailVersion)
                    .fromOrderStatus(fromOrderStatus)
                    .toOrderStatus(decision.orderStatus())
                    .fromAftersaleStatus((short) 0)
                    .toAftersaleStatus((short) 0)
                    .businessStage(decision.businessStage())
                    .sourceEventType("FULFILLMENT_MANUAL")
                    .sourceEventNo(bOrder.getBOrderNo())
                    .reasonCode(FulfillmentExceptionReasonCodes.QNM_ASSET_RECOVERY_001)
                    .traceId(traceId)
                    .createdAt(now).build());

            fundReservationService.commitByReference(request.getBuyerId(),
                    FundReferenceType.CARD_ORDER.code(), request.getRequestNo(),
                    order.getOrderNo(), traceId, bOrder.getBOrderNo());
            openRecoverAssetCase(operation, bOrder, request, order, now);
            return;
        }

        // 1. 推进 Operation 状态为 40 成功
        operation.setStatus(SupplierOrderOperationStatus.SUCCEEDED.getCode());
        operation.setResultClass(outcome.outcomeClass().name());
        if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
            operation.setExternalOperationNo(outcome.externalOrderNo());
        }
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setCompletedAt(now);
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Operation CAS update lost optimistic lock: " + operation.getOperationNo());
        }

        // 2. 推进 BOrder 状态为 40 成功
        bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.SUCCEEDED.getCode());
        bOrder.setFulfilledQuantity(bOrder.getRequestedQuantity());
        bOrder.setActiveFlag(null); // 终态 activeFlag 置空
        if (outcome.reportedSettlementAmount() != null) {
            bOrder.setReportedSupplierSettlementAmount(outcome.reportedSettlementAmount());
            bOrder.setConfirmedSupplierSettlementAmount(outcome.reportedSettlementAmount());
        } else {
            bOrder.setReportedSupplierSettlementAmount(bOrder.getExpectedSupplierSettlementAmount());
            bOrder.setConfirmedSupplierSettlementAmount(bOrder.getExpectedSupplierSettlementAmount());
        }
        bOrder.setSettlementStatus(BOrderSettlementStatus.MATCHED.getCode());
        if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
            bOrder.setExternalOrderNo(outcome.externalOrderNo());
        }
        bOrder.setCompletedAt(now);
        bOrder.setUpdatedAt(now);
        if (bOrderMapper.updateById(bOrder) != 1) {
            throw new IllegalStateException("BOrder CAS update lost optimistic lock: " + bOrder.getBOrderNo());
        }

        // 3. 持久化交付资产至 sc_fulfillment_asset
        persistAssets(bOrder, operation, request, order, outcome, now);

        // 4. 推进 FulfillmentRequest 状态为 40 成功
        request.setFulfilledQuantity(bOrder.getRequestedQuantity());
        request.setStatus(FulfillmentRequestStatus.SUCCEEDED.getCode());
        request.setUpdatedAt(now);
        if (requestMapper.updateById(request) != 1) {
            throw new IllegalStateException("FulfillmentRequest CAS update lost optimistic lock: " + request.getRequestNo());
        }

        // 5. 对齐 CardOrderSummaryPolicy 推进总订单为 30 成功
        OrderSummaryFact fact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT,
                FulfillmentRequestStatus.SUCCEEDED.getCode(),
                order.getQuantity(), order.getQuantity(), null);
        OrderSummaryDecision decision = cardOrderSummaryPolicy.decide(fact);
        short fromOrderStatus = order.getOrderStatus();
        order.setOrderStatus(decision.orderStatus());
        order.setAftersaleStatus(decision.aftersaleStatus());
        order.setBusinessStage(decision.businessStage());
        long nextDetailVersion = (order.getDetailVersion() != null ? order.getDetailVersion() : 0L) + 1L;
        order.setDetailVersion(nextDetailVersion);
        order.setCompletedAt(now);
        order.setUpdatedAt(now);
        if (orderMapper.updateById(order) != 1) {
            throw new IllegalStateException("SupplyChainOrder CAS update lost optimistic lock: " + order.getOrderNo());
        }

        // 6. 记录订单状态变更审计
        statusLogMapper.insert(OrderStatusLogDO.builder()
                .orderId(order.getId())
                .detailVersion(nextDetailVersion)
                .fromOrderStatus(fromOrderStatus)
                .toOrderStatus(decision.orderStatus())
                .fromAftersaleStatus((short) 0)
                .toAftersaleStatus((short) 0)
                .businessStage(decision.businessStage())
                .sourceEventType("FULFILLMENT_SUCCEEDED")
                .sourceEventNo(bOrder.getBOrderNo())
                .reasonCode("SUPPLIER_SUCCEEDED")
                .traceId(traceId)
                .createdAt(now).build());

        // 7. 资金确认闭环：调用 fundReservationService.commit 实扣资金
        fundReservationService.commitByReference(request.getBuyerId(),
                FundReferenceType.CARD_ORDER.code(), request.getRequestNo(),
                order.getOrderNo(), traceId, bOrder.getBOrderNo());
    }

    private void handleRejected(FulfillmentOperationDO operation, BOrderDO bOrder,
                                FulfillmentRequestDO request, SupplyChainOrderDO order,
                                OperationOutcome outcome, String traceId, LocalDateTime now) {
        // 1. 推进 Operation 状态为 50 失败
        operation.setStatus(SupplierOrderOperationStatus.FAILED.getCode());
        operation.setResultClass(outcome.outcomeClass().name());
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setCompletedAt(now);
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Operation CAS update lost optimistic lock: " + operation.getOperationNo());
        }

        // 2. 推进 BOrder 状态为 50 失败
        bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.FAILED.getCode());
        bOrder.setFulfilledQuantity(0L);
        bOrder.setActiveFlag(null); // 终态 activeFlag 置空
        bOrder.setCompletedAt(now);
        bOrder.setUpdatedAt(now);
        if (bOrderMapper.updateById(bOrder) != 1) {
            throw new IllegalStateException("BOrder CAS update lost optimistic lock: " + bOrder.getBOrderNo());
        }

        // 3. M1 切片单候选耗尽：推进 FulfillmentRequest 为 50 失败
        request.setStatus(FulfillmentRequestStatus.FAILED.getCode());
        request.setUpdatedAt(now);
        if (requestMapper.updateById(request) != 1) {
            throw new IllegalStateException("FulfillmentRequest CAS update lost optimistic lock: " + request.getRequestNo());
        }

        // 4. 对齐 CardOrderSummaryPolicy 推进总订单为 40 失败
        OrderSummaryFact fact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT,
                FulfillmentRequestStatus.FAILED.getCode(),
                order.getQuantity(), 0L, outcome.externalErrorCode());
        OrderSummaryDecision decision = cardOrderSummaryPolicy.decide(fact);
        short fromOrderStatus = order.getOrderStatus();
        order.setOrderStatus(decision.orderStatus());
        order.setAftersaleStatus(decision.aftersaleStatus());
        order.setBusinessStage(decision.businessStage());
        long nextDetailVersion = (order.getDetailVersion() != null ? order.getDetailVersion() : 0L) + 1L;
        order.setDetailVersion(nextDetailVersion);
        order.setCompletedAt(now);
        order.setUpdatedAt(now);
        if (orderMapper.updateById(order) != 1) {
            throw new IllegalStateException("SupplyChainOrder CAS update lost optimistic lock: " + order.getOrderNo());
        }

        // 5. 记录订单状态变更审计
        statusLogMapper.insert(OrderStatusLogDO.builder()
                .orderId(order.getId())
                .detailVersion(nextDetailVersion)
                .fromOrderStatus(fromOrderStatus)
                .toOrderStatus(decision.orderStatus())
                .fromAftersaleStatus((short) 0)
                .toAftersaleStatus((short) 0)
                .businessStage(decision.businessStage())
                .sourceEventType("FULFILLMENT_FAILED")
                .sourceEventNo(bOrder.getBOrderNo())
                .reasonCode(outcome.externalErrorCode() != null ? outcome.externalErrorCode() : "SUPPLIER_REJECTED")
                .traceId(traceId)
                .createdAt(now).build());

        // 6. 资金释放闭环：全败释放预冻结资金
        fundReservationService.releaseByReference(request.getBuyerId(),
                FundReferenceType.CARD_ORDER.code(), request.getRequestNo(),
                order.getOrderNo(), traceId, bOrder.getBOrderNo());
    }

    private void handleResultUnknown(FulfillmentOperationDO operation, BOrderDO bOrder,
                                     OperationOutcome outcome, LocalDateTime now) {
        operation.setStatus(SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode());
        operation.setResultClass(outcome.outcomeClass().name());
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setNextRecoverAt(now.plusSeconds(30));
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Operation CAS update lost optimistic lock: " + operation.getOperationNo());
        }

        bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.PROCESSING.getCode());
        bOrder.setUpdatedAt(now);
        if (bOrderMapper.updateById(bOrder) != 1) {
            throw new IllegalStateException("BOrder CAS update lost optimistic lock: " + bOrder.getBOrderNo());
        }
    }

    private void handleAccepted(FulfillmentOperationDO operation, BOrderDO bOrder,
                                OperationOutcome outcome, LocalDateTime now) {
        operation.setStatus(SupplierOrderOperationStatus.EXECUTING.getCode());
        operation.setResultClass(outcome.outcomeClass().name());
        if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
            operation.setExternalOperationNo(outcome.externalOrderNo());
        }
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Operation CAS update lost optimistic lock: " + operation.getOperationNo());
        }

        bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.PENDING.getCode());
        if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
            bOrder.setExternalOrderNo(outcome.externalOrderNo());
        }
        bOrder.setUpdatedAt(now);
        if (bOrderMapper.updateById(bOrder) != 1) {
            throw new IllegalStateException("BOrder CAS update lost optimistic lock: " + bOrder.getBOrderNo());
        }
    }

    private void handleManualOrOther(FulfillmentOperationDO operation, BOrderDO bOrder,
                                     FulfillmentRequestDO request, SupplyChainOrderDO order,
                                     OperationOutcome outcome, String traceId, LocalDateTime now) {
        operation.setStatus(SupplierOrderOperationStatus.MANUAL.getCode());
        operation.setResultClass(outcome.outcomeClass().name());
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setCompletedAt(now);
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Operation CAS update lost optimistic lock: " + operation.getOperationNo());
        }

        bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.MANUAL.getCode());
        bOrder.setActiveFlag(null);
        bOrder.setCompletedAt(now);
        bOrder.setUpdatedAt(now);
        if (bOrderMapper.updateById(bOrder) != 1) {
            throw new IllegalStateException("BOrder CAS update lost optimistic lock: " + bOrder.getBOrderNo());
        }

        request.setStatus(FulfillmentRequestStatus.MANUAL.getCode());
        request.setUpdatedAt(now);
        if (requestMapper.updateById(request) != 1) {
            throw new IllegalStateException("FulfillmentRequest CAS update lost optimistic lock: " + request.getRequestNo());
        }

        OrderSummaryFact fact = new OrderSummaryFact(SupplyChainOrderTypes.ORDER_TYPE_CARD,
                SupplyChainOrderTypes.DETAIL_TYPE_FULFILLMENT,
                FulfillmentRequestStatus.MANUAL.getCode(),
                order.getQuantity(), 0L, outcome.externalErrorCode());
        OrderSummaryDecision decision = cardOrderSummaryPolicy.decide(fact);
        short fromOrderStatus = order.getOrderStatus();
        order.setOrderStatus(decision.orderStatus());
        order.setAftersaleStatus(decision.aftersaleStatus());
        order.setBusinessStage(decision.businessStage());
        long nextDetailVersion = (order.getDetailVersion() != null ? order.getDetailVersion() : 0L) + 1L;
        order.setDetailVersion(nextDetailVersion);
        order.setUpdatedAt(now);
        if (orderMapper.updateById(order) != 1) {
            throw new IllegalStateException("SupplyChainOrder CAS update lost optimistic lock: " + order.getOrderNo());
        }

        statusLogMapper.insert(OrderStatusLogDO.builder()
                .orderId(order.getId())
                .detailVersion(nextDetailVersion)
                .fromOrderStatus(fromOrderStatus)
                .toOrderStatus(decision.orderStatus())
                .fromAftersaleStatus((short) 0)
                .toAftersaleStatus((short) 0)
                .businessStage(decision.businessStage())
                .sourceEventType("FULFILLMENT_MANUAL")
                .sourceEventNo(bOrder.getBOrderNo())
                .reasonCode("MANUAL_REQUIRED")
                .traceId(traceId)
                .createdAt(now).build());
    }

    private void persistAssets(BOrderDO bOrder, FulfillmentOperationDO operation,
                               FulfillmentRequestDO request, SupplyChainOrderDO order,
                               OperationOutcome outcome, LocalDateTime now) {
        if (outcome.assets() == null || outcome.assets().isEmpty()) {
            return;
        }
        for (OperationAssetFact assetFact : outcome.assets()) {
            String assetNo = businessNoGenerator.next(BusinessNoPrefixes.FA);
            Map<String, String> payloadMap = new LinkedHashMap<>();
            payloadMap.put("assetType", assetFact.assetType());
            if (assetFact.cardNo() != null) {
                payloadMap.put("cardCode", assetFact.cardNo());
            }
            if (assetFact.cardSecret() != null) {
                payloadMap.put("cardSecret", assetFact.cardSecret());
            }
            byte[] plaintext;
            try {
                plaintext = objectMapper.writeValueAsBytes(payloadMap);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize asset payload", e);
            }

            byte[] associatedData = (bOrder.getId() + "|" + assetFact.sequenceNo() + "|" + assetFact.assetType())
                    .getBytes(StandardCharsets.UTF_8);
            EnvelopeCryptoPort.EncryptedPayload encrypted = envelopeCrypto.encrypt(plaintext, associatedData);

            String fingerprintSource = bOrder.getSupplierId() + ":"
                    + (assetFact.cardNo() != null ? assetFact.cardNo() : "") + ":"
                    + (assetFact.cardSecret() != null ? assetFact.cardSecret() : "");
            byte[] fingerprint = hmacFingerprint.fingerprint("FULFILLMENT_ASSET",
                    fingerprintSource.getBytes(StandardCharsets.UTF_8));

            String maskedValue;
            if (assetFact.cardNo() != null && !assetFact.cardNo().isBlank()) {
                maskedValue = QnmMaskingUtils.maskCardNo(assetFact.cardNo());
            } else if (assetFact.cardSecret() != null && !assetFact.cardSecret().isBlank()) {
                maskedValue = QnmMaskingUtils.maskSecret(assetFact.cardSecret());
            } else {
                maskedValue = "";
            }

            FulfillmentAssetDO assetDO = FulfillmentAssetDO.builder()
                    .assetNo(assetNo)
                    .fulfillmentRequestId(request.getId())
                    .bOrderId(bOrder.getId())
                    .operationId(operation.getId())
                    .supplierId(bOrder.getSupplierId())
                    .buyerId(request.getBuyerId())
                    .buyerUserId(order.getBuyerUserId())
                    .sequenceNo(assetFact.sequenceNo())
                    .unitQuantity(1L)
                    .assetType(assetFact.assetType())
                    .externalAssetId(assetFact.externalAssetId())
                    .encryptedPayload(encrypted.ciphertext())
                    .maskedValue(maskedValue)
                    .fingerprint(fingerprint)
                    .keyVersion(encrypted.keyVersion())
                    .validFrom(assetFact.validFrom())
                    .validUntil(assetFact.validUntil())
                    .lifecycleStatus(FulfillmentAssetStatus.AVAILABLE.getCode())
                    .version(0L)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            assetMapper.insert(assetDO);
            log.info("[供应商订单][lifecycle] 资产持久化成功 assetNo={}, bOrderNo={}, sequenceNo={}",
                    assetNo, bOrder.getBOrderNo(), assetFact.sequenceNo());
        }
    }

    private void persistCallAndObservation(FulfillmentOperationDO operation, BOrderDO bOrder,
                                           OperationOutcome outcome, String traceId, LocalDateTime now) {
        persistCallLog(operation, bOrder, outcome.callDigest(), now);
        persistObservation(operation, bOrder, outcome, traceId, now);
    }

    private void persistCallLog(FulfillmentOperationDO operation, BOrderDO bOrder,
                                CallDigestFact digest, LocalDateTime now) {
        if (digest == null) {
            return;
        }
        int attemptNo = (operation.getAttemptCount() == null ? 0 : operation.getAttemptCount()) + 1;
        long duration = digest.durationMs();
        LocalDateTime startedAt = now.minusNanos(Math.max(0, duration) * 1_000_000L);
        byte[] requestDigest = digest.requestDigest() != null ? digest.requestDigest() : EMPTY_DIGEST;
        callLogMapper.insert(FulfillmentCallLogDO.builder()
                .callNo(businessNoGenerator.next(BusinessNoPrefixes.CL))
                .operationId(operation.getId())
                .bOrderId(bOrder.getId())
                .attemptNo(attemptNo)
                .direction(FulfillmentCallDirection.OUTBOUND.name())
                .connectorVersionId(bOrder.getConnectorVersionId() != null ? bOrder.getConnectorVersionId() : 0L)
                .endpointCode(digest.endpointCode() != null ? digest.endpointCode() : "")
                .startedAt(startedAt)
                .durationMs(duration)
                .resultClass(digest.resultClass() != null ? digest.resultClass() : "")
                .httpStatus(digest.httpStatus())
                .requestDigest(requestDigest)
                .responseDigest(digest.responseDigest())
                .redactionVersion(CALL_LOG_REDACTION_VERSION)
                .traceId(digest.traceId() != null ? digest.traceId() : "")
                .createdAt(now)
                .build());
    }

    private void persistObservation(FulfillmentOperationDO operation, BOrderDO bOrder,
                                    OperationOutcome outcome, String traceId, LocalDateTime now) {
        RawObservationFact observation = outcome.rawObservation();
        if (observation == null) {
            return;
        }
        byte[] payloadDigest = observation.payloadDigest() != null ? observation.payloadDigest() : EMPTY_DIGEST;
        String sourceType = observation.sourceType() != null ? observation.sourceType() : "CREATE_RESPONSE";
        String observationKey = operation.getOperationNo() + ":" + sourceType + ":" + HexFormat.of().formatHex(payloadDigest);
        if (observationKey.length() > 128) {
            observationKey = observationKey.substring(0, 128);
        }
        String externalSubStatus = observation.externalSubStatus();
        if (externalSubStatus != null) {
            externalSubStatus = QnmMaskingUtils.maskResponseBody(externalSubStatus);
        }
        observationMapper.insertIdempotent(RawStatusObservationDO.builder()
                .observationNo(businessNoGenerator.next(BusinessNoPrefixes.OBS))
                .observationKey(observationKey)
                .sourceType(sourceType)
                .operationId(operation.getId())
                .bOrderId(bOrder.getId())
                .connectorVersionId(bOrder.getConnectorVersionId() != null ? bOrder.getConnectorVersionId() : 0L)
                .externalOrderNo(observation.externalOrderNo())
                .externalStatus(observation.externalStatus() != null ? observation.externalStatus() : "UNKNOWN")
                .externalSubStatus(externalSubStatus)
                .mappingDefinitionId(0L)
                .mappedResultClass(outcome.outcomeClass().name())
                .payloadDigest(payloadDigest)
                .observedAt(observation.observedAt() != null ? observation.observedAt() : now)
                .receivedAt(now)
                .traceId(traceId != null ? traceId : "")
                .build());
    }

    /** 矛盾供应商终态仅开异常审计，不覆写既有订单事实或重复资金动作。设计见限频设计 #5-结果窗口和资金。 */
    private void openContradictoryCase(FulfillmentOperationDO operation, BOrderDO bOrder,
                                       FulfillmentRequestDO request, SupplyChainOrderDO order, LocalDateTime now) {
        if (exceptionCaseMapper.selectActiveByOperationId(operation.getId()) != null) { return; }
        String evidenceRef = "TERMINAL_CONTRADICTION:" + operation.getOperationNo();
        exceptionCaseMapper.insert(FulfillmentExceptionCaseDO.builder()
                .caseNo(businessNoGenerator.next(BusinessNoPrefixes.CASE)).orderId(order.getId())
                .fulfillmentRequestId(request.getId()).bOrderId(bOrder.getId()).operationId(operation.getId())
                .reasonCode(FulfillmentExceptionReasonCodes.SUPPLIER_TERMINAL_CONTRADICTION)
                .status(FulfillmentExceptionCaseStatus.OPEN.getCode()).evidenceRef(evidenceRef)
                .evidenceDigest(hmacFingerprint.fingerprint("EXCEPTION_CASE", evidenceRef.getBytes(StandardCharsets.UTF_8)))
                .activeCaseFlag(1).createdAt(now).updatedAt(now).version(0L).build());
    }

    private void openRecoverAssetCase(FulfillmentOperationDO operation, BOrderDO bOrder,
                                      FulfillmentRequestDO request, SupplyChainOrderDO order,
                                      LocalDateTime now) {
        if (exceptionCaseMapper.selectActiveByOperationId(operation.getId()) != null) {
            return;
        }
        String evidenceRef = "RECOVER_ASSET:" + bOrder.getId();
        byte[] evidenceDigest = hmacFingerprint.fingerprint("EXCEPTION_CASE",
                evidenceRef.getBytes(StandardCharsets.UTF_8));
        exceptionCaseMapper.insert(FulfillmentExceptionCaseDO.builder()
                .caseNo(businessNoGenerator.next(BusinessNoPrefixes.CASE))
                .orderId(order.getId())
                .fulfillmentRequestId(request.getId())
                .bOrderId(bOrder.getId())
                .operationId(operation.getId())
                .reasonCode(FulfillmentExceptionReasonCodes.QNM_ASSET_MISSING_AFTER_SUCCESS)
                .status(FulfillmentExceptionCaseStatus.OPEN.getCode())
                .evidenceRef(evidenceRef)
                .evidenceDigest(evidenceDigest)
                .activeCaseFlag(1)
                .createdAt(now)
                .updatedAt(now)
                .version(0L)
                .build());
        log.info("[供应商订单][lifecycle] 已打开空卡密异常 Case operationNo={}, bOrderNo={}",
                operation.getOperationNo(), bOrder.getBOrderNo());
    }

    public record TransitionResult(String operationNo, String bOrderNo, String orderNo,
                                    short operationStatus, short bOrderStatus, short orderStatus,
                                    boolean isTerminal) {
    }
}
