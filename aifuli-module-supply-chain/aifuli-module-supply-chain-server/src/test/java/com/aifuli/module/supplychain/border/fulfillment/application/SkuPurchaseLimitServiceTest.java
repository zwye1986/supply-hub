package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainAftersaleStatus;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKU 限购校验单测：覆盖每订单数量上限、每人周期、绑定手机号周期与关闭开关不计数。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。
 */
class SkuPurchaseLimitServiceTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long SKU_ID = 11L;
    private static final String USER_PHONE_ID = "13800138000";
    private static final String FULFILLMENT_PHONE = "13900139000";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 10, 0);

    private final SupplyChainOrderMapper orderMapper = mock(SupplyChainOrderMapper.class);
    private final SkuPurchaseLimitService service = new SkuPurchaseLimitService(orderMapper);

    /**
     * 过渡期新语义：实际 MOBILE 规范化手机号为空（卡券/非手机号账号，无购买人身份可补造）时
     * 跳过每人维度，不查询订单聚合；绑定手机号维度不受影响（有手机目标仍按其统计）。
     * 设计见 docs/superpowers/specs/2026-10-02-standard-acceptance-project-key-design.md#2-首次身份与重放优先。
     */
    @Test
    void emptyPersonalIdentitySkipsPersonalDimensionWithoutFabricatingIdentity() {
        assertThat(service.validateLockedSku(
                PROJECT_ID, null, null, sku(true, "DAY", 2L, true, 9L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);
        assertThat(service.validateLockedSku(
                PROJECT_ID, "  ", null, sku(true, "DAY", 2L, true, 9L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);
        assertThat(service.validateLockedSku(
                PROJECT_ID, "", null, sku(true, "DAY", 2L, true, 9L, true, "DAY", 1L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.PHONE_BIND_TARGET_REQUIRED);

        verify(orderMapper, never()).sumEffectivePurchaseQuantity(
                anyString(), anyLong(), anyString(), anyLong(), any(), any(), anyShort(), anyShort(), anyShort());
        verify(orderMapper, never()).sumEffectivePhoneBindPurchaseQuantity(
                anyString(), anyLong(), anyString(), anyLong(), any(), any(), anyShort(), anyShort(), anyShort());
    }

    @Test
    void disabledPersonalLimitDoesNotReadOrders() {
        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, FULFILLMENT_PHONE, sku(false, null, null, false, 9L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);

        verify(orderMapper, never()).sumEffectivePurchaseQuantity(
                anyString(), anyLong(), anyString(), anyLong(), any(), any(), anyShort(), anyShort(), anyShort());
        verify(orderMapper, never()).sumEffectivePhoneBindPurchaseQuantity(
                anyString(), anyLong(), anyString(), anyLong(), any(), any(), anyShort(), anyShort(), anyShort());
    }

    @Test
    void orderLimitRestrictsRequestedPurchaseQuantity() {
        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, FULFILLMENT_PHONE, sku(false, null, null, true, 1L), 2L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.ORDER_LIMIT_EXCEEDED);
    }

    @Test
    void dailyLimitUsesProjectPhoneSkuWindowAndRejectsOverflow() {
        when(orderMapper.sumEffectivePurchaseQuantity(
                SupplyChainOrderTypes.ORDER_TYPE_CARD, PROJECT_ID, USER_PHONE_ID, SKU_ID,
                LocalDateTime.of(2026, 9, 15, 0, 0), LocalDateTime.of(2026, 9, 16, 0, 0),
                SupplyChainOrderStatus.FAILED.getCode(), SupplyChainOrderStatus.CANCELLED.getCode(),
                SupplyChainAftersaleStatus.FULLY_REFUNDED.getCode())).thenReturn(2L);

        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, FULFILLMENT_PHONE, sku(true, "DAY", 2L, true, 1L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.USER_LIMIT_EXCEEDED);

        verify(orderMapper).sumEffectivePurchaseQuantity(
                SupplyChainOrderTypes.ORDER_TYPE_CARD, PROJECT_ID, USER_PHONE_ID, SKU_ID,
                LocalDateTime.of(2026, 9, 15, 0, 0), LocalDateTime.of(2026, 9, 16, 0, 0),
                SupplyChainOrderStatus.FAILED.getCode(), SupplyChainOrderStatus.CANCELLED.getCode(),
                SupplyChainAftersaleStatus.FULLY_REFUNDED.getCode());
    }

    @Test
    void lifetimeLimitQueriesWithoutTimeWindow() {
        when(orderMapper.sumEffectivePurchaseQuantity(
                eq(SupplyChainOrderTypes.ORDER_TYPE_CARD), eq(PROJECT_ID), eq(USER_PHONE_ID), eq(SKU_ID),
                isNull(), isNull(),
                eq(SupplyChainOrderStatus.FAILED.getCode()), eq(SupplyChainOrderStatus.CANCELLED.getCode()),
                eq(SupplyChainAftersaleStatus.FULLY_REFUNDED.getCode()))).thenReturn(1L);

        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, FULFILLMENT_PHONE, sku(true, "LIFETIME", 2L, false, 9L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);
    }

    @Test
    void usedEqualsRemainingCapacityIsAllowedAndOverflowIsRejected() {
        when(orderMapper.sumEffectivePurchaseQuantity(
                SupplyChainOrderTypes.ORDER_TYPE_CARD, PROJECT_ID, USER_PHONE_ID, SKU_ID,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0),
                SupplyChainOrderStatus.FAILED.getCode(), SupplyChainOrderStatus.CANCELLED.getCode(),
                SupplyChainAftersaleStatus.FULLY_REFUNDED.getCode())).thenReturn(1L, 2L);

        PlatformSkuDO yearly = sku(true, "YEAR", 2L, false, 9L);
        assertThat(service.validateLockedSku(PROJECT_ID, USER_PHONE_ID, FULFILLMENT_PHONE, yearly, 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);
        assertThat(service.validateLockedSku(PROJECT_ID, USER_PHONE_ID, FULFILLMENT_PHONE, yearly, 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.USER_LIMIT_EXCEEDED);
    }

    @Test
    void monthlyAndWeeklyWindowsArePassedToAggregation() {
        when(orderMapper.sumEffectivePurchaseQuantity(
                SupplyChainOrderTypes.ORDER_TYPE_CARD, PROJECT_ID, USER_PHONE_ID, SKU_ID,
                LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 10, 1, 0, 0),
                SupplyChainOrderStatus.FAILED.getCode(), SupplyChainOrderStatus.CANCELLED.getCode(),
                SupplyChainAftersaleStatus.FULLY_REFUNDED.getCode())).thenReturn(0L);
        when(orderMapper.sumEffectivePurchaseQuantity(
                SupplyChainOrderTypes.ORDER_TYPE_CARD, PROJECT_ID, USER_PHONE_ID, SKU_ID,
                LocalDateTime.of(2026, 9, 14, 0, 0), LocalDateTime.of(2026, 9, 21, 0, 0),
                SupplyChainOrderStatus.FAILED.getCode(), SupplyChainOrderStatus.CANCELLED.getCode(),
                SupplyChainAftersaleStatus.FULLY_REFUNDED.getCode())).thenReturn(0L);

        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, FULFILLMENT_PHONE, sku(true, "MONTH", 2L, false, 9L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);
        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, FULFILLMENT_PHONE, sku(true, "WEEK", 2L, false, 9L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);
    }

    @Test
    void disabledOrderLimitDoesNotRejectQuantityAboveMax() {
        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, FULFILLMENT_PHONE, sku(false, null, null, false, 1L), 2L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);
        verify(orderMapper, never()).sumEffectivePurchaseQuantity(
                anyString(), anyLong(), anyString(), anyLong(), any(), any(), anyShort(), anyShort(), anyShort());
    }

    @Test
    void orderLimitWithNullMaxQuantityDoesNotThrowNpe() {
        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, FULFILLMENT_PHONE, sku(false, null, null, true, null), 2L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);
    }

    @Test
    void phoneBindLimitCountsByFulfillmentAccountNotBuyerUserId() {
        when(orderMapper.sumEffectivePhoneBindPurchaseQuantity(
                SupplyChainOrderTypes.ORDER_TYPE_CARD, PROJECT_ID, FULFILLMENT_PHONE, SKU_ID,
                LocalDateTime.of(2026, 9, 15, 0, 0), LocalDateTime.of(2026, 9, 16, 0, 0),
                SupplyChainOrderStatus.FAILED.getCode(), SupplyChainOrderStatus.CANCELLED.getCode(),
                SupplyChainAftersaleStatus.FULLY_REFUNDED.getCode())).thenReturn(1L);

        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, FULFILLMENT_PHONE,
                sku(false, null, null, false, 9L, true, "DAY", 1L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.PHONE_BIND_LIMIT_EXCEEDED);

        verify(orderMapper).sumEffectivePhoneBindPurchaseQuantity(
                SupplyChainOrderTypes.ORDER_TYPE_CARD, PROJECT_ID, FULFILLMENT_PHONE, SKU_ID,
                LocalDateTime.of(2026, 9, 15, 0, 0), LocalDateTime.of(2026, 9, 16, 0, 0),
                SupplyChainOrderStatus.FAILED.getCode(), SupplyChainOrderStatus.CANCELLED.getCode(),
                SupplyChainAftersaleStatus.FULLY_REFUNDED.getCode());
        verify(orderMapper, never()).sumEffectivePurchaseQuantity(
                anyString(), anyLong(), anyString(), anyLong(), any(), any(), anyShort(), anyShort(), anyShort());
    }

    @Test
    void phoneBindLimitAllowsRemainingCapacityOnSameFulfillmentAccount() {
        when(orderMapper.sumEffectivePhoneBindPurchaseQuantity(
                eq(SupplyChainOrderTypes.ORDER_TYPE_CARD), eq(PROJECT_ID), eq(FULFILLMENT_PHONE), eq(SKU_ID),
                isNull(), isNull(),
                eq(SupplyChainOrderStatus.FAILED.getCode()), eq(SupplyChainOrderStatus.CANCELLED.getCode()),
                eq(SupplyChainAftersaleStatus.FULLY_REFUNDED.getCode()))).thenReturn(1L);

        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, FULFILLMENT_PHONE,
                sku(false, null, null, false, 9L, true, "LIFETIME", 2L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);
    }

    @Test
    void enabledPhoneBindLimitWithoutMobileTargetIsRejected() {
        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, null,
                sku(false, null, null, false, 9L, true, "DAY", 1L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.PHONE_BIND_TARGET_REQUIRED);
        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, "  ",
                sku(false, null, null, false, 9L, true, "DAY", 1L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.PHONE_BIND_TARGET_REQUIRED);

        verify(orderMapper, never()).sumEffectivePhoneBindPurchaseQuantity(
                anyString(), anyLong(), anyString(), anyLong(), any(), any(), anyShort(), anyShort(), anyShort());
    }

    private static PlatformSkuDO sku(boolean userEnabled, String period, Long userQuantity,
                                     boolean orderEnabled, Long maxQuantity) {
        return sku(userEnabled, period, userQuantity, orderEnabled, maxQuantity, false, null, null);
    }

    private static PlatformSkuDO sku(boolean userEnabled, String period, Long userQuantity,
                                     boolean orderEnabled, Long maxQuantity,
                                     boolean phoneEnabled, String phonePeriod, Long phoneQuantity) {
        return PlatformSkuDO.builder().id(SKU_ID).skuCode("SKU_LIMIT")
                .userLimitEnabled(userEnabled).userLimitPeriod(period).userLimitQuantity(userQuantity)
                .orderLimitEnabled(orderEnabled).maxQuantity(maxQuantity)
                .phoneBindLimitEnabled(phoneEnabled).phoneBindLimitPeriod(phonePeriod)
                .phoneBindLimitQuantity(phoneQuantity).build();
    }
}
