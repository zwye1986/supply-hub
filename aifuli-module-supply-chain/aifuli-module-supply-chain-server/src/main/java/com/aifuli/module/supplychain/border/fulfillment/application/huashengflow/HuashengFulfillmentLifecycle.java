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
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
            currentOperation.setStatus(SupplierOrderOperationStatus.MANUAL.getCode());
            currentOperation.setResultClass(OperationOutcomeClass.MANUAL_REQUIRED.name());
            currentOperation.setCompletedAt(now);
            currentOperation.setUpdatedAt(now);
            operationMapper.updateById(currentOperation);
            return HuashengApplyResult.disqualified("Checkpoint decryption failed: " + e.getMessage());
        }

        if (!Objects.equals(checkpoint.placeParameters().outOrderSn(), currentBOrder.getBillno())) {
            log.warn("[华昇生命周期][单号不符] operationNo={}, billno={}, checkpointOutOrderSn={}",
                    currentOperation.getOperationNo(), currentBOrder.getBillno(), checkpoint.placeParameters().outOrderSn());
            currentOperation.setStatus(SupplierOrderOperationStatus.MANUAL.getCode());
            currentOperation.setResultClass(OperationOutcomeClass.MANUAL_REQUIRED.name());
            currentOperation.setCompletedAt(now);
            currentOperation.setUpdatedAt(now);
            operationMapper.updateById(currentOperation);
            return HuashengApplyResult.disqualified("outOrderSn does not match billno");
        }

        if (!(outcome.protocolFact() instanceof HuashengStepFact fact)) {
            throw new IllegalArgumentException("Huasheng apply requires HuashengStepFact");
        }

        long expectedAmount = currentBOrder.getExpectedSupplierSettlementAmount() != null
                ? currentBOrder.getExpectedSupplierSettlementAmount() : 0L;
        HuashengFlowDecision decision = HuashengFlowPolicy.decide(checkpoint, fact, expectedAmount, now);

        if (checkpoint.pendingAssetPayload() != null && fact.protectedAssetPayload() != null
                && !Arrays.equals(checkpoint.pendingAssetPayload(), fact.protectedAssetPayload())) {
            decision = HuashengFlowDecision.MANUAL;
        }

        log.info("[华昇生命周期][事实决策] operationNo={}, endpoint={}, factStatus={}, decision={}",
                currentOperation.getOperationNo(), fact.endpoint(), fact.status(), decision);

        return switch (decision) {
            case NEXT_CONFIRM, NEXT_TAKE -> advanceToNextStep(currentOperation, currentBOrder, checkpoint, fact, outcome, decision, traceId, now);
            case WAIT_QUERY -> scheduleQueryRecovery(currentOperation, checkpoint, fact, decision, traceId, now);
            case FINAL_SUCCESS -> {
                // TAKE 本身无金额；只在锁后使用已核历史金额，绝不从冻结成本兜底（设计 §6.3）。
                Long amount = fact.endpoint() == HuashengStepFact.Endpoint.TAKE
                        ? checkpoint.observation().totalPriceMinorUnits() : fact.totalPriceMinorUnits();
                supplierOrderLifecycle.commitHuashengSuccess(permit, terminalOutcome(outcome, OperationOutcomeClass.SUCCEEDED, amount), traceId);
                yield new HuashengApplyResult(decision, null, true, "Final success committed");
            }
            case FINAL_REJECTION -> {
                supplierOrderLifecycle.releaseHuashengRejection(permit, terminalOutcome(outcome, OperationOutcomeClass.REJECTED, outcome.reportedSettlementAmount()), traceId);
                yield new HuashengApplyResult(decision, null, true, "Final rejection released");
            }
            case MANUAL -> {
                // 用相同事实去除自动配额再判断，仅“因配额阻断”的人工单可被可信回调恢复；
                // 同时到限的金额／资产冲突仍是冲突，不能仅按状态或queryCount误归类（设计 §5.3）。
                var unlimited = new HuashengCheckpoint(checkpoint.binding(), checkpoint.placeParameters(), checkpoint.orderSn(),
                        checkpoint.observation(), new HuashengCheckpoint.Progress(0, 0, 0, null, null,
                        checkpoint.progress().originTraceId()), checkpoint.pendingAssetPayload());
                boolean recoveryLimit = HuashengFlowPolicy.decide(unlimited, fact, expectedAmount, now) != HuashengFlowDecision.MANUAL
                        && (checkpoint.pendingAssetPayload() == null || fact.protectedAssetPayload() == null
                        || Arrays.equals(checkpoint.pendingAssetPayload(), fact.protectedAssetPayload()));
                holdManual(locked, checkpoint, fact, recoveryLimit ? FulfillmentExceptionReasonCodes.HUASHENG_RECOVERY_LIMIT
                        : FulfillmentExceptionReasonCodes.HUASHENG_FACT_CONFLICT, false, now);
                yield new HuashengApplyResult(decision, null, true, "Transitioned to manual");
            }
        };
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
     * 6. FINAL_SUCCESS 时核验完整资产与金额，提交资金；FINAL_REJECTION 且无资产时释放资金；
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
        if (succeeded || failed) {
            boolean same = Objects.equals(fact.outOrderSn(), currentBOrder.getBillno())
                    && Objects.equals(fact.orderSn(), currentBOrder.getExternalOrderNo())
                    && (checkpoint.observation() == null || isPriceRawEquivalent(checkpoint.observation().totalPriceRaw(), fact.totalPriceRaw()))
                    && (succeeded && decision == HuashengFlowDecision.FINAL_SUCCESS
                        && supplierOrderLifecycle.matchesHuashengDeliveredAsset(currentOperation.getOperationNo(), assets)
                        || failed && decision == HuashengFlowDecision.FINAL_REJECTION);
            if (same) return new HuashengApplyResult(succeeded ? HuashengFlowDecision.FINAL_SUCCESS
                    : HuashengFlowDecision.FINAL_REJECTION, null, false, "Already terminated with identical fact");
            holdManual(locked, checkpoint, fact, FulfillmentExceptionReasonCodes.SUPPLIER_TERMINAL_CONTRADICTION, true, now);
            return new HuashengApplyResult(HuashengFlowDecision.MANUAL, null, true, "Terminal conflict registered");
        }
        if (checkpoint.pendingAssetPayload() != null && fact.protectedAssetPayload() != null
                && !Arrays.equals(checkpoint.pendingAssetPayload(), fact.protectedAssetPayload())) {
            // 首期仅一个证据槽；不同卡密不能覆盖，也不能绕过证据直接交付。
            throw new HuashengCallbackConflictException("Existing protected evidence differs");
        }
        var activeCase = exceptionCaseMapper.selectActiveByOperationId(currentOperation.getId());
        if (activeCase != null && !FulfillmentExceptionReasonCodes.HUASHENG_RECOVERY_LIMIT.equals(activeCase.getReasonCode())) {
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
                    fact.protectedAssetPayload() != null ? fact.protectedAssetPayload() : checkpoint.pendingAssetPayload()
            );
            var terminalStored = checkpointCodec.encrypt(updatedCheckpoint);
            currentOperation.setCheckpointSchemaVersion(terminalStored.schemaVersion());
            currentOperation.setCheckpointKeyVersion(terminalStored.keyVersion());
            currentOperation.setCheckpointCiphertext(terminalStored.ciphertext());
        }
        currentOperation.setUpdatedAt(now);
        if (operationMapper.updateById(currentOperation) != 1) throw new IllegalStateException("Callback generation CAS failed");

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
            case NEXT_CONFIRM, NEXT_TAKE -> advanceToNextStep(currentOperation, currentBOrder, checkpoint, fact, outcome, decision, traceId, now);
            case WAIT_QUERY -> scheduleQueryRecovery(currentOperation, checkpoint, fact, decision, traceId, now);
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
                        checkpoint.observation(), checkpoint.progress(), previous != null ? previous : incoming);
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
            locked.bOrder().setFulfillmentStatus(BOrderFulfillmentStatus.MANUAL.getCode());
            locked.bOrder().setUpdatedAt(now);
            if (bOrderMapper.updateById(locked.bOrder()) != 1) throw new IllegalStateException("Manual BOrder CAS failed");
            locked.request().setStatus(FulfillmentRequestStatus.MANUAL.getCode()); locked.request().setUpdatedAt(now);
            if (requestMapper.updateById(locked.request()) != 1) throw new IllegalStateException("Manual Request CAS failed");
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

        HuashengCheckpoint nextCheckpoint = new HuashengCheckpoint(
                nextBinding, checkpoint.placeParameters(), orderSn, observation, nextProgress, checkpoint.pendingAssetPayload());

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

    private HuashengApplyResult scheduleQueryRecovery(
            FulfillmentOperationDO currentOperation,
            HuashengCheckpoint checkpoint,
            HuashengStepFact fact,
            HuashengFlowDecision decision,
            String traceId,
            LocalDateTime now
    ) {
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
        HuashengCheckpoint.Observation observation = toObservation(checkpoint, fact);
        String orderSn = fact.orderSn() != null ? fact.orderSn() : checkpoint.orderSn();

        HuashengCheckpoint nextCheckpoint = new HuashengCheckpoint(
                checkpoint.binding(), checkpoint.placeParameters(), orderSn, observation, nextProgress,
                fact.protectedAssetPayload() != null ? fact.protectedAssetPayload() : checkpoint.pendingAssetPayload());
        HuashengCheckpointCodec.Stored stored = checkpointCodec.encrypt(nextCheckpoint);

        currentOperation.setCheckpointSchemaVersion(stored.schemaVersion());
        currentOperation.setCheckpointKeyVersion(stored.keyVersion());
        currentOperation.setCheckpointCiphertext(stored.ciphertext());
        currentOperation.setRecoveryDeadlineAt(recoveryDeadlineAt);
        currentOperation.setStatus(SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode());
        currentOperation.setResultClass(OperationOutcomeClass.RESULT_UNKNOWN.name());
        LocalDateTime nextRecoverAt = now.plusSeconds(HuashengFlowPolicy.QUERY_INTERVAL_SECONDS);
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

        logAfterCommit("[华昇生命周期][安排查单恢复] operationNo={}, queryCount={}, nextRecoverAt={}",
                currentOperation.getOperationNo(), queryCount, nextRecoverAt);

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
                checkpoint.observation(), updatedProgress, checkpoint.pendingAssetPayload()));
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
