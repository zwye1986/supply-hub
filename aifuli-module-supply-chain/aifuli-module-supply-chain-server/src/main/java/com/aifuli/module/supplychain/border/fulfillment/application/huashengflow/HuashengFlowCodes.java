package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

/**
 * 华昇 checkpoint 的稳定技术契约；不在本片注册生产任务或可售能力。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#42-检查点-v1。
 */
public final class HuashengFlowCodes {
    public static final int CHECKPOINT_SCHEMA_VERSION = 1;
    /**
     * V2 在 V1 基础上新增可选 notCreatedCandidate 事实（可信未建单候选：最早拒绝码、
     * 首次拒绝时间、有效 7014 次数、查询时间与退出标记）；V1 密文按缺失字段兼容读取为无候选。
     * 设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#54-可信未建单的最终失败。
     */
    public static final int CHECKPOINT_SCHEMA_VERSION_2 = 2;
    public static final String CHECKPOINT_AAD_PURPOSE = "HUA_SHENG_CHECKPOINT";
    public static final int CHECKPOINT_MAX_BYTES = 1_048_576;
    public static final int CHECKPOINT_MAX_DEPTH = 32;
    public static final int CHECKPOINT_MAX_STRING_LENGTH = 16_384;
    public static final int DIGEST_BYTES = 32;
    /** 待核 case 摘要用途，不能复用资产指纹或回调身份用途。 */
    public static final String EXCEPTION_EVIDENCE_PURPOSE = "HUASHENG_EXCEPTION_EVIDENCE";
    public static final int TYPE201_ORDER_QUANTITY = 1;
    /** 失败回调报告的是零交付，不能用原订单数量代替。 */
    public static final int FAILED_CALLBACK_DELIVERY_QUANTITY = 0;
    public static final int EXPIRE_TIME_MIN_SECONDS = 60;
    public static final int EXPIRE_TIME_MAX_SECONDS = 1800;
    static final String INVALID_CHECKPOINT = "华昇恢复检查点无效";

    public static final String EXECUTE_TASK = com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes.HUASHENG_EXECUTE;
    public static final String LEASE_WATCHDOG_TASK = com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes.HUASHENG_LEASE_WATCHDOG;
    public static final String PREPARATION_WATCHDOG_TASK = com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes.HUASHENG_PREPARATION_WATCHDOG;
    public static final String RECOVERY_TASK = com.aifuli.module.supplychain.border.fulfillment.domain.FulfillmentTimerTaskTypes.HUASHENG_RECOVERY;

    private HuashengFlowCodes() { }
}
