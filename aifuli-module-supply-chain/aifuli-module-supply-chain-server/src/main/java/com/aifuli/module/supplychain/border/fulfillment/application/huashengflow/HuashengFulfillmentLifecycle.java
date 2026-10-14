package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

import java.math.BigDecimal;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.SupplierOrderLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.BOrderDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentOperationDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.BOrderMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentOperationMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentRequestMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.AdapterStepCode;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentIdempotencyKeys;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentOperationKind;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationAssetFact;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcome;
import com.aifuli.module.supplychain.border.fulfillment.domain.OperationOutcomeClass;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentExceptionReasonCodes;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentExceptionCaseStatus;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentRequestStatus;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentExceptionCaseDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.FulfillmentExceptionCaseMapper;
import com.aifuli.module.supplychain.border.order.domain.BOrderFulfillmentStatus;
import com.aifuli.module.supplychain.border.order.domain.BOrderSettlementStatus;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 华昇生产履约生命周期推进服务（短事务认领、租约看门狗、唯一下一步推进与状态收敛）。
 *
 * <p>职责：
 * 1. claimForExecution：聚合锁后原子认领 PENDING，递增 generation，写入 EXECUTING 与租约，注册租约看门狗 Timer；
 * 2. claimForRecovery：租约过期或未知结果后认领 QUERY，递增 generation 使旧 permit 失格；
 * 3. apply：在相同锁序下校验 generation、当前活动尝试、租约未过期，根据纯决策表推进唯一下一步并调度 Timer，或提交/释放资金。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#5-资格、唯一下一步与宕机恢复}
 * 以及 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#6-协议事实推进规则}。</p>
 */
@Slf4j
@Service
public class HuashengFulfillmentLifecycle {

    public static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";

    private final RechargeFrequencyLifecycle frequencyLifecycle;
    private final SupplierOrderLifecycle supplierOrderLifecycle;
    private final FulfillmentOperationMapper operationMapper;
    private final BOrderMapper bOrderMapper;
    private final FulfillmentRequestMapper requestMapper;
    private final SupplyChainOrderMapper orderMapper;
    private final PersistentTimer timer;
    private final HuashengCheckpointCodec checkpointCodec;
    private final BusinessNoGenerator businessNoGenerator;
    private final SupplyChainDatabaseClock databaseClock;
    private final ObjectMapper objectMapper;
    private final FulfillmentExceptionCaseMapper exceptionCaseMapper;
    private final HmacFingerprintPort hmacFingerprint;
    @org.springframework.beans.factory.annotation.Autowired
    private com.aifuli.module.supplychain.border.order.dal.mysql.OrderStatusLogMapper statusLogMapper;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier(TRANSACTION_MANAGER)
    private PlatformTransactionManager transactionManager;

    public HuashengFulfillmentLifecycle(
            RechargeFrequencyLifecycle frequencyLifecycle,
            SupplierOrderLifecycle supplierOrderLifecycle,
            FulfillmentOperationMapper operationMapper,
            BOrderMapper bOrderMapper,
            FulfillmentRequestMapper requestMapper,
            SupplyChainOrderMapper orderMapper,
            PersistentTimer timer,
            HuashengCheckpointCodec checkpointCodec,
            BusinessNoGenerator businessNoGenerator,
            SupplyChainDatabaseClock databaseClock,
            ObjectMapper objectMapper,
            FulfillmentExceptionCaseMapper exceptionCaseMapper,
            HmacFingerprintPort hmacFingerprint
    ) {
        this.frequencyLifecycle = Objects.requireNonNull(frequencyLifecycle, "frequencyLifecycle is required");
        this.supplierOrderLifecycle = Objects.requireNonNull(supplierOrderLifecycle, "supplierOrderLifecycle is required");
        this.operationMapper = Objects.requireNonNull(operationMapper, "operationMapper is required");
        this.bOrderMapper = Objects.requireNonNull(bOrderMapper, "bOrderMapper is required");
        this.requestMapper = Objects.requireNonNull(requestMapper, "requestMapper is required");
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper is required");
        this.timer = Objects.requireNonNull(timer, "timer is required");
        this.checkpointCodec = Objects.requireNonNull(checkpointCodec, "checkpointCodec is required");
        this.businessNoGenerator = Objects.requireNonNull(businessNoGenerator, "businessNoGenerator is required");
        this.databaseClock = Objects.requireNonNull(databaseClock, "databaseClock is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.exceptionCaseMapper = Objects.requireNonNull(exceptionCaseMapper, "exceptionCaseMapper is required");
        this.hmacFingerprint = Objects.requireNonNull(hmacFingerprint, "hmacFingerprint is required");
    }

    /**
     * 短事务认领出站执行资格。
     * 仅认领 PENDING 步骤，递增代次，设置 EXECUTING 与租约，注册租约到期看门狗。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", rollbackFor = Exception.class)
    public HuashengExecutionPermit claimForExecution(String operationNo) {
        Objects.requireNonNull(operationNo, "operationNo is required");
        var locked = frequencyLifecycle.lockForResult(operationNo);
        if (locked == null) {
            return null;
        }
        FulfillmentOperationDO operation = locked.operation();
        if (operation == null || operation.getStatus() == null) {
            return null;
        }
        if (operation.getStatus() != SupplierOrderOperationStatus.PENDING.getCode()
                && operation.getStatus() != SupplierOrderOperationStatus.CLAIMED.getCode()) {
            return null;
        }
        BOrderDO bOrder = locked.bOrder();
        if (bOrder == null || !Boolean.TRUE.equals(bOrder.getActiveFlag())) {
            return null;
        }

        LocalDateTime now = databaseClock.now();
        long currentGen = operation.getExecutionGeneration() != null ? operation.getExecutionGeneration() : 0L;
        long nextGen = currentGen + 1;
        LocalDateTime leaseUntil = now.plusSeconds(HuashengFlowPolicy.LEASE_SECONDS);
        AdapterStepCode step = AdapterStepCode.valueOf(operation.getAdapterStepCode());
        if (!consumeClaimQuota(locked, step, now)) return null;

        operation.setStatus(SupplierOrderOperationStatus.EXECUTING.getCode());
        operation.setExecutionGeneration(nextGen);
        operation.setExecutionLeaseUntil(leaseUntil);
        operation.setBusinessSendPossibleAt(now);
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Failed to update operation for execution claim: " + operationNo);
        }

        // 注册租约到期看门狗 Timer
        ObjectNode leasePayload = objectMapper.createObjectNode();
        leasePayload.put("operationNo", operationNo);
        leasePayload.put("expectedGeneration", nextGen);
        leasePayload.put("trigger", HuashengFlowTrigger.EXECUTE.name());
        putOrigin(leasePayload, operation, bOrder);
        timer.scheduleOnce(HuashengFlowCodes.LEASE_WATCHDOG_TASK,
                "HS:LEASE:" + operationNo + ":" + nextGen, leasePayload, databaseClock.toInstant(leaseUntil));

        log.info("[华昇生命周期][执行认领] operationNo={}, bOrderId={}, generation={}, step={}, leaseUntil={}",
                operationNo, bOrder.getId(), nextGen, step, leaseUntil);

        return new HuashengExecutionPermit(
                operationNo,
                bOrder.getId(),
                nextGen,
                step,
                HuashengFlowTrigger.EXECUTE,
                leaseUntil
        );
    }

    /**
     * 短事务认领宕机/超时恢复资格。
     * 仅针对 EXECUTING 租约已过期或 RESULT_UNKNOWN，递增代次使旧 permit 失格，步骤固定为 QUERY_ORDER。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", rollbackFor = Exception.class)
    public HuashengExecutionPermit claimForRecovery(String operationNo) {
        Objects.requireNonNull(operationNo, "operationNo is required");
        var locked = frequencyLifecycle.lockForResult(operationNo);
        if (locked == null) {
            return null;
        }
        FulfillmentOperationDO operation = locked.operation();
        if (operation == null || operation.getStatus() == null) {
            return null;
        }
        if (isTerminal(operation.getStatus())) {
            return null;
        }

        LocalDateTime now = databaseClock.now();
        boolean leaseExpired = operation.getExecutionLeaseUntil() != null && !now.isBefore(operation.getExecutionLeaseUntil());
        boolean isUnknown = operation.getStatus() == SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode();
        if (!leaseExpired && !isUnknown) {
            return null;
        }
        if (isUnknown && operation.getNextRecoverAt() != null && now.isBefore(operation.getNextRecoverAt())) {
            return null;
        }

        BOrderDO bOrder = locked.bOrder();
        if (bOrder == null || !Boolean.TRUE.equals(bOrder.getActiveFlag())) {
            return null;
        }

        long currentGen = operation.getExecutionGeneration() != null ? operation.getExecutionGeneration() : 0L;
        long nextGen = currentGen + 1;
        LocalDateTime leaseUntil = now.plusSeconds(HuashengFlowPolicy.LEASE_SECONDS);
        int recoveryCount = operation.getRecoveryCount() != null ? operation.getRecoveryCount() + 1 : 1;
        if (!consumeClaimQuota(locked, AdapterStepCode.QUERY_ORDER, now)) return null;

        operation.setStatus(SupplierOrderOperationStatus.EXECUTING.getCode());
        operation.setExecutionGeneration(nextGen);
        operation.setExecutionLeaseUntil(leaseUntil);
        operation.setRecoveryCount(recoveryCount);
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) {
            throw new IllegalStateException("Failed to update operation for recovery claim: " + operationNo);
        }

        // 注册新代次租约看门狗
        ObjectNode leasePayload = objectMapper.createObjectNode();
        leasePayload.put("operationNo", operationNo);
        leasePayload.put("expectedGeneration", nextGen);
        leasePayload.put("trigger", HuashengFlowTrigger.RECOVER.name());
        putOrigin(leasePayload, operation, bOrder);
        timer.scheduleOnce(HuashengFlowCodes.LEASE_WATCHDOG_TASK,
                "HS:LEASE:" + operationNo + ":" + nextGen, leasePayload, databaseClock.toInstant(leaseUntil));

        log.info("[华昇生命周期][恢复认领] operationNo={}, bOrderId={}, generation={}, recoveryCount={}, leaseUntil={}",
                operationNo, bOrder.getId(), nextGen, recoveryCount, leaseUntil);

        return new HuashengExecutionPermit(
                operationNo,
                bOrder.getId(),
                nextGen,
                AdapterStepCode.QUERY_ORDER,
                HuashengFlowTrigger.RECOVER,
                leaseUntil
        );
    }

    /**
     * 应用单次执行结果事实并推进唯一下一步或资金终态。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", rollbackFor = Exception.class)
    public HuashengApplyResult apply(HuashengExecutionPermit permit, OperationOutcome outcome, String traceId) {
        Objects.requireNonNull(permit, "permit is required");
        Objects.requireNonNull(outcome, "outcome is required");

        var locked = frequencyLifecycle.lockForResult(permit.operationNo());
        if (locked == null) {
            return HuashengApplyResult.disqualified("LockedExecution is null for operation: " + permit.operationNo());
        }
        FulfillmentOperationDO currentOperation = locked.operation();
        if (currentOperation == null) {
            return HuashengApplyResult.disqualified("Current operation not found: " + permit.operationNo());
        }

        // 围栏校验 1: 状态必须为 EXECUTING
        if (currentOperation.getStatus() != SupplierOrderOperationStatus.EXECUTING.getCode()) {
            return HuashengApplyResult.disqualified("Operation status is not EXECUTING: " + currentOperation.getStatus());
        }
        // 围栏校验 2: generation 严格一致
        if (currentOperation.getExecutionGeneration() == null
                || !currentOperation.getExecutionGeneration().equals(permit.executionGeneration())) {
            return HuashengApplyResult.disqualified("Execution generation mismatch: current="
                    + currentOperation.getExecutionGeneration() + ", permit=" + permit.executionGeneration());
        }
        // 围栏校验 3: 租约未过期
        LocalDateTime now = databaseClock.now();
        if (currentOperation.getExecutionLeaseUntil() != null && now.isAfter(currentOperation.getExecutionLeaseUntil())) {
            return HuashengApplyResult.disqualified("Execution lease expired at " + currentOperation.getExecutionLeaseUntil());
        }

        BOrderDO currentBOrder = locked.bOrder();
        if (currentBOrder == null) {
            return HuashengApplyResult.disqualified("BOrder not found for operation: " + permit.operationNo());
        }

        // 解密与严格核实 Checkpoint
        HuashengCheckpointCodec.Stored stored = new HuashengCheckpointCodec.Stored(
                currentOperation.getCheckpointSchemaVersion(),
                currentOperation.getCheckpointKeyVersion(),
                currentOperation.getCheckpointCiphertext()
        );
        HuashengCheckpoint.Binding expectedBinding = new HuashengCheckpoint.Binding(
                currentBOrder.getId(),
                currentBOrder.getBOrderNo(),
                currentOperation.getOperationNo(),
                currentOperation.getSequenceNo(),
                AdapterStepCode.valueOf(currentOperation.getAdapterStepCode()),
                currentBOrder.getConnectorVersionId(),
                currentBOrder.getOfferVersionId(),
                currentOperation.getConfigDigest()
        );
        HuashengCheckpoint checkpoint;
        try {
            checkpoint = checkpointCodec.decrypt(stored, expectedBinding);
        } catch (Exception e) {
            log.warn("[华昇生命周期][解密失败] operationNo={}, error={}", currentOperation.getOperationNo(), e.getMessage());
            holdManual(locked, null, null, FulfillmentExceptionReasonCodes.HUASHENG_PREPARATION_FAILED, false, now);
            return HuashengApplyResult.disqualified("Checkpoint decryption failed: " + e.getMessage());
        }

        if (!Objects.equals(checkpoint.placeParameters().outOrderSn(), currentBOrder.getBillno())) {
            log.warn("[华昇生命周期][单号不符] operationNo={}, billno={}, checkpointOutOrderSn={}",
                    currentOperation.getOperationNo(), currentBOrder.getBillno(), checkpoint.placeParameters().outOrderSn());
            holdManual(locked, checkpoint, null, FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT, false, now);
            return HuashengApplyResult.disqualified("outOrderSn does not match billno");
        }

        if (!(outcome.protocolFact() instanceof HuashengStepFact fact)) {
            throw new IllegalArgumentException("Huasheng apply requires HuashengStepFact");
        }

        long expectedAmount = currentBOrder.getExpectedSupplierSettlementAmount() != null
                ? currentBOrder.getExpectedSupplierSettlementAmount() : 0L;

        // 无副作用证明：覆盖本单全部相关历史尝试（设计 §4.1）；仅首 PLACE 步骤维护并使用。
        LocalDateTime startedAt = currentOperation.getUpdatedAt();
        boolean placeStep = fact.endpoint() == HuashengStepFact.Endpoint.PLACE
                && AdapterStepCode.PLACE_ORDER.name().equals(currentOperation.getAdapterStepCode());
        boolean sideEffectFreeProven = placeStep
                && computeSideEffectFreeProven(currentOperation, fact);
        if (placeStep) {
            updateSideEffectFreeFlag(currentOperation, sideEffectFreeProven, now);
        }
        supplierOrderLifecycle.recordHuashengResult(currentOperation.getOperationNo(), fact, traceId, startedAt);

        HuashengFlowDecision decision = HuashengFlowPolicy.decide(checkpoint, fact, expectedAmount, now,
                sideEffectFreeProven, currentOperation.getAttemptCount() != null ? currentOperation.getAttemptCount() : 0);

        if (checkpoint.pendingAssetPayload() != null && fact.protectedAssetPayload() != null
                && !Arrays.equals(checkpoint.pendingAssetPayload(), fact.protectedAssetPayload())) {
            decision = HuashengFlowDecision.MANUAL;
        }
        recordSupplierTakenSuccess(locked, checkpoint, fact, now);
        HuashengStableFailureReason closureReason = switch (decision) {
            case FINAL_REJECTION -> rejectionReason(fact);
            case FINAL_CANCELLATION -> HuashengStableFailureReason.HUASHENG_SUPPLIER_CANCELLED;
            case FINAL_NOT_CREATED -> HuashengStableFailureReason.HUASHENG_NOT_CREATED;
            default -> null;
        };
        if (closureReason != null && supplierOrderLifecycle.hasHuashengFailureEvidenceConflict(
                currentOperation.getOperationNo(), closureReason)) {
            holdManual(locked, checkpoint, fact, FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT, false, now);
            return new HuashengApplyResult(HuashengFlowDecision.MANUAL, null, true, "Historical evidence blocks failure closure");
        }

        log.info("[华昇生命周期][事实决策] operationNo={}, endpoint={}, factStatus={}, rawCode={}, decision={}",
                currentOperation.getOperationNo(), fact.endpoint(), fact.status(), fact.rawErrorCode(), decision);

        // 价格差异保护：身份/数量正确、金额格式有效但与冻结成本不符时，保存实报金额并置 MISMATCH（设计 §5.1）。
        if (decision == HuashengFlowDecision.WAIT_QUERY) {
            recordPriceMismatchIfPresent(currentBOrder, fact, expectedAmount, now);
        }

        return switch (decision) {
            case NEXT_CONFIRM, NEXT_TAKE -> advanceToNextStep(currentOperation, currentBOrder, checkpoint, fact, outcome, decision, traceId, now);
            case WAIT_QUERY -> scheduleQueryRecovery(currentOperation, currentBOrder, checkpoint, fact, decision, traceId, now, sideEffectFreeProven);
            case FINAL_SUCCESS -> {
                // TAKE没有金额，历史金额也可能缺失；只留存已有事实，不作为履约成功门槛（设计 §7.3）。
                Long amount = fact.endpoint() == HuashengStepFact.Endpoint.TAKE
                        ? checkpoint.observation() == null ? null : checkpoint.observation().totalPriceMinorUnits()
                        : fact.totalPriceMinorUnits();
                supplierOrderLifecycle.commitHuashengSuccess(permit, terminalOutcome(outcome, OperationOutcomeClass.SUCCEEDED, amount), traceId);
                yield new HuashengApplyResult(decision, null, true, "Final success committed");
            }
            case FINAL_REJECTION -> {
                var closure = new SupplierOrderLifecycle.HuashengTrustedFailureClosure(false,
                        rejectionReason(fact), rejectionNote(fact), outcome.reportedSettlementAmount());
                supplierOrderLifecycle.closeHuashengTrustedFailure(permit,
                        terminalOutcome(outcome, OperationOutcomeClass.REJECTED, outcome.reportedSettlementAmount()),
                        traceId, closure);
                yield new HuashengApplyResult(decision, null, true, "Trusted rejection closed");
            }
            case FINAL_CANCELLATION -> {
                // 可信取消：BOrder CANCELLED、平台 FAILED、释放本单预留；价差独立留存（设计 §3.2、§5.2）。
                if (checkpoint.pendingAssetPayload() != null) {
                    holdManual(locked, checkpoint, fact, FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT, false, now);
                    yield new HuashengApplyResult(HuashengFlowDecision.MANUAL, null, true, "Pending asset blocks cancellation closure");
                }
                var closure = new SupplierOrderLifecycle.HuashengTrustedFailureClosure(true,
                        HuashengStableFailureReason.HUASHENG_SUPPLIER_CANCELLED, "供应商返回已取消",
                        outcome.reportedSettlementAmount());
                supplierOrderLifecycle.closeHuashengTrustedFailure(permit,
                        terminalOutcome(outcome, OperationOutcomeClass.REJECTED, outcome.reportedSettlementAmount()),
                        traceId, closure);
                yield new HuashengApplyResult(decision, null, true, "Trusted cancellation closed");
            }
            case FINAL_NOT_CREATED -> {
                // 可信未建单：锁后复核已由候选事实、资产与资金 Guard 覆盖（设计 §5.4）。
                if (checkpoint.pendingAssetPayload() != null) {
                    holdManual(locked, checkpoint, fact, FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT, false, now);
                    yield new HuashengApplyResult(HuashengFlowDecision.MANUAL, null, true, "Pending asset blocks not-created closure");
                }
                var closure = new SupplierOrderLifecycle.HuashengTrustedFailureClosure(false,
                        HuashengStableFailureReason.HUASHENG_NOT_CREATED, "供应商未建单，订单履约失败",
                        null);
                supplierOrderLifecycle.closeHuashengTrustedFailure(permit,
                        terminalOutcome(outcome, OperationOutcomeClass.REJECTED, null),
                        traceId, closure);
                yield new HuashengApplyResult(decision, null, true, "Trusted not-created failure closed");
            }
            case RETRY_PRE_SEND -> {
                // 发送前重试：保持原业务身份与原步骤，退避后重新认领（设计 §4.5）。
                yield schedulePreSendRetry(currentOperation, fact, now);
            }
            case MANUAL -> {
                // 用相同事实去除自动配额再判断，仅“因配额阻断”的人工单可被可信回调恢复；
                // 同时到限的金额／资产冲突仍是冲突，不能仅按状态或queryCount误归类（设计 §5.3）。
                var unlimited = new HuashengCheckpoint(checkpoint.binding(), checkpoint.placeParameters(), checkpoint.orderSn(),
                        checkpoint.observation(), new HuashengCheckpoint.Progress(0, 0, 0, null, null,
                        checkpoint.progress().originTraceId()), checkpoint.pendingAssetPayload(),
                        checkpoint.notCreatedCandidate());
                boolean recoveryLimit = HuashengFlowPolicy.decide(unlimited, fact, expectedAmount, now,
                        sideEffectFreeProven, 0) != HuashengFlowDecision.MANUAL
                        && (checkpoint.pendingAssetPayload() == null || fact.protectedAssetPayload() == null
                        || Arrays.equals(checkpoint.pendingAssetPayload(), fact.protectedAssetPayload()));
                holdManual(locked, checkpoint, fact, recoveryLimit ? FulfillmentExceptionReasonCodes.HUASHENG_RECOVERY_LIMIT
                        : FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT, false, now);
                yield new HuashengApplyResult(decision, null, true, "Transitioned to manual");
            }
        };
    }

    /**
     * 计算无副作用证明：历史标记为真或仅有本次认领，且本次事实为明确建单前拒绝或从未发送业务请求。
     * 缺失（NULL）历史且已有多次认领时无法证明，关闭快捷路径（设计 §4.1、§7）。
     */
    private static boolean computeSideEffectFreeProven(FulfillmentOperationDO operation, HuashengStepFact fact) {
        boolean historyClean = Boolean.TRUE.equals(operation.getSideEffectFree())
                || (operation.getSideEffectFree() == null
                    && (operation.getAttemptCount() == null || operation.getAttemptCount() <= 1));
        if (!historyClean || fact.attempts().isEmpty()) {
            return false;
        }
        if (fact.resultClass() == HuashengStepFact.StepResult.ACCEPTED) {
            // 已接收业务数据（订单可能已创建），本次存在业务副作用。
            return false;
        }
        boolean tokenOnly = fact.attempts().stream().allMatch(attempt -> "TOKEN".equals(attempt.endpoint())
                && !attempt.wasBusinessCallStarted());
        if (tokenOnly) return true;
        if (fact.rawErrorCode() != null) {
            if (HuashengErrorClassification.isAuthorizationRejection(fact.rawErrorCode())) return false;
            // 每个业务尝试都必须明确前置拒绝，末次拒绝不能掩盖此前超时或未知发送。
            if (fact.attempts().stream().anyMatch(attempt -> attempt.wasBusinessCallStarted()
                    && (attempt.rawBusinessCode() == null || (attempt.rawBusinessCode() != 61
                        && !Objects.equals(attempt.rawBusinessCode(), fact.rawErrorCode()))))) return false;
            return HuashengErrorClassification.isPlaceCreationRejection(fact.rawErrorCode())
                    || fact.rawErrorCode() == 61
                    || (fact.rawErrorCode() == 66 && fact.securityReason() == HuashengSecurityReason.REQUEST_EXPIRED)
                    || HuashengErrorClassification.isDefinitivePreCreationRejection(fact.rawErrorCode(), fact.securityReason());
        }
        // 无码未知：仅当全部物理尝试均未开始业务请求时证明无副作用。
        return fact.attempts().stream().allMatch(attempt -> !attempt.wasBusinessCallStarted());
    }

    /** 持久化无副作用标记；证明一旦失效（false）不可逆，不回填历史行。 */
    private void updateSideEffectFreeFlag(FulfillmentOperationDO operation, boolean proven, LocalDateTime now) {
        Boolean newValue = proven;
        if (Objects.equals(operation.getSideEffectFree(), newValue)) {
            return;
        }
        operation.setSideEffectFree(newValue);
        operation.setUpdatedAt(now);
        if (operationMapper.update(operation, new LambdaUpdateWrapper<FulfillmentOperationDO>()
                .eq(FulfillmentOperationDO::getId, operation.getId())
                .set(FulfillmentOperationDO::getSideEffectFree, newValue)) != 1) {
            throw new IllegalStateException("Side-effect-free flag CAS failed: " + operation.getOperationNo());
        }
    }

    /** FINAL_REJECTION 的稳定原因：按事实区分供应商失败、请求拒绝与授权不可用（设计 §3.3、§4.2）。 */
    private static HuashengStableFailureReason rejectionReason(HuashengStepFact fact) {
        if (fact.status() == HuashengStepFact.Status.FAILED || fact.status() == HuashengStepFact.Status.CANCEL) {
            return HuashengStableFailureReason.HUASHENG_SUPPLIER_FAILED;
        }
        if (fact.rawErrorCode() != null) {
            if (HuashengErrorClassification.isDefinitivePreCreationRejection(fact.rawErrorCode(), fact.securityReason())) {
                return HuashengStableFailureReason.HUASHENG_REQUEST_REJECTED;
            }
            if (fact.rawErrorCode() == 61 || HuashengErrorClassification.isTransientBeforeSend(fact.rawErrorCode())) {
                return HuashengStableFailureReason.HUASHENG_AUTH_UNAVAILABLE;
            }
        }
        return HuashengStableFailureReason.HUASHENG_SUPPLIER_FAILED;
    }

    /** 脱敏终结说明：含原始拒绝码，不含自由文本或原始报文。 */
    private static String rejectionNote(HuashengStepFact fact) {
        if (fact.rawErrorCode() != null
                && HuashengErrorClassification.isDefinitivePreCreationRejection(fact.rawErrorCode(), fact.securityReason())) {
            return "供应商拒绝请求（原始码=" + fact.rawErrorCode() + "）";
        }
        if (fact.rawErrorCode() != null && fact.rawErrorCode() == 61) {
            return "授权不可用（二次61，发送前预算耗尽）";
        }
        if (fact.rawErrorCode() != null && HuashengErrorClassification.isTransientBeforeSend(fact.rawErrorCode())) {
            return "授权不可用（发送前暂时故障，预算耗尽，原始码=" + fact.rawErrorCode() + "）";
        }
        return "供应商履约失败";
    }

    /**
     * 价格差异留证：实报金额格式有效但与冻结成本不符时，写 reported 与 settlementStatus=MISMATCH。
     * 只保存事实，不覆盖冻结报价，不阻止后续可信失败收敛（设计 §5.1、§5.3）。
     */
    private void recordPriceMismatchIfPresent(BOrderDO bOrder, HuashengStepFact fact, long expectedAmount, LocalDateTime now) {
        if (fact.totalPriceMinorUnits() == null || fact.totalPriceRaw() == null
                || fact.endpoint() == HuashengStepFact.Endpoint.TAKE
                || fact.endpoint() == HuashengStepFact.Endpoint.CALLBACK) {
            return;
        }
        if (!fact.totalPriceRaw().matches("[0-9]+(\\.[0-9]{1,4})?")) {
            return;
        }
        if (Objects.equals(fact.totalPriceMinorUnits(), expectedAmount)) {
            return;
        }
        if (Objects.equals(bOrder.getReportedSupplierSettlementAmount(), fact.totalPriceMinorUnits())
                && bOrder.getSettlementStatus() != null
                && bOrder.getSettlementStatus() == BOrderSettlementStatus.MISMATCH.getCode()) {
            return;
        }
        bOrder.setReportedSupplierSettlementAmount(fact.totalPriceMinorUnits());
        bOrder.setSettlementStatus(BOrderSettlementStatus.MISMATCH.getCode());
        bOrder.setUpdatedAt(now);
        if (bOrderMapper.update(bOrder, new LambdaUpdateWrapper<BOrderDO>()
                .eq(BOrderDO::getId, bOrder.getId())
                .set(BOrderDO::getReportedSupplierSettlementAmount, bOrder.getReportedSupplierSettlementAmount())
                .set(BOrderDO::getSettlementStatus, bOrder.getSettlementStatus())) != 1) {
            throw new IllegalStateException("Price mismatch record CAS failed: " + bOrder.getBOrderNo());
        }
        log.info("[华昇生命周期][价格差异留证] bOrderNo={}, expected={}, reported={}",
                bOrder.getBOrderNo(), expectedAmount, fact.totalPriceMinorUnits());
    }

    /**
     * 发送前重试调度：操作回到 PENDING 保持原业务身份与原步骤，按 5／15 秒退避后重新认领。
     * 认领次数即预算（上限 3 次总认领）；重试 Timer 键含认领次序，避免与首次 EXECUTE 幂等冲突（设计 §4.5）。
     */
    private HuashengApplyResult schedulePreSendRetry(FulfillmentOperationDO operation, HuashengStepFact fact, LocalDateTime now) {
        int attemptCount = operation.getAttemptCount() != null ? operation.getAttemptCount() : 1;
        int delaySeconds = HuashengErrorClassification.preSendRetryDelaySeconds(attemptCount);
        LocalDateTime retryAt = now.plusSeconds(delaySeconds);

        operation.setStatus(SupplierOrderOperationStatus.PENDING.getCode());
        operation.setNextRecoverAt(retryAt);
        operation.setUpdatedAt(now);
        if (operationMapper.update(operation, new LambdaUpdateWrapper<FulfillmentOperationDO>()
                .eq(FulfillmentOperationDO::getId, operation.getId())
                .set(FulfillmentOperationDO::getStatus, operation.getStatus())
                .set(FulfillmentOperationDO::getNextRecoverAt, retryAt)) != 1) {
            throw new IllegalStateException("Pre-send retry CAS failed: " + operation.getOperationNo());
        }

        ObjectNode retryPayload = objectMapper.createObjectNode();
        retryPayload.put("operationNo", operation.getOperationNo());
        retryPayload.put("trigger", HuashengFlowTrigger.EXECUTE.name());
        timer.scheduleOnce(HuashengFlowCodes.EXECUTE_TASK,
                "HS:EXEC:" + operation.getOperationNo() + ":" + (attemptCount + 1),
                retryPayload, databaseClock.toInstant(retryAt));

        logAfterCommit("[华昇生命周期][发送前重试] operationNo={}, attemptCount={}, delaySeconds={}, retryAt={}, rawCode={}",
                operation.getOperationNo(), attemptCount, delaySeconds, retryAt, fact.rawErrorCode());
        return new HuashengApplyResult(HuashengFlowDecision.RETRY_PRE_SEND, null, true,
                "Pre-send retry scheduled in " + delaySeconds + "s");
    }

    /**
     * 华昇可信回调应用入口：独立事实源推进，不伪造同步执行 permit。
     *
     * <p>规则约束：
     * 1. 锁聚合：BOrder、Request、Order；
     * 2. 首 PLACE 业务发送 marker (businessSendPossibleAt) 必须已存在，否则拒绝（防止尚未真实外呼即接收回调）；
     * 3. 唯一下一步仍 PENDING 且 marker 为空不阻断回调，依据首 PLACE 的持久 marker 收敛原单；
     * 4. 递增当前操作 generation，使在途同步或恢复 permit 失格；
     * 5. 校验 outOrderSn 与 billno 一致，经 HuashengFlowPolicy 纯决策；
     * 6. 供应商已取卡成功独立留存；FINAL_SUCCESS须保存完整卡密，金额不作履约门槛（§7.3）；
     * 7. 终态幂等安全收敛。
     *
     * <p>设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#8-回调协议}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public HuashengApplyResult applyCallback(Long bOrderId, HuashengStepFact fact, String traceId) {
        return applyCallback(bOrderId, fact, List.of(), traceId);
    }

    /** 完整回调事实入口，资产与步骤载荷同源；设计见生产闭环设计 §7.1、§8.2。 */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public HuashengApplyResult applyCallback(Long bOrderId, HuashengStepFact fact, List<OperationAssetFact> assets, String traceId) {
        Objects.requireNonNull(bOrderId, "bOrderId must not be null");
        Objects.requireNonNull(fact, "fact must not be null");

        List<FulfillmentOperationDO> operations = operationMapper.selectList(
                new LambdaQueryWrapper<FulfillmentOperationDO>()
                        .eq(FulfillmentOperationDO::getBOrderId, bOrderId)
                        .orderByAsc(FulfillmentOperationDO::getSequenceNo));
        if (operations.isEmpty()) {
            return HuashengApplyResult.disqualified("No operations found for BOrder: " + bOrderId);
        }

        // 预读只定位聚合锁；最新步骤、首 PLACE marker 和终态必须从锁后行重新选择（设计 §8.2）。
        var locked = frequencyLifecycle.lockForResult(operations.getLast().getOperationNo());
        if (locked == null) return HuashengApplyResult.disqualified("LockedExecution is null");
        List<FulfillmentOperationDO> lockedOperations = locked.relatedOperations().stream()
                .filter(op -> Objects.equals(op.getBOrderId(), bOrderId))
                .sorted(Comparator.comparing(FulfillmentOperationDO::getSequenceNo)).toList();
        if (lockedOperations.isEmpty()) return HuashengApplyResult.disqualified("No locked operations");
        FulfillmentOperationDO firstOp = lockedOperations.getFirst();
        if (firstOp.getBusinessSendPossibleAt() == null) {
            log.warn("[华昇生命周期][回调被拒] 首 PLACE 发送 marker 不存在, bOrderId={}", bOrderId);
            return HuashengApplyResult.disqualified("First PLACE send marker missing");
        }

        FulfillmentOperationDO currentOperation = lockedOperations.getLast();
        BOrderDO currentBOrder = locked.bOrder();
        // lockForResult 的 relatedOperations 已持有全部 Operation 锁，重新定位最新行不改变锁序。
        locked = new RechargeFrequencyLifecycle.LockedExecution(locked.queue(), locked.entry(), locked.request(), locked.order(),
                currentBOrder, currentOperation, locked.relatedBOrders(), locked.relatedOperations(), locked.waitingHeadId());

        LocalDateTime now = databaseClock.now();

        // 解密当前操作的 Checkpoint
        HuashengCheckpointCodec.Stored stored = new HuashengCheckpointCodec.Stored(
                currentOperation.getCheckpointSchemaVersion(),
                currentOperation.getCheckpointKeyVersion(),
                currentOperation.getCheckpointCiphertext()
        );
        HuashengCheckpoint.Binding expectedBinding = new HuashengCheckpoint.Binding(
                currentBOrder.getId(),
                currentBOrder.getBOrderNo(),
                currentOperation.getOperationNo(),
                currentOperation.getSequenceNo(),
                AdapterStepCode.valueOf(currentOperation.getAdapterStepCode()),
                currentBOrder.getConnectorVersionId(),
                currentBOrder.getOfferVersionId(),
                currentOperation.getConfigDigest()
        );
        HuashengCheckpoint checkpoint;
        try {
            checkpoint = checkpointCodec.decrypt(stored, expectedBinding);
        } catch (Exception e) {
            log.warn("[华昇生命周期][回调解密失败] operationNo={}, error={}", currentOperation.getOperationNo(), e.getMessage());
            return HuashengApplyResult.disqualified("Checkpoint decryption failed: " + e.getMessage());
        }

        if (!Objects.equals(checkpoint.placeParameters().outOrderSn(), currentBOrder.getBillno())) {
            log.warn("[华昇生命周期][单号不符] operationNo={}, billno={}, checkpointOutOrderSn={}",
                    currentOperation.getOperationNo(), currentBOrder.getBillno(), checkpoint.placeParameters().outOrderSn());
            return HuashengApplyResult.disqualified("outOrderSn does not match billno");
        }

        long expectedAmount = currentBOrder.getExpectedSupplierSettlementAmount() != null
                ? currentBOrder.getExpectedSupplierSettlementAmount() : 0L;
        HuashengFlowDecision decision = HuashengFlowPolicy.decide(checkpoint, fact, expectedAmount, now);

        boolean succeeded = currentBOrder.getFulfillmentStatus() == BOrderFulfillmentStatus.SUCCEEDED.getCode();
        boolean failed = currentBOrder.getFulfillmentStatus() == BOrderFulfillmentStatus.FAILED.getCode();
        boolean cancelled = currentBOrder.getFulfillmentStatus() == BOrderFulfillmentStatus.CANCELLED.getCode();
        // 供应商成功不等于本地交付完成；未保存卡密时必须允许后续有效回调补齐（设计 §7.3）。
        if ((succeeded && locked.request().getStatus() == FulfillmentRequestStatus.SUCCEEDED.getCode()) || failed || cancelled) {
            boolean same = Objects.equals(fact.outOrderSn(), currentBOrder.getBillno())
                    && Objects.equals(fact.orderSn(), currentBOrder.getExternalOrderNo())
                    && (succeeded || checkpoint.observation() == null || isPriceRawEquivalent(checkpoint.observation().totalPriceRaw(), fact.totalPriceRaw()))
                    && (succeeded && decision == HuashengFlowDecision.FINAL_SUCCESS
                        && supplierOrderLifecycle.matchesHuashengDeliveredAsset(currentOperation.getOperationNo(), assets)
                        || failed && decision == HuashengFlowDecision.FINAL_REJECTION
                        || cancelled && decision == HuashengFlowDecision.FINAL_CANCELLATION);
            if (same) return new HuashengApplyResult(decision, null, false, "Already terminated with identical fact");
            holdManual(locked, checkpoint, fact, FulfillmentExceptionReasonCodes.SUPPLIER_TERMINAL_CONTRADICTION, true, now);
            return new HuashengApplyResult(HuashengFlowDecision.MANUAL, null, true, "Terminal conflict registered");
        }
        // 在递增回调代次前留存原围栏，资产写入回滚后仅保留已核实的供应商成功（设计 §7.3）。
        recordSupplierTakenSuccess(locked, checkpoint, fact, now);
        if (checkpoint.pendingAssetPayload() != null && fact.protectedAssetPayload() != null
                && !Arrays.equals(checkpoint.pendingAssetPayload(), fact.protectedAssetPayload())) {
            // 首期仅一个证据槽；不同卡密不能覆盖，也不能绕过证据直接交付。
            throw new HuashengCallbackConflictException("Existing protected evidence differs");
        }
        var activeCase = exceptionCaseMapper.selectActiveByOperationId(currentOperation.getId());
        if (activeCase != null && !FulfillmentExceptionReasonCodes.HUASHENG_RECOVERY_LIMIT.equals(activeCase.getReasonCode())) {
            // 人工保留不等于丢弃回调：保存相反事实并失效旧核实；不恢复购买、交付或自动结案（设计 §7）。
            currentOperation.setExecutionGeneration((currentOperation.getExecutionGeneration() == null
                    ? 0L : currentOperation.getExecutionGeneration()) + 1L);
            currentOperation.setUpdatedAt(now);
            if (operationMapper.updateById(currentOperation) != 1) throw new IllegalStateException("Manual callback generation CAS failed");
            supplierOrderLifecycle.recordHuashengResult(currentOperation.getOperationNo(), fact, traceId, now);
            holdManual(locked, checkpoint, fact, FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT, false, now);
            log.info("[华昇生命周期][已存在冲突Case保持MANUAL] operationNo={}, caseReason={}",
                    currentOperation.getOperationNo(), activeCase.getReasonCode());
            return new HuashengApplyResult(HuashengFlowDecision.MANUAL, null, true, "Existing conflict retained");
        }

        // 只在非终态事实应用时递增代次；终态冲突不修改已终结业务或资金。
        long newGeneration = (currentOperation.getExecutionGeneration() == null ? 0L : currentOperation.getExecutionGeneration()) + 1L;
        currentOperation.setExecutionGeneration(newGeneration);
        if (decision == HuashengFlowDecision.FINAL_SUCCESS || decision == HuashengFlowDecision.FINAL_REJECTION) {
            var observation = toObservation(checkpoint, fact);
            var updatedCheckpoint = new HuashengCheckpoint(
                    checkpoint.binding(),
                    checkpoint.placeParameters(),
                    fact.orderSn() != null ? fact.orderSn() : checkpoint.orderSn(),
                    observation,
                    checkpoint.progress(),
                    fact.protectedAssetPayload() != null ? fact.protectedAssetPayload() : checkpoint.pendingAssetPayload(),
                    checkpoint.notCreatedCandidate()
            );
            var terminalStored = checkpointCodec.encrypt(updatedCheckpoint);
            currentOperation.setCheckpointSchemaVersion(terminalStored.schemaVersion());
            currentOperation.setCheckpointKeyVersion(terminalStored.keyVersion());
            currentOperation.setCheckpointCiphertext(terminalStored.ciphertext());
        }
        currentOperation.setUpdatedAt(now);
        if (operationMapper.updateById(currentOperation) != 1) throw new IllegalStateException("Callback generation CAS failed");
        supplierOrderLifecycle.recordHuashengResult(currentOperation.getOperationNo(), fact, traceId, now);
        if ((decision == HuashengFlowDecision.FINAL_REJECTION || decision == HuashengFlowDecision.FINAL_CANCELLATION)
                && supplierOrderLifecycle.hasHuashengFailureEvidenceConflict(currentOperation.getOperationNo(),
                    decision == HuashengFlowDecision.FINAL_CANCELLATION ? HuashengStableFailureReason.HUASHENG_SUPPLIER_CANCELLED
                            : HuashengStableFailureReason.HUASHENG_SUPPLIER_FAILED)) {
            holdManual(locked, checkpoint, fact, FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT, false, now);
            return new HuashengApplyResult(HuashengFlowDecision.MANUAL, null, true, "Historical evidence blocks callback failure");
        }

        log.info("[华昇生命周期][回调决策] operationNo={}, endpoint={}, factStatus={}, decision={}",
                currentOperation.getOperationNo(), fact.endpoint(), fact.status(), decision);

        HuashengExecutionPermit callbackPermit = new HuashengExecutionPermit(
                currentOperation.getOperationNo(),
                currentBOrder.getId(),
                newGeneration,
                AdapterStepCode.valueOf(currentOperation.getAdapterStepCode()),
                HuashengFlowTrigger.CALLBACK,
                now.plusMinutes(5)
        );

        OperationOutcome outcome = OperationOutcome.builder()
                .outcomeClass(decision == HuashengFlowDecision.FINAL_SUCCESS ? OperationOutcomeClass.SUCCEEDED
                        : decision == HuashengFlowDecision.FINAL_REJECTION ? OperationOutcomeClass.REJECTED
                        : OperationOutcomeClass.ACCEPTED)
                .protocolFact(fact)
                .externalOrderNo(fact.orderSn())
                .reportedSettlementAmount(fact.totalPriceMinorUnits())
                .assets(assets)
                .build();

        if (decision == HuashengFlowDecision.FINAL_SUCCESS && (assets.size() != 1
                || !Arrays.equals(assets.getFirst().protectedPayload(), fact.protectedAssetPayload()))) {
            throw new IllegalArgumentException("Callback complete asset fact required");
        }

        return switch (decision) {
            case FINAL_SUCCESS -> {
                supplierOrderLifecycle.commitHuashengSuccess(callbackPermit, outcome, traceId);
                annotateRecoveryLimitCase(activeCase, now);
                yield new HuashengApplyResult(decision, null, true, "Callback final success committed");
            }
            case FINAL_REJECTION -> {
                supplierOrderLifecycle.releaseHuashengRejection(callbackPermit, outcome, traceId);
                annotateRecoveryLimitCase(activeCase, now);
                yield new HuashengApplyResult(decision, null, true, "Callback final rejection released");
            }
            case FINAL_CANCELLATION -> {
                // 回调不产生 CANCEL 状态（协议只接受 SUCCESS/FAILED）；防御性按可信取消收敛或保留。
                if (checkpoint.pendingAssetPayload() != null) {
                    holdManual(locked, checkpoint, fact, FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT, false, now);
                    yield new HuashengApplyResult(HuashengFlowDecision.MANUAL, null, true, "Pending asset blocks cancellation closure");
                }
                supplierOrderLifecycle.closeHuashengTrustedFailure(callbackPermit, outcome, traceId,
                        new SupplierOrderLifecycle.HuashengTrustedFailureClosure(true,
                                HuashengStableFailureReason.HUASHENG_SUPPLIER_CANCELLED, "供应商返回已取消",
                                outcome.reportedSettlementAmount()));
                annotateRecoveryLimitCase(activeCase, now);
                yield new HuashengApplyResult(decision, null, true, "Callback trusted cancellation closed");
            }
            case NEXT_CONFIRM, NEXT_TAKE -> advanceToNextStep(currentOperation, currentBOrder, checkpoint, fact, outcome, decision, traceId, now);
            case WAIT_QUERY -> scheduleQueryRecovery(currentOperation, currentBOrder, checkpoint, fact, decision, traceId, now, false);
            case FINAL_NOT_CREATED, RETRY_PRE_SEND -> {
                // 回调事实不会产生未建单收敛或发送前重试；防御性保留人工，不执行动账。
                holdManual(locked, checkpoint, fact, FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT, false, now);
                yield new HuashengApplyResult(HuashengFlowDecision.MANUAL, null, true, "Unexpected callback decision retained manual");
            }
            case MANUAL -> {
                holdManual(locked, checkpoint, fact, FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT, false, now);
                yield new HuashengApplyResult(decision, null, true, "Transitioned to manual by callback");
            }
        };
    }

    private static boolean isPriceRawEquivalent(String a, String b) {
        if (Objects.equals(a, b)) return true;
        if (a == null || b == null) return false;
        try {
            return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 复制终结分类及可信金额，其余执行事实完整保留；不修改 executor 输出（设计 §6.3）。 */
    private static OperationOutcome terminalOutcome(OperationOutcome source, OperationOutcomeClass resultClass, Long amount) {
        return new OperationOutcome(resultClass, source.externalOrderNo(), amount, source.externalErrorCode(),
                source.externalErrorMessage(), source.assets(), source.rawObservation(), source.callDigest(),
                source.nextCheckpoint(), source.protocolFact());
    }

    /** 回调核实后保留 case 审计，不能伪造人工操作人自动结案（设计 §5.3）。 */
    private void annotateRecoveryLimitCase(FulfillmentExceptionCaseDO activeCase, LocalDateTime now) {
        if (activeCase == null || !FulfillmentExceptionReasonCodes.HUASHENG_RECOVERY_LIMIT.equals(activeCase.getReasonCode())) return;
        activeCase.setResolutionNote("可信华昇回调已核实原单终态，待人工审计结案"); activeCase.setUpdatedAt(now);
        if (exceptionCaseMapper.updateById(activeCase) != 1) {
            throw new IllegalStateException("Recovery case CAS failed");
        }
    }

    /**
     * 正常随结果事务保存供应商成功；若本地交付回滚，仅在原围栏仍有效时独立保存供应商事实。
     * 必须等原事务回滚释放聚合锁后再开启REQUIRES_NEW，避免持锁嵌套事务自锁。
     * 不在独立事务保存资产、资金或Inbox成功；不重复注册回滚处理。
     * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#73-201202卡号卡密兼容与新单成功闭环。
     */
    private void recordSupplierTakenSuccess(RechargeFrequencyLifecycle.LockedExecution locked,
                                            HuashengCheckpoint checkpoint, HuashengStepFact fact, LocalDateTime now) {
        if (!recordSupplierTakenSuccess(locked.bOrder(), checkpoint, fact, now)) return;
        String operationNo = locked.operation().getOperationNo();
        Long generation = locked.operation().getExecutionGeneration();
        Short operationStatus = locked.operation().getStatus();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) return;
                var tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                tx.executeWithoutResult(ignored -> {
                    var current = frequencyLifecycle.lockForResult(operationNo);
                    if (current == null || !Objects.equals(current.operation().getExecutionGeneration(), generation)
                            || !Objects.equals(current.operation().getStatus(), operationStatus)) return;
                    var currentCheckpoint = decryptCheckpoint(current.operation(), current.bOrder());
                    if (!HuashengCheckpointCodec.sameBinding(currentCheckpoint.binding(), checkpoint.binding())) return;
                    recordSupplierTakenSuccess(current.bOrder(), currentCheckpoint, fact, databaseClock.now());
                });
            }
        });
    }

    /**
     * 保存可信“履约成功且已取卡”事实，不等待本地交付或金额核对。
     * activeFlag暂留作原单补卡占位；不得因本地缺卡转人工而抹掉供应商成功。
     * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#73-201202卡号卡密兼容与新单成功闭环。
     */
    private boolean recordSupplierTakenSuccess(BOrderDO bOrder, HuashengCheckpoint checkpoint, HuashengStepFact fact, LocalDateTime now) {
        if (fact.resultClass() != HuashengStepFact.StepResult.ACCEPTED || fact.status() != HuashengStepFact.Status.SUCCESS
                || fact.num() != checkpoint.placeParameters().num()
                || !Objects.equals(fact.outOrderSn(), bOrder.getBillno())
                || fact.orderSn() == null || fact.orderSn().isBlank()
                || checkpoint.orderSn() != null && !Objects.equals(checkpoint.orderSn(), fact.orderSn())
                || bOrder.getExternalOrderNo() != null && !Objects.equals(bOrder.getExternalOrderNo(), fact.orderSn())
                || bOrder.getFulfillmentStatus() == BOrderFulfillmentStatus.FAILED.getCode()
                || bOrder.getFulfillmentStatus() == BOrderFulfillmentStatus.CANCELLED.getCode()
                || bOrder.getFulfillmentStatus() == BOrderFulfillmentStatus.SUCCEEDED.getCode()) return false;
        boolean taken = fact.isTake() == HuashengStepFact.IsTake.Y || fact.endpoint() == HuashengStepFact.Endpoint.TAKE
                || fact.endpoint() == HuashengStepFact.Endpoint.CALLBACK && HuashengFlowPolicy.isValidType201Payload(fact.protectedAssetPayload());
        if (!taken) return false;
        bOrder.setFulfillmentStatus(BOrderFulfillmentStatus.SUCCEEDED.getCode());
        bOrder.setFulfilledQuantity(bOrder.getRequestedQuantity());
        bOrder.setExternalOrderNo(fact.orderSn());
        bOrder.setCompletedAt(now); bOrder.setUpdatedAt(now);
        if (bOrderMapper.updateById(bOrder) != 1) throw new IllegalStateException("Supplier success CAS failed");
        logAfterCommit("[华昇生命周期][供应商履约成功] bOrderNo={}, endpoint={}", bOrder.getBOrderNo(), fact.endpoint());
        return true;
    }

    /** 同事务隔离资产证据与唯一 case，不交付、不动资金、不覆盖既有终态；设计 §6.3、§8.2。 */
    private void holdManual(RechargeFrequencyLifecycle.LockedExecution locked, HuashengCheckpoint checkpoint,
                            HuashengStepFact fact, String reason, boolean preserveTerminal, LocalDateTime now) {
        var operation = locked.operation();
        if (checkpoint != null) {
            byte[] previous = checkpoint.pendingAssetPayload();
            byte[] incoming = fact == null ? null : fact.protectedAssetPayload();
            try {
                if (!preserveTerminal && previous != null && incoming != null && !Arrays.equals(previous, incoming)) {
                    // 单证据槽不能无声覆盖；回滚本次 Inbox，非200要求人工核实。
                    throw new HuashengCallbackConflictException("Existing protected evidence differs");
                }
                var retained = new HuashengCheckpoint(checkpoint.binding(), checkpoint.placeParameters(), checkpoint.orderSn(),
                        checkpoint.observation(), checkpoint.progress(), previous != null ? previous : incoming,
                        checkpoint.notCreatedCandidate());
                var stored = checkpointCodec.encrypt(retained);
                operation.setCheckpointSchemaVersion(stored.schemaVersion()); operation.setCheckpointKeyVersion(stored.keyVersion());
                operation.setCheckpointCiphertext(stored.ciphertext());
            } finally {
                if (previous != null) Arrays.fill(previous, (byte) 0);
                if (incoming != null) Arrays.fill(incoming, (byte) 0);
            }
        }
        if (!preserveTerminal) {
            operation.setStatus(SupplierOrderOperationStatus.MANUAL.getCode());
            operation.setResultClass(OperationOutcomeClass.MANUAL_REQUIRED.name());
            if (locked.bOrder().getFulfillmentStatus() != BOrderFulfillmentStatus.SUCCEEDED.getCode()) {
                locked.bOrder().setFulfillmentStatus(BOrderFulfillmentStatus.MANUAL.getCode());
            }
            locked.bOrder().setUpdatedAt(now);
            if (bOrderMapper.updateById(locked.bOrder()) != 1) throw new IllegalStateException("Manual BOrder CAS failed");
            locked.request().setStatus(FulfillmentRequestStatus.MANUAL.getCode()); locked.request().setUpdatedAt(now);
            if (requestMapper.updateById(locked.request()) != 1) throw new IllegalStateException("Manual Request CAS failed");
            var order = locked.order();
            if (order.getOrderStatus() != com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus.MANUAL.getCode()) {
                short fromStatus = order.getOrderStatus();
                order.setOrderStatus(com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus.MANUAL.getCode());
                order.setBusinessStage(com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes.STAGE_CARD_MANUAL);
                order.setDetailVersion(order.getDetailVersion() + 1); order.setUpdatedAt(now);
                if (orderMapper.updateById(order) != 1) throw new IllegalStateException("Manual Order CAS failed");
                statusLogMapper.insert(com.aifuli.module.supplychain.border.order.dal.dataobject.OrderStatusLogDO.builder()
                        .orderId(order.getId()).detailVersion(order.getDetailVersion()).fromOrderStatus(fromStatus)
                        .toOrderStatus(order.getOrderStatus()).fromAftersaleStatus(order.getAftersaleStatus()).toAftersaleStatus(order.getAftersaleStatus())
                        .businessStage(order.getBusinessStage()).sourceEventType("FULFILLMENT_MANUAL").sourceEventNo(operation.getOperationNo())
                        .reasonCode(reason).traceId(checkpoint == null ? "" : checkpoint.progress().originTraceId()).createdAt(now).build());
            }
        }
        operation.setUpdatedAt(now);
        if (operationMapper.updateById(operation) != 1) throw new IllegalStateException("Manual Operation CAS failed");
        var existingCase = exceptionCaseMapper.selectActiveByOperationId(operation.getId());
        String evidenceRef = "HUASHENG_CHECKPOINT:" + operation.getOperationNo();
        byte[] evidence = checkpoint == null ? evidenceRef.getBytes(StandardCharsets.UTF_8)
                : checkpointCodec.encodePlaintext(new HuashengCheckpoint(checkpoint.binding(), checkpoint.placeParameters(),
                checkpoint.orderSn(), checkpoint.observation(), checkpoint.progress(), fact == null ? checkpoint.pendingAssetPayload() : fact.protectedAssetPayload()));
        try {
            if (existingCase == null) {
                exceptionCaseMapper.insert(FulfillmentExceptionCaseDO.builder()
                        .caseNo(businessNoGenerator.next(BusinessNoPrefixes.CASE)).orderId(locked.order().getId())
                        .fulfillmentRequestId(locked.request().getId()).bOrderId(locked.bOrder().getId()).operationId(operation.getId())
                        .reasonCode(reason).status(FulfillmentExceptionCaseStatus.OPEN.getCode()).evidenceRef(evidenceRef)
                        .evidenceDigest(hmacFingerprint.fingerprint(HuashengFlowCodes.EXCEPTION_EVIDENCE_PURPOSE, evidence))
                        .activeCaseFlag(1).createdAt(now).updatedAt(now).version(0L).build());
            } else if (FulfillmentExceptionReasonCodes.HUASHENG_RECOVERY_LIMIT.equals(existingCase.getReasonCode())
                    && (FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT.equals(reason)
                        || FulfillmentExceptionReasonCodes.SUPPLIER_TERMINAL_CONTRADICTION.equals(reason))) {
                existingCase.setReasonCode(reason);
                existingCase.setEvidenceRef(evidenceRef);
                existingCase.setEvidenceDigest(hmacFingerprint.fingerprint(HuashengFlowCodes.EXCEPTION_EVIDENCE_PURPOSE, evidence));
                existingCase.setUpdatedAt(now);
                if (exceptionCaseMapper.updateById(existingCase) != 1) {
                    throw new IllegalStateException("Exception case upgrade CAS failed");
                }
            }
        } finally { Arrays.fill(evidence, (byte) 0); }
    }

    private HuashengApplyResult advanceToNextStep(
            FulfillmentOperationDO currentOperation,
            BOrderDO currentBOrder,
            HuashengCheckpoint checkpoint,
            HuashengStepFact fact,
            OperationOutcome outcome,
            HuashengFlowDecision decision,
            String traceId,
            LocalDateTime now
    ) {
        // 1. CAS 结束当前操作
        currentOperation.setStatus(SupplierOrderOperationStatus.SUCCEEDED.getCode());
        currentOperation.setResultClass(OperationOutcomeClass.ACCEPTED.name());
        if (outcome.externalOrderNo() != null && !outcome.externalOrderNo().isBlank()) {
            currentOperation.setExternalOperationNo(outcome.externalOrderNo());
        }
        currentOperation.setCompletedAt(now);
        currentOperation.setUpdatedAt(now);
        if (operationMapper.updateById(currentOperation) != 1) {
            throw new IllegalStateException("Operation CAS update failed: " + currentOperation.getOperationNo());
        }

        // 2. 构造 sequence+1 唯一下一步操作与新 AAD checkpoint
        int nextSeq = currentOperation.getSequenceNo() + 1;
        AdapterStepCode nextStep = decision == HuashengFlowDecision.NEXT_CONFIRM
                ? AdapterStepCode.CONFIRM_ORDER : AdapterStepCode.TAKE_CARD;
        String nextOpNo = businessNoGenerator.next(BusinessNoPrefixes.OP);
        String nextIdempotencyKey = FulfillmentIdempotencyKeys.operation(currentBOrder.getBOrderNo(), nextSeq);
        byte[] nextConfigDigest = sha256((nextIdempotencyKey + "|" + currentBOrder.getOfferVersionId()
                + "|" + currentBOrder.getConnectorVersionId()).getBytes(StandardCharsets.UTF_8));

        String orderSn = fact.orderSn() != null ? fact.orderSn() : checkpoint.orderSn();
        HuashengCheckpoint.Observation observation = toObservation(checkpoint, fact);

        int queryCount = checkpoint.progress().queryCount();
        int confirmCount = checkpoint.progress().confirmCount();
        int takeCount = checkpoint.progress().takeCount();
        LocalDateTime firstSendAt = checkpoint.progress().firstSendAt() != null
                ? checkpoint.progress().firstSendAt() : now.truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime recoveryDeadlineAt = checkpoint.progress().recoveryDeadlineAt() != null
                ? checkpoint.progress().recoveryDeadlineAt() : firstSendAt.plusMinutes(HuashengFlowPolicy.RECOVERY_DEADLINE_MINUTES);
        String originTraceId = checkpoint.progress().originTraceId();

        HuashengCheckpoint.Progress nextProgress = new HuashengCheckpoint.Progress(
                queryCount, confirmCount, takeCount, firstSendAt, recoveryDeadlineAt, originTraceId);

        HuashengCheckpoint.Binding nextBinding = new HuashengCheckpoint.Binding(
                currentBOrder.getId(), currentBOrder.getBOrderNo(), nextOpNo, nextSeq, nextStep,
                currentBOrder.getConnectorVersionId(), currentBOrder.getOfferVersionId(), nextConfigDigest);

        // 推进下一步意味着订单已确认存在：活跃候选永久退出（设计 §5.4.4）。
        var candidate = checkpoint.notCreatedCandidate();
        if (candidate != null && !candidate.exited()) {
            candidate = new HuashengCheckpoint.NotCreatedCandidate(
                    candidate.earliestRejectionCode(), candidate.firstRejectionAt(),
                    candidate.validNotCreatedQueryCount(), candidate.lastQueryAt(),
                    candidate.nextQueryAt(), true, candidate.queryResultCount());
        }

        HuashengCheckpoint nextCheckpoint = new HuashengCheckpoint(
                nextBinding, checkpoint.placeParameters(), orderSn, observation, nextProgress,
                checkpoint.pendingAssetPayload(), candidate);

        HuashengCheckpointCodec.Stored nextStored = checkpointCodec.encrypt(nextCheckpoint);

        FulfillmentOperationDO nextOp = FulfillmentOperationDO.builder()
                .operationNo(nextOpNo)
                .bOrderId(currentBOrder.getId())
                .operationKind(FulfillmentOperationKind.FULFILL.name())
                .adapterStepCode(nextStep.name())
                .sequenceNo(nextSeq)
                .idempotencyKey(nextIdempotencyKey)
                .configDigest(nextConfigDigest)
                .status(SupplierOrderOperationStatus.PENDING.getCode())
                .attemptCount(0)
                .executionGeneration(0L)
                .recoveryDeadlineAt(recoveryDeadlineAt)
                .checkpointSchemaVersion(nextStored.schemaVersion())
                .checkpointKeyVersion(nextStored.keyVersion())
                .checkpointCiphertext(nextStored.ciphertext())
                .version(0L)
                .createdAt(now)
                .updatedAt(now)
                .build();
        operationMapper.insert(nextOp);

        // 3. 同事务调度 Timers
        ObjectNode execPayload = objectMapper.createObjectNode();
        execPayload.put("operationNo", nextOpNo);
        execPayload.put("trigger", HuashengFlowTrigger.EXECUTE.name());
        execPayload.put("originTraceId", originTraceId);
        timer.scheduleOnce(HuashengFlowCodes.EXECUTE_TASK,
                "HS:EXEC:" + nextOpNo, execPayload, databaseClock.toInstant(now));

        ObjectNode prepPayload = objectMapper.createObjectNode();
        prepPayload.put("operationNo", nextOpNo);
        prepPayload.put("originTraceId", originTraceId);
        timer.scheduleOnce(HuashengFlowCodes.PREPARATION_WATCHDOG_TASK,
                "HS:PREP:" + nextOpNo, prepPayload,
                databaseClock.toInstant(now.plusMinutes(HuashengFlowPolicy.PREPARATION_WATCHDOG_MINUTES)));

        logAfterCommit("[华昇生命周期][推进下一步] currentOpNo={}, nextOpNo={}, nextSeq={}, nextStep={}",
                currentOperation.getOperationNo(), nextOpNo, nextSeq, nextStep);

        return new HuashengApplyResult(decision, nextOpNo, true, "Advanced to " + nextStep);
    }

    /**
     * 查单恢复调度，同时推进未建单候选状态机（设计 §5.4）：
     * 首次创建拒绝后进入候选（5 秒后首查）；每次有效 7014 累计并按 15／30／60 秒退避（封顶 60 秒）；
     * 查到订单即永久退出候选。普通恢复间隔保持 30 秒。候选事实与结果状态及 Timer 意图同事务更新。
     */
    private HuashengApplyResult scheduleQueryRecovery(
            FulfillmentOperationDO currentOperation,
            BOrderDO currentBOrder,
            HuashengCheckpoint checkpoint,
            HuashengStepFact fact,
            HuashengFlowDecision decision,
            String traceId,
            LocalDateTime now,
            boolean sideEffectFreeProven
    ) {
        int queryCount = checkpoint.progress().queryCount();
        int confirmCount = checkpoint.progress().confirmCount();
        int takeCount = checkpoint.progress().takeCount();
        LocalDateTime firstSendAt = checkpoint.progress().firstSendAt() != null
                ? checkpoint.progress().firstSendAt() : now.truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime recoveryDeadlineAt = checkpoint.progress().recoveryDeadlineAt() != null
                ? checkpoint.progress().recoveryDeadlineAt() : firstSendAt.plusMinutes(HuashengFlowPolicy.RECOVERY_DEADLINE_MINUTES);
        String originTraceId = checkpoint.progress().originTraceId();

        LocalDateTime nowMillis = now.truncatedTo(ChronoUnit.MILLIS);
        var existingCandidate = checkpoint.notCreatedCandidate();
        boolean candidateActive = existingCandidate != null && !existingCandidate.exited();

        // 候选进入：首次 PLACE 明确创建拒绝且完整历史证明无未核实发送（设计 §5.4.1）。
        boolean enterCandidacy = existingCandidate == null
                && sideEffectFreeProven
                && HuashengFlowPolicy.qualifiesNotCreatedCandidacy(checkpoint, fact);

        // 有效未建单查询：候选活跃且 QUERY 正式解析得到 7014（设计 §5.4.3）。
        boolean validNotCreatedQuery = candidateActive
                && fact.endpoint() == HuashengStepFact.Endpoint.QUERY
                && fact.resultClass() == HuashengStepFact.StepResult.RESULT_UNKNOWN
                && fact.rawErrorCode() != null && fact.rawErrorCode() == 7014;

        HuashengCheckpoint.NotCreatedCandidate nextCandidate = existingCandidate;
        LocalDateTime nextRecoverAt = now.plusSeconds(HuashengFlowPolicy.QUERY_INTERVAL_SECONDS);
        if (enterCandidacy) {
            nextCandidate = new HuashengCheckpoint.NotCreatedCandidate(
                    fact.rawErrorCode(), nowMillis, 0, null,
                    nowMillis.plusSeconds(HuashengErrorClassification.notCreatedBackoffSeconds(0)), false);
            nextRecoverAt = nextCandidate.nextQueryAt();
        } else if (validNotCreatedQuery) {
            int newCount = existingCandidate.validNotCreatedQueryCount() + 1;
            int queryResultCount = existingCandidate.queryResultCount() + 1;
            nextCandidate = new HuashengCheckpoint.NotCreatedCandidate(
                    existingCandidate.earliestRejectionCode(), existingCandidate.firstRejectionAt(),
                    newCount, nowMillis,
                    nowMillis.plusSeconds(HuashengErrorClassification.notCreatedBackoffSeconds(queryResultCount)), false, queryResultCount);
            nextRecoverAt = nextCandidate.nextQueryAt();
        } else if (candidateActive && HuashengFlowPolicy.indicatesSupplierOrderExists(fact)) {
            // 查到订单即永久退出候选，不得被后续 7014 覆盖（设计 §5.4.4）。
            nextCandidate = new HuashengCheckpoint.NotCreatedCandidate(
                    existingCandidate.earliestRejectionCode(), existingCandidate.firstRejectionAt(),
                    existingCandidate.validNotCreatedQueryCount(), existingCandidate.lastQueryAt(),
                    existingCandidate.nextQueryAt(), true, existingCandidate.queryResultCount());
        } else if (candidateActive) {
            int queryResultCount = existingCandidate.queryResultCount() + 1;
            nextCandidate = new HuashengCheckpoint.NotCreatedCandidate(
                    existingCandidate.earliestRejectionCode(), existingCandidate.firstRejectionAt(),
                    existingCandidate.validNotCreatedQueryCount(), nowMillis,
                    nowMillis.plusSeconds(HuashengErrorClassification.notCreatedBackoffSeconds(
                            queryResultCount)), false, queryResultCount);
            nextRecoverAt = nextCandidate.nextQueryAt();
        } else if (fact.resultClass() == HuashengStepFact.StepResult.RESULT_UNKNOWN
                && fact.endpoint() == HuashengStepFact.Endpoint.QUERY) {
            nextRecoverAt = now.plusSeconds(queryCount <= 1 ? 30 : queryCount == 2 ? 60 : 120);
        }

        HuashengCheckpoint.Progress nextProgress = new HuashengCheckpoint.Progress(
                queryCount, confirmCount, takeCount, firstSendAt, recoveryDeadlineAt, originTraceId);
        HuashengCheckpoint.Observation observation = toObservation(checkpoint, fact);
        String orderSn = fact.orderSn() != null ? fact.orderSn() : checkpoint.orderSn();

        HuashengCheckpoint nextCheckpoint = new HuashengCheckpoint(
                checkpoint.binding(), checkpoint.placeParameters(), orderSn, observation, nextProgress,
                fact.protectedAssetPayload() != null ? fact.protectedAssetPayload() : checkpoint.pendingAssetPayload(),
                nextCandidate);
        HuashengCheckpointCodec.Stored stored = checkpointCodec.encrypt(nextCheckpoint);

        currentOperation.setCheckpointSchemaVersion(stored.schemaVersion());
        currentOperation.setCheckpointKeyVersion(stored.keyVersion());
        currentOperation.setCheckpointCiphertext(stored.ciphertext());
        currentOperation.setRecoveryDeadlineAt(recoveryDeadlineAt);
        currentOperation.setStatus(SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode());
        currentOperation.setResultClass(OperationOutcomeClass.RESULT_UNKNOWN.name());
        currentOperation.setNextRecoverAt(nextRecoverAt);
        currentOperation.setUpdatedAt(now);
        if (operationMapper.updateById(currentOperation) != 1) {
            throw new IllegalStateException("Operation CAS update failed: " + currentOperation.getOperationNo());
        }

        ObjectNode recPayload = objectMapper.createObjectNode();
        recPayload.put("operationNo", currentOperation.getOperationNo());
        recPayload.put("expectedGeneration", currentOperation.getExecutionGeneration());
        recPayload.put("trigger", HuashengFlowTrigger.RECOVER.name());
        recPayload.put("originTraceId", originTraceId);
        timer.scheduleOnce(HuashengFlowCodes.RECOVERY_TASK,
                "HS:REC:" + currentOperation.getOperationNo() + ":" + currentOperation.getExecutionGeneration(),
                recPayload, databaseClock.toInstant(nextRecoverAt));

        logAfterCommit("[华昇生命周期][安排查单恢复] operationNo={}, queryCount={}, nextRecoverAt={}, candidate={}, validCount={}",
                currentOperation.getOperationNo(), queryCount, nextRecoverAt,
                nextCandidate != null, nextCandidate != null ? nextCandidate.validNotCreatedQueryCount() : 0);

        return new HuashengApplyResult(decision, null, true, "Scheduled query recovery");
    }

    private HuashengCheckpoint.Observation toObservation(HuashengCheckpoint checkpoint, HuashengStepFact fact) {
        if (fact.endpoint() == HuashengStepFact.Endpoint.TAKE) {
            return checkpoint.observation();
        }
        if (fact.totalPriceRaw() == null || fact.totalPriceMinorUnits() == null) {
            return checkpoint.observation();
        }
        String orderSn = fact.orderSn() != null ? fact.orderSn() : checkpoint.orderSn();
        if (orderSn == null && checkpoint.observation() != null) {
            orderSn = checkpoint.observation().orderSn();
        }
        if (orderSn == null) {
            return checkpoint.observation();
        }
        HuashengEndpoint endpoint = HuashengEndpoint.valueOf(fact.endpoint().name());
        HuashengOrderStatus status = HuashengOrderStatus.valueOf(fact.status().name());
        HuashengField<HuashengRefundStatus> refundStatus = fact.refundStatus() == HuashengStepFact.RefundStatus.ABSENT
                ? HuashengField.absent()
                : HuashengField.ofValue(HuashengRefundStatus.valueOf(fact.refundStatus().name()));
        HuashengField<HuashengTakeStatus> isTake = fact.isTake() == HuashengStepFact.IsTake.ABSENT
                ? HuashengField.absent()
                : HuashengField.ofValue(HuashengTakeStatus.valueOf(fact.isTake().name()));
        return new HuashengCheckpoint.Observation(
                endpoint,
                orderSn,
                fact.outOrderSn(),
                fact.num(),
                fact.totalPriceRaw(),
                fact.totalPriceMinorUnits(),
                status,
                refundStatus,
                isTake
        );
    }

    /**
     * 在认领事务内消耗整笔业务配额；宕机不依赖 apply 才记数，最后一次合法结果仍可收敛。
     * 设计见 docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#53-watchdog与计数。
     */
    private boolean consumeClaimQuota(RechargeFrequencyLifecycle.LockedExecution locked, AdapterStepCode step, LocalDateTime now) {
        var operation = locked.operation();
        var bOrder = locked.bOrder();
        var binding = new HuashengCheckpoint.Binding(bOrder.getId(), bOrder.getBOrderNo(), operation.getOperationNo(),
                operation.getSequenceNo(), AdapterStepCode.valueOf(operation.getAdapterStepCode()),
                bOrder.getConnectorVersionId(), bOrder.getOfferVersionId(), operation.getConfigDigest());
        HuashengCheckpoint checkpoint;
        try {
            checkpoint = checkpointCodec.decrypt(new HuashengCheckpointCodec.Stored(operation.getCheckpointSchemaVersion(),
                    operation.getCheckpointKeyVersion(), operation.getCheckpointCiphertext()), binding);
        } catch (IllegalArgumentException invalidCheckpoint) {
            holdManual(locked, null, null, FulfillmentExceptionReasonCodes.HUASHENG_PREPARATION_FAILED, false, now);
            return false;
        }
        var progress = checkpoint.progress();
        if (!Objects.equals(checkpoint.placeParameters().outOrderSn(), bOrder.getBillno())
                || !Objects.equals(operation.getRecoveryDeadlineAt(), progress.recoveryDeadlineAt())) {
            holdManual(locked, checkpoint, null, FulfillmentExceptionReasonCodes.HUASHENG_PREPARATION_FAILED, false, now);
            return false;
        }
        boolean exceeded = progress.recoveryDeadlineAt() != null && !now.isBefore(progress.recoveryDeadlineAt())
                || step == AdapterStepCode.QUERY_ORDER && progress.queryCount() >= HuashengFlowPolicy.MAX_QUERY_COUNT
                || step == AdapterStepCode.CONFIRM_ORDER && progress.confirmCount() >= HuashengFlowPolicy.MAX_CONFIRM_COUNT
                || step == AdapterStepCode.TAKE_CARD && progress.takeCount() >= HuashengFlowPolicy.MAX_TAKE_COUNT;
        if (exceeded) {
            holdManual(locked, checkpoint, null, FulfillmentExceptionReasonCodes.HUASHENG_RECOVERY_LIMIT, false, now);
            return false;
        }
        LocalDateTime firstSendAt = progress.firstSendAt() == null ? now.truncatedTo(ChronoUnit.MILLIS) : progress.firstSendAt();
        LocalDateTime deadline = progress.recoveryDeadlineAt() == null
                ? firstSendAt.plusMinutes(HuashengFlowPolicy.RECOVERY_DEADLINE_MINUTES) : progress.recoveryDeadlineAt();
        var updatedProgress = new HuashengCheckpoint.Progress(
                progress.queryCount() + (step == AdapterStepCode.QUERY_ORDER ? 1 : 0),
                progress.confirmCount() + (step == AdapterStepCode.CONFIRM_ORDER ? 1 : 0),
                progress.takeCount() + (step == AdapterStepCode.TAKE_CARD ? 1 : 0),
                firstSendAt, deadline, progress.originTraceId());
        var stored = checkpointCodec.encrypt(new HuashengCheckpoint(binding, checkpoint.placeParameters(), checkpoint.orderSn(),
                checkpoint.observation(), updatedProgress, checkpoint.pendingAssetPayload(), checkpoint.notCreatedCandidate()));
        operation.setCheckpointSchemaVersion(stored.schemaVersion()); operation.setCheckpointKeyVersion(stored.keyVersion());
        operation.setCheckpointCiphertext(stored.ciphertext()); operation.setRecoveryDeadlineAt(deadline);
        return true;
    }

    private static boolean isTerminal(Short status) {
        if (status == null) return false;
        return status == SupplierOrderOperationStatus.SUCCEEDED.getCode()
                || status == SupplierOrderOperationStatus.FAILED.getCode()
                || status == SupplierOrderOperationStatus.MANUAL.getCode();
    }

    private HuashengCheckpoint decryptCheckpoint(FulfillmentOperationDO operation, BOrderDO bOrder) {
        var binding = new HuashengCheckpoint.Binding(bOrder.getId(), bOrder.getBOrderNo(), operation.getOperationNo(),
                operation.getSequenceNo(), AdapterStepCode.valueOf(operation.getAdapterStepCode()),
                bOrder.getConnectorVersionId(), bOrder.getOfferVersionId(), operation.getConfigDigest());
        return checkpointCodec.decrypt(new HuashengCheckpointCodec.Stored(operation.getCheckpointSchemaVersion(),
                operation.getCheckpointKeyVersion(), operation.getCheckpointCiphertext()), binding);
    }

    /**
     * 跨单资产唯一键冲突后，在独立受控事务内安全留证、开 HUASHENG_FACT_CONFLICT case 并转 MANUAL。
     * 资金绝不释放；设计见 docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#71-task2桥接与持久基础边界。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordFactConflictAndHoldManual(Long bOrderId, HuashengStepFact fact, String traceId) {
        Objects.requireNonNull(bOrderId, "bOrderId must not be null");
        List<FulfillmentOperationDO> operations = operationMapper.selectList(
                new LambdaQueryWrapper<FulfillmentOperationDO>()
                        .eq(FulfillmentOperationDO::getBOrderId, bOrderId)
                        .orderByAsc(FulfillmentOperationDO::getSequenceNo));
        if (operations.isEmpty()) return;
        var locked = frequencyLifecycle.lockForResult(operations.getLast().getOperationNo());
        if (locked == null) return;
        List<FulfillmentOperationDO> lockedOperations = locked.relatedOperations().stream()
                .filter(op -> Objects.equals(op.getBOrderId(), bOrderId))
                .sorted(Comparator.comparing(FulfillmentOperationDO::getSequenceNo)).toList();
        if (lockedOperations.isEmpty()) return;
        FulfillmentOperationDO currentOperation = lockedOperations.getLast();
        locked = new RechargeFrequencyLifecycle.LockedExecution(locked.queue(), locked.entry(), locked.request(), locked.order(),
                locked.bOrder(), currentOperation, locked.relatedBOrders(), locked.relatedOperations(), locked.waitingHeadId());
        LocalDateTime now = databaseClock.now();
        HuashengCheckpoint checkpoint = decryptCheckpoint(currentOperation, locked.bOrder());
        recordSupplierTakenSuccess(locked, checkpoint, fact, now);
        holdManual(locked, checkpoint, fact, FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT, false, now);
        log.warn("[华昇生命周期][跨单卡密冲突留证] bOrderId={}, operationNo={}, traceId={}",
                bOrderId, currentOperation.getOperationNo(), traceId);
    }

    /** Timer 继承初始来源，不使用本次后台traceId或taskId（设计 §5.3、日志规范 §5.3）。 */
    private void putOrigin(ObjectNode payload, FulfillmentOperationDO operation, BOrderDO bOrder) {
        var cp = decryptCheckpoint(operation, bOrder);
        payload.put("originTraceId", cp.progress().originTraceId());
    }

    private static void logAfterCommit(String message, Object... arguments) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { log.info(message, arguments); }
        });
    }

    /**
     * 准备看门狗收尾处理（首步准备超时）。
     * 尚无发送 marker (businessSendPossibleAt == null) 且仍为 PENDING 时，
     * 转本地准备人工 case，保留资金，不自行 PLACE 或宣称供应商 FAILED。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean handlePreparationWatchdog(String operationNo) {
        Objects.requireNonNull(operationNo, "operationNo is required");
        var locked = frequencyLifecycle.lockForResult(operationNo);
        if (locked == null) {
            return false;
        }
        FulfillmentOperationDO operation = locked.operation();
        if (operation == null || isTerminal(operation.getStatus())) {
            return false;
        }
        if (operation.getBusinessSendPossibleAt() == null
                && operation.getStatus() == SupplierOrderOperationStatus.PENDING.getCode()) {
            LocalDateTime now = databaseClock.now();
            holdManual(locked, null, null, FulfillmentExceptionReasonCodes.HUASHENG_PREPARATION_FAILED, false, now);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    log.warn("[华昇生命周期][准备看门狗超时] operationNo={}, reasonCode={}", operationNo,
                            FulfillmentExceptionReasonCodes.HUASHENG_PREPARATION_FAILED);
                }
            });
            return true;
        }
        return false;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
