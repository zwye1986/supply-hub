package com.aifuli.module.supplychain.infrastructure.ledger;

/**
 * 账本系统完整性与防锁环协议异常。
 */
public class SystemIntegrityException extends RuntimeException {

    public SystemIntegrityException(String message) {
        super(message);
    }

    public SystemIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
