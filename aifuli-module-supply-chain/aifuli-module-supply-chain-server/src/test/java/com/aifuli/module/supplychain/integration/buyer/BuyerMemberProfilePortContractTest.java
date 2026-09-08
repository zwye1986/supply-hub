package com.aifuli.module.supplychain.integration.buyer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 采购商会员资料端口契约测试，确保外部会员身份始终保持字符串三元组且不暴露 SaaS 内部主键。
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-08-22-instore-project-member-entry-design.md#BuyerMemberProfilePort}。</p>
 */
class BuyerMemberProfilePortContractTest {

    private static final String BUYER_USER_ID_SENTINEL = "001234";
    private static final String MOBILE_SENTINEL = "13800138000";
    private static final String USER_NAME_SENTINEL = "会员甲";
    private static final String SOURCE_SYSTEM = "AIFULI_MALL";
    private static final String EXTERNAL_TENANT_ID = "TENANT-SECRET-SENTINEL";
    private static final String TRANSPORT_MODE = "RPC";
    private static final String CONTRACT_VERSION = "v1";

    @Test
    void commandPreservesOpaqueLeadingZeroIdentityAndRouteCoordinates() {
        BuyerIntegrationRoute route = memberRoute();

        BuyerMemberProfilePort.ResolveMemberCommand command =
                new BuyerMemberProfilePort.ResolveMemberCommand(route, BUYER_USER_ID_SENTINEL);

        assertThat(command.buyerUserId()).isEqualTo(BUYER_USER_ID_SENTINEL);
        assertThat(command.route().sourceSystem()).isEqualTo(SOURCE_SYSTEM);
        assertThat(command.route().externalTenantId()).isEqualTo(EXTERNAL_TENANT_ID);
        assertThat(command.route()).isSameAs(route);
    }

    @Test
    void diagnosticsRedactBuyerIdentityAndMemberPersonalInformation() {
        BuyerMemberProfilePort.ResolveMemberCommand command =
                new BuyerMemberProfilePort.ResolveMemberCommand(memberRoute(), BUYER_USER_ID_SENTINEL);
        BuyerMemberProfilePort.MemberProfileResult result = new BuyerMemberProfilePort.MemberProfileResult(
                BuyerMemberProfilePort.MemberResolutionStatus.FOUND, MOBILE_SENTINEL, USER_NAME_SENTINEL);

        assertThat(command.toString()).doesNotContain(BUYER_USER_ID_SENTINEL, EXTERNAL_TENANT_ID);
        assertThat(result.toString()).doesNotContain(MOBILE_SENTINEL, USER_NAME_SENTINEL);
    }

    @Test
    void commandRejectsNullOrNonMemberProfileRoute() {
        assertThatThrownBy(() -> new BuyerMemberProfilePort.ResolveMemberCommand(null, BUYER_USER_ID_SENTINEL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEMBER_PROFILE");
        assertThatThrownBy(() -> new BuyerMemberProfilePort.ResolveMemberCommand(
                cashierRoute(), BUYER_USER_ID_SENTINEL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEMBER_PROFILE");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void commandRejectsBlankBuyerUserId(String buyerUserId) {
        assertThatThrownBy(() -> new BuyerMemberProfilePort.ResolveMemberCommand(memberRoute(), buyerUserId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("buyerUserId");
    }

    @Test
    void resultRequiresStatusWithoutInventingInternalMemberIdentity() {
        BuyerMemberProfilePort.MemberProfileResult result = new BuyerMemberProfilePort.MemberProfileResult(
                BuyerMemberProfilePort.MemberResolutionStatus.FOUND, MOBILE_SENTINEL, USER_NAME_SENTINEL);

        assertThat(result.status()).isEqualTo(BuyerMemberProfilePort.MemberResolutionStatus.FOUND);
        assertThat(result.mobile()).isEqualTo(MOBILE_SENTINEL);
        assertThat(result.userName()).isEqualTo(USER_NAME_SENTINEL);
        assertThat(Arrays.stream(BuyerMemberProfilePort.MemberProfileResult.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly("status", "mobile", "userName")
                .doesNotContain("memberId", "userId");
        assertThatThrownBy(() -> new BuyerMemberProfilePort.MemberProfileResult(null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("status");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void foundResultRejectsBlankMobile(String mobile) {
        assertThatThrownBy(() -> new BuyerMemberProfilePort.MemberProfileResult(
                BuyerMemberProfilePort.MemberResolutionStatus.FOUND, mobile, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mobile");
    }

    @Test
    void foundResultAllowsNullableUserName() {
        BuyerMemberProfilePort.MemberProfileResult result = new BuyerMemberProfilePort.MemberProfileResult(
                BuyerMemberProfilePort.MemberResolutionStatus.FOUND, MOBILE_SENTINEL, null);

        assertThat(result.mobile()).isEqualTo(MOBILE_SENTINEL);
        assertThat(result.userName()).isNull();
    }

    private static BuyerIntegrationRoute memberRoute() {
        return route(BuyerIntegrationCapabilityCode.MEMBER_PROFILE);
    }

    private static BuyerIntegrationRoute cashierRoute() {
        return route(BuyerIntegrationCapabilityCode.CASHIER);
    }

    private static BuyerIntegrationRoute route(BuyerIntegrationCapabilityCode capability) {
        return new BuyerIntegrationRoute(
                101L, 202L, capability, SOURCE_SYSTEM, EXTERNAL_TENANT_ID,
                TRANSPORT_MODE, "SAAS_" + capability.name() + "_RPC", CONTRACT_VERSION, null);
    }
}
