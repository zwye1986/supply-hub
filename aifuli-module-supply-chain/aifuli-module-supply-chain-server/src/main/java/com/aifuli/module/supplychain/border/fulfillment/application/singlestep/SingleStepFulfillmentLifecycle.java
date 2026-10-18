package com.aifuli.module.supplychain.border.fulfillment.application.singlestep;

import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle;
import com.aifuli.module.supplychain.border.fulfillment.application.RechargeFrequencyLifecycle.LockedExecution;
import com.aifuli.module.supplychain.border.fulfillment.application.SupplierOrderLifecycle;
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
import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
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
 * 不在锁内准备秘密或发HTTP；成功保护先独立提交，再委派受控订单资金事务，未知保留预留。
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
    private final SupplierOrderLifecycle supplierOrders;
    private final OutboxEventRepository outbox;
    private final SingleStepCallbackInbox callbackInbox;

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
        save(op, runtime.withProgress(new Progress(action,
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

    /**
     * 资金事务前独立提交成功保护；不持外层聚合锁调用，不随之后的订单/资金回滚。
     * 首份成功完整事实保持不变，旧代可信成功仍保护原单；不自动翻转已失败订单。
     * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#76-t04公共终态与资金切片2026-10-18。
     */
    public void observeSuccess(SingleStepExecutionPermit permit, OperationOutcome outcome, String traceId) {
        if (!(outcome.protocolFact() instanceof SingleStepSupplierFact fact)
                || fact.observation() != SingleStepSupplierFact.Observation.SUCCESS) return;
        var locked = frequency.lockForResult(permit.operationNo());
        if (locked == null) return;
        var runtime = runtime(locked);
        if (!validFact(locked, runtime, permit, outcome)) return;
        var now = clock.now();
        persistObservation(locked, permit, fact, now, traceId);
        if (runtime.successEvidence() == null) {
            var protectedRuntime = withExists(runtime, true);
            save(locked.operation(), new SingleStepRuntimeCheckpoint(SCHEMA_VERSION, runtime.frozen(),
                    protectedRuntime.progress(), new ResultEvidence(permit.generation(), fact), runtime.terminalEvidence()), now);
        }
    }

    /** 回调独立保护成功；generation仅作原单留证，不是QUERY资格。设计同详细设计§7.7。 */
    public void observeCallbackSuccess(SingleStepSupplierFact fact, String traceId) {
        if (fact.observation() != SingleStepSupplierFact.Observation.SUCCESS) return;
        var locked = frequency.lockForResult(fact.binding().operationNo());
        if (locked == null) return;
        var runtime = runtime(locked);
        if (!validCallback(locked, runtime, fact)) return;
        var now = clock.now();
        persistCallbackObservation(locked, fact, now, traceId);
        if (runtime.successEvidence() == null) {
            save(locked.operation(), new SingleStepRuntimeCheckpoint(SCHEMA_VERSION, runtime.frozen(),
                    withExists(runtime, true).progress(), new ResultEvidence(locked.operation().getExecutionGeneration(), fact),
                    runtime.terminalEvidence()), now);
        }
    }

    /**
     * 先Inbox后聚合锁，消费标记与订单/资金/终态Outbox同事务；不补造执行permit。
     * 非终态仅留证及永久存在保护，既有QUERY租约/预算/到期点均不变。设计同详细设计§7.7。
     */
    public void applyCallback(SingleStepCallbackInbox.Prepared prepared, String traceId) {
        var now = clock.now();
        var claim = callbackInbox.claim(prepared, traceId, now);
        if (claim.completed()) return;
        var fact = prepared.fact();
        var locked = frequency.lockForResult(fact.binding().operationNo());
        if (locked == null) throw new IllegalStateException("单步骤回调原单不存在");
        var runtime = runtime(locked);
        persistCallbackObservation(locked, fact, now, traceId);
        var protectedSuccess = runtime.successEvidence();
        boolean changedSuccess = fact.observation() == SingleStepSupplierFact.Observation.SUCCESS && protectedSuccess != null
                && protectedSuccess.fact().source() == SingleStepSupplierFact.Source.CALLBACK
                && !java.security.MessageDigest.isEqual(protectedSuccess.fact().payloadDigest(), fact.payloadDigest());
        if (claim.conflict() || !validCallback(locked, runtime, fact) || changedSuccess) {
            manual(locked, runtime, Fact.SECURITY_CONFLICT, now);
            callbackInbox.conflict(claim, now);
            return;
        }
        var op = locked.operation();
        if (op.getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode()
                || op.getStatus() == SupplierOrderOperationStatus.FAILED.getCode()) {
            boolean contrary = fact.observation() == SingleStepSupplierFact.Observation.SUCCESS
                    && op.getStatus() == SupplierOrderOperationStatus.FAILED.getCode()
                    || fact.observation() == SingleStepSupplierFact.Observation.FAILURE
                    && op.getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode();
            if (contrary) manual(locked, runtime, Fact.TERMINAL_CONFLICT, now);
        } else {
            boolean exists = runtime.progress().trustedExists() || switch (fact.observation()) {
                case ACCEPTED, PROCESSING, EXISTS_UNKNOWN, SUCCESS, FAILURE -> true;
                default -> false;
            };
            if (exists && fact.externalOrderNo() != null && locked.bOrder().getExternalOrderNo() == null) {
                locked.bOrder().setExternalOrderNo(fact.externalOrderNo()); locked.bOrder().setUpdatedAt(now);
                if (bOrders.updateById(locked.bOrder()) != 1) throw new IllegalStateException("单步骤回调外部单号版本冲突");
            }
            if (fact.observation() == SingleStepSupplierFact.Observation.SUCCESS) {
                if (protectedSuccess == null) manual(locked, withExists(runtime, true), Fact.TERMINAL_OBSERVED, now);
                else settle(locked, runtime, protectedSuccess, false, now, traceId);
            } else if (fact.observation() == SingleStepSupplierFact.Observation.FAILURE) {
                if (protectedSuccess != null) manual(locked, runtime, Fact.TERMINAL_CONFLICT, now);
                else if (!fact.zeroDeliveryConfirmed()) manual(locked, withExists(runtime, true), Fact.UNCONFIRMED_FAILURE, now);
                else settle(locked, withExists(runtime, true), new ResultEvidence(op.getExecutionGeneration(), fact), false, now, traceId);
            } else if (exists != runtime.progress().trustedExists()) {
                save(op, withExists(runtime, true), now);
            }
        }
        callbackInbox.succeed(claim, now);
    }

    /** 原绑定验证先于留证；可信旧代不改发送资格，终态资金须经过独立成功保护及共同检查。 */
    public String apply(SingleStepExecutionPermit permit, OperationOutcome outcome, String originTraceId) {
        var locked = frequency.lockForResult(permit.operationNo());
        if (locked == null) return "STALE";
        var runtime = runtime(locked);
        var now = clock.now();
        if (!validFact(locked, runtime, permit, outcome)) {
            manual(locked, runtime, Fact.SECURITY_CONFLICT, now); return "MANUAL";
        }
        var fact = (SingleStepSupplierFact) outcome.protocolFact();
        persistObservation(locked, permit, fact, now, originTraceId);
        if (locked.operation().getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode()
                || locked.operation().getStatus() == SupplierOrderOperationStatus.FAILED.getCode()) {
            boolean contrary = fact.observation() == SingleStepSupplierFact.Observation.SUCCESS
                    && locked.operation().getStatus() == SupplierOrderOperationStatus.FAILED.getCode()
                    || fact.observation() == SingleStepSupplierFact.Observation.FAILURE
                    && locked.operation().getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode();
            if (contrary) manual(locked, runtime, Fact.TERMINAL_CONFLICT, now);
            return "ALREADY_COMPLETED";
        }
        boolean exists = runtime.progress().trustedExists() || switch (fact.observation()) {
            case ACCEPTED, PROCESSING, EXISTS_UNKNOWN, SUCCESS, FAILURE -> true;
            default -> false;
        };
        if (exists && fact.externalOrderNo() != null && locked.bOrder().getExternalOrderNo() == null) {
            locked.bOrder().setExternalOrderNo(fact.externalOrderNo()); locked.bOrder().setUpdatedAt(now);
            if (bOrders.updateById(locked.bOrder()) != 1) throw new IllegalStateException("单步骤外部单号版本冲突");
        }
        if (fact.observation() == SingleStepSupplierFact.Observation.SUCCESS) {
            if (runtime.successEvidence() == null) {
                manual(locked, withExists(runtime, true), Fact.TERMINAL_OBSERVED, now); return "TERMINAL_OBSERVED";
            }
            return settle(locked, runtime, runtime.successEvidence(), false, now, originTraceId);
        }
        if (runtime.successEvidence() != null && fact.observation() == SingleStepSupplierFact.Observation.FAILURE) {
            manual(locked, runtime, Fact.TERMINAL_CONFLICT, now); return "MANUAL";
        }
        if (fact.observation() == SingleStepSupplierFact.Observation.FAILURE) {
            if (!current(locked, runtime, permit) || !fact.zeroDeliveryConfirmed()) {
                manual(locked, withExists(runtime, true), Fact.UNCONFIRMED_FAILURE, now); return "MANUAL";
            }
            return settle(locked, withExists(runtime, true), new ResultEvidence(permit.generation(), fact), false, now, originTraceId);
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
        if (factKind == Fact.NOT_FOUND && permit.action() == SingleStepAction.QUERY
                && count >= runtime.frozen().recoveryContract().notFoundConsecutiveThreshold()
                && !exists && runtime.successEvidence() == null && now.isBefore(locked.operation().getRecoveryDeadlineAt())) {
            // 最后合法QUERY可到1000次；截止优先，但不能被预算判断提前丢弃第三份有效事实。
            var closed = runtime.withProgress(new Progress(permit.action(), permit.generation(), false, count, Fact.NOT_FOUND_EXHAUSTED, now));
            return settle(locked, closed, new ResultEvidence(permit.generation(), fact), true, now, originTraceId);
        }
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
        if (runtime.successEvidence() != null) return settle(locked, runtime, runtime.successEvidence(), false, now, originTraceId);
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
        var updated = runtime.withProgress(new Progress(p.action(), op.getExecutionGeneration(), exists, count, fact, now));
        if (!now.isBefore(op.getRecoveryDeadlineAt())) {
            manual(locked, updated, Fact.DEADLINE_REACHED, now); return "MANUAL";
        }
        if (op.getRecoveryCount() >= c.maxQueryClaims()) {
            manual(locked, updated, Fact.BUDGET_EXHAUSTED, now); return "MANUAL";
        }
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
                && permit.action().name().equals(fact.source().name()) && frozen.business().billno().equals(fact.billno())
                && frozen.business().protectedTargetReference().targetDigest().equals(fact.targetDigest())
                && frozen.business().quantity() == fact.quantity();
    }
    private static SingleStepRuntimeCheckpoint withExists(SingleStepRuntimeCheckpoint runtime, boolean exists) {
        var p = runtime.progress();
        return runtime.withProgress(new Progress(p.action(), p.appliedGeneration(),
                exists, p.consecutiveNotFound(), p.lastFact(), p.factAt()));
    }

    /** 锁后再核事实与第一份成功的双号，不放宽MANUAL之外的资金/活动身份约束。设计同§7.7。 */
    private static boolean validCallback(LockedExecution locked, SingleStepRuntimeCheckpoint runtime, SingleStepSupplierFact fact) {
        var frozen = runtime.frozen();
        return fact.source() == SingleStepSupplierFact.Source.CALLBACK && frozen.binding().equals(fact.binding())
                && frozen.business().billno().equals(fact.billno()) && frozen.business().quantity() == fact.quantity()
                && frozen.business().protectedTargetReference().targetDigest().equals(fact.targetDigest())
                && locked.operation().getBusinessSendPossibleAt() != null && locked.operation().getExecutionGeneration() > 0
                && fact.verification() == SingleStepSupplierFact.Verification.VERIFIED
                && fact.observation() != SingleStepSupplierFact.Observation.NOT_FOUND
                && (fact.reportedSettlementAmount() == null || fact.reportedSettlementAmount() >= 0)
                && (!fact.zeroDeliveryConfirmed() || fact.observation() == SingleStepSupplierFact.Observation.FAILURE)
                && (fact.externalOrderNo() == null || locked.bOrder().getExternalOrderNo() == null
                    || fact.externalOrderNo().equals(locked.bOrder().getExternalOrderNo()))
                && (fact.externalOrderNo() == null || runtime.successEvidence() == null
                    || runtime.successEvidence().fact().externalOrderNo() == null
                    || fact.externalOrderNo().equals(runtime.successEvidence().fact().externalOrderNo()));
    }

    private static boolean validFact(LockedExecution locked, SingleStepRuntimeCheckpoint runtime,
                                     SingleStepExecutionPermit permit, OperationOutcome outcome) {
        return outcome.protocolFact() instanceof SingleStepSupplierFact fact && matches(runtime.frozen(), permit, fact)
                && locked.operation().getBusinessSendPossibleAt() != null && permit.generation() > 0
                && permit.generation() <= locked.operation().getExecutionGeneration()
                && (permit.action() != SingleStepAction.CREATE || permit.generation() == 1)
                && fact.verification() == SingleStepSupplierFact.Verification.VERIFIED && outcome.assets().isEmpty()
                && (fact.reportedSettlementAmount() == null || fact.reportedSettlementAmount() >= 0)
                && Objects.equals(outcome.reportedSettlementAmount(), fact.reportedSettlementAmount())
                && (!fact.zeroDeliveryConfirmed() || fact.observation() == SingleStepSupplierFact.Observation.FAILURE)
                && (outcome.externalOrderNo() == null || Objects.equals(outcome.externalOrderNo(), fact.externalOrderNo()))
                && (fact.externalOrderNo() == null || locked.bOrder().getExternalOrderNo() == null
                    || fact.externalOrderNo().equals(locked.bOrder().getExternalOrderNo()))
                && (fact.externalOrderNo() == null || runtime.successEvidence() == null
                    || runtime.successEvidence().fact().externalOrderNo() == null
                    || fact.externalOrderNo().equals(runtime.successEvidence().fact().externalOrderNo()));
    }

    /** 已保护的终态与本地资金/事件同事务；失败保留原证据，绝不兜底退款。设计同§7.6。 */
    private String settle(LockedExecution locked, SingleStepRuntimeCheckpoint runtime, ResultEvidence evidence,
                          boolean notFoundClosure, LocalDateTime now, String traceId) {
        if (!active(locked) || runtime.progress().lastFact() == Fact.SECURITY_CONFLICT) {
            manual(locked, runtime, Fact.TERMINAL_CONFLICT, now); return "MANUAL";
        }
        var completed = new SingleStepRuntimeCheckpoint(SCHEMA_VERSION, runtime.frozen(), runtime.progress(), runtime.successEvidence(), evidence);
        var op = locked.operation();
        op.setExecutionLeaseUntil(null); op.setNextRecoverAt(null);
        save(op, completed, now);
        if (!supplierOrders.completeSingleStep(locked, completed, notFoundClosure, traceId)) {
            manual(locked, completed, Fact.FUNDS_CONFLICT, now); return "MANUAL";
        }
        outbox.appendLocal(FulfillmentOutboxCodes.AGGREGATE_SUPPLY_CHAIN_ORDER, locked.order().getOrderNo(), locked.order().getDetailVersion(),
                FulfillmentOutboxCodes.EVENT_SUPPLY_CHAIN_ORDER_CHANGED, FulfillmentOutboxCodes.TARGET_SUPPLY_CHAIN_ORDER_EVENT,
                java.util.Map.of("orderNo", locked.order().getOrderNo(), "requestNo", locked.request().getRequestNo(),
                        "orderStatus", com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus.fromCode(locked.order().getOrderStatus()).name()),
                traceId, null, op.getOperationNo(), now);
        return notFoundClosure ? Fact.NOT_FOUND_EXHAUSTED.name() : evidence.fact().observation().name();
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
        // 已有业务终态只留Case，不翻转成MANUAL。
        if (op.getStatus() == SupplierOrderOperationStatus.SUCCEEDED.getCode() || op.getStatus() == SupplierOrderOperationStatus.FAILED.getCode()) return;
        op.setStatus(SupplierOrderOperationStatus.MANUAL.getCode()); op.setNextRecoverAt(null);
        if (runtime != null) {
            var p = runtime.progress();
            // 后续重复事实不能抹掉安全冲突，再借可信成功绕过人工核实。设计见§7.6。
            if (p.lastFact() == Fact.SECURITY_CONFLICT) reason = Fact.SECURITY_CONFLICT;
            runtime = runtime.withProgress(new Progress(p.action(),
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
        persistObservation(locked, permit.operationNo() + ":" + permit.generation() + ":" + HexFormat.of().formatHex(fact.payloadDigest()), fact, now, traceId);
    }
    private void persistCallbackObservation(LockedExecution locked, SingleStepSupplierFact fact, LocalDateTime now, String traceId) {
        persistObservation(locked, fact.binding().operationNo() + ":CALLBACK:" + HexFormat.of().formatHex(fact.payloadDigest()), fact, now, traceId);
    }
    private void persistObservation(LockedExecution locked, String key, SingleStepSupplierFact fact, LocalDateTime now, String traceId) {
        observations.insertIdempotent(RawStatusObservationDO.builder().observationNo(numbers.next(BusinessNoPrefixes.OBS))
                .observationKey(key)
                .sourceType(fact.source().observationSource())
                .operationId(locked.operation().getId()).bOrderId(locked.bOrder().getId()).connectorVersionId(locked.bOrder().getConnectorVersionId())
                .externalOrderNo(fact.externalOrderNo()).externalStatus(fact.observation().name()).externalSubStatus("")
                .mappingDefinitionId(0L).mappedResultClass(fact.observation().name()).payloadDigest(fact.payloadDigest())
                .observedAt(fact.receivedAt()).receivedAt(now).traceId(traceId == null ? "" : traceId).build());
    }
}
