package com.aifuli.module.supplychain.border.instore.domain;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 到店购状态枚举与 DDL CHECK 码值互逆：未知码拒绝，禁止猜测状态。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-18-rtmart-instore-channel-design.md#4-中台侧场景详情模块}。</p>
 */
class InstoreStatusEnumsTest {

    @ParameterizedTest
    @EnumSource(InstoreEntryCredentialStatus.class)
    void entryCredentialStatusRoundTripsByCode(InstoreEntryCredentialStatus status) {
        assertThat(InstoreEntryCredentialStatus.fromCode(status.getCode())).isEqualTo(status);
    }

    @Test
    void entryCredentialPurposeAndBusinessPrefixAreClosed() {
        assertThat(InstoreEntryCredentialPurpose.values())
                .containsExactly(InstoreEntryCredentialPurpose.HOME, InstoreEntryCredentialPurpose.DETAIL);
        assertThat(BusinessNoPrefixes.IEC).isEqualTo("IEC_");
        assertThat(BusinessNoPrefixes.ALL).contains(BusinessNoPrefixes.IEC);
        assertThat(BusinessNoPrefixes.ALL).contains(
                BusinessNoPrefixes.BYR, BusinessNoPrefixes.BPD,
                BusinessNoPrefixes.PRJ, BusinessNoPrefixes.PLT);
        assertThatThrownBy(() -> InstoreEntryCredentialStatus.fromCode((short) 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown instore entry credential status");
    }

    @Test
    void entryCredentialRevokeReasonsAreClosed() {
        assertThat(InstoreCredentialRevokeReason.values())
                .containsExactly(InstoreCredentialRevokeReason.ENTRY_URL_CALL_FAILED,
                        InstoreCredentialRevokeReason.MEMBER_PROFILE_RETIRED,
                        InstoreCredentialRevokeReason.MIGRATION_UNFROZEN_ROUTE);
    }

    @ParameterizedTest
    @EnumSource(InstoreOrderStatus.class)
    void orderStatusRoundTripsByCode(InstoreOrderStatus status) {
        assertThat(InstoreOrderStatus.fromCode(status.getCode())).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(InstorePaymentFlowStatus.class)
    void paymentFlowStatusRoundTripsByCode(InstorePaymentFlowStatus status) {
        assertThat(InstorePaymentFlowStatus.fromCode(status.getCode())).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(InstoreRefundStatus.class)
    void refundStatusRoundTripsByCode(InstoreRefundStatus status) {
        assertThat(InstoreRefundStatus.fromCode(status.getCode())).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(InstoreOperationStatus.class)
    void operationStatusRoundTripsByCode(InstoreOperationStatus status) {
        assertThat(InstoreOperationStatus.fromCode(status.getCode())).isEqualTo(status);
    }

    @Test
    void unknownCodesAreRejected() {
        assertThatThrownBy(() -> InstoreOrderStatus.fromCode((short) 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown instore order status");
        assertThatThrownBy(() -> InstorePaymentFlowStatus.fromCode((short) 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown instore payment flow status");
        assertThatThrownBy(() -> InstoreRefundStatus.fromCode((short) 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown instore refund status");
        assertThatThrownBy(() -> InstoreOperationStatus.fromCode((short) 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown instore operation status");
    }

    @Test
    void refundStatusesHaveNoChannelNotifyCode() {
        assertThat(InstoreRefundStatus.values())
                .extracting(InstoreRefundStatus::name)
                .doesNotContain("NOTIFYING_CHANNEL");
        assertThat(InstoreOperationType.values())
                .extracting(InstoreOperationType::name)
                .doesNotContain("CHANNEL_NOTIFY");
    }
}
