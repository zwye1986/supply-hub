package com.aifuli.module.supplychain.infrastructure.timer;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO;
import com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** {@link PersistentTimer} 的数据库实现；任务真相源为 {@code sc_timer_task}。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#持久化定时器}。</p>
 */
@Service
@RequiredArgsConstructor
public class DatabasePersistentTimer implements PersistentTimer {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    private static final String SCHEDULE_ONCE = "ONCE";

    private final TimerTaskMapper timerTaskMapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final ObjectMapper objectMapper;
    private final PersistentTimerHandlerRegistry handlerRegistry;
    private final SupplyChainDatabaseClock databaseClock;

    @Override
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public TimerHandle scheduleOnce(String taskType, String idempotencyKey, JsonNode payload, java.time.Instant fireAt) {
        if (taskType == null || taskType.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()
                || payload == null || fireAt == null) {
            throw new IllegalArgumentException("Timer schedule parameters are required");
        }
        TimerTaskDO existing = timerTaskMapper.selectByTypeAndKey(taskType, idempotencyKey);
        if (existing != null) {
            return new TimerHandle(existing.getTaskNo(), existing.getId(), existing.getFencingToken());
        }
        LocalDateTime now = databaseClock.now();
        TimerTaskDO task = TimerTaskDO.builder()
                .taskNo(businessNoGenerator.next("TMR_"))
                .taskType(taskType).idempotencyKey(idempotencyKey)
                .payloadVersion(1).payloadJson(writeJson(payload))
                .scheduleType(SCHEDULE_ONCE).nextFireAt(databaseClock.fromInstant(fireAt))
                .priority(0).shard(0).status((short) 0).attemptCount(0)
                .maxAttempts(handlerRegistry.maxAttempts(taskType))
                .fencingToken(0L).createdAt(now).updatedAt(now).version(0L).build();
        try {
            timerTaskMapper.insert(task);
        } catch (DuplicateKeyException duplicate) {
            TimerTaskDO retry = timerTaskMapper.selectByTypeAndKey(taskType, idempotencyKey);
            if (retry == null) {
                throw duplicate;
            }
            return new TimerHandle(retry.getTaskNo(), retry.getId(), retry.getFencingToken());
        }
        return new TimerHandle(task.getTaskNo(), task.getId(), task.getFencingToken());
    }

    @Override
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public boolean cancel(String taskNo, String reason) {
        if (taskNo == null || taskNo.isBlank()) {
            throw new IllegalArgumentException("Timer task number is required");
        }
        TimerTaskDO task = timerTaskMapper.selectOne(TimerTaskDO::getTaskNo, taskNo);
        if (task == null) {
            return false;
        }
        LocalDateTime now = databaseClock.now();
        return timerTaskMapper.cancel(task.getId(), task.getVersion(), now) == 1;
    }

    private String writeJson(JsonNode payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Timer payload cannot be serialized", exception);
        }
    }
}
