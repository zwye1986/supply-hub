package com.aifuli.module.supplychain.infrastructure.timer;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerExecutionDO;
import com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO;
import com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerExecutionMapper;
import com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Timer 引擎使用的短事务边界：抢占、续租、完成与失败均带 fencing 条件。 */
@Service
@RequiredArgsConstructor
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

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public List<ClaimedTask> claimBatch(String workerId, int batchSize, Duration leaseDuration) {
        LocalDateTime now = databaseClock.now();
        List<TimerTaskDO> candidates = timerTaskMapper.selectClaimable(now, batchSize);
        List<ClaimedTask> claimed = new ArrayList<>();
        LocalDateTime leaseUntil = now.plus(leaseDuration);
        for (TimerTaskDO task : candidates) {
            if (timerTaskMapper.claim(task.getId(), task.getVersion(), workerId, leaseUntil, now) == 1) {
                int claimedAttemptNo = task.getAttemptCount() + 1;
                claimed.add(new ClaimedTask(task.getId(), task.getTaskNo(), task.getTaskType(),
                        claimedAttemptNo, task.getFencingToken() + 1, task.getPayloadJson()));
            }
        }
        return claimed;
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void renewLease(Long taskId, long fencingToken, String workerId, Duration leaseDuration) {
        LocalDateTime now = databaseClock.now();
        timerTaskMapper.renewLease(taskId, fencingToken, workerId, now.plus(leaseDuration), now);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void startExecution(ClaimedTask task, String workerId) {
        timerTaskMapper.markExecuting(task.taskId(), task.fencingToken(), workerId,
                databaseClock.now());
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void complete(ClaimedTask task, String workerId, LocalDateTime startedAt,
                         String resultCode, byte[] resultDigest) {
        LocalDateTime now = databaseClock.now();
        timerTaskMapper.complete(task.taskId(), task.fencingToken(), workerId, now);
        recordExecution(task, workerId, STATUS_EXEC_SUCCESS, startedAt, now, null, resultDigest);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void fail(ClaimedTask task, String workerId, LocalDateTime startedAt,
                     String errorCode, byte[] errorDigest, Duration backoff, boolean deadLetter) {
        LocalDateTime now = databaseClock.now();
        short taskStatus = deadLetter ? STATUS_DEAD : STATUS_RETRY;
        LocalDateTime nextFireAt = deadLetter ? null : now.plus(backoff);
        timerTaskMapper.finishAttempt(task.taskId(), task.fencingToken(), workerId, taskStatus,
                nextFireAt, errorCode, now);
        recordExecution(task, workerId, deadLetter ? STATUS_EXEC_DEAD : STATUS_EXEC_RETRY,
                startedAt, now, errorCode, errorDigest);
    }

    private void recordExecution(ClaimedTask task, String workerId, short execStatus,
                                 LocalDateTime startedAt, LocalDateTime finishedAt,
                                 String errorCode, byte[] digest) {
        TimerExecutionDO execution = TimerExecutionDO.builder()
                .executionNo(businessNoGenerator.next("TMR_"))
                .taskId(task.taskId()).attemptNo(task.attemptNo()).fencingToken(task.fencingToken())
                .workerId(workerId).status(execStatus).startedAt(startedAt).finishedAt(finishedAt)
                .durationMs(Duration.between(startedAt, finishedAt).toMillis())
                .errorCode(errorCode).errorDigest(null).resultDigest(digest)
                .createdAt(finishedAt).build();
        timerExecutionMapper.insert(execution);
    }

    public record ClaimedTask(Long taskId, String taskNo, String taskType, int attemptNo,
                              long fencingToken, String payloadJson) {
    }
}
