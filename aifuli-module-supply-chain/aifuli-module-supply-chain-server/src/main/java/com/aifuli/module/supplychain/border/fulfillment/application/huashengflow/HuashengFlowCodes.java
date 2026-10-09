package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

/**
 * 华昇 checkpoint 的稳定技术契约；不在本片注册生产任务或可售能力。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#42-检查点-v1。
 */
public final class HuashengFlowCodes {
    public static final int CHECKPOINT_SCHEMA_VERSION = 1;
    public static final String CHECKPOINT_AAD_PURPOSE = "HUA_SHENG_CHECKPOINT";
    public static final int CHECKPOINT_MAX_BYTES = 1_048_576;
    public static final int CHECKPOINT_MAX_DEPTH = 32;
    public static final int CHECKPOINT_MAX_STRING_LENGTH = 16_384;
    public static final int DIGEST_BYTES = 32;
    public static final int TYPE201_ORDER_QUANTITY = 1;
    public static final int EXPIRE_TIME_MIN_SECONDS = 60;
    public static final int EXPIRE_TIME_MAX_SECONDS = 1800;
    static final String INVALID_CHECKPOINT = "华昇恢复检查点无效";

    private HuashengFlowCodes() { }
}
