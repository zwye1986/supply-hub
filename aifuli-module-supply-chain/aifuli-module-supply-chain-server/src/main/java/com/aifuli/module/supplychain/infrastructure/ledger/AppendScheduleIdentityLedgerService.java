package com.aifuli.module.supplychain.infrastructure.ledger;

import java.time.LocalDateTime;

/**
 * 独立不可变身份账本排他建槽防锁环协议服务。
 * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#72-独立不可变身份账本确定消除重复追加等待源行
 * 与 #721-冻结排他建槽sql与首次所有权。
 */
public interface AppendScheduleIdentityLedgerService {

    short STATUS_RESERVED = 10;
    short STATUS_BOUND = 20;

    record TimerReservationResult(
            boolean firstReserved,
            Long slotId,
            byte[] nonce,
            Long existingTaskId,
            String existingTaskNo
    ) {
        public TimerReservationResult {
            nonce = nonce == null ? null : nonce.clone();
        }

        /** 返回防御副本；不让调用者修改实际 reservation 的保留标识。 */
        @Override
        public byte[] nonce() {
            return nonce == null ? null : nonce.clone();
        }

        public static TimerReservationResult first(Long slotId, byte[] nonce) {
            return new TimerReservationResult(true, slotId, nonce, null, null);
        }

        public static TimerReservationResult existing(Long taskId, String taskNo) {
            return new TimerReservationResult(false, null, null, taskId, taskNo);
        }
    }

    TimerReservationResult reserveTimerSlot(
            String taskType,
            String idempotencyKey,
            Integer payloadVersion,
            String comparisonProtocol,
            String payloadJson,
            LocalDateTime now);

    void bindTimerTask(Long slotId, byte[] nonce, Long taskId, String taskNo, LocalDateTime boundAt);
}
