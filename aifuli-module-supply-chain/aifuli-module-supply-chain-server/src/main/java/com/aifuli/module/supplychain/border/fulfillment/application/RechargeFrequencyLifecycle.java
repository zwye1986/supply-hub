package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyQueueDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyEntryDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyQueueMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyEntryMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.*;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.*;
import com.aifuli.module.supplychain.border.order.dal.dataobject.SupplyChainOrderDO;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplierOrderOperationStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.Objects;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyEntryStatus;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.application.RechargeFrequencyProductResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * 与首次建单和资金预占同事务入队。锁控制行分配FIFO序号，存储故障必须向外传播使整个受理回滚。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#41-入队}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RechargeFrequencyLifecycle {
    private static final Pattern IDENTITY_KEY = Pattern.compile("for key ['`](?:[^'`]*\\.)?uk_sc_recharge_frequency_queue_identity['`]");
    private final RechargeFrequencyQueueMapper queueMapper;
    private final RechargeFrequencyEntryMapper entryMapper;
    private final RechargeFrequencyIdentityService identityService;
    private final RechargeFrequencyProductResolver productResolver;
    private final SupplyChainDatabaseClock databaseClock;
    private final RechargeFrequencyExecutionMapper executionMapper;
    private final FulfillmentOperationMapper operationMapper;
    private final BOrderMapper bOrderMapper;
    private final SupplyChainOrderMapper orderMapper;

    /**
     * 当前事务独占的新订单先入队、后预占资金；同单重放复用冻结队列，不能重新解析可变SPU。
     * targetAccountType来自已校验实际target，不能从buyerUserId推断。
     * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#42-认领}。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void enqueue(EnqueueCommand command) {
        log.debug("[直充限频] 入队入口 orderNo={}, platformSkuId={}, buyerId={}, projectId={}",
                command.orderNo(), command.platformSkuId(), command.buyerId(), command.projectId());
        if (entryMapper.selectByOrderId(command.orderId()) != null) { return; }
        var product = productResolver.resolve(command.platformSkuId());
        if (!product.directRecharge() || !FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE.equals(command.targetAccountType())) { return; }
        var identity = identityService.identify(command.actualMobile());
        var now = databaseClock.now();
        var queue = queueMapper.selectByIdentity(identity.mobileDigest(), product.platformSpuId());
        if (queue == null) {
            queue = RechargeFrequencyQueueDO.builder().mobileDigest(identity.mobileDigest()).mobileMasked(identity.mobileMasked())
                    .platformSpuId(product.platformSpuId()).nextEnqueueSequence(1L).occupancyGeneration(0L)
                    .wakeGeneration(0L).occupiedAlertActive(false).backlogAlertActive(false).version(0L)
                    .createdAt(now).updatedAt(now).build();
            try { queueMapper.insert(queue); }
            catch (DuplicateKeyException exception) {
                if (isIdentityCompetition(exception)) { throw new QueueIdentityCreationConflictException(exception); }
                throw exception;
            }
        }
        queue = queueMapper.selectByIdForUpdate(queue.getId());
        if (queue == null) { throw new IllegalStateException("直充限频控制行丢失"); }
        long sequence = queue.getNextEnqueueSequence();
        entryMapper.insert(RechargeFrequencyEntryDO.builder().queueId(queue.getId()).orderId(command.orderId())
                .orderNo(command.orderNo()).fulfillmentRequestId(command.fulfillmentRequestId()).enqueueSequence(sequence)
                .status(RechargeFrequencyEntryStatus.WAITING.getCode()).activeOperationNo(command.activeOperationNo())
                .occupancyGeneration(0L).buyerId(command.buyerId()).projectId(command.projectId())
                .enqueuedAt(now).createdAt(now).updatedAt(now).version(0L).build());
        queue.setNextEnqueueSequence(Math.incrementExact(sequence));
        queue.setWakeGeneration(Math.incrementExact(queue.getWakeGeneration()));
        queue.setNextCheckAt(now);
        queue.setUpdatedAt(now);
        if (queueMapper.updateById(queue) != 1) { throw new IllegalStateException("直充限频入队控制行版本冲突"); }
        // TODO docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#6-timer-与补偿：
        // 后续Task5注册Handler后消费持久化唤醒意图；当前不向未注册类型调度任务。
        log.info("[直充限频] 入队成功 orderNo={}, queueId={}, platformSpuId={}, sequence={}, wakeGeneration={}",
                command.orderNo(), queue.getId(), product.platformSpuId(), sequence, queue.getWakeGeneration());
    }

    /**
     * 仅实际充值副作用参与频控；冻结项优先。历史无项时必须从可信SKU及解密target类型证实可走普通认领。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#2-现有实现与接入边界。
     */
    public boolean requiresFrequency(String operationNo, String actualTargetType) {
        var operation = findOperation(operationNo);
        if (operation == null) { throw new IllegalArgumentException("履约操作不存在"); }
        if (!isRechargeSideEffect(operation)) { return false; }
        var bOrder = Objects.requireNonNull(bOrderMapper.selectById(operation.getBOrderId()), "BOrder不存在");
        if (entryMapper.selectByOrderId(bOrder.getOrderId()) != null) { return true; }
        var order = Objects.requireNonNull(orderMapper.selectById(bOrder.getOrderId()), "总单不存在");
        if (!productResolver.resolve(order.getPlatformSkuId()).directRecharge()) { return false; }
        if (FulfillmentTargetFields.ACCOUNT_TYPE_EXTERNAL_ACCOUNT.equals(actualTargetType)) { return false; }
        throw new MissingFrequencyEntryException();
    }

    /**
     * QUEUE→ENTRY→Request/Order→BOrder/Operation短事务认领；首次提交后的版本由MP版本插件返回，不刷新旧资格。
     * 非队首正常返回空，不改操作状态与外呼次数。设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#42-认领。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public RechargeFrequencyExecutionPermit claim(String operationNo) {
        log.info("[直充限频] 认领入口 operationNo={}", operationNo);
        var locked = lockExecution(operationNo, true);
        if (locked == null || locked.entry() == null || !isRechargeSideEffect(locked.operation())) { return null; }
        var queue = locked.queue(); var entry = locked.entry(); var operation = locked.operation();
        if (queue.getBlockedReason() != null || !canClaim(operation)) { return null; }
        if (queue.getHolderEntryId() == null) {
            if (!entry.getId().equals(locked.waitingHeadId())) { return null; }
            queue.setOccupancyGeneration(Math.incrementExact(queue.getOccupancyGeneration()));
            queue.setHolderEntryId(entry.getId());
            queue.setAcquiredAt(databaseClock.now());
            queue.setMinimumHoldUntil(queue.getAcquiredAt().plusSeconds(RechargeFrequencyConstants.SUCCESS_MINIMUM_HOLD_SECONDS));
            entry.setOccupancyGeneration(queue.getOccupancyGeneration());
        } else if (!queue.getHolderEntryId().equals(entry.getId())
                || !queue.getOccupancyGeneration().equals(entry.getOccupancyGeneration())) { return null; }
        // 当前合法operation只能由同单受控继续履约入口绑定，不能任意旧操作抢占。
        if (!operationNo.equals(entry.getActiveOperationNo())) { return null; }
        entry.setStatus(RechargeFrequencyEntryStatus.HOLDING.getCode());
        entry.setDispatchPhase(RechargeFrequencyDispatchPhase.PREPARED.getCode());
        entry.setUpdatedAt(databaseClock.now());
        operation.setStatus(SupplierOrderOperationStatus.EXECUTING.getCode());
        operation.setUpdatedAt(databaseClock.now());
        saveQueue(queue); saveEntry(entry);
        if (operationMapper.updateById(operation) != 1) { throw new IllegalStateException("限频认领操作版本冲突"); }
        return new RechargeFrequencyExecutionPermit(queue.getId(), entry.getId(), operationNo,
                queue.getOccupancyGeneration(), entry.getVersion(), operation.getVersion());
    }

    /**
     * 独立短事务越过发送边界；与人工PREPARED撤销竞争同一锁，提交成功后才能在事务外发送原ticket。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#42-认领。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean markSendPossible(RechargeFrequencyExecutionPermit permit) {
        log.info("[直充限频] 发送边界 operationNo={}, queueId={}, generation={}", permit.operationNo(), permit.queueId(), permit.generation());
        var locked = lockForResult(permit.operationNo());
        if (!matches(locked, permit) || locked.entry().getVersion() != permit.entryVersion()
                || locked.operation().getVersion() != permit.operationVersion()
                || locked.operation().getStatus() != SupplierOrderOperationStatus.EXECUTING.getCode()
                || !Objects.equals(locked.entry().getDispatchPhase(), RechargeFrequencyDispatchPhase.PREPARED.getCode())) { return false; }
        locked.entry().setDispatchPhase(RechargeFrequencyDispatchPhase.SEND_POSSIBLE.getCode());
        locked.entry().setUpdatedAt(databaseClock.now()); saveEntry(locked.entry());
        return true;
    }

    /**
     * 内部受控撤销，只允许首次操作无任何外呼事实且PREPARED仍匹配原资格；原序号、原操作号恢复WAITING。
     * 不改变订单结果或资金。设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", rollbackFor = Exception.class)
    public boolean revokePrepared(RechargeFrequencyExecutionPermit permit) {
        log.info("[直充限频] 撤销准备资格 operationNo={}, queueId={}, generation={}", permit.operationNo(), permit.queueId(), permit.generation());
        var locked = lockForResult(permit.operationNo());
        if (!matches(locked, permit) || locked.entry().getVersion() != permit.entryVersion()
                || locked.operation().getVersion() != permit.operationVersion()
                || !Objects.equals(locked.entry().getDispatchPhase(), RechargeFrequencyDispatchPhase.PREPARED.getCode())
                || locked.operation().getStatus() != SupplierOrderOperationStatus.EXECUTING.getCode()
                || locked.operation().getAttemptCount() != 0 || locked.operation().getResultClass() != null
                || locked.operation().getExternalOperationNo() != null) { return false; }
        // 只读QUERY没有充值风险；严格后序且没有任何发送迹象的预建候选不能阻断本次未发送撤销。
        // 其余充值操作只有可信FAILED/REJECTED可清除风险，序号靠后不能忽略UNKNOWN/EXECUTING/MANUAL。
        for (var related : locked.relatedOperations()) {
            if (related.getId().equals(locked.operation().getId()) || isReadOnlyQuery(related)
                    || isStrictlyLaterUnsent(locked, related)) { continue; }
            if (related.getStatus() != SupplierOrderOperationStatus.FAILED.getCode()
                    || !OperationOutcomeClass.REJECTED.name().equals(related.getResultClass())) { return false; }
        }
        locked.operation().setStatus(SupplierOrderOperationStatus.PENDING.getCode());
        locked.operation().setUpdatedAt(databaseClock.now());
        if (operationMapper.updateById(locked.operation()) != 1) { throw new IllegalStateException("撤销操作版本冲突"); }
        locked.entry().setStatus(RechargeFrequencyEntryStatus.WAITING.getCode());
        locked.entry().setDispatchPhase(null); saveEntry(locked.entry());
        clearHolder(locked.queue());
        return true;
    }

    /**
     * 按锁定的BO尝试号与Op序号确定严格后序；任何外呼次数、结果、外部引用或完成记录均不能猜为未发送。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#42-认领。
     */
    private static boolean isStrictlyLaterUnsent(LockedExecution locked, FulfillmentOperationDO related) {
        if (!isRechargeSideEffect(related) || !canClaim(related) || related.getAttemptCount() != 0
                || related.getResultClass() != null || related.getExternalOperationNo() != null || related.getCompletedAt() != null) { return false; }
        var bOrder = locked.relatedBOrders().stream().filter(bo -> bo.getId().equals(related.getBOrderId())).findFirst().orElse(null);
        if (bOrder == null || !bOrder.getFulfillmentRequestId().equals(locked.request().getId())
                || !bOrder.getOrderId().equals(locked.order().getId())) { return false; }
        return bOrder.getId().equals(locked.bOrder().getId())
                ? related.getSequenceNo() > locked.operation().getSequenceNo()
                : bOrder.getAttemptNo() > locked.bOrder().getAttemptNo();
    }
    private static boolean isReadOnlyQuery(FulfillmentOperationDO operation) {
        return FulfillmentOperationKind.QUERY_ORDER.name().equals(operation.getOperationKind())
                || FulfillmentOperationKind.QUERY_ASSET.name().equals(operation.getOperationKind());
    }

    /**
     * 仅由可信拒绝结果事务完成绑定；不得在整单失败/资金释放后通过独立事务救回。
     * 前置合法性由SupplierOrderLifecycle在同一锁上下文验证；保持原SPU、FIFO序号、代次和窗口。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#5-结果窗口和资金。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    void bindContinuingOperation(LockedExecution locked, FulfillmentOperationDO next) {
        if (!isCurrentHolder(locked) || locked.operation().getStatus() != SupplierOrderOperationStatus.FAILED.getCode()
                || !OperationOutcomeClass.REJECTED.name().equals(locked.operation().getResultClass())
                || locked.request().getStatus() != FulfillmentRequestStatus.FULFILLING.getCode()
                || !locked.relatedOperations().contains(next) || !canClaim(next) || !isRechargeSideEffect(next)) {
            throw new IllegalStateException("可信续履约绑定前置条件不成立");
        }
        locked.entry().setActiveOperationNo(next.getOperationNo());
        locked.entry().setDispatchPhase(RechargeFrequencyDispatchPhase.PREPARED.getCode()); saveEntry(locked.entry());
        locked.queue().setNextCheckAt(databaseClock.now());
        locked.queue().setWakeGeneration(Math.incrementExact(locked.queue().getWakeGeneration())); saveQueue(locked.queue());
        // TODO docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#6-timer-与补偿：Task5消费续履约持久化唤醒意图。
    }

    /**
     * 预读仅定位锁，锁后重新核对关联；结果入口不拿SKU限购锁，资金锁由既有资金服务最后获取。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#42-认领。
     */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public LockedExecution lockForResult(String operationNo) {
        return lockExecution(operationNo, false);
    }

    /**
     * 认领独立READ_COMMITTED事务取得QUEUE锁后才定位队首，当前语句读取最新提交；同队列写入口均先锁QUEUE。
     * 仅锁队首/候选两行并按PK升序，不因积压增长扫描锁住全部等待项；结果入口只锁当前项。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#42-认领。
     */
    private LockedExecution lockExecution(String operationNo, boolean locateWaitingHead) {
        var operation = findOperation(operationNo);
        if (operation == null) { return null; }
        var bOrder = bOrderMapper.selectById(operation.getBOrderId());
        if (bOrder == null) { throw new IllegalStateException("BOrder不存在"); }
        var locator = entryMapper.selectByOrderId(bOrder.getOrderId());
        var queue = locator == null ? null : queueMapper.selectByIdForUpdate(locator.getQueueId());
        RechargeFrequencyEntryDO entry = null;
        RechargeFrequencyEntryDO head = null;
        if (locator != null) {
            var headLocator = locateWaitingHead ? entryMapper.selectFirstWaiting(locator.getQueueId()) : null;
            var ids = new java.util.TreeSet<Long>(); ids.add(locator.getId());
            if (headLocator != null) { ids.add(headLocator.getId()); }
            for (Long id : ids) {
                var row = entryMapper.selectByIdForUpdate(id);
                if (row == null || !row.getQueueId().equals(locator.getQueueId())) { throw new IllegalStateException("限频队列项关联变化"); }
                if (id.equals(locator.getId())) { entry = row; }
                if (headLocator != null && id.equals(headLocator.getId())) {
                    if (row.getStatus() != RechargeFrequencyEntryStatus.WAITING.getCode()) { throw new IllegalStateException("限频队首状态变化"); }
                    head = row;
                }
            }
        }
        var request = executionMapper.lockRequest(bOrder.getFulfillmentRequestId());
        var order = executionMapper.lockOrder(bOrder.getOrderId());
        var bOrders = executionMapper.lockBOrders(bOrder.getOrderId());
        var operations = executionMapper.lockOperations(bOrder.getOrderId());
        var currentBOrder = bOrders.stream().filter(row -> row.getId().equals(bOrder.getId())).findFirst().orElse(null);
        var currentOperation = operations.stream().filter(row -> row.getId().equals(operation.getId())).findFirst().orElse(null);
        if (request == null || order == null || currentBOrder == null || currentOperation == null
                || (locator != null && (queue == null || entry == null
                || !entry.getQueueId().equals(queue.getId()) || !entry.getOrderId().equals(order.getId())
                || !entry.getFulfillmentRequestId().equals(request.getId())))
                || !currentBOrder.getFulfillmentRequestId().equals(request.getId()) || !currentBOrder.getOrderId().equals(order.getId())
                || !currentOperation.getBOrderId().equals(currentBOrder.getId())) { throw new IllegalStateException("限频执行关联变化"); }
        return new LockedExecution(queue, entry, request, order, currentBOrder, currentOperation, bOrders, operations, head == null ? null : head.getId());
    }

    /** 可信业务终态后处理队列，资金动作仍由SupplierOrderLifecycle先完成。重复成功不刷新acquiredAt。 */
    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void reconcile(LockedExecution locked) {
        if (locked == null || !isCurrentHolder(locked)) { return; }
        var now = databaseClock.now();
        boolean failed = locked.request().getStatus() == FulfillmentRequestStatus.FAILED.getCode();
        boolean success = locked.request().getStatus() == FulfillmentRequestStatus.SUCCEEDED.getCode();
        if (!failed && !success) { return; }
        if (success && !RechargeFrequencyPolicy.canReleaseSuccess(locked.queue().getAcquiredAt(), now)) {
            if (Objects.equals(locked.queue().getNextCheckAt(), locked.queue().getMinimumHoldUntil())) { return; }
            locked.queue().setNextCheckAt(locked.queue().getMinimumHoldUntil());
            locked.queue().setWakeGeneration(Math.incrementExact(locked.queue().getWakeGeneration())); saveQueue(locked.queue());
            // TODO docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#6-timer-与补偿：Task5消费持久化意图。
            return;
        }
        locked.entry().setStatus(RechargeFrequencyEntryStatus.FINISHED.getCode());
        locked.entry().setCompletedAt(now); saveEntry(locked.entry()); clearHolder(locked.queue());
    }

    public static boolean isCurrentHolder(LockedExecution locked) {
        return locked != null && locked.entry() != null && locked.queue() != null && Objects.equals(locked.queue().getHolderEntryId(), locked.entry().getId())
                && Objects.equals(locked.queue().getOccupancyGeneration(), locked.entry().getOccupancyGeneration())
                && Objects.equals(locked.entry().getActiveOperationNo(), locked.operation().getOperationNo());
    }
    private static boolean matches(LockedExecution locked, RechargeFrequencyExecutionPermit permit) {
        return isCurrentHolder(locked) && locked.queue().getId() == permit.queueId() && locked.entry().getId() == permit.entryId()
                && locked.queue().getOccupancyGeneration() == permit.generation();
    }
    private static boolean canClaim(FulfillmentOperationDO operation) {
        return operation.getStatus() != null && (operation.getStatus() == SupplierOrderOperationStatus.PENDING.getCode()
                || operation.getStatus() == SupplierOrderOperationStatus.CLAIMED.getCode());
    }
    private static boolean isRechargeSideEffect(FulfillmentOperationDO operation) {
        return FulfillmentOperationKind.FULFILL.name().equals(operation.getOperationKind())
                && (AdapterStepCode.SEND.name().equals(operation.getAdapterStepCode()) || AdapterStepCode.SEND_KM.name().equals(operation.getAdapterStepCode()));
    }
    private FulfillmentOperationDO findOperation(String no) {
        return operationMapper.selectOne(new LambdaQueryWrapper<FulfillmentOperationDO>().eq(FulfillmentOperationDO::getOperationNo, no));
    }
    private void saveQueue(RechargeFrequencyQueueDO queue) {
        queue.setUpdatedAt(databaseClock.now());
        if (queueMapper.updateById(queue) != 1) { throw new IllegalStateException("限频控制行版本冲突"); }
    }
    private void saveEntry(RechargeFrequencyEntryDO entry) {
        entry.setUpdatedAt(databaseClock.now());
        if (entryMapper.updateById(entry) != 1) { throw new IllegalStateException("限频队列项版本冲突"); }
    }
    private void clearHolder(RechargeFrequencyQueueDO queue) {
        queue.setHolderEntryId(null); queue.setAcquiredAt(null); queue.setMinimumHoldUntil(null);
        queue.setNextCheckAt(databaseClock.now()); queue.setWakeGeneration(Math.incrementExact(queue.getWakeGeneration())); saveQueue(queue);
    }
    /**
     * 统一锁序取得的当前读行；关联BOrder/Operation列表按PK锁定，结果编排直接复用而不再普通查询旧快照。
     * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#42-认领。
     */
    public record LockedExecution(RechargeFrequencyQueueDO queue, RechargeFrequencyEntryDO entry,
                                  FulfillmentRequestDO request, SupplyChainOrderDO order, BOrderDO bOrder, FulfillmentOperationDO operation,
                                  java.util.List<BOrderDO> relatedBOrders, java.util.List<FulfillmentOperationDO> relatedOperations, Long waitingHeadId) { }
    public static final class MissingFrequencyEntryException extends IllegalStateException {
        MissingFrequencyEntryException() { super(FulfillmentExecutionFailureCodes.FREQUENCY_MIGRATION_MISSING); }
    }

    /** 仅识别MySQL1062、23000且明确命中特定身份唯一键；其他完整性异常不能重试。 */
    static boolean isIdentityCompetition(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062 && "23000".equals(sql.getSQLState())
                    && sql.getMessage() != null && IDENTITY_KEY.matcher(sql.getMessage()).find()) { return true; }
        }
        return false;
    }

    /** 内部命令禁止自动toString输出实际手机号。 */
    public record EnqueueCommand(Long orderId, String orderNo, Long fulfillmentRequestId, String activeOperationNo,
                                 Long buyerId, Long projectId, Long platformSkuId, String targetAccountType, String actualMobile) {
        @Override public String toString() { return "EnqueueCommand[orderNo=" + orderNo + ", platformSkuId=" + platformSkuId + "]"; }
    }
    public static final class QueueIdentityCreationConflictException extends RuntimeException {
        QueueIdentityCreationConflictException(DuplicateKeyException cause) { super("直充限频队列身份并发创建冲突", cause); }
    }
}
