package com.aifuli.module.supplychain.infrastructure.outbox;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.ledger.LedgerJsonEncoding;
import com.aifuli.module.supplychain.infrastructure.ledger.SystemIntegrityException;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.dataobject.OutboxAppendIdentityDO;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.OutboxAppendIdentityMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.JdbcTransactionObjectSupport;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.aifuli.module.supplychain.infrastructure.configuration.SupplyChainTransactionObservationManager;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 仅负责在调用方现有中台事务中追加 Outbox，不负责投递。
 */
@Repository
public class OutboxEventRepository {

    private final OutboxEventMapper mapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final ObjectMapper objectMapper;
    private final TrustedOutboxAppendCore trustedCore;

    public OutboxEventRepository(OutboxEventMapper mapper,
                                 BusinessNoGenerator businessNoGenerator,
                                 ObjectMapper objectMapper,
                                 OutboxAppendIdentityMapper outboxAppendIdentityMapper,
                                 @Qualifier("supplyChainDataSource") DataSource supplyChainDataSource) {
        this.mapper = mapper;
        this.businessNoGenerator = businessNoGenerator;
        this.objectMapper = objectMapper;
        this.trustedCore = new TrustedOutboxAppendCore(mapper, businessNoGenerator,
                outboxAppendIdentityMapper, supplyChainDataSource);
    }

    public String appendLocal(String aggregateType, String aggregateId, long aggregateVersion,
                              String eventType, String targetName, Object payload,
                              String traceId, String correlationId, String causationId,
                              LocalDateTime occurredAt) {
        String eventId = businessNoGenerator.next("EVT_");
        OutboxEventDO event = OutboxEventDO.builder()
                .eventId(eventId)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .aggregateVersion(aggregateVersion)
                .eventType(eventType)
                .targetType("LOCAL")
                .targetName(targetName)
                .payloadVersion(1)
                .payloadJson(writePayload(payload))
                .availableAt(occurredAt)
                .status((short) 0)
                .attemptCount(0)
                .maxAttempts(20)
                .fencingToken(0L)
                .traceId(traceId)
                .correlationId(correlationId)
                .causationId(causationId)
                .createdAt(occurredAt)
                .updatedAt(occurredAt)
                .build();
        mapper.insert(event);
        return eventId;
    }

    /**
     * B 消费流身份账本幂等追加入口（仅返回 eventId）。
     * 设计：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#721。
     * BCS 业务闭合 Schema 与投影注册尚未冻结：本入口在账本／源 DAL 前稳定失败关闭，
     * 连同已有 BOUND 也不能绕过；不暂用 LEGACY、不虚构 BCS 载荷。
     */
    public String appendLocalIdempotent(String aggregateType, String aggregateId, Long aggregateVersion,
                                        String eventType, String targetName, Integer payloadVersion,
                                        String comparisonProtocol, String payloadJson,
                                        String traceId, LocalDateTime occurredAt) {
        throw new SystemIntegrityException("B身份账本幂等追加入口未启用：BCS业务Schema与投影注册尚未冻结");
    }

    private String writePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Outbox payload cannot be serialized", exception);
        }
    }

    /**
     * 可信源追加底座：真实执行账本排他建槽、当前锁读、源严格 INSERT、真实读回核验、
     * 绑定 CAS 与提交守卫。私有嵌套，不注册独立 Spring bean，不设 public 工厂。
     * 唯一新增跨包生产调用者为账本服务 Timer 路径既有调用者之外的底座（D2）。
     */
    private static final class TrustedOutboxAppendCore {

        private static final short STATUS_RESERVED = 10;
        private static final short STATUS_BOUND = 20;
        private static final short SOURCE_STATUS_PENDING = 0;
        private static final int SOURCE_MAX_ATTEMPTS = 20;

        private final OutboxEventMapper sourceMapper;
        private final BusinessNoGenerator businessNoGenerator;
        private final OutboxAppendIdentityMapper ledgerMapper;
        private final DataSource supplyChainDataSource;

        private TrustedOutboxAppendCore(OutboxEventMapper sourceMapper,
                                        BusinessNoGenerator businessNoGenerator,
                                        OutboxAppendIdentityMapper ledgerMapper,
                                        DataSource supplyChainDataSource) {
            this.sourceMapper = sourceMapper;
            this.businessNoGenerator = businessNoGenerator;
            this.ledgerMapper = ledgerMapper;
            this.supplyChainDataSource = supplyChainDataSource;
        }

        /**
         * 完整追加：解析／预算已由已验证命令构造完成（先于 DAL），此处按 §7.2.1 冻结协议执行。
         * 顺序固定：事务守卫 → 生成 nonce → 单行排他建槽（不靠行数判首次）→ 当前锁读
         * → 判态（RESERVED 且 nonce 匹配为首次；BOUND 走重复比较；其它完整性失败）。
         */
        private String append(VerifiedAppendCommand command) {
            requireTransactionBound();
            byte[] nonce = generateNonce();
            OutboxAppendIdentityDO slot = OutboxAppendIdentityDO.builder()
                    .aggregateType(command.aggregateType())
                    .aggregateId(command.aggregateId())
                    .aggregateVersion(command.aggregateVersion())
                    .eventType(command.eventType())
                    .bindingStatus(STATUS_RESERVED)
                    .reservationNonce(nonce)
                    .targetType(command.targetType())
                    .targetName(command.targetName())
                    .payloadVersion(command.payloadVersion())
                    .comparisonProtocol(command.comparisonProtocol())
                    .payloadJson(command.payloadJson())
                    .createdAt(truncateMillis(command.occurredAt()))
                    .build();
            ledgerMapper.insertSlotOnDuplicateKeyUpdate(slot);
            OutboxAppendIdentityDO row = ledgerMapper.selectForUpdate(
                    command.aggregateType(), command.aggregateId(),
                    command.aggregateVersion(), command.eventType());
            if (row == null) {
                throw new SystemIntegrityException("Outbox身份账本槽行不存在: aggregateType="
                        + command.aggregateType() + ", aggregateId=" + command.aggregateId());
            }
            String key = command.aggregateType() + "|" + command.aggregateId() + "|"
                    + command.aggregateVersion() + "|" + command.eventType();
            if (Objects.equals(row.getBindingStatus(), STATUS_RESERVED)) {
                if (Arrays.equals(row.getReservationNonce(), nonce)) {
                    if (transactionGuard(false) != null && transactionGuard(false).pending.containsKey(key)) {
                        throw new SystemIntegrityException("拒绝重入未完成账本槽: " + key);
                    }
                    guardReservation(key);
                    return bindFirst(command, row, nonce, key);
                }
                throw new SystemIntegrityException("Outbox身份账本存在并发保留冲突或未完成保留槽: aggregateType="
                        + command.aggregateType() + ", aggregateId=" + command.aggregateId());
            }
            if (Objects.equals(row.getBindingStatus(), STATUS_BOUND)) {
                return returnExisting(command, row);
            }
            throw new SystemIntegrityException("未知Outbox身份账本状态: " + row.getBindingStatus());
        }

        private byte[] generateNonce() {
            byte[] nonce = new byte[16];
            ThreadLocalRandom.current().nextBytes(nonce);
            return nonce;
        }

        private String bindFirst(VerifiedAppendCommand command, OutboxAppendIdentityDO slot,
                                 byte[] nonce, String key) {
            String eventId = businessNoGenerator.next(BusinessNoPrefixes.EVT);
            OutboxEventDO event = OutboxEventDO.builder()
                    .eventId(eventId)
                    .aggregateType(command.aggregateType())
                    .aggregateId(command.aggregateId())
                    .aggregateVersion(command.aggregateVersion())
                    .eventType(command.eventType())
                    .targetType(command.targetType())
                    .targetName(command.targetName())
                    .payloadVersion(command.payloadVersion())
                    .payloadJson(command.payloadJson())
                    .availableAt(truncateMillis(command.occurredAt()))
                    .status(SOURCE_STATUS_PENDING)
                    .attemptCount(0)
                    .maxAttempts(SOURCE_MAX_ATTEMPTS)
                    .fencingToken(0L)
                    .traceId(command.traceId())
                    .correlationId(command.correlationId())
                    .causationId(command.causationId())
                    .createdAt(truncateMillis(command.occurredAt()))
                    .updatedAt(truncateMillis(command.occurredAt()))
                    .build();
            int inserted = sourceMapper.insert(event);
            if (inserted != 1) {
                throw new SystemIntegrityException("源Outbox事件插入行数不为1: inserted=" + inserted);
            }
            OutboxEventDO actual = sourceMapper.selectByIdForUpdate(event.getId());
            verifySourceRow(command, eventId, actual);
            BindingProof proof = new BindingProof(slot.getId(), nonce, eventId);
            LocalDateTime boundAt = LocalDateTime.now();
            int affected = ledgerMapper.bindEventCas(proof.slotId(), proof.nonceCopy(), proof.eventId(), boundAt);
            if (affected != 1) {
                throw new SystemIntegrityException("Outbox身份账本绑定CAS更新行数不为1: slotId="
                        + proof.slotId() + ", affected=" + affected);
            }
            transactionGuard(false).pending.get(key).proof = proof;
            markBound(key);
            return eventId;
        }

        /**
         * 重复路径：仅当前锁读账本；源 SELECT/INSERT/锁均为 0。旧载荷资源准入先于比较，
         * 不因 target/version 差异短路；预算超限保留 cause。
         */
        private String returnExisting(VerifiedAppendCommand command, OutboxAppendIdentityDO row) {
            LedgerJsonEncoding.canonicalBytes(row.getPayloadJson());
            String existing = row.getEventId();
            if (existing == null || existing.isEmpty()) {
                throw new SystemIntegrityException("Outbox身份账本BOUND映射非法eventId: aggregateType="
                        + command.aggregateType() + ", aggregateId=" + command.aggregateId());
            }
            boolean same = Objects.equals(command.targetType(), row.getTargetType())
                    && Objects.equals(command.targetName(), row.getTargetName())
                    && Objects.equals(command.payloadVersion(), row.getPayloadVersion())
                    && Objects.equals(command.comparisonProtocol(), row.getComparisonProtocol())
                    && LedgerJsonEncoding.legacyMatches(command.payloadJson(), row.getPayloadJson());
            if (!same) {
                throw new SystemIntegrityException("Outbox身份账本载荷协议或内容冲突: aggregateType="
                        + command.aggregateType() + ", aggregateId=" + command.aggregateId());
            }
            return existing;
        }

        /**
         * 同一事务连接按刚插入的源内部 id 当前锁读真实源并逐字段核验，
         * 不信任可变 DO 或 generated key 单独证明。
         */
        private void verifySourceRow(VerifiedAppendCommand command, String eventId, OutboxEventDO actual) {
            if (actual == null) {
                throw new SystemIntegrityException("源Outbox事件行缺失: eventId=" + eventId);
            }
            if (!Objects.equals(eventId, actual.getEventId())) {
                throw new SystemIntegrityException("源事件编号与本次分配不一致: expected=" + eventId
                        + ", actual=" + actual.getEventId());
            }
            if (!Objects.equals(command.aggregateType(), actual.getAggregateType())
                    || !Objects.equals(command.aggregateId(), actual.getAggregateId())
                    || !Objects.equals(command.aggregateVersion(), actual.getAggregateVersion())
                    || !Objects.equals(command.eventType(), actual.getEventType())) {
                throw new SystemIntegrityException("源事件四列聚合身份与本次命令不一致: eventId=" + eventId);
            }
            if (!"LOCAL".equals(actual.getTargetType())) {
                throw new SystemIntegrityException("源事件targetType非LOCAL: eventId=" + eventId);
            }
            if (!Objects.equals(command.targetName(), actual.getTargetName())) {
                throw new SystemIntegrityException("源事件targetName与本次命令不一致: eventId=" + eventId);
            }
            if (!Objects.equals(command.payloadVersion(), actual.getPayloadVersion())) {
                throw new SystemIntegrityException("源事件payloadVersion与本次命令不一致: eventId=" + eventId);
            }
            if (!LedgerJsonEncoding.legacyMatches(command.payloadJson(), actual.getPayloadJson())) {
                throw new SystemIntegrityException("源事件完整载荷与本次命令不一致: eventId=" + eventId);
            }
            if (!Objects.equals(SOURCE_STATUS_PENDING, actual.getStatus())) {
                throw new SystemIntegrityException("源事件初始状态非PENDING: eventId=" + eventId);
            }
            if (!Objects.equals(0, actual.getAttemptCount())) {
                throw new SystemIntegrityException("源事件初始attemptCount非0: eventId=" + eventId);
            }
            if (!Objects.equals(0L, actual.getFencingToken())) {
                throw new SystemIntegrityException("源事件初始fencingToken非0: eventId=" + eventId);
            }
            if (!Objects.equals(command.traceId(), actual.getTraceId())) {
                throw new SystemIntegrityException("源事件traceId与本次命令不一致: eventId=" + eventId);
            }
            if (!Objects.equals(command.correlationId(), actual.getCorrelationId())) {
                throw new SystemIntegrityException("源事件correlationId与本次命令不一致: eventId=" + eventId);
            }
            if (!Objects.equals(command.causationId(), actual.getCausationId())) {
                throw new SystemIntegrityException("源事件causationId与本次命令不一致: eventId=" + eventId);
            }
            if (actual.getAvailableAt() == null
                    || !actual.getAvailableAt().equals(truncateMillis(command.occurredAt()))) {
                throw new SystemIntegrityException("源事件availableAt非法或与计划不一致: eventId=" + eventId);
            }
            if (actual.getCreatedAt() == null
                    || !actual.getCreatedAt().equals(truncateMillis(command.occurredAt()))) {
                throw new SystemIntegrityException("源事件createdAt非法或与命令不一致: eventId=" + eventId);
            }
            if (actual.getUpdatedAt() == null
                    || !actual.getUpdatedAt().equals(truncateMillis(command.occurredAt()))) {
                throw new SystemIntegrityException("源事件updatedAt非法或与命令不一致: eventId=" + eventId);
            }
        }

        private void requireTransactionBound() {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new SystemIntegrityException("可信追加底座必须在有效写事务内执行");
            }
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                throw new SystemIntegrityException("可信追加底座需要活动事务同步资源");
            }
            if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                throw new SystemIntegrityException("可信追加底座拒绝只读事务");
            }
            Object resource = TransactionSynchronizationManager.getResource(supplyChainDataSource);
            if (!(resource instanceof ConnectionHolder holder) || !holder.isSynchronizedWithTransaction()) {
                throw new SystemIntegrityException("可信追加底座未绑定供应链事务连接资源");
            }
            if (proxyRollbackOnly(holder) || holder.isRollbackOnly()) {
                throw new SystemIntegrityException("可信追加底座拒绝已标记回滚的事务");
            }
            checkObservationScope(holder);
        }

        private void checkObservationScope(ConnectionHolder holder) {
            SupplyChainTransactionObservationManager.Scope observationScope = null;
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                if (sync instanceof SupplyChainTransactionObservationManager.Scope s
                        && s.getHolder() == holder
                        && !s.isCompleted()) {
                    observationScope = s;
                    break;
                }
            }
            if (observationScope == null) {
                throw new SystemIntegrityException("可信追加底座事务资源归属不匹配供应链manager");
            }
            for (TransactionStatus status : observationScope.getStatuses().keySet()) {
                if (!status.isCompleted() && status.isRollbackOnly()) {
                    throw new SystemIntegrityException("可信追加底座拒绝已标记回滚的事务");
                }
            }
        }

        /**
         * 代理localRollbackOnly尚未传播到ConnectionHolder，必须同时检查当前代理状态。
         * 设计：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md
         * #2026-10-06-o-s01o-s07有界代理检查点实现细化非验收。
         */
        private boolean proxyRollbackOnly(ConnectionHolder holder) {
            try {
                var status = TransactionAspectSupport.currentTransactionStatus();
                if (status.isCompleted()
                        || !(status instanceof DefaultTransactionStatus current)
                        || !(current.getTransaction() instanceof JdbcTransactionObjectSupport jdbc)
                        || !jdbc.hasConnectionHolder() || jdbc.getConnectionHolder() != holder) {
                    throw new SystemIntegrityException("可信追加底座事务资源归属不匹配供应链manager");
                }
                return status.isRollbackOnly();
            } catch (NoTransactionException noProxy) {
                // 既有TransactionTemplate不是代理；真实holder/活动同步仍由入口逐项校验。
                return false;
            }
        }

        /**
         * guard属于活动同步集合与实际holder，不属于线程；挂起/结束事务不能污染新事务。
         * 设计：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md
         * #2026-10-06-o-s01o-s07有界代理检查点实现细化非验收。
         */
        private TransactionGuard transactionGuard(boolean create) {
            requireTransactionBound();
            ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(supplyChainDataSource);
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                if (synchronization instanceof TransactionGuard guard && guard.owner == this) {
                    if (guard.completed || guard.holder != holder) {
                        throw new SystemIntegrityException("可信追加guard事务资源归属已失效");
                    }
                    return guard;
                }
            }
            if (!create) {
                return null;
            }
            TransactionGuard guard = new TransactionGuard(holder);
            TransactionSynchronizationManager.registerSynchronization(guard);
            return guard;
        }

        private void guardReservation(String key) {
            TransactionGuard guard = transactionGuard(true);
            if (guard.pending.putIfAbsent(key, new ReservationState()) != null) {
                throw new SystemIntegrityException("拒绝重入未完成账本槽: " + key);
            }
        }

        private void markBound(String key) {
            TransactionGuard guard = transactionGuard(false);
            ReservationState state = guard == null ? null : guard.pending.get(key);
            if (state == null || state.proof == null || state.bound) {
                throw new SystemIntegrityException("可信追加guard拒绝未验证或重复markBound: " + key);
            }
            state.bound = true;
        }

        private static final class ReservationState {
            private BindingProof proof;
            private boolean bound;
        }

        /** 仅真实源核验及CAS1后的私有证明允许提交；异常与完成均由同步生命周期收敛。 */
        private final class TransactionGuard implements TransactionSynchronization {
            private final TrustedOutboxAppendCore owner = TrustedOutboxAppendCore.this;
            private final ConnectionHolder holder;
            private final Map<String, ReservationState> pending = new LinkedHashMap<>();
            private boolean completed;

            private TransactionGuard(ConnectionHolder holder) {
                this.holder = holder;
            }

            @Override
            public void beforeCommit(boolean readOnly) {
                // Spring提交阶段可能已恢复外层代理状态；此处只核本同步捕获的真实事务资源。
                if (!TransactionSynchronizationManager.isActualTransactionActive()
                        || !TransactionSynchronizationManager.isSynchronizationActive()
                        || readOnly || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                        || holder.isRollbackOnly()) {
                    throw new SystemIntegrityException("可信追加guard提交时事务资源或写资格已失效");
                }
                if (completed || TransactionSynchronizationManager.getResource(supplyChainDataSource) != holder) {
                    throw new SystemIntegrityException("可信追加guard事务资源归属已失效");
                }
                for (Map.Entry<String, ReservationState> reservation : pending.entrySet()) {
                    if (!reservation.getValue().bound || reservation.getValue().proof == null) {
                        throw new SystemIntegrityException("事务提交前守卫拒绝残留RESERVED账本槽: " + reservation.getKey());
                    }
                }
            }

            @Override
            public void afterCompletion(int status) {
                completed = true;
                pending.clear();
            }
        }

        private static LocalDateTime truncateMillis(LocalDateTime value) {
            return value.truncatedTo(ChronoUnit.MILLIS);
        }
    }

    /**
     * 深不可变已验证追加命令：构造即完成非空、长度、协议注册与 Q-R3-01-01 资源预算校验
     * （先于任何 DAL）。fixture 不得提供 eventId／slotId／nonce／绑定证明。
     */
    private static final class VerifiedAppendCommand {

        private final String aggregateType;
        private final String aggregateId;
        private final Long aggregateVersion;
        private final String eventType;
        private final String targetType;
        private final String targetName;
        private final Integer payloadVersion;
        private final String comparisonProtocol;
        private final String payloadJson;
        private final String traceId;
        private final String correlationId;
        private final String causationId;
        private final LocalDateTime occurredAt;

        private VerifiedAppendCommand(String aggregateType, String aggregateId, Long aggregateVersion,
                                      String eventType, String targetType, String targetName,
                                      Integer payloadVersion, String comparisonProtocol, String payloadJson,
                                      String traceId, String correlationId, String causationId,
                                      LocalDateTime occurredAt) {
            this.aggregateType = requireNonEmpty(aggregateType, "aggregateType", 64);
            this.aggregateId = requireNonEmpty(aggregateId, "aggregateId", 64);
            this.aggregateVersion = Objects.requireNonNull(aggregateVersion, "aggregateVersion不能为null");
            if (aggregateVersion < 0) {
                throw new IllegalArgumentException("aggregateVersion不能为负: " + aggregateVersion);
            }
            this.eventType = requireNonEmpty(eventType, "eventType", 128);
            if (!"LOCAL".equals(targetType)) {
                throw new SystemIntegrityException("可信追加底座仅支持LOCAL投递目标: " + targetType);
            }
            this.targetType = targetType;
            this.targetName = requireNonEmpty(targetName, "targetName", 128);
            this.payloadVersion = Objects.requireNonNull(payloadVersion, "payloadVersion不能为null");
            if (payloadVersion < 1) {
                throw new IllegalArgumentException("payloadVersion必须>=1: " + payloadVersion);
            }
            if (!"LEGACY_JSON_V1".equals(comparisonProtocol)) {
                if ("BCS_JSON_V1".equals(comparisonProtocol)) {
                    throw new SystemIntegrityException("BCS业务Schema与投影注册尚未冻结，拒绝构造B命令");
                }
                throw new SystemIntegrityException("不支持的载荷比较协议: " + comparisonProtocol);
            }
            this.comparisonProtocol = comparisonProtocol;
            this.payloadJson = Objects.requireNonNull(payloadJson, "payloadJson不能为null");
            LedgerJsonEncoding.canonicalBytes(payloadJson);
            this.traceId = requireNonEmpty(traceId, "traceId", 64);
            this.correlationId = nullableBounded(correlationId, "correlationId", 64);
            this.causationId = nullableBounded(causationId, "causationId", 64);
            this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt不能为null");
        }

        private String aggregateType() {
            return aggregateType;
        }

        private String aggregateId() {
            return aggregateId;
        }

        private Long aggregateVersion() {
            return aggregateVersion;
        }

        private String eventType() {
            return eventType;
        }

        private String targetType() {
            return targetType;
        }

        private String targetName() {
            return targetName;
        }

        private Integer payloadVersion() {
            return payloadVersion;
        }

        private String comparisonProtocol() {
            return comparisonProtocol;
        }

        private String payloadJson() {
            return payloadJson;
        }

        private String traceId() {
            return traceId;
        }

        private String correlationId() {
            return correlationId;
        }

        private String causationId() {
            return causationId;
        }

        private LocalDateTime occurredAt() {
            return occurredAt;
        }

        private static String requireNonEmpty(String value, String name, int maxCodePoints) {
            Objects.requireNonNull(value, name + "不能为null");
            if (value.isEmpty()) {
                throw new IllegalArgumentException(name + "不能为空");
            }
            int codePoints = value.codePointCount(0, value.length());
            if (codePoints > maxCodePoints) {
                throw new IllegalArgumentException(name + "码点长度超限: " + codePoints + " > " + maxCodePoints);
            }
            return value;
        }

        private static String nullableBounded(String value, String name, int maxCodePoints) {
            if (value == null) {
                return null;
            }
            int codePoints = value.codePointCount(0, value.length());
            if (codePoints > maxCodePoints) {
                throw new IllegalArgumentException(name + "码点长度超限: " + codePoints + " > " + maxCodePoints);
            }
            return value;
        }
    }

    /**
     * 私有映射绑定证明：只取本事务持有的真实槽／nonce／源 eventId；nonce 防御复制，
     * 不得输出保留标识原值。
     */
    private static final class BindingProof {

        private final Long slotId;
        private final byte[] nonce;
        private final String eventId;

        private BindingProof(Long slotId, byte[] nonce, String eventId) {
            this.slotId = Objects.requireNonNull(slotId, "slotId不能为null");
            this.nonce = Objects.requireNonNull(nonce, "nonce不能为null").clone();
            this.eventId = Objects.requireNonNull(eventId, "eventId不能为null");
        }

        private Long slotId() {
            return slotId;
        }

        private byte[] nonceCopy() {
            return nonce.clone();
        }

        private String eventId() {
            return eventId;
        }
    }
}
