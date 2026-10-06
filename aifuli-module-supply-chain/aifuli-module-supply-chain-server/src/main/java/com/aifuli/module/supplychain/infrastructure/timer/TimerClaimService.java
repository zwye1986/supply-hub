package com.aifuli.module.supplychain.infrastructure.timer;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.claim.ClaimRecoveryPolicy;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerExecutionDO;
import com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO;
import com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerExecutionMapper;
import com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Timer 引擎使用的短事务边界：抢占、续租、完成与失败均带 fencing 条件。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TimerClaimService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final short STATUS_RETRY = 30;
    private static final short STATUS_DEAD = 60;
    private static final short STATUS_EXEC_SUCCESS = 20;
    private static final short STATUS_EXEC_RETRY = 30;
    private static final short STATUS_EXEC_DEAD = 40;

    private final TimerTaskMapper timerTaskMapper;
    private final TimerExecutionMapper timerExecutionMapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final SupplyChainDatabaseClock databaseClock;
    private final ClaimRecoveryPolicy.DiagnosticGate diagnosticGate = new ClaimRecoveryPolicy.DiagnosticGate();

    /**
     * 短事务锁定有限候选，按锁后的库时钟/version/合法前态/持久预算CAS；仅1行领取返回身份。
     * 耗尽回收不写execution，没有可信Handler开始时间不能补startedAt；其它审计转换未在本片修改。
     * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#31-扫描和预算
     * 及 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#33-timer审计与失败处理。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public List<ClaimedTask> claimBatch(String workerId, int batchSize, Duration leaseDuration) {
        log.debug("Timer领取扫描：workerId={}, batchSize={}, leaseDuration={}", workerId, batchSize, leaseDuration);
        List<Long> broken = timerTaskMapper.selectBrokenActiveLeaseIds(ClaimRecoveryPolicy.DIAGNOSTIC_SAMPLE_SIZE);
        if (!broken.isEmpty() && diagnosticGate.acquire()) {
            log.warn("Timer协议损坏：code={}, sampleIds={}，跳过且不自动修复", ClaimRecoveryPolicy.ACTIVE_LEASE_MISSING, broken);
        }
        LocalDateTime now = databaseClock.now();
        List<TimerTaskDO> candidates = timerTaskMapper.selectClaimable(now, batchSize);
        List<ClaimedTask> claimed = new ArrayList<>();
        // 锁后重新取库时钟，并只根据真实1行CAS后的对象生成领取身份。
        // 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#31-扫描和预算。
        for (TimerTaskDO candidate : candidates) {
            TimerTaskDO task = timerTaskMapper.selectByIdForUpdate(candidate.getId());
            LocalDateTime lockedNow = databaseClock.now();
            if (task.getAttemptCount() >= task.getMaxAttempts()) {
                timerTaskMapper.exhaustBudget(task.getId(), task.getVersion(), lockedNow);
                continue;
            }
            if (timerTaskMapper.claim(task.getId(), task.getVersion(), workerId,
                    lockedNow.plus(leaseDuration), lockedNow) == 1) {
                task = timerTaskMapper.selectByIdForUpdate(task.getId());
                claimed.add(new ClaimedTask(task.getId(), task.getTaskNo(), task.getTaskType(),
                        task.getAttemptCount(), task.getFencingToken(), task.getPayloadJson()));
            }
        }
        return claimed;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean renewLease(Long taskId, long fencingToken, String workerId, Duration leaseDuration) {
        TimerTaskDO task = timerTaskMapper.selectByIdForUpdate(taskId);
        if (task == null) {
            log.debug("Timer renewLease 失格：未找到任务 taskId={}", taskId);
            return false;
        }
        LocalDateTime now = databaseClock.now();
        if (task.getFencingToken() == null || task.getFencingToken() != fencingToken
                || !java.util.Objects.equals(task.getLeaseOwner(), workerId)
                || (task.getStatus() != TimerTaskMapper.STATUS_CLAIMED
                    && task.getStatus() != TimerTaskMapper.STATUS_EXECUTING)
                || task.getLeaseUntil() == null || !task.getLeaseUntil().isAfter(now)) {
            log.debug("Timer renewLease 失格或到期：taskId={}, expectedToken={}, actualToken={}, "
                    + "expectedOwner={}, actualOwner={}, status={}, leaseUntil={}, now={}",
                    taskId, fencingToken, task.getFencingToken(), workerId, task.getLeaseOwner(),
                    task.getStatus(), task.getLeaseUntil(), now);
            return false;
        }
        int rows = timerTaskMapper.renewLease(taskId, task.getVersion(), fencingToken, workerId,
                now.plus(leaseDuration), now);
        return rows == 1;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean startExecution(ClaimedTask task, String workerId) {
        return markExecuting(task.taskId(), task.fencingToken(), workerId);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean markExecuting(Long taskId, long fencingToken, String workerId) {
        TimerTaskDO task = timerTaskMapper.selectByIdForUpdate(taskId);
        if (task == null) {
            log.warn("Timer markExecuting 失格：未找到任务 taskId={}", taskId);
            return false;
        }
        LocalDateTime now = databaseClock.now();
        if (task.getFencingToken() == null || task.getFencingToken() != fencingToken
                || !java.util.Objects.equals(task.getLeaseOwner(), workerId)
                || task.getStatus() != TimerTaskMapper.STATUS_CLAIMED
                || task.getLeaseUntil() == null || !task.getLeaseUntil().isAfter(now)) {
            log.warn("Timer markExecuting 失格：taskId={}, expectedToken={}, actualToken={}, "
                    + "expectedOwner={}, actualOwner={}, status={}, leaseUntil={}, now={}",
                    taskId, fencingToken, task.getFencingToken(), workerId, task.getLeaseOwner(),
                    task.getStatus(), task.getLeaseUntil(), now);
            return false;
        }
        int rows = timerTaskMapper.markExecuting(taskId, task.getVersion(), fencingToken, workerId, now);
        if (rows == 1) {
            return true;
        }
        log.warn("Timer markExecuting 失格（影响0行）：taskId={}, token={}, workerId={}", taskId, fencingToken, workerId);
        return false;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean complete(ClaimedTask task, String workerId, LocalDateTime startedAt,
                         String resultCode, byte[] resultDigest) {
        TimerTaskDO taskDO = timerTaskMapper.selectByIdForUpdate(task.taskId());
        if (taskDO == null) {
            log.warn("Timer complete 失格：未找到任务 taskId={}", task.taskId());
            return false;
        }
        LocalDateTime now = databaseClock.now();
        if (taskDO.getFencingToken() == null || taskDO.getFencingToken() != task.fencingToken()
                || !java.util.Objects.equals(taskDO.getLeaseOwner(), workerId)
                || (taskDO.getStatus() != TimerTaskMapper.STATUS_CLAIMED
                    && taskDO.getStatus() != TimerTaskMapper.STATUS_EXECUTING)
                || taskDO.getLeaseUntil() == null || !taskDO.getLeaseUntil().isAfter(now)) {
            log.warn("Timer complete 失格：taskId={}, expectedToken={}, actualToken={}, "
                    + "expectedOwner={}, actualOwner={}, status={}, leaseUntil={}, now={}",
                    task.taskId(), task.fencingToken(), taskDO.getFencingToken(), workerId, taskDO.getLeaseOwner(),
                    taskDO.getStatus(), taskDO.getLeaseUntil(), now);
            return false;
        }
        int rows = timerTaskMapper.complete(task.taskId(), taskDO.getVersion(), task.fencingToken(), workerId, now);
        if (rows == 1) {
            recordExecution(task, workerId, STATUS_EXEC_SUCCESS, startedAt, now, null, null, resultDigest);
            return true;
        }
        log.warn("Timer complete 失格（影响0行）：taskId={}, token={}, workerId={}",
                task.taskId(), task.fencingToken(), workerId);
        return false;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean fail(ClaimedTask task, String workerId, LocalDateTime startedAt,
                     String errorCode, byte[] errorDigest, Duration backoff, boolean deadLetter) {
        TimerTaskDO taskDO = timerTaskMapper.selectByIdForUpdate(task.taskId());
        if (taskDO == null) {
            log.warn("Timer fail 失格：未找到任务 taskId={}", task.taskId());
            return false;
        }
        LocalDateTime now = databaseClock.now();
        if (taskDO.getFencingToken() == null || taskDO.getFencingToken() != task.fencingToken()
                || !java.util.Objects.equals(taskDO.getLeaseOwner(), workerId)
                || (taskDO.getStatus() != TimerTaskMapper.STATUS_CLAIMED
                    && taskDO.getStatus() != TimerTaskMapper.STATUS_EXECUTING)
                || taskDO.getLeaseUntil() == null || !taskDO.getLeaseUntil().isAfter(now)) {
            log.warn("Timer fail 失格：taskId={}, expectedToken={}, actualToken={}, "
                    + "expectedOwner={}, actualOwner={}, status={}, leaseUntil={}, now={}",
                    task.taskId(), task.fencingToken(), taskDO.getFencingToken(), workerId, taskDO.getLeaseOwner(),
                    taskDO.getStatus(), taskDO.getLeaseUntil(), now);
            return false;
        }
        short taskStatus = deadLetter ? STATUS_DEAD : STATUS_RETRY;
        LocalDateTime nextFireAt = deadLetter
                ? (taskDO.getNextFireAt() != null ? taskDO.getNextFireAt() : now)
                : now.plus(backoff);
        int rows = timerTaskMapper.finishAttempt(task.taskId(), taskDO.getVersion(), task.fencingToken(), workerId,
                taskStatus, nextFireAt, errorCode, now);
        if (rows == 1) {
            recordExecution(task, workerId, deadLetter ? STATUS_EXEC_DEAD : STATUS_EXEC_RETRY,
                    startedAt, now, errorCode, errorDigest, null);
            return true;
        }
        log.warn("Timer fail 失格（影响0行）：taskId={}, token={}, workerId={}",
                task.taskId(), task.fencingToken(), workerId);
        return false;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean finishAttempt(ClaimedTask task, String workerId, LocalDateTime startedAt,
                                 String errorCode, byte[] errorDigest, Duration backoff, boolean deadLetter) {
        return fail(task, workerId, startedAt, errorCode, errorDigest, backoff, deadLetter);
    }

    private void recordExecution(ClaimedTask task, String workerId, short execStatus,
                                 LocalDateTime startedAt, LocalDateTime finishedAt,
                                 String errorCode, byte[] errorDigest, byte[] resultDigest) {
        TimerExecutionDO execution = TimerExecutionDO.builder()
                .executionNo(businessNoGenerator.next("TMR_"))
                .taskId(task.taskId()).attemptNo(task.attemptNo()).fencingToken(task.fencingToken())
                .workerId(workerId).status(execStatus).startedAt(startedAt).finishedAt(finishedAt)
                .durationMs(Duration.between(startedAt, finishedAt).toMillis())
                .errorCode(errorCode).errorDigest(errorDigest).resultDigest(resultDigest)
                .createdAt(finishedAt).build();
        timerExecutionMapper.insert(execution);
    }

    public record ClaimedTask(Long taskId, String taskNo, String taskType, int attemptNo,
                              long fencingToken, String payloadJson) {
    }
}
