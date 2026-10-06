package com.aifuli.module.supplychain.infrastructure.configuration;

/**
 * 预算超时与中断取消异常。
 * 依据 R01 冻结规范：命令截止时间 15s、单次借连接 1500ms、准入下限 3500ms、最小可执行 SQL 1000ms。
 */
public class BudgetTimeoutException extends RuntimeException {

    public BudgetTimeoutException(String message) {
        super(message);
    }

    public BudgetTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
