package com.aifuli.module.supplychain.tradecore.supply.application;

import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferDO;
import com.aifuli.module.supplychain.tradecore.supply.dal.dataobject.SupplierOfferVersionDO;
import com.aifuli.module.supplychain.tradecore.supply.domain.FulfillmentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * i-b 履约就绪度三态纯函数各分支（NOT_CONFIGURED/STALE/READY）。
 *
 * <p>设计见 {@code docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#67-listoffers-投影扩展m1-沿用--i-b}：
 * {@code current_version_id==null}→NOT_CONFIGURED；否则 diff 身份列（external_sku_code/draft_unit_price/
 * draft_min_quantity/draft_max_quantity）与当前版本冻结字段（external_sku_code/supplier_settlement_unit_price/
 * min_quantity/max_quantity），任一不一致→STALE，全一致→READY。</p>
 */
class SupplierCooperativeProductFulfillmentStatusTest {

    private static final String EXTERNAL_SKU = "QNM-EXT-001";
    private static final long UNIT_PRICE = 1999L;
    private static final long MIN_QTY = 1L;
    private static final long MAX_QTY = 100L;

    private SupplierOfferDO offer(Long currentVersionId, String externalSkuCode, Long unitPrice,
                                  Long minQty, Long maxQty) {
        return SupplierOfferDO.builder()
                .currentVersionId(currentVersionId)
                .externalSkuCode(externalSkuCode)
                .draftUnitPrice(unitPrice)
                .draftMinQuantity(minQty)
                .draftMaxQuantity(maxQty)
                .build();
    }

    private SupplierOfferVersionDO version(String externalSkuCode, Long settlementPrice,
                                           Long minQty, Long maxQty) {
        return SupplierOfferVersionDO.builder()
                .externalSkuCode(externalSkuCode)
                .supplierSettlementUnitPrice(settlementPrice)
                .minQuantity(minQty)
                .maxQuantity(maxQty)
                .build();
    }

    private SupplierOfferDO alignedOffer() {
        return offer(9L, EXTERNAL_SKU, UNIT_PRICE, MIN_QTY, MAX_QTY);
    }

    private SupplierOfferVersionDO alignedVersion() {
        return version(EXTERNAL_SKU, UNIT_PRICE, MIN_QTY, MAX_QTY);
    }

    @Test
    void nullCurrentVersionIdIsNotConfigured() {
        assertThat(SupplierCooperativeProductService.computeFulfillmentStatus(
                offer(null, EXTERNAL_SKU, UNIT_PRICE, MIN_QTY, MAX_QTY), null))
                .isEqualTo(FulfillmentStatus.NOT_CONFIGURED);
    }

    @Test
    void currentVersionIdSetButVersionRowMissingIsNotConfigured() {
        assertThat(SupplierCooperativeProductService.computeFulfillmentStatus(alignedOffer(), null))
                .isEqualTo(FulfillmentStatus.NOT_CONFIGURED);
    }

    @Test
    void allFrozenFieldsMatchIsReady() {
        assertThat(SupplierCooperativeProductService.computeFulfillmentStatus(alignedOffer(), alignedVersion()))
                .isEqualTo(FulfillmentStatus.READY);
    }

    @Test
    void externalSkuCodeDriftIsStale() {
        assertThat(SupplierCooperativeProductService.computeFulfillmentStatus(
                alignedOffer(), version("QNM-EXT-999", UNIT_PRICE, MIN_QTY, MAX_QTY)))
                .isEqualTo(FulfillmentStatus.STALE);
    }

    @Test
    void settlementPriceDriftIsStale() {
        assertThat(SupplierCooperativeProductService.computeFulfillmentStatus(
                alignedOffer(), version(EXTERNAL_SKU, 2999L, MIN_QTY, MAX_QTY)))
                .isEqualTo(FulfillmentStatus.STALE);
    }

    @Test
    void minQuantityDriftIsStale() {
        assertThat(SupplierCooperativeProductService.computeFulfillmentStatus(
                alignedOffer(), version(EXTERNAL_SKU, UNIT_PRICE, 5L, MAX_QTY)))
                .isEqualTo(FulfillmentStatus.STALE);
    }

    @Test
    void maxQuantityDriftIsStale() {
        assertThat(SupplierCooperativeProductService.computeFulfillmentStatus(
                alignedOffer(), version(EXTERNAL_SKU, UNIT_PRICE, MIN_QTY, 500L)))
                .isEqualTo(FulfillmentStatus.STALE);
    }

    @Test
    void nullVersusNonNullIdentityColumnIsStale() {
        assertThat(SupplierCooperativeProductService.computeFulfillmentStatus(
                offer(9L, null, UNIT_PRICE, MIN_QTY, MAX_QTY), alignedVersion()))
                .isEqualTo(FulfillmentStatus.STALE);
    }

    @Test
    void enumExposesThreeStatesWithCode() {
        assertThat(FulfillmentStatus.values())
                .containsExactlyInAnyOrder(FulfillmentStatus.NOT_CONFIGURED, FulfillmentStatus.STALE,
                        FulfillmentStatus.READY);
        assertThat(FulfillmentStatus.READY.code()).isEqualTo("READY");
    }
}
