package com.aifuli.module.supplychain.border.fulfillment.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直充手机号限频的纯领域规则。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#1-目标与已确认决策}。
 */
class RechargeFrequencyPolicyTest {

    private static final LocalDateTime ACQUIRED_AT = LocalDateTime.of(2026, 9, 30, 10, 0);

    @Test
    void releasesTrustedSuccessOnlyAfterMinimumHoldMeasuredFromAcquisition() {
        assertThat(RechargeFrequencyPolicy.canReleaseSuccess(ACQUIRED_AT, ACQUIRED_AT.plusNanos(4_999_000_000L))).isFalse();
        assertThat(RechargeFrequencyPolicy.canReleaseSuccess(ACQUIRED_AT, ACQUIRED_AT.plusSeconds(5))).isTrue();
        assertThat(RechargeFrequencyPolicy.canReleaseSuccess(ACQUIRED_AT, ACQUIRED_AT.plusSeconds(6))).isTrue();
    }

    @Test
    void missingAcquisitionTimeFailsClosedForSuccessReleaseAndOverdueCheck() {
        assertThat(RechargeFrequencyPolicy.canReleaseSuccess(null, ACQUIRED_AT.plusHours(1))).isFalse();
        assertThat(RechargeFrequencyPolicy.canReleaseSuccess(ACQUIRED_AT, null)).isFalse();
        assertThat(RechargeFrequencyPolicy.isOverdue(null, ACQUIRED_AT.plusHours(1))).isFalse();
        assertThat(RechargeFrequencyPolicy.isOverdue(ACQUIRED_AT, null)).isFalse();
    }

    @Test
    void preparedCanBeRevokedOnlyWhenEarlierExternalSendIsResolved() {
        assertThat(RechargeFrequencyPolicy.canRevokePrepared(true, RechargeFrequencyDispatchPhase.PREPARED)).isTrue();
        assertThat(RechargeFrequencyPolicy.canRevokePrepared(false, RechargeFrequencyDispatchPhase.PREPARED)).isFalse();
    }

    @Test
    void sendPossibleAndMissingPhaseCannotBeRevokedByTimeout() {
        assertThat(RechargeFrequencyPolicy.canRevokePrepared(true, RechargeFrequencyDispatchPhase.SEND_POSSIBLE)).isFalse();
        assertThat(RechargeFrequencyPolicy.canRevokePrepared(false, RechargeFrequencyDispatchPhase.SEND_POSSIBLE)).isFalse();
        assertThat(RechargeFrequencyPolicy.canRevokePrepared(true, null)).isFalse();
    }

    @Test
    void occupiedAlertRequiresMoreThanThirtyMinutes() {
        assertThat(RechargeFrequencyPolicy.isOverdue(ACQUIRED_AT, ACQUIRED_AT.plusSeconds(1_800))).isFalse();
        assertThat(RechargeFrequencyPolicy.isOverdue(ACQUIRED_AT, ACQUIRED_AT.plusSeconds(1_800).plusNanos(1))).isTrue();
    }

    @Test
    void backlogAlertRequiresMoreThanFiveWaitingRecords() {
        assertThat(RechargeFrequencyPolicy.isBacklogged(0)).isFalse();
        assertThat(RechargeFrequencyPolicy.isBacklogged(5)).isFalse();
        assertThat(RechargeFrequencyPolicy.isBacklogged(6)).isTrue();
    }

    @Test
    void appliesOnlyToDirectRechargeWithMobileTarget() {
        assertThat(RechargeFrequencyPolicy.isApplicable("DIRECT_RECHARGE", "MOBILE")).isTrue();
        assertThat(RechargeFrequencyPolicy.isApplicable("DIRECT_RECHARGE", "EXTERNAL_ACCOUNT")).isFalse();
        assertThat(RechargeFrequencyPolicy.isApplicable("CARD_CODE_SECRET", "MOBILE")).isFalse();
        assertThat(RechargeFrequencyPolicy.isApplicable(null, "MOBILE")).isFalse();
        assertThat(RechargeFrequencyPolicy.isApplicable("DIRECT_RECHARGE", null)).isFalse();
    }

    @Test
    void reusesCanonicalMobileNormalizationForChinaPrefixes() {
        assertThat(FulfillmentTargetFields.normalizeMobile("13900139000"))
                .isEqualTo(FulfillmentTargetFields.normalizeMobile("+8613900139000"))
                .isEqualTo(FulfillmentTargetFields.normalizeMobile("008613900139000"));
    }

    @Test
    void exposesStablePersistenceCodesAndHmacPurpose() {
        assertThat(RechargeFrequencyEntryStatus.WAITING.getCode()).isEqualTo((short) 0);
        assertThat(RechargeFrequencyEntryStatus.HOLDING.getCode()).isEqualTo((short) 10);
        assertThat(RechargeFrequencyEntryStatus.FINISHED.getCode()).isEqualTo((short) 20);
        assertThat(RechargeFrequencyDispatchPhase.PREPARED.getCode()).isEqualTo((short) 0);
        assertThat(RechargeFrequencyDispatchPhase.SEND_POSSIBLE.getCode()).isEqualTo((short) 10);
        assertThat(RechargeFrequencyConstants.HMAC_PURPOSE).isEqualTo("RECHARGE_FREQUENCY_MOBILE");
    }
}
