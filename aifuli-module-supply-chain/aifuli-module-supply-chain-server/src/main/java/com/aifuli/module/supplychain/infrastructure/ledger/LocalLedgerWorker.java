package com.aifuli.module.supplychain.infrastructure.ledger;

/**
 * 纯本地完整业务事务工作者函数接口。
 */
@FunctionalInterface
public interface LocalLedgerWorker<T> {

    /**
     * 执行完整本地事务（包含前置领域写、账本排他建槽、严格源插入与原子绑定）。
     *
     * @param attemptNonce 本次尝试不可变保留标识 nonce (16 字节)
     * @param attempt      尝试序号 (1 或 2)
     * @return 业务执行结果
     */
    T execute(byte[] attemptNonce, int attempt);
}
