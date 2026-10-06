package com.aifuli.module.supplychain.infrastructure.ledger;

import com.aifuli.module.supplychain.infrastructure.outbox.OutboxEventRepository;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 完整技术 worker 意图收集、确定性排序与单次 Flush 收集器。
 * <p>
 * 依据设计规范：
 * docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#722-完整技术-worker-意图收集数据库-collation-确定性排序与单次-flush-协议r3-03
 * 职责与不变量：
 * 1. 严格归属于当前活动最外层写事务，在领域写操作执行期间仅在事务本地内存中缓冲意图，不提前加锁；
 * 2. 同键等价载荷合并为单槽并保留最新元数据；同键异载荷在记录或 flush 阶段立即抛出 SystemIntegrityException 阻断；
 * 3. 确定性全局排序加锁：Outbox 在前，Timer 在后；表内部严格遵循源唯一索引实际 collation 规则升序加锁；
 * 4. 同一事务内保证单次 flush；flush 后禁止再次记录新意图；
 * 5. afterCompletion 彻底解绑和清空收集器状态，杜绝线程污染。
 */
@Service
@RequiredArgsConstructor
public class LocalLedgerIntentCollector {

    private final OutboxEventRepository outboxEventRepository;
    private final PersistentTimer persistentTimer;
    private final ObjectMapper objectMapper;

    public record OutboxIntent(
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            String eventType,
            String targetName,
            Object payload,
            String traceId,
            LocalDateTime occurredAt
    ) {
        String key() {
            return aggregateType + "|" + aggregateId + "|" + aggregateVersion + "|" + eventType;
        }
    }

    public record TimerIntent(
            String taskType,
            String idempotencyKey,
            JsonNode payload,
            Instant fireAt
    ) {
        String key() {
            return taskType + "|" + idempotencyKey;
        }
    }

    private static final Comparator<OutboxIntent> OUTBOX_COMPARATOR = Comparator
            .comparing(OutboxIntent::aggregateType, String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo))
            .thenComparing(OutboxIntent::aggregateId, String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo))
            .thenComparingLong(OutboxIntent::aggregateVersion)
            .thenComparing(OutboxIntent::eventType, String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo));

    private static final Comparator<TimerIntent> TIMER_COMPARATOR = Comparator
            .comparing(TimerIntent::taskType, String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo))
            .thenComparing(TimerIntent::idempotencyKey, String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo));

    public void recordOutboxIntent(OutboxIntent intent) {
        Objects.requireNonNull(intent, "OutboxIntent cannot be null");
        TransactionContext context = requireContext();
        context.recordOutbox(intent);
    }

    public void recordTimerIntent(TimerIntent intent) {
        Objects.requireNonNull(intent, "TimerIntent cannot be null");
        TransactionContext context = requireContext();
        context.recordTimer(intent);
    }

    public void flush() {
        TransactionContext context = requireContext();
        context.flush();
    }

    private TransactionContext requireContext() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new SystemIntegrityException("意图收集器必须在有效写事务内使用");
        }
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof TransactionContext ctx && ctx.owner == this) {
                return ctx;
            }
        }
        TransactionContext context = new TransactionContext(this);
        TransactionSynchronizationManager.registerSynchronization(context);
        return context;
    }

    private boolean isPayloadEquivalent(Object payload1, Object payload2) {
        if (payload1 == null && payload2 == null) return true;
        if (payload1 == null || payload2 == null) return false;
        String json1 = writeJson(payload1);
        String json2 = writeJson(payload2);
        try {
            byte[] bytes1 = LedgerJsonEncoding.canonicalBytes(json1);
            byte[] bytes2 = LedgerJsonEncoding.canonicalBytes(json2);
            return Arrays.equals(bytes1, bytes2);
        } catch (Exception e) {
            return false;
        }
    }

    private String writeJson(Object payload) {
        if (payload instanceof String s) return s;
        if (payload instanceof JsonNode node) return node.toString();
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload cannot be serialized", e);
        }
    }

    private enum State {
        COLLECTING,
        FLUSHING,
        FLUSHED
    }

    private static final class TransactionContext implements TransactionSynchronization {
        private final LocalLedgerIntentCollector owner;
        private final Map<String, OutboxIntent> outboxIntents = new LinkedHashMap<>();
        private final Map<String, TimerIntent> timerIntents = new LinkedHashMap<>();
        private State state = State.COLLECTING;

        TransactionContext(LocalLedgerIntentCollector owner) {
            this.owner = owner;
        }

        void recordOutbox(OutboxIntent intent) {
            checkCollecting();
            String key = intent.key();
            OutboxIntent existing = outboxIntents.get(key);
            if (existing != null) {
                if (!owner.isPayloadEquivalent(existing.payload(), intent.payload())) {
                    throw new SystemIntegrityException("意图收集器检测到同键载荷冲突: " + key);
                }
            }
            outboxIntents.put(key, intent);
        }

        void recordTimer(TimerIntent intent) {
            checkCollecting();
            String key = intent.key();
            TimerIntent existing = timerIntents.get(key);
            if (existing != null) {
                if (!owner.isPayloadEquivalent(existing.payload(), intent.payload())) {
                    throw new SystemIntegrityException("意图收集器检测到同键载荷冲突: " + key);
                }
            }
            timerIntents.put(key, intent);
        }

        @Override
        public void flush() {
            if (state == State.FLUSHED || state == State.FLUSHING) {
                throw new SystemIntegrityException("同一事务内禁止重复flush");
            }
            state = State.FLUSHING;

            // 1. Outbox 槽确定性排序并执行
            List<OutboxIntent> sortedOutbox = new ArrayList<>(outboxIntents.values());
            sortedOutbox.sort(OUTBOX_COMPARATOR);
            for (OutboxIntent intent : sortedOutbox) {
                owner.outboxEventRepository.appendLocal(
                        intent.aggregateType(),
                        intent.aggregateId(),
                        intent.aggregateVersion(),
                        intent.eventType(),
                        intent.targetName(),
                        intent.payload(),
                        intent.traceId(),
                        null,
                        null,
                        intent.occurredAt()
                );
            }

            // 2. Timer 槽确定性排序并执行
            List<TimerIntent> sortedTimer = new ArrayList<>(timerIntents.values());
            sortedTimer.sort(TIMER_COMPARATOR);
            for (TimerIntent intent : sortedTimer) {
                owner.persistentTimer.scheduleOnce(
                        intent.taskType(),
                        intent.idempotencyKey(),
                        intent.payload(),
                        intent.fireAt()
                );
            }

            state = State.FLUSHED;
        }

        private void checkCollecting() {
            if (state != State.COLLECTING) {
                throw new SystemIntegrityException("意图收集器已flush，禁止追加新意图");
            }
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            if ((!outboxIntents.isEmpty() || !timerIntents.isEmpty()) && state != State.FLUSHED) {
                throw new SystemIntegrityException("存在未flush的事务意图，提交前守卫拒绝提交");
            }
        }

        @Override
        public void afterCompletion(int status) {
            outboxIntents.clear();
            timerIntents.clear();
            state = State.COLLECTING;
        }
    }
}
