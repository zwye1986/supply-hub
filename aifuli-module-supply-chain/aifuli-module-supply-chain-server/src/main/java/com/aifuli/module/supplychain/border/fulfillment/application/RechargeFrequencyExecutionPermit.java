package com.aifuli.module.supplychain.border.fulfillment.application;

/**
 * 首次认领提交后的不可变执行资格。旧执行者禁止以重新读取的代次或版本替换本快照。
 * 设计见 docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#42-认领。
 */
public record RechargeFrequencyExecutionPermit(long queueId, long entryId, String operationNo,
        long generation, long entryVersion, long operationVersion) { }
