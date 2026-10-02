package com.aifuli.module.supplychain.border.fulfillment.domain;

import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;

import java.time.LocalDateTime;

/**
 * 直充手机号全局限频的纯领域规则。调用方只可在得到可信成功结果后使用成功释放判断；超时或未知结果不能据此释放占用。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#5-结果窗口和资金}。
 */
public final class RechargeFrequencyPolicy {

    private RechargeFrequencyPolicy() {
    }

    /**
     * 判断可信成功结果是否已达到最短占用窗口。窗口从实际获取占用时刻计算，而非结果返回时刻。
     * 缺少任一时间时保守地不释放；本规则本身不判断结果是否可信。
     */
    public static boolean canReleaseSuccess(LocalDateTime acquiredAt, LocalDateTime now) {
        return acquiredAt != null && now != null
                && !now.isBefore(acquiredAt.plusSeconds(RechargeFrequencyConstants.SUCCESS_MINIMUM_HOLD_SECONDS));
    }

    /**
     * PREPARED 仅在确认不存在更早的未决外部发送时可撤销；SEND_POSSIBLE 不能因超时撤销。
     */
    public static boolean canRevokePrepared(
            boolean noUnresolvedEarlierSend,
            RechargeFrequencyDispatchPhase phase) {
        return noUnresolvedEarlierSend && phase == RechargeFrequencyDispatchPhase.PREPARED;
    }

    /**
     * 判断持有时间是否严格超过告警阈值。达到阈值的时刻不告警；缺少时间或时钟早于获取时间时不告警。
     */
    public static boolean isOverdue(LocalDateTime acquiredAt, LocalDateTime now) {
        return acquiredAt != null && now != null
                && now.isAfter(acquiredAt.plusSeconds(RechargeFrequencyConstants.OCCUPIED_ALERT_SECONDS));
    }

    /** 等待记录数量严格超过积压阈值时触发告警。 */
    public static boolean isBacklogged(long waitingCount) {
        return waitingCount > RechargeFrequencyConstants.BACKLOG_THRESHOLD;
    }

    /**
     * 仅标准直充且目标账户类型为 MOBILE 时使用手机号全局限频；卡密及其它履约类型不参与。
     */
    public static boolean isApplicable(String fulfillmentType, String accountType) {
        return FulfillmentType.DIRECT_RECHARGE.code().equals(fulfillmentType)
                && FulfillmentTargetFields.ACCOUNT_TYPE_MOBILE.equals(accountType);
    }
}
