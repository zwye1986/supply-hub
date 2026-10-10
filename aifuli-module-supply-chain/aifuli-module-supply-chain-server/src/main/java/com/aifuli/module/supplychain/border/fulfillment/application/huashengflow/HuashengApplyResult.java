package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

/**
 * 华昇单次结果应用执行事实。
 */
public record HuashengApplyResult(
        HuashengFlowDecision decision,
        String nextOperationNo,
        boolean applied,
        String message
) {
    public static HuashengApplyResult disqualified(String message) {
        return new HuashengApplyResult(null, null, false, message);
    }
}
