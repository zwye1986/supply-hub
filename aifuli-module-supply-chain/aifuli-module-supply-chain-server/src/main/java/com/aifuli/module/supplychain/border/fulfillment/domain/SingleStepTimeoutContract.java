package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 三家单步骤协议随原单冻结的HTTP及执行资格时限，单位为毫秒。
 * 设计：docs/superpowers/specs/2026-10-15-renyue-qnm-szcard-detailed-design.md#46-http预算与租约的本地实施切片。
 */
public record SingleStepTimeoutContract(
        int connectTimeoutMs,
        int readWriteTimeoutMs,
        int totalCallTimeoutMs,
        int executionLeaseMs
) {

    private static final SingleStepTimeoutContract INITIAL =
            new SingleStepTimeoutContract(10_000, 20_000, 30_000, 60_000);

    /**
     * 阶段时限不能大于整次预算；认领租约须留出本地处理余量。
     * 这里只校验合同自身，不凭租约或HTTP异常推导供应商业务终态。
     */
    public SingleStepTimeoutContract {
        if (connectTimeoutMs <= 0 || readWriteTimeoutMs <= 0 || totalCallTimeoutMs <= 0
                || connectTimeoutMs > totalCallTimeoutMs || readWriteTimeoutMs > totalCallTimeoutMs
                || executionLeaseMs <= totalCallTimeoutMs) {
            throw new IllegalArgumentException("单步骤阶段时限必须为正且不超过总预算，执行租约必须大于总预算");
        }
    }

    /** 已批准的平台初值，不代表供应商SLA。 */
    public static SingleStepTimeoutContract initial() {
        return INITIAL;
    }
}
