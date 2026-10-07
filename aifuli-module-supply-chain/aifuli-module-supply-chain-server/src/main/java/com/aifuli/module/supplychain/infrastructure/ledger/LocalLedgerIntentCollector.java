package com.aifuli.module.supplychain.infrastructure.ledger;

import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainTransactionObservationManager;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.CollationOrderingMapper;
import com.aifuli.module.supplychain.infrastructure.timer.PersistentTimer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.JdbcTransactionObjectSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
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
public class LocalLedgerIntentCollector {

    private final PersistentTimer persistentTimer;
    private final ObjectMapper objectMapper;
    private final DataSource supplyChainDataSource;
    private final CollationOrderingMapper collationOrderingMapper;

    public LocalLedgerIntentCollector(PersistentTimer persistentTimer,
                                      ObjectMapper objectMapper,
                                      @Qualifier("supplyChainDataSource") DataSource supplyChainDataSource,
                                      CollationOrderingMapper collationOrderingMapper) {
        this.persistentTimer = persistentTimer;
        this.objectMapper = objectMapper;
        this.supplyChainDataSource = supplyChainDataSource;
        this.collationOrderingMapper = collationOrderingMapper;
    }

    /** 键为不可变 record 元组（Outbox 四列），禁止分隔符拼接编码（复合键分隔碰撞）。 */
    public record OutboxKey(String aggregateType, String aggregateId, long aggregateVersion, String eventType) {}

    /** 键为不可变 record 元组（Timer 两列），禁止分隔符拼接编码。 */
    public record TimerKey(String taskType, String idempotencyKey) {}

    /**
     * 意图显式携带 targetType(仅LOCAL)／targetName／payloadVersion(≥1)／comparisonProtocol(仅LEGACY_JSON_V1)，
     * 记录时校验；键为不可变元组。
     */
    public record OutboxIntent(
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            String eventType,
            String targetType,
            String targetName,
            int payloadVersion,
            String comparisonProtocol,
            Object payload,
            String traceId,
            LocalDateTime occurredAt
    ) {
        /** 便捷构造（技术夹具形态）：targetType=LOCAL、payloadVersion=1、LEGACY_JSON_V1。 */
        public OutboxIntent(String aggregateType, String aggregateId, long aggregateVersion,
                            String eventType, String targetName, Object payload,
                            String traceId, LocalDateTime occurredAt) {
            this(aggregateType, aggregateId, aggregateVersion, eventType, "LOCAL", targetName, 1,
                    "LEGACY_JSON_V1", payload, traceId, occurredAt);
        }
    }

    /** 意图携带 payloadVersion(仅1，与 scheduleOnce 冻结一致)／comparisonProtocol(仅LEGACY_JSON_V1)，记录时校验。 */
    public record TimerIntent(
            String taskType,
            String idempotencyKey,
            int payloadVersion,
            String comparisonProtocol,
            JsonNode payload,
            Instant fireAt
    ) {
        /** 便捷构造（技术夹具形态）：payloadVersion=1、LEGACY_JSON_V1。 */
        public TimerIntent(String taskType, String idempotencyKey, JsonNode payload, Instant fireAt) {
            this(taskType, idempotencyKey, 1, "LEGACY_JSON_V1", payload, fireAt);
        }
    }

    /** Outbox 冻结计划：DB 等价合并后的单槽执行单元（代表身份与元数据 = recordSeq 最大者，N14）。 */
    public record OutboxGroupPlan(
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            String eventType,
            String targetType,
            String targetName,
            int payloadVersion,
            String comparisonProtocol,
            String payloadJson,
            String traceId,
            LocalDateTime occurredAt
    ) {}

    /** Timer 冻结计划：DB 等价合并后的单槽执行单元（代表身份与元数据 = recordSeq 最大者，N14）。 */
    public record TimerGroupPlan(
            String taskType,
            String idempotencyKey,
            int payloadVersion,
            String comparisonProtocol,
            String payloadJson,
            Instant fireAt
    ) {}

    /**
     * 包私有冻结（仅测试夹具路径与 flush 使用）：Outbox 意图按生产 CollationOrderingMapper 取得
     * DB 等价分组与组序（N≥1 恰好 1 条分组 SQL；N=0 零 SQL），按 grp 聚类＋组内全等/冲突判定＋
     * 合并保留 recordSeq 最大者元数据；成功后把意图从缓冲移出（提交守卫只核未冻结意图）。
     * 冲突 → SystemIntegrityException（首个账本锁之前、零账本/源 DAL）。
     */
    List<OutboxGroupPlan> freezeOutboxPlan() {
        TransactionContext context = requireContext();
        context.checkNotFlushed();
        List<OutboxKey> keys = new ArrayList<>(context.outboxRecords.keySet());
        if (keys.isEmpty()) {
            return List.of();
        }
        List<CollationOrderingMapper.OutboxKeyRow> rows = collationOrderingMapper.orderOutboxKeys(keys);
        List<OutboxGroupPlan> plans = mergeOutboxGroups(context.outboxRecords, rows);
        context.outboxRecords.clear();
        return plans;
    }

    /** 包私有冻结（与生产 flush 同一实现）：Timer 意图按 DB 等价分组＋组内合并/冲突＋按组序计划。 */
    List<TimerGroupPlan> freezeTimerPlan() {
        TransactionContext context = requireContext();
        context.checkNotFlushed();
        List<TimerKey> keys = new ArrayList<>(context.timerRecords.keySet());
        if (keys.isEmpty()) {
            return List.of();
        }
        List<CollationOrderingMapper.TimerKeyRow> rows = collationOrderingMapper.orderTimerKeys(keys);
        List<TimerGroupPlan> plans = mergeTimerGroups(context.timerRecords, rows);
        context.timerRecords.clear();
        return plans;
    }

    /** grp 聚类＋k*_orig 定位＋组内全等/冲突判定＋合并（生产实现，Outbox 与 Timer 共用同一聚类结构；包私有供 N17 夹具）。 */
    static List<OutboxGroupPlan> mergeOutboxGroups(Map<OutboxKey, OutboxRecord> records,
                                                   List<CollationOrderingMapper.OutboxKeyRow> rows) {
        List<OutboxGroupPlan> plans = new ArrayList<>();
        List<OutboxRecord> group = new ArrayList<>();
        Long currentGrp = null;
        for (CollationOrderingMapper.OutboxKeyRow row : rows) {
            if (!Objects.equals(currentGrp, row.getGrp())) {
                if (!group.isEmpty()) {
                    plans.add(mergeOutboxGroup(group));
                    group = new ArrayList<>();
                }
                currentGrp = row.getGrp();
            }
            OutboxKey key = new OutboxKey(row.getAggregateTypeOrig(), row.getAggregateIdOrig(),
                    row.getAggregateVersionOrig() == null ? -1L : row.getAggregateVersionOrig(),
                    row.getEventTypeOrig());
            OutboxRecord record = records.get(key);
            if (record == null) {
                // N17：k*_orig 定位 miss 显式失败关闭
                throw new SystemIntegrityException("意图分组定位失败（k*_orig 与内存原始元组不符）: " + key);
            }
            group.add(record);
        }
        if (!group.isEmpty()) {
            plans.add(mergeOutboxGroup(group));
        }
        return plans;
    }

    static List<TimerGroupPlan> mergeTimerGroups(Map<TimerKey, TimerRecord> records,
                                                 List<CollationOrderingMapper.TimerKeyRow> rows) {
        List<TimerGroupPlan> plans = new ArrayList<>();
        List<TimerRecord> group = new ArrayList<>();
        Long currentGrp = null;
        for (CollationOrderingMapper.TimerKeyRow row : rows) {
            if (!Objects.equals(currentGrp, row.getGrp())) {
                if (!group.isEmpty()) {
                    plans.add(mergeTimerGroup(group));
                    group = new ArrayList<>();
                }
                currentGrp = row.getGrp();
            }
            TimerKey key = new TimerKey(row.getTaskTypeOrig(), row.getIdempotencyKeyOrig());
            TimerRecord record = records.get(key);
            if (record == null) {
                // N17：k*_orig 定位 miss 显式失败关闭
                throw new SystemIntegrityException("意图分组定位失败（k*_orig 与内存原始元组不符）: " + key);
            }
            group.add(record);
        }
        if (!group.isEmpty()) {
            plans.add(mergeTimerGroup(group));
        }
        return plans;
    }

    /** 组内 target/version/protocol/canonical payload 全等 → 合并（recordSeq 最大者元数据与身份字节）；任一不同 → 冲突。 */
    private static OutboxGroupPlan mergeOutboxGroup(List<OutboxRecord> group) {
        OutboxRecord latest = group.get(0);
        for (int i = 1; i < group.size(); i++) {
            OutboxRecord record = group.get(i);
            OutboxIntent a = latest.intent;
            OutboxIntent b = record.intent;
            if (!a.targetType().equals(b.targetType())
                    || !a.targetName().equals(b.targetName())
                    || a.payloadVersion() != b.payloadVersion()
                    || !a.comparisonProtocol().equals(b.comparisonProtocol())
                    || !Arrays.equals(latest.canonical, record.canonical)) {
                throw new SystemIntegrityException("意图收集器检测到DB等价键组冲突: 组内 target/version/协议/载荷不一致");
            }
            if (record.recordSeq > latest.recordSeq) {
                latest = record;
            }
        }
        OutboxIntent intent = latest.intent;
        return new OutboxGroupPlan(intent.aggregateType(), intent.aggregateId(), intent.aggregateVersion(),
                intent.eventType(), intent.targetType(), intent.targetName(), intent.payloadVersion(),
                intent.comparisonProtocol(), latest.payloadJson, intent.traceId(), intent.occurredAt());
    }

    /** 组内 version/protocol/canonical payload 全等 → 合并（recordSeq 最大者元数据与身份字节）；任一不同 → 冲突。 */
    private static TimerGroupPlan mergeTimerGroup(List<TimerRecord> group) {
        TimerRecord latest = group.get(0);
        for (int i = 1; i < group.size(); i++) {
            TimerRecord record = group.get(i);
            TimerIntent a = latest.intent;
            TimerIntent b = record.intent;
            if (a.payloadVersion() != b.payloadVersion()
                    || !a.comparisonProtocol().equals(b.comparisonProtocol())
                    || !Arrays.equals(latest.canonical, record.canonical)) {
                throw new SystemIntegrityException("意图收集器检测到DB等价键组冲突: 组内载荷协议或内容不一致");
            }
            if (record.recordSeq > latest.recordSeq) {
                latest = record;
            }
        }
        TimerIntent intent = latest.intent;
        return new TimerGroupPlan(intent.taskType(), intent.idempotencyKey(), intent.payloadVersion(),
                intent.comparisonProtocol(), latest.payloadJson, intent.fireAt());
    }

    public void recordOutboxIntent(OutboxIntent intent) {
        Objects.requireNonNull(intent, "OutboxIntent cannot be null");
        validateOutboxIntent(intent);
        TransactionContext context = requireContext();
        context.recordOutbox(intent);
    }

    public void recordTimerIntent(TimerIntent intent) {
        Objects.requireNonNull(intent, "TimerIntent cannot be null");
        validateTimerIntent(intent);
        TransactionContext context = requireContext();
        context.recordTimer(intent);
    }

    private void validateOutboxIntent(OutboxIntent intent) {
        if (!"LOCAL".equals(intent.targetType())) {
            throw new SystemIntegrityException("意图收集器仅支持LOCAL投递目标: " + intent.targetType());
        }
        if (intent.targetName() == null || intent.targetName().isEmpty()) {
            throw new IllegalArgumentException("OutboxIntent targetName不能为空");
        }
        if (intent.payloadVersion() < 1) {
            throw new IllegalArgumentException("OutboxIntent payloadVersion必须>=1: " + intent.payloadVersion());
        }
        if (!"LEGACY_JSON_V1".equals(intent.comparisonProtocol())) {
            throw new SystemIntegrityException("不支持的载荷比较协议: " + intent.comparisonProtocol());
        }
    }

    private void validateTimerIntent(TimerIntent intent) {
        if (intent.payloadVersion() != 1) {
            throw new IllegalArgumentException("TimerIntent payloadVersion必须为1: " + intent.payloadVersion());
        }
        if (!"LEGACY_JSON_V1".equals(intent.comparisonProtocol())) {
            throw new SystemIntegrityException("不支持的载荷比较协议: " + intent.comparisonProtocol());
        }
    }

    public void flush() {
        TransactionContext context = requireContext();
        context.flushNow();
    }

    /**
     * 全量资源守卫（§7.2.2(1)）：activity／synchronization／read-only／holder 同步与 rollbackOnly／
     * 代理 status／观测 Scope 存在且未 completed／在途 status 全非 rollbackOnly，任一不满足零 SQL 拒绝。
     * 错 manager／只读／rollbackOnly／外层 scope 不明一律拒绝；REQUIRED 嵌套 join 复用同一上下文。
     */
    private TransactionContext requireContext() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new SystemIntegrityException("意图收集器必须在有效写事务内使用");
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new SystemIntegrityException("意图收集器需要活动事务同步资源");
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new SystemIntegrityException("意图收集器拒绝只读事务");
        }
        Object resource = TransactionSynchronizationManager.getResource(supplyChainDataSource);
        if (!(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction()) {
            throw new SystemIntegrityException("意图收集器未绑定供应链事务连接资源");
        }
        if (proxyRollbackOnly(holder) || holder.isRollbackOnly()) {
            throw new SystemIntegrityException("意图收集器拒绝已标记回滚的事务");
        }
        checkObservationScope(holder);
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof TransactionContext ctx && ctx.owner == this) {
                if (ctx.completed || ctx.holder != holder) {
                    throw new SystemIntegrityException("意图收集器上下文事务资源归属已失效");
                }
                return ctx;
            }
        }
        TransactionContext context = new TransactionContext(this, holder);
        TransactionSynchronizationManager.registerSynchronization(context);
        return context;
    }

    /**
     * 观测 Scope 守卫：活动同步集合必须存在本 holder 的未 completed
     * {@link SupplyChainTransactionObservationManager.Scope}，且全部在途 status 非 rollbackOnly。
     */
    private void checkObservationScope(ConnectionHolder holder) {
        SupplyChainTransactionObservationManager.Scope observationScope = null;
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            if (sync instanceof SupplyChainTransactionObservationManager.Scope s
                    && s.getHolder() == holder && !s.isCompleted()) {
                observationScope = s;
                break;
            }
        }
        if (observationScope == null) {
            throw new SystemIntegrityException("意图收集器事务资源归属不匹配供应链manager");
        }
        for (TransactionStatus status : observationScope.getStatuses().keySet()) {
            if (!status.isCompleted() && status.isRollbackOnly()) {
                throw new SystemIntegrityException("意图收集器拒绝已标记回滚的事务");
            }
        }
    }

    /**
     * 代理 localRollbackOnly 尚未传播到 ConnectionHolder，必须同时检查当前代理状态（O-S07 同构）。
     */
    private boolean proxyRollbackOnly(ConnectionHolder holder) {
        try {
            TransactionStatus status = TransactionAspectSupport.currentTransactionStatus();
            if (status.isCompleted()
                    || !(status instanceof DefaultTransactionStatus current)
                    || !(current.getTransaction() instanceof JdbcTransactionObjectSupport jdbc)
                    || !jdbc.hasConnectionHolder() || jdbc.getConnectionHolder() != holder) {
                throw new SystemIntegrityException("意图收集器事务资源归属不匹配供应链manager");
            }
            return status.isRollbackOnly();
        } catch (NoTransactionException noProxy) {
            // 既有 TransactionTemplate 不是代理；真实 holder／活动同步仍由入口逐项校验。
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

    /** 从不可变缓冲字符串还原 JsonNode（仅槽执行阶段；字符串由 record 时点序列化固定）。 */
    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new SystemIntegrityException("意图载荷不可解析", e);
        }
    }

    /** Outbox 缓冲意图：record 时点序列化的不可变 String 与 canonical byte[]（深不可变）。 */
    static final class OutboxRecord {
        final OutboxIntent intent;
        final String payloadJson;
        final byte[] canonical;
        final long recordSeq;

        OutboxRecord(OutboxIntent intent, String payloadJson, byte[] canonical, long recordSeq) {
            this.intent = intent;
            this.payloadJson = payloadJson;
            this.canonical = canonical;
            this.recordSeq = recordSeq;
        }
    }

    /** Timer 缓冲意图：record 时点序列化的不可变 String 与 canonical byte[]（深不可变）。 */
    static final class TimerRecord {
        final TimerIntent intent;
        final String payloadJson;
        final byte[] canonical;
        final long recordSeq;

        TimerRecord(TimerIntent intent, String payloadJson, byte[] canonical, long recordSeq) {
            this.intent = intent;
            this.payloadJson = payloadJson;
            this.canonical = canonical;
            this.recordSeq = recordSeq;
        }
    }

    enum State {
        COLLECTING,
        FLUSHING,
        FLUSHED
    }

    /**
     * 事务上下文（包私有：flush 后写守卫拦截器与同包测试夹具核验状态/归属）。
     * 实现 TransactionSynchronization 但不得覆写 flush()（§7.2.2(5)）。
     */
    static final class TransactionContext implements TransactionSynchronization {
        private final LocalLedgerIntentCollector owner;
        private final ConnectionHolder holder;
        private boolean completed;
        private final Map<OutboxKey, OutboxRecord> outboxRecords = new LinkedHashMap<>();
        private final Map<TimerKey, TimerRecord> timerRecords = new LinkedHashMap<>();
        private long recordSeqCounter;
        private State state = State.COLLECTING;

        TransactionContext(LocalLedgerIntentCollector owner, ConnectionHolder holder) {
            this.owner = owner;
            this.holder = holder;
        }

        private long nextRecordSeq() {
            return ++recordSeqCounter;
        }

        void recordOutbox(OutboxIntent intent) {
            checkCollecting();
            OutboxKey key = new OutboxKey(intent.aggregateType(), intent.aggregateId(),
                    intent.aggregateVersion(), intent.eventType());
            // record 时点立即序列化＋canonicalBytes（预算/语法/规范化），缓冲不可变 String 与 byte[]。
            String payloadJson = owner.writeJson(intent.payload());
            byte[] canonical = LedgerJsonEncoding.canonicalBytes(payloadJson);
            OutboxRecord existing = outboxRecords.get(key);
            if (existing != null) {
                // 字节相等快路径预合并：全等（canonical＋targetName＋payloadVersion）→ 保留 recordSeq 最大者；
                // 任一不同 → 冲突（§7.2.2(3)）。targetType/comparisonProtocol 在记录时校验恒为同一常量，无需比较。
                if (!Arrays.equals(existing.canonical, canonical)
                        || !existing.intent.targetName().equals(intent.targetName())
                        || existing.intent.payloadVersion() != intent.payloadVersion()) {
                    throw new SystemIntegrityException("意图收集器检测到同键冲突: " + key);
                }
            }
            outboxRecords.put(key, new OutboxRecord(intent, payloadJson, canonical, nextRecordSeq()));
        }

        void recordTimer(TimerIntent intent) {
            checkCollecting();
            TimerKey key = new TimerKey(intent.taskType(), intent.idempotencyKey());
            // record 时点立即序列化＋canonicalBytes，缓冲不可变 String 与 byte[]。
            String payloadJson = owner.writeJson(intent.payload());
            byte[] canonical = LedgerJsonEncoding.canonicalBytes(payloadJson);
            TimerRecord existing = timerRecords.get(key);
            if (existing != null) {
                if (!Arrays.equals(existing.canonical, canonical)) {
                    throw new SystemIntegrityException("意图收集器检测到同键冲突: " + key);
                }
            }
            timerRecords.put(key, new TimerRecord(intent, payloadJson, canonical, nextRecordSeq()));
        }

        /**
         * 显式 flush 仅经 {@link LocalLedgerIntentCollector#flush()} 公共入口进入状态机；
         * 守卫同步不覆写 TransactionSynchronization.flush()（保留接口默认 no-op），
         * 防止 TransactionSynchronizationUtils.triggerFlush() 偷触发状态机重入（§7.2.2(5)）。
         */
        void flushNow() {
            if (state == State.FLUSHED || state == State.FLUSHING) {
                throw new SystemIntegrityException("同一事务内禁止重复flush");
            }
            state = State.FLUSHING;

            // 用户裁定 B（rev2）：生产 collector 对 Outbox 槽执行保持失败关闭——冻结意图集合含 Outbox
            // 意图 → 立即 SystemIntegrityException（零 DAL：任何分组 SQL／账本锁／源 DAL 之前；
            // 不静默跳过，Timer 也不得执行，整事务失败）。Outbox 协议由仅 src/test 反射桥驱动真实
            // main 底座证明；商业接线时再裁决生产执行通道（R3-02O-S 冻结边界文本不改）。
            if (!outboxRecords.isEmpty()) {
                throw new SystemIntegrityException("生产意图收集器拒绝执行Outbox槽：商业接线通道未裁决（零账本/源DAL）");
            }

            // Timer：冻结＋DB 等价合并＋按组序经 scheduleOnce 执行
            List<TimerGroupPlan> timerPlan = owner.freezeTimerPlan();
            for (TimerGroupPlan plan : timerPlan) {
                owner.persistentTimer.scheduleOnce(
                        plan.taskType(),
                        plan.idempotencyKey(),
                        owner.parseJson(plan.payloadJson()),
                        plan.fireAt()
                );
            }

            state = State.FLUSHED;
        }

        private void checkCollecting() {
            if (state != State.COLLECTING) {
                throw new SystemIntegrityException("意图收集器已flush，禁止追加新意图");
            }
        }

        /** 冻结阶段允许 COLLECTING／FLUSHING（flush 自身），FLUSHED 后禁止。 */
        void checkNotFlushed() {
            if (state == State.FLUSHED) {
                throw new SystemIntegrityException("意图收集器已flush，禁止重复操作");
            }
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            if ((!outboxRecords.isEmpty() || !timerRecords.isEmpty()) && state != State.FLUSHED) {
                throw new SystemIntegrityException("存在未flush的事务意图，提交前守卫拒绝提交");
            }
        }

        @Override
        public void afterCompletion(int status) {
            completed = true;
            outboxRecords.clear();
            timerRecords.clear();
            // 不重置回可复用 COLLECTING：本上下文为终止态，同线程后续事务必须新建上下文
        }

        /** 包私有状态/归属访问（flush 后写守卫拦截器与测试夹具核验用）。 */
        State state() {
            return state;
        }

        ConnectionHolder holder() {
            return holder;
        }

        boolean completed() {
            return completed;
        }

        boolean outboxRecordsEmpty() {
            return outboxRecords.isEmpty();
        }

        boolean timerRecordsEmpty() {
            return timerRecords.isEmpty();
        }
    }
}
