package com.aifuli.module.supplychain.border.fulfillment.application.singlestep;

import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle.LockedExecution;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.*;
import com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepRuntimeCheckpoint.*;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.security.HmacFingerprintPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.aifuli.module.supplychain.supplieradapter.metadata.PublishedConnectorConfigurationResolver;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

import static com.aifuli.module.supplychain.border.fulfillment.domain.singlestep.SingleStepRuntimeCheckpoint.SCHEMA_VERSION;

/**
 * 单步骤唯一持久围栏：频控与外呼资格原子认领，结果/未知事实与下一Timer同事务。
 * 不在锁内准备秘密或发HTTP，不调用通用结果动账；终态观察/安全冲突保留预留进入Case，T04再接资金。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#47-t03本地执行与恢复接线2026-10-18。
 */
@Service
@RequiredArgsConstructor
@Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
public class SingleStepFulfillmentLifecycle {
    private final RechargeFrequencyLifecycle frequency;
    private final FulfillmentOperationMapper operations;
    private final BOrderMapper bOrders;
    private final FulfillmentExceptionCaseMapper cases;
    private final RawStatusObservationMapper observations;
    private final RechargeFrequencyEntryMapper frequencyEntries;
    private final SingleStepCheckpointCodec codec;
    private final PublishedConnectorConfigurationResolver connectors;
    private final SupplyChainDatabaseClock clock;
    private final PersistentTimer timer;
    private final ObjectMapper json;
    private final BusinessNoGenerator numbers;
    private final HmacFingerprintPort fingerprint;

    /** CREATE先竞争频控，锁后失败必须抛出回滚，不能留下孤立SEND_POSSIBLE。 */
    public SingleStepExecutionPermit claim(String operationNo, SingleStepAction action, long expectedGeneration,
                                         SingleStepCheckpoint.Binding preparedBinding, String originTraceId) {
        if (action == SingleStepAction.CREATE && !frequency.claimSingleStepSend(operationNo)) return null;
        var locked = frequency.lockForResult(operationNo);
        if (locked == null) return null;
        var op = locked.operation();
        var runtime = runtime(locked);
        boolean eligible = active(locked) && !terminal(op) && cases.selectActiveByOperationId(op.getId()) == null
                && op.getExecutionGeneration() == expectedGeneration
                && runtime.frozen().binding().equals(preparedBinding);
        if (!eligible) {
            if (action == SingleStepAction.CREATE) throw new IllegalStateException("单步骤创建冻结身份或活动资格变化");
            return null;
        }
        long anchor = System.nanoTime();
        var now = clock.now();
        var contract = runtime.frozen().recoveryContract();
        if (action == SingleStepAction.CREATE) {
            if (op.getBusinessSendPossibleAt() != null || expectedGeneration != 0 || runtime.progress().trustedExists()) {
                throw new IllegalStateException("单步骤创建已越过发送边界");
            }
            op.setBusinessSendPossibleAt(now);
            op.setRecoveryDeadlineAt(now.toLocalDate().plusDays(2).atStartOfDay());
            op.setSideEffectFree(false);
        } else {
            if (op.getBusinessSendPossibleAt() == null || op.getStatus() == SupplierOrderOperationStatus.EXECUTING.getCode()
                    || op.getNextRecoverAt() == null) return null;
            if (!now.isBefore(op.getRecoveryDeadlineAt())) {
                manual(locked, runtime, Fact.DEADLINE_REACHED, now); return null;
            }
            if (op.getRecoveryCount() >= contract.maxQueryClaims()) {
                manual(locked, runtime, Fact.BUDGET_EXHAUSTED, now); return null;
            }
            if (now.isBefore(op.getNextRecoverAt())) return null;
            op.setRecoveryCount(Math.incrementExact(op.getRecoveryCount()));
        }
        op.setExecutionGeneration(Math.incrementExact(expectedGeneration));
        op.setExecutionLeaseUntil(now.plusNanos(contract.executionLeaseMs() * 1_000_000L));
        op.setNextRecoverAt(null);
        op.setStatus(SupplierOrderOperationStatus.EXECUTING.getCode());
        var progress = runtime.progress();
        save(op, new SingleStepRuntimeCheckpoint(SCHEMA_VERSION, runtime.frozen(), new Progress(action,
                progress.appliedGeneration(), progress.trustedExists(), progress.consecutiveNotFound(), Fact.CLAIMED, now)), now);
        schedule(FulfillmentTimerTaskTypes.SINGLE_STEP_WATCHDOG, op, op.getExecutionLeaseUntil(), originTraceId);
        return new SingleStepExecutionPermit(runtime.frozen().binding(), action, op.getExecutionGeneration(),
                op.getExecutionLeaseUntil(), anchor + Duration.between(now, op.getExecutionLeaseUntil()).toNanos());
    }

    /** 发送前核当前代与原lease；时间只扣不加，不靠此检查声称取消远端已发送请求。 */
    public boolean canDispatch(SingleStepExecutionPermit permit) {
        var locked = frequency.lockForResult(permit.operationNo());
        if (locked == null) return false;
        var runtime = runtime(locked);
        return current(locked, runtime, permit) && active(locked)
                && clock.now().isBefore(permit.leaseUntil()) && permit.deadlineNanos() - System.nanoTime() > 0;
    }

    /** 网络未知由执行边界单独报告，不能用任意异常或outcome枚举宣称供应商终态。 */
    public String networkUnknown(SingleStepExecutionPermit permit, String originTraceId) {
        var locked = frequency.lockForResult(permit.operationNo());
        if (locked == null) return "STALE";
        var runtime = runtime(locked);
        if (!current(locked, runtime, permit)) return "STALE";
        return defer(locked, runtime, Fact.NETWORK_UNKNOWN, 0, runtime.progress().trustedExists(),
                clock.now(), null, originTraceId);
    }

    /** 原绑定验证先于留证；可信旧代只保留存在保护与观察，不改当前租约/计数/调度。 */
    public String apply(SingleStepExecutionPermit permit, OperationOutcome outcome, String originTraceId) {
        var locked = frequency.lockForResult(permit.operationNo());
        if (locked == null) return "STALE";
        var runtime = runtime(locked);
        var now = clock.now();
        if (!(outcome.protocolFact() instanceof SingleStepSupplierFact fact)
                || !matches(runtime.frozen(), permit, fact) || !outcome.assets().isEmpty()
                || fact.verification() != SingleStepSupplierFact.Verification.VERIFIED
                || fact.externalOrderNo() != null && locked.bOrder().getExternalOrderNo() != null
                && !fact.externalOrderNo().equals(locked.bOrder().getExternalOrderNo())) {
            manual(locked, runtime, Fact.SECURITY_CONFLICT, now); return "MANUAL";
        }
        persistObservation(locked, permit, fact, now, originTraceId);
        boolean exists = runtime.progress().trustedExists() || switch (fact.observation()) {
            case ACCEPTED, PROCESSING, EXISTS_UNKNOWN, SUCCESS, FAILURE -> true;
            default -> false;
        };
        if (exists && fact.externalOrderNo() != null && locked.bOrder().getExternalOrderNo() == null) {
            locked.bOrder().setExternalOrderNo(fact.externalOrderNo()); locked.bOrder().setUpdatedAt(now);
            if (bOrders.updateById(locked.bOrder()) != 1) throw new IllegalStateException("单步骤外部单号版本冲突");
        }
        if (fact.observation() == SingleStepSupplierFact.Observation.SUCCESS
                || fact.observation() == SingleStepSupplierFact.Observation.FAILURE) {
            var protectedRuntime = withExists(runtime, exists);
            // TODO: T04在受保护观察与原单锁下接资金收敛，当前不把终态观察等同资金已完成。
            // docs/superpowers/plans/2026-10-15-renyue-qnm-szcard-development-plan.md#t04-回调inbox可信结果与资金4～6人日be-aqa
            manual(locked, protectedRuntime, Fact.TERMINAL_OBSERVED, now); return "TERMINAL_OBSERVED";
        }
        if (!current(locked, runtime, permit)) {
            if (exists != runtime.progress().trustedExists()) save(locked.operation(), withExists(runtime, true), now);
            return "STALE";
        }
        var factKind = switch (fact.observation()) {
            case ACCEPTED -> Fact.ACCEPTED;
            case PROCESSING -> Fact.PROCESSING;
            case NOT_FOUND -> Fact.NOT_FOUND;
            default -> Fact.RESPONSE_UNKNOWN;
        };
        int count = factKind == Fact.NOT_FOUND && permit.action() == SingleStepAction.QUERY
                ? Math.min(runtime.progress().consecutiveNotFound() + 1, runtime.frozen().recoveryContract().notFoundConsecutiveThreshold()) : 0;
        return defer(locked, runtime, factKind, count, exists, now, fact.receivedAt(), originTraceId);
    }

    /** 当前代过期才产生一次未知；事实起点为本事务DB时间，不是lease到期或重启时间。 */
    public String watchdog(String operationNo, long expectedGeneration, String originTraceId) {
        var locked = frequency.lockForResult(operationNo);
        if (locked == null) return "STALE";
        var op = locked.operation(); var now = clock.now();
        if (!active(locked) || terminal(op) || op.getExecutionGeneration() != expectedGeneration
                || op.getExecutionLeaseUntil() == null || now.isBefore(op.getExecutionLeaseUntil())) return "STALE";
        SingleStepRuntimeCheckpoint runtime;
        try {
            runtime = runtime(locked);
        } catch (IllegalArgumentException | IllegalStateException invalidMaterial) {
            // 仅材料校验/认证失败在此终结；DataAccessException等数据库故障继续抛出并回滚。
            manual(locked, null, Fact.LOCAL_PREPARATION_FAILED, now);
            return "MANUAL";
        }
        if (runtime.progress().appliedGeneration() >= expectedGeneration) return "STALE";
        return defer(locked, runtime, runtime.progress().action() == SingleStepAction.CREATE
                ? Fact.CREATE_LEASE_EXPIRED : Fact.QUERY_LEASE_EXPIRED, 0, runtime.progress().trustedExists(), now, null, originTraceId);
    }

    /** 非网络执行故障同样受原permit围栏约束，迟到旧线程不能关闭当前代。 */
    public String executionFailed(SingleStepExecutionPermit permit) {
        var locked = frequency.lockForResult(permit.operationNo());
        if (locked == null) return "STALE";
        var runtime = runtime(locked);
        if (!current(locked, runtime, permit)) return "STALE";
        manual(locked, runtime, Fact.LOCAL_PREPARATION_FAILED, clock.now());
        return "MANUAL";
    }

    /** 材料异常只开Case；正常FIFO等待不占发送资格，也不被5分钟准备任务误关单。 */
    public String preparationFailed(String operationNo, long expectedGeneration, boolean watchdog) {
        var locked = frequency.lockForResult(operationNo);
        if (locked == null || terminal(locked.operation()) || locked.operation().getExecutionGeneration() != expectedGeneration) return "STALE";
        if (watchdog) {
            if (locked.operation().getBusinessSendPossibleAt() != null) return "STALE";
            if (clock.now().isBefore(locked.operation().getCreatedAt().plusMinutes(FulfillmentTimerTaskTypes.SINGLE_STEP_PREPARATION_WATCHDOG_MINUTES))) return "NOT_DUE";
            if (locked.queue() != null && locked.queue().getBlockedReason() == null) {
                if (locked.queue().getHolderEntryId() != null && !RechargeFrequencyLifecycle.isCurrentHolder(locked)) return "FREQUENCY_WAITING";
                var head = frequencyEntries.selectFirstWaiting(locked.queue().getId());
                if (locked.queue().getHolderEntryId() == null && head != null && !head.getId().equals(locked.entry().getId())) return "FREQUENCY_WAITING";
            }
        }
        // 坏密文本身也须可定位Case，不能因无法解密而无限重试；原聚合身份已经锁后验证。
        manual(locked, null, Fact.LOCAL_PREPARATION_FAILED, clock.now());
        return "MANUAL";
    }

    private String defer(LockedExecution locked, SingleStepRuntimeCheckpoint runtime, Fact fact, int count,
                         boolean exists, LocalDateTime now, LocalDateTime receivedAt, String originTraceId) {
        var op = locked.operation(); var p = runtime.progress(); var c = runtime.frozen().recoveryContract();
        int seconds = switch (fact) {
            case NOT_FOUND -> c.notFoundIntervalSeconds();
            case ACCEPTED, PROCESSING -> p.action() == SingleStepAction.CREATE ? c.firstQueryDelaySeconds() : c.processingIntervalSeconds();
            default -> p.action() == SingleStepAction.CREATE ? c.timeoutFirstQueryDelaySeconds() : c.errorBackoffSeconds();
        };
        var base = (fact == Fact.ACCEPTED || fact == Fact.PROCESSING) && p.action() == SingleStepAction.CREATE && receivedAt != null ? receivedAt : now;
        op.setNextRecoverAt(base.plusSeconds(seconds));
        op.setStatus(SupplierOrderOperationStatus.RESULT_UNKNOWN.getCode());
        op.setResultClass(OperationOutcomeClass.RESULT_UNKNOWN.name());
        var updated = new SingleStepRuntimeCheckpoint(SCHEMA_VERSION, runtime.frozen(),
                new Progress(p.action(), op.getExecutionGeneration(), exists, count, fact, now));
        if (!now.isBefore(op.getRecoveryDeadlineAt())) {
            manual(locked, updated, Fact.DEADLINE_REACHED, now); return "MANUAL";
        }
        if (op.getRecoveryCount() >= c.maxQueryClaims()) {
            manual(locked, updated, Fact.BUDGET_EXHAUSTED, now); return "MANUAL";
        }
        // TODO: 三次NOT_FOUND只冻结计数；T04补齐受保护历史/并发成功校验后再允许一次释放。
        // docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#45-连续查无单的平台风险关单主要计数规则已确认恢复合同未完成
        save(op, updated, now);
        var fireAt = op.getNextRecoverAt().isBefore(op.getRecoveryDeadlineAt()) ? op.getNextRecoverAt() : op.getRecoveryDeadlineAt();
        schedule(FulfillmentTimerTaskTypes.SINGLE_STEP_QUERY, op, fireAt, originTraceId);
        return fact.name();
    }

    private SingleStepRuntimeCheckpoint runtime(LockedExecution locked) {
        var op = locked.operation(); var bo = locked.bOrder();
        var metadata = connectors.singleStepMetadata(bo.getConnectorVersionId());
        if (metadata.singleStepRecoveryContract() == null) throw new IllegalStateException("非单步骤原单");
        var binding = new SingleStepCheckpoint.Binding(op.getOperationNo(), bo.getId(), metadata.adapterType(), AdapterStepCode.SEND,
                bo.getSupplierId(), bo.getConnectorVersionId(), bo.getOfferVersionId(), HexFormat.of().formatHex(op.getConfigDigest()));
        var runtime = codec.decryptRuntime(new SingleStepCheckpointCodec.Stored(op.getCheckpointSchemaVersion(), op.getCheckpointKeyVersion(), op.getCheckpointCiphertext()), binding);
        var business = runtime.frozen().business(); var reference = business.protectedTargetReference(); var request = locked.request();
        if (!FulfillmentOperationKind.FULFILL.name().equals(op.getOperationKind()) || !AdapterStepCode.SEND.name().equals(op.getAdapterStepCode())
                || !Objects.equals(op.getSequenceNo(), 1) || !Objects.equals(op.getIdempotencyKey(), FulfillmentIdempotencyKeys.operation(bo.getBOrderNo(), 1))
                || !business.billno().equals(bo.getBillno()) || !Objects.equals(business.quantity(), bo.getRequestedQuantity())
                || !Objects.equals(business.quantity(), request.getRequestedQuantity()) || !Objects.equals(business.quantity(), locked.order().getQuantity())
                || !Objects.equals(business.settlementUnitPriceMinorUnits(), bo.getSupplierSettlementUnitPrice())
                || !business.currency().equals(bo.getCurrency()) || reference.requestId() != request.getId()
                || !reference.requestNo().equals(request.getRequestNo()) || !Objects.equals(reference.targetSchemaVersion(), request.getTargetSchemaVersion())
                || !reference.targetKeyVersion().equals(request.getTargetKeyVersion())
                || !reference.targetDigest().equals(HexFormat.of().formatHex(request.getTargetDigest()))
                || runtime.progress().appliedGeneration() > op.getExecutionGeneration()) throw new IllegalStateException("单步骤锁后原单内容变化");
        return runtime;
    }

    private static boolean active(LockedExecution locked) {
        return Boolean.TRUE.equals(locked.bOrder().getActiveFlag()) && Objects.equals(locked.request().getActiveBOrderId(), locked.bOrder().getId())
                && locked.request().getStatus() == FulfillmentRequestStatus.FULFILLING.getCode()
                && RechargeFrequencyLifecycle.isCurrentHolder(locked)
                && locked.queue().getBlockedReason() == null
                && Objects.equals(locked.entry().getDispatchPhase(), RechargeFrequencyDispatchPhase.SEND_POSSIBLE.getCode());
    }
    private static boolean terminal(FulfillmentOperationDO op) {
        return op.getStatus() == SupplierOrderOperationStatus.MANUAL.getCode() || op.getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode()
                || op.getStatus() == SupplierOrderOperationStatus.FAILED.getCode();
    }
    private static boolean current(LockedExecution locked, SingleStepRuntimeCheckpoint runtime, SingleStepExecutionPermit permit) {
        var op = locked.operation();
        return active(locked) && !terminal(op) && op.getStatus() == SupplierOrderOperationStatus.EXECUTING.getCode()
                && runtime.frozen().binding().equals(permit.binding()) && op.getExecutionGeneration() == permit.generation()
                && runtime.progress().appliedGeneration() < permit.generation() && runtime.progress().action() == permit.action()
                && Objects.equals(op.getExecutionLeaseUntil(), permit.leaseUntil());
    }
    private static boolean matches(SingleStepCheckpoint frozen, SingleStepExecutionPermit permit, SingleStepSupplierFact fact) {
        return frozen.binding().equals(permit.binding()) && frozen.binding().equals(fact.binding())
                && permit.action() == fact.source() && frozen.business().billno().equals(fact.billno())
                && frozen.business().protectedTargetReference().targetDigest().equals(fact.targetDigest())
                && frozen.business().quantity() == fact.quantity();
    }
    private static SingleStepRuntimeCheckpoint withExists(SingleStepRuntimeCheckpoint runtime, boolean exists) {
        var p = runtime.progress();
        return new SingleStepRuntimeCheckpoint(SCHEMA_VERSION, runtime.frozen(), new Progress(p.action(), p.appliedGeneration(),
                exists, p.consecutiveNotFound(), p.lastFact(), p.factAt()));
    }
    private void manual(LockedExecution locked, SingleStepRuntimeCheckpoint runtime, Fact reason, LocalDateTime now) {
        var op = locked.operation();
        if (cases.selectActiveByOperationId(op.getId()) == null) {
            String ref = "SINGLE_STEP:" + reason + ":" + op.getOperationNo();
            cases.insert(FulfillmentExceptionCaseDO.builder().caseNo(numbers.next(BusinessNoPrefixes.CASE))
                    .orderId(locked.order().getId()).fulfillmentRequestId(locked.request().getId()).bOrderId(locked.bOrder().getId())
                    .operationId(op.getId()).reasonCode("SINGLE_STEP_" + reason).status(FulfillmentExceptionCaseStatus.OPEN.getCode())
                    .evidenceRef(ref).evidenceDigest(fingerprint.fingerprint("EXCEPTION_CASE", ref.getBytes(StandardCharsets.UTF_8)))
                    .activeCaseFlag(1).version(0L).createdAt(now).updatedAt(now).build());
        }
        // 已有业务终态只留Case，不翻转成MANUAL；当前切片不会产生资金终态。
        if (op.getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode() || op.getStatus() == SupplierOrderOperationStatus.FAILED.getCode()) return;
        op.setStatus(SupplierOrderOperationStatus.MANUAL.getCode()); op.setNextRecoverAt(null);
        if (runtime != null) {
            var p = runtime.progress();
            runtime = new SingleStepRuntimeCheckpoint(SCHEMA_VERSION, runtime.frozen(), new Progress(p.action(),
                    Math.max(p.appliedGeneration(), op.getExecutionGeneration()), p.trustedExists(), p.consecutiveNotFound(), reason, now));
        }
        save(op, runtime, now);
    }
    private void save(FulfillmentOperationDO op, SingleStepRuntimeCheckpoint runtime, LocalDateTime now) {
        if (runtime != null) {
            var stored = codec.encryptRuntime(runtime);
            op.setCheckpointSchemaVersion(stored.schemaVersion()); op.setCheckpointKeyVersion(stored.keyVersion()); op.setCheckpointCiphertext(stored.ciphertext());
        }
        long version = op.getVersion();
        int changed = operations.update(null, new LambdaUpdateWrapper<FulfillmentOperationDO>()
                .eq(FulfillmentOperationDO::getId, op.getId()).eq(FulfillmentOperationDO::getVersion, version)
                .set(FulfillmentOperationDO::getVersion, version + 1).set(FulfillmentOperationDO::getUpdatedAt, now)
                .set(FulfillmentOperationDO::getStatus, op.getStatus()).set(FulfillmentOperationDO::getResultClass, op.getResultClass())
                .set(FulfillmentOperationDO::getExecutionGeneration, op.getExecutionGeneration()).set(FulfillmentOperationDO::getExecutionLeaseUntil, op.getExecutionLeaseUntil())
                .set(FulfillmentOperationDO::getBusinessSendPossibleAt, op.getBusinessSendPossibleAt()).set(FulfillmentOperationDO::getSideEffectFree, op.getSideEffectFree())
                .set(FulfillmentOperationDO::getRecoveryCount, op.getRecoveryCount()).set(FulfillmentOperationDO::getRecoveryDeadlineAt, op.getRecoveryDeadlineAt())
                .set(FulfillmentOperationDO::getNextRecoverAt, op.getNextRecoverAt()).set(FulfillmentOperationDO::getCheckpointSchemaVersion, op.getCheckpointSchemaVersion())
                .set(FulfillmentOperationDO::getCheckpointKeyVersion, op.getCheckpointKeyVersion()).set(FulfillmentOperationDO::getCheckpointCiphertext, op.getCheckpointCiphertext()));
        if (changed != 1) throw new IllegalStateException("单步骤操作版本冲突");
        op.setVersion(version + 1);
    }
    private void schedule(String type, FulfillmentOperationDO op, LocalDateTime at, String originTraceId) {
        var payload = json.createObjectNode().put("operationNo", op.getOperationNo()).put("expectedGeneration", op.getExecutionGeneration());
        if (originTraceId != null && !originTraceId.isBlank()) payload.put("originTraceId", originTraceId);
        timer.scheduleOnce(type, type + ":" + op.getOperationNo() + ":" + op.getExecutionGeneration(), payload, clock.toInstant(at));
    }
    private void persistObservation(LockedExecution locked, SingleStepExecutionPermit permit, SingleStepSupplierFact fact, LocalDateTime now, String traceId) {
        observations.insertIdempotent(RawStatusObservationDO.builder().observationNo(numbers.next(BusinessNoPrefixes.OBS))
                .observationKey(permit.operationNo() + ":" + permit.generation() + ":" + HexFormat.of().formatHex(fact.payloadDigest()))
                .sourceType(fact.source() == SingleStepAction.CREATE ? "CREATE_RESPONSE" : "QUERY_RESPONSE")
                .operationId(locked.operation().getId()).bOrderId(locked.bOrder().getId()).connectorVersionId(locked.bOrder().getConnectorVersionId())
                .externalOrderNo(fact.externalOrderNo()).externalStatus(fact.observation().name()).externalSubStatus("")
                .mappingDefinitionId(0L).mappedResultClass(fact.observation().name()).payloadDigest(fact.payloadDigest())
                .observedAt(fact.receivedAt()).receivedAt(now).traceId(traceId == null ? "" : traceId).build());
    }
}
