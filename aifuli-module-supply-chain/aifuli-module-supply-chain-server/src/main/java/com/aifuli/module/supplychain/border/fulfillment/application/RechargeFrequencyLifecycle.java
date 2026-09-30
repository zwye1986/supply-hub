package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyQueueDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.RechargeFrequencyEntryDO;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyQueueMapper;
import com.aifuli.module.supplychain.border.fulfillment.dal.mysql.RechargeFrequencyEntryMapper;
import com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTargetFields;
import com.aifuli.module.supplychain.border.fulfillment.domain.RechargeFrequencyEntryStatus;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.application.RechargeFrequencyProductResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
