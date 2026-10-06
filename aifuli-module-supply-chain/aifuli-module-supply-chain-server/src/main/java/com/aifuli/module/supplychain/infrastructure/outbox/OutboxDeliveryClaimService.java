package com.aifuli.module.supplychain.infrastructure.outbox;

import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.claim.ClaimRecoveryPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Outbox 投递引擎的短事务边界：抢占、续租、投递成功与失败均带 fencing 条件。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxDeliveryClaimService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final short STATUS_RETRY = 30;
    private static final short STATUS_DEAD = 50;

    private final OutboxEventMapper outboxEventMapper;
    private final SupplyChainDatabaseClock databaseClock;
    private final ClaimRecoveryPolicy.DiagnosticGate diagnosticGate = new ClaimRecoveryPolicy.DiagnosticGate();

    /**
     * 短事务锁定有限候选，按锁后的库时钟/version/合法前态/预算CAS；仅1行领取返回真实对象。
     * 耗尽到期行直接死信，普通fail等其它转换留待后续切片，不据此宣称全部失格路径已封闭。
     * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#31-扫描和预算。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public List<OutboxEventDO> claimBatch(String workerId, int batchSize, Duration leaseDuration) {
        log.debug("Outbox领取扫描：workerId={}, batchSize={}, leaseDuration={}", workerId, batchSize, leaseDuration);
        List<Long> broken = outboxEventMapper.selectBrokenActiveLeaseIds(ClaimRecoveryPolicy.DIAGNOSTIC_SAMPLE_SIZE);
        if (!broken.isEmpty() && diagnosticGate.acquire()) {
            log.warn("Outbox协议损坏：code={}, sampleIds={}，跳过且不自动修复", ClaimRecoveryPolicy.ACTIVE_LEASE_MISSING, broken);
        }
        LocalDateTime now = databaseClock.now();
        List<OutboxEventDO> candidates = outboxEventMapper.selectDeliverable(now, batchSize);
        List<OutboxEventDO> claimed = new ArrayList<>();
        // 当前锁读之后再取库时钟；不把扫描开始时刻当成本批每一行的租约起点。
        // 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#31-扫描和预算。
        for (OutboxEventDO candidate : candidates) {
            OutboxEventDO event = outboxEventMapper.selectByIdForUpdate(candidate.getId());
            LocalDateTime lockedNow = databaseClock.now();
            if (event.getAttemptCount() >= event.getMaxAttempts()) {
                outboxEventMapper.exhaustBudget(event.getId(), event.getVersion(), lockedNow);
                continue;
            }
            if (outboxEventMapper.claim(event.getId(), event.getVersion(), workerId,
                    lockedNow.plus(leaseDuration), lockedNow) == 1) {
                // 只返回真实1行更新后的当前对象，owner/version/lease不能保留扫描时的旧值。
                claimed.add(outboxEventMapper.selectByIdForUpdate(event.getId()));
            }
        }
        return claimed;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean markDelivering(Long id, long fencingToken, String workerId) {
        OutboxEventDO event = outboxEventMapper.selectByIdForUpdate(id);
        if (event == null) {
            log.warn("Outbox markDelivering 失格：未找到事件 id={}", id);
            return false;
        }
        LocalDateTime now = databaseClock.now();
        if (event.getFencingToken() == null || event.getFencingToken() != fencingToken
                || !java.util.Objects.equals(event.getLeaseOwner(), workerId)
                || event.getStatus() != OutboxEventMapper.STATUS_CLAIMED
                || event.getLeaseUntil() == null || !event.getLeaseUntil().isAfter(now)) {
            log.warn("Outbox markDelivering 失格：id={}, expectedToken={}, actualToken={}, "
                    + "expectedOwner={}, actualOwner={}, status={}, leaseUntil={}, now={}",
                    id, fencingToken, event.getFencingToken(), workerId, event.getLeaseOwner(),
                    event.getStatus(), event.getLeaseUntil(), now);
            return false;
        }
        int rows = outboxEventMapper.markDelivering(id, event.getVersion(), fencingToken, workerId, now);
        if (rows == 1) {
            return true;
        }
        log.warn("Outbox markDelivering 失格（影响0行）：id={}, token={}, workerId={}", id, fencingToken, workerId);
        return false;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean publish(Long id, long fencingToken, String workerId) {
        OutboxEventDO event = outboxEventMapper.selectByIdForUpdate(id);
        if (event == null) {
            log.warn("Outbox publish 失格：未找到事件 id={}", id);
            return false;
        }
        LocalDateTime now = databaseClock.now();
        if (event.getFencingToken() == null || event.getFencingToken() != fencingToken
                || !java.util.Objects.equals(event.getLeaseOwner(), workerId)
                || (event.getStatus() != OutboxEventMapper.STATUS_CLAIMED
                    && event.getStatus() != OutboxEventMapper.STATUS_DELIVERING)
                || event.getLeaseUntil() == null || !event.getLeaseUntil().isAfter(now)) {
            log.warn("Outbox publish 失格：id={}, expectedToken={}, actualToken={}, "
                    + "expectedOwner={}, actualOwner={}, status={}, leaseUntil={}, now={}",
                    id, fencingToken, event.getFencingToken(), workerId, event.getLeaseOwner(),
                    event.getStatus(), event.getLeaseUntil(), now);
            return false;
        }
        int rows = outboxEventMapper.publish(id, event.getVersion(), fencingToken, workerId, now);
        if (rows == 1) {
            return true;
        }
        log.warn("Outbox publish 失格（影响0行）：id={}, token={}, workerId={}", id, fencingToken, workerId);
        return false;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean fail(Long id, long fencingToken, String workerId, String errorCode,
                     Duration backoff, boolean deadLetter) {
        OutboxEventDO event = outboxEventMapper.selectByIdForUpdate(id);
        if (event == null) {
            log.warn("Outbox fail 失格：未找到事件 id={}", id);
            return false;
        }
        LocalDateTime now = databaseClock.now();
        if (event.getFencingToken() == null || event.getFencingToken() != fencingToken
                || !java.util.Objects.equals(event.getLeaseOwner(), workerId)
                || (event.getStatus() != OutboxEventMapper.STATUS_CLAIMED
                    && event.getStatus() != OutboxEventMapper.STATUS_DELIVERING)
                || event.getLeaseUntil() == null || !event.getLeaseUntil().isAfter(now)) {
            log.warn("Outbox fail 失格：id={}, expectedToken={}, actualToken={}, "
                    + "expectedOwner={}, actualOwner={}, status={}, leaseUntil={}, now={}",
                    id, fencingToken, event.getFencingToken(), workerId, event.getLeaseOwner(),
                    event.getStatus(), event.getLeaseUntil(), now);
            return false;
        }
        short status = deadLetter ? STATUS_DEAD : STATUS_RETRY;
        // 死信时保留非空available_at（绝不能写NULL，保留原available_at或当前计划时间），清租约；普通失败设置nextAvailableAt
        LocalDateTime nextAvailable = deadLetter
                ? (event.getAvailableAt() != null ? event.getAvailableAt() : now)
                : now.plus(backoff);
        int rows = outboxEventMapper.failDelivery(id, event.getVersion(), fencingToken, workerId,
                status, nextAvailable, errorCode, now);
        if (rows == 1) {
            return true;
        }
        log.warn("Outbox fail 失格（影响0行）：id={}, token={}, workerId={}", id, fencingToken, workerId);
        return false;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean renewLease(Long id, long fencingToken, String workerId, Duration leaseDuration) {
        OutboxEventDO event = outboxEventMapper.selectByIdForUpdate(id);
        if (event == null) {
            log.debug("Outbox renewLease 失格：未找到事件 id={}", id);
            return false;
        }
        LocalDateTime now = databaseClock.now();
        if (event.getFencingToken() == null || event.getFencingToken() != fencingToken
                || !java.util.Objects.equals(event.getLeaseOwner(), workerId)
                || (event.getStatus() != OutboxEventMapper.STATUS_CLAIMED
                    && event.getStatus() != OutboxEventMapper.STATUS_DELIVERING)
                || event.getLeaseUntil() == null || !event.getLeaseUntil().isAfter(now)) {
            log.debug("Outbox renewLease 失格或到期：id={}, expectedToken={}, actualToken={}, "
                    + "expectedOwner={}, actualOwner={}, status={}, leaseUntil={}, now={}",
                    id, fencingToken, event.getFencingToken(), workerId, event.getLeaseOwner(),
                    event.getStatus(), event.getLeaseUntil(), now);
            return false;
        }
        int rows = outboxEventMapper.renewLease(id, event.getVersion(), fencingToken, workerId,
                now.plus(leaseDuration), now);
        return rows == 1;
    }
}
