package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

/**
 * 华昇进程执行互斥锁忙异常。
 *
 * <p>职责：
 * 当尝试获取华昇单进程互斥锁失败时抛出。
 * 此异常属于未执行的可重试本地故障，必须向外抛出使 TimerEngine/重试调度器捕获并安排退避重试，
 * 绝不能吞掉或返回已完成状态。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#31-执行边界}。
 */
public class HuashengLockBusyException extends RuntimeException {

    public HuashengLockBusyException(String message) {
        super(message);
    }

    public HuashengLockBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
