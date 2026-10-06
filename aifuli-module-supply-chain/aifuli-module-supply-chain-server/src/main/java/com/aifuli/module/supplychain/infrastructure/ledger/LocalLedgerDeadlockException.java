package com.aifuli.module.supplychain.infrastructure.ledger;

/**
 * 专用技术异常：仅在纯本地不可变身份账本建槽排他锁阶段（upsert 或紧邻 SELECT FOR UPDATE）
 * 捕获到的精确死锁异常（vendorCode=1213, SQLState=40001）。
 * 设计见 docs/superpowers/specs/2026-10-05-catalog-reliable-consumption-prerequisite-design.md#v4a-纯本地完整worker事务重跑。
 */
public class LocalLedgerDeadlockException extends RuntimeException {

    public LocalLedgerDeadlockException(Throwable cause) {
        super("纯本地账本槽排他锁阶段检测到死锁 (vendorCode=1213, SQLState=40001)", cause);
    }
}
