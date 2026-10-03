package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.supplieradapter.metadata.Capability;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * fulfillmentType→primaryCapability 推导纯函数（B1 上架即发布用于自动选定主能力）。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#9-测试}：
 * DIRECT_RECHARGE→CREATE_RECHARGE；VOUCHER_CODE/CARD_CODE_SECRET/VOUCHER_URL→ISSUE_ASSET；
 * ACCOUNT_BOUND_BENEFIT→BIND_BENEFIT；未知类型抛 IllegalArgumentException。与
 * {@code requireCapabilityMatchesSku} 共用同一映射，消除双份漂移。</p>
 */
class SupplierOfferPublicationCapabilityDerivationTest {

    @Test
    void directRechargeDerivesCreateRecharge() {
        assertThat(SupplierOfferPublicationService.derivePrimaryCapability("DIRECT_RECHARGE"))
                .isEqualTo(Capability.CREATE_RECHARGE);
    }

    @Test
    void voucherCodeDerivesIssueAsset() {
        assertThat(SupplierOfferPublicationService.derivePrimaryCapability("VOUCHER_CODE"))
                .isEqualTo(Capability.ISSUE_ASSET);
    }

    @Test
    void cardCodeSecretDerivesIssueAsset() {
        assertThat(SupplierOfferPublicationService.derivePrimaryCapability("CARD_CODE_SECRET"))
                .isEqualTo(Capability.ISSUE_ASSET);
    }

    @Test
    void voucherUrlDerivesIssueAsset() {
        assertThat(SupplierOfferPublicationService.derivePrimaryCapability("VOUCHER_URL"))
                .isEqualTo(Capability.ISSUE_ASSET);
    }

    @Test
    void accountBoundBenefitDerivesBindBenefit() {
        assertThat(SupplierOfferPublicationService.derivePrimaryCapability("ACCOUNT_BOUND_BENEFIT"))
                .isEqualTo(Capability.BIND_BENEFIT);
    }

    @Test
    void unknownFulfillmentTypeThrows() {
        assertThatThrownBy(() -> SupplierOfferPublicationService.derivePrimaryCapability("NOT_A_TYPE"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
