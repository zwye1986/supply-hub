package com.aifuli.module.supplychain.infrastructure.timer;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.ledger.AppendScheduleIdentityLedgerService;
import com.aifuli.module.supplychain.infrastructure.ledger.SystemIntegrityException;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.infrastructure.timer.dal.dataobject.TimerTaskDO;
import com.aifuli.module.supplychain.infrastructure.timer.dal.mysql.TimerTaskMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** {@link PersistentTimer} 的数据库实现；任务真相源为 {@code sc_timer_task}。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#81-真实-timer-调度与账本绑定实现r3-02t}。</p>
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
    private final ObjectProvider<AppendScheduleIdentityLedgerService> ledgerServiceProvider;

    @Override
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public TimerHandle scheduleOnce(String taskType, String idempotencyKey, JsonNode payload, java.time.Instant fireAt) {
        if (taskType == null || taskType.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()
                || payload == null || fireAt == null) {
            throw new IllegalArgumentException("Timer schedule parameters are required");
        }
        LocalDateTime now = databaseClock.now();
        String payloadJson = writeJson(payload);

        AppendScheduleIdentityLedgerService ledgerService = ledgerServiceProvider.getIfAvailable();
        if (ledgerService != null) {
            AppendScheduleIdentityLedgerService.TimerReservationResult reservation =
                    ledgerService.reserveTimerSlot(taskType, idempotencyKey, 1, "LEGACY_JSON_V1", payloadJson, now);

            if (!reservation.firstReserved()) {
                return new TimerHandle(reservation.existingTaskNo(), reservation.existingTaskId(), 0L);
            }

            TimerTaskDO task = TimerTaskDO.builder()
                    .taskNo(businessNoGenerator.next("TMR_"))
                    .taskType(taskType).idempotencyKey(idempotencyKey)
                    .payloadVersion(1).payloadJson(payloadJson)
                    .scheduleType(SCHEDULE_ONCE).nextFireAt(databaseClock.fromInstant(fireAt))
                    .priority(0).shard(0).status((short) 0).attemptCount(0)
                    .maxAttempts(handlerRegistry.maxAttempts(taskType))
                    .fencingToken(0L).createdAt(now).updatedAt(now).version(0L).build();

            int inserted = timerTaskMapper.insert(task);
            if (inserted != 1 || task.getId() == null || task.getId() <= 0) {
                throw new SystemIntegrityException("Timer源任务插入失败: taskType=" + taskType + ", key=" + idempotencyKey);
            }

            ledgerService.bindTimerTask(reservation.slotId(), reservation.nonce(), task.getId(), task.getTaskNo(), now);
            return new TimerHandle(task.getTaskNo(), task.getId(), 0L);
        }

        // 仅供无账本依赖的狭窄夹具降级兼容；生产环境始终经组件扫描注入账本服务
        TimerTaskDO existing = timerTaskMapper.selectByTypeAndKey(taskType, idempotencyKey);
        if (existing != null) {
            return new TimerHandle(existing.getTaskNo(), existing.getId(), existing.getFencingToken());
        }
        TimerTaskDO task = TimerTaskDO.builder()
                .taskNo(businessNoGenerator.next("TMR_"))
                .taskType(taskType).idempotencyKey(idempotencyKey)
                .payloadVersion(1).payloadJson(payloadJson)
                .scheduleType(SCHEDULE_ONCE).nextFireAt(databaseClock.fromInstant(fireAt))
                .priority(0).shard(0).status((short) 0).attemptCount(0)
                .maxAttempts(handlerRegistry.maxAttempts(taskType))
                .fencingToken(0L).createdAt(now).updatedAt(now).version(0L).build();
        timerTaskMapper.insert(task);
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
