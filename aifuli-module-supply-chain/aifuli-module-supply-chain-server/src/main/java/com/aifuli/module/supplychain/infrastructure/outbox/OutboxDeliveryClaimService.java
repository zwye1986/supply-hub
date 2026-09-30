package com.aifuli.module.supplychain.infrastructure.outbox;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Outbox 投递引擎的短事务边界：抢占、续租、投递成功与失败均带 fencing 条件。 */
@Service
@RequiredArgsConstructor
public class OutboxDeliveryClaimService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final short STATUS_RETRY = 30;
    private static final short STATUS_DEAD = 50;

    private final OutboxEventMapper outboxEventMapper;
    private final SupplyChainDatabaseClock databaseClock;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public List<OutboxEventDO> claimBatch(String workerId, int batchSize, Duration leaseDuration) {
        LocalDateTime now = databaseClock.now();
        List<OutboxEventDO> candidates = outboxEventMapper.selectDeliverable(now, batchSize);
        List<OutboxEventDO> claimed = new ArrayList<>();
        LocalDateTime leaseUntil = now.plus(leaseDuration);
        for (OutboxEventDO event : candidates) {
            if (outboxEventMapper.claim(event.getId(), event.getVersion(), workerId, leaseUntil, now) == 1) {
                event.setFencingToken(event.getFencingToken() + 1);
                event.setAttemptCount(event.getAttemptCount() + 1);
                event.setStatus((short) 10);
                claimed.add(event);
            }
        }
        return claimed;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void markDelivering(Long id, long fencingToken, String workerId) {
        outboxEventMapper.markDelivering(id, fencingToken, workerId, databaseClock.now());
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void publish(Long id, long fencingToken, String workerId) {
        outboxEventMapper.publish(id, fencingToken, workerId, databaseClock.now());
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void fail(Long id, long fencingToken, String workerId, String errorCode,
                     Duration backoff, boolean deadLetter) {
        LocalDateTime now = databaseClock.now();
        short status = deadLetter ? STATUS_DEAD : STATUS_RETRY;
        LocalDateTime nextAvailable = deadLetter ? null : now.plus(backoff);
        outboxEventMapper.failDelivery(id, fencingToken, workerId, status, nextAvailable, errorCode, now);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void renewLease(Long id, long fencingToken, String workerId, Duration leaseDuration) {
        LocalDateTime now = databaseClock.now();
        outboxEventMapper.renewLease(id, fencingToken, workerId, now.plus(leaseDuration), now);
    }
}
