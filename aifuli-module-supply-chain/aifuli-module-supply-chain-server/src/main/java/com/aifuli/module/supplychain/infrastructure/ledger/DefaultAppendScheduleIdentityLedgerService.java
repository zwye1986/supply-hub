package com.aifuli.module.supplychain.infrastructure.ledger;

import com.aifuli.module.supplychain.infrastructure.ledger.dal.dataobject.TimerScheduleIdentityDO;
import com.aifuli.module.supplychain.infrastructure.ledger.dal.mysql.TimerScheduleIdentityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独立不可变身份账本排他建槽防锁环协议服务默认实现。
 * 设计依据：docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#72-独立不可变身份账本确定消除重复追加等待源行
 * 与 #721-冻结排他建槽sql与首次所有权。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultAppendScheduleIdentityLedgerService implements AppendScheduleIdentityLedgerService {

    private final TimerScheduleIdentityMapper timerScheduleIdentityMapper;

    private final ThreadLocal<Map<String, AtomicBoolean>> pendingReservations =
            ThreadLocal.withInitial(LinkedHashMap::new);

    @Override
    public TimerReservationResult reserveTimerSlot(
            String taskType,
            String idempotencyKey,
            Integer payloadVersion,
            String comparisonProtocol,
            String payloadJson,
            LocalDateTime now) {
        // 0. 事务与比较协议守卫
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new SystemIntegrityException("排他建槽防锁环协议必须在有效业务事务内执行");
        }
        if (!"LEGACY_JSON_V1".equals(comparisonProtocol)) {
            throw new SystemIntegrityException("不支持的载荷比较协议: " + comparisonProtocol);
        }
        // 首次与重复都先验证完整JSON；不允许坏载荷进入RESERVED或走文本相等捷径。
        LedgerJsonEncoding.canonicalBytes(payloadJson);

        // 1. 生成本次保留标识不可变 nonce (16 字节)
        byte[] nonce = generateNonce();

        // 2. 单槽排他建槽：INSERT ... ON DUPLICATE KEY UPDATE id=id（显式省略 id）
        TimerScheduleIdentityDO entity = TimerScheduleIdentityDO.builder()
                .taskType(taskType)
                .idempotencyKey(idempotencyKey)
                .bindingStatus(STATUS_RESERVED)
                .reservationNonce(nonce)
                .payloadVersion(payloadVersion)
                .comparisonProtocol(comparisonProtocol)
                .payloadJson(payloadJson)
                .createdAt(now)
                .build();
        try {
            timerScheduleIdentityMapper.insertSlotOnDuplicateKeyUpdate(entity);
        } catch (Throwable e) {
            if (LocalLedgerDeadlockDetector.isExactDeadlock(e)) {
                throw new LocalLedgerDeadlockException(e);
            }
            throw e;
        }

        // 3. 执行后立即当前锁读 SELECT ... FOR UPDATE 该槽
        TimerScheduleIdentityDO row;
        try {
            row = timerScheduleIdentityMapper.selectForUpdate(taskType, idempotencyKey);
        } catch (Throwable e) {
            if (LocalLedgerDeadlockDetector.isExactDeadlock(e)) {
                throw new LocalLedgerDeadlockException(e);
            }
            throw e;
        }
        if (row == null) {
            throw new SystemIntegrityException("Timer身份账本槽行不存在: taskType="
                    + taskType + ", key=" + idempotencyKey);
        }

        // 4. 判定槽状态与所有权
        if (Objects.equals(row.getBindingStatus(), STATUS_RESERVED)) {
            if (Arrays.equals(row.getReservationNonce(), nonce)) {
                // 首次保留者，注册事务提交前守卫
                guardReservation(row.getId(), "TIMER");
                return TimerReservationResult.first(row.getId(), nonce);
            } else {
                throw new SystemIntegrityException("Timer身份账本存在并发保留冲突或未完成保留槽: taskType="
                        + taskType + ", key=" + idempotencyKey);
            }
        } else if (Objects.equals(row.getBindingStatus(), STATUS_BOUND)) {
            // 旧载荷资源准入不受target/version短路；超限保cause而非普通不匹配。
            LedgerJsonEncoding.canonicalBytes(row.getPayloadJson());
            // 已绑定槽：严格比对 payloadVersion, comparisonProtocol 及 payloadJson
            boolean match = Objects.equals(payloadVersion, row.getPayloadVersion())
                    && Objects.equals(comparisonProtocol, row.getComparisonProtocol())
                    && isPayloadMatch(payloadJson, row.getPayloadJson(), comparisonProtocol);
            if (match) {
                return TimerReservationResult.existing(row.getTaskId(), row.getTaskNo());
            } else {
                throw new SystemIntegrityException("Timer身份账本载荷协议或内容冲突: taskType="
                        + taskType + ", key=" + idempotencyKey);
            }
        } else {
            throw new SystemIntegrityException("未知Timer身份账本状态: " + row.getBindingStatus());
        }
    }

    @Override
    public void bindTimerTask(Long slotId, byte[] nonce, Long taskId, String taskNo, LocalDateTime boundAt) {
        int affected = timerScheduleIdentityMapper.bindTaskCas(slotId, nonce, taskId, taskNo, boundAt);
        if (affected != 1) {
            throw new SystemIntegrityException("Timer身份账本绑定CAS更新行数不为1: slotId="
                    + slotId + ", affected=" + affected);
        }
        markBound(slotId, "TIMER");
    }

    private byte[] generateNonce() {
        byte[] nonce = new byte[16];
        ThreadLocalRandom.current().nextBytes(nonce);
        return nonce;
    }

    private boolean isPayloadMatch(String expectedJson, String actualJson, String protocol) {
        if (!"LEGACY_JSON_V1".equals(protocol)) {
            throw new SystemIntegrityException("不支持的载荷比较协议: " + protocol);
        }
        return LedgerJsonEncoding.legacyMatches(expectedJson, actualJson);
    }

    private void guardReservation(Long slotId, String type) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            String key = type + ":" + slotId;
            AtomicBoolean bound = new AtomicBoolean(false);
            pendingReservations.get().put(key, bound);

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    if (!bound.get()) {
                        throw new SystemIntegrityException("事务提交前守卫拒绝残留RESERVED账本槽: " + key);
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    Map<String, AtomicBoolean> map = pendingReservations.get();
                    if (map != null) {
                        map.remove(key);
                        if (map.isEmpty()) {
                            pendingReservations.remove();
                        }
                    }
                }
            });
        }
    }

    private void markBound(Long slotId, String type) {
        String key = type + ":" + slotId;
        Map<String, AtomicBoolean> map = pendingReservations.get();
        if (map != null) {
            AtomicBoolean bound = map.get(key);
            if (bound != null) {
                bound.set(true);
            }
        }
    }
}
