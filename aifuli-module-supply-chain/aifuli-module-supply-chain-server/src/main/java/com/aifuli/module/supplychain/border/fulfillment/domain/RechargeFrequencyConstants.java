package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 直充手机号限频规则的稳定常量。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#1-目标与已确认决策}。
 */
public final class RechargeFrequencyConstants {

    /** 成功结果释放占用前的最短时长，按占用获取时间计算。 */
    public static final long SUCCESS_MINIMUM_HOLD_SECONDS = 5L;

    /** 持续占用告警时长；达到该时长本身不触发告警。 */
    public static final long OCCUPIED_ALERT_SECONDS = 1_800L;

    /** 等待记录超过该数量时触发积压告警。 */
    public static final long BACKLOG_THRESHOLD = 5L;

    /** 手机号 HMAC 摘要用途码，与履约目标指纹用途隔离。 */
    public static final String HMAC_PURPOSE = "RECHARGE_FREQUENCY_MOBILE";

    private RechargeFrequencyConstants() {
    }
}
