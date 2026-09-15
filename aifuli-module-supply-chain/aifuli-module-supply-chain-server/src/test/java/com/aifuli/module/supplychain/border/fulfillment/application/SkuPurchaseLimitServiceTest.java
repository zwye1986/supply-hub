package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainAftersaleStatus;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SKU 限购校验单测：覆盖每订单数量上限、周期查询参数与关闭开关不计数。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。
 */
class SkuPurchaseLimitServiceTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long SKU_ID = 11L;
    private static final String USER_PHONE_ID = "13800138000";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 10, 0);

    private final SupplyChainOrderMapper orderMapper = mock(SupplyChainOrderMapper.class);
    private final SkuPurchaseLimitService service = new SkuPurchaseLimitService(orderMapper);

    @Test
    void disabledPersonalLimitDoesNotReadOrders() {
        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, sku(false, null, null, false, 9L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);

        verify(orderMapper, never()).sumEffectivePurchaseQuantity(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyShort(), org.mockito.ArgumentMatchers.anyShort(),
                org.mockito.ArgumentMatchers.anyShort());
    }

    @Test
    void orderLimitRestrictsRequestedPurchaseQuantity() {
        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, sku(false, null, null, true, 1L), 2L, NOW))
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
                PROJECT_ID, USER_PHONE_ID, sku(true, "DAY", 2L, true, 1L), 1L, NOW))
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
                PROJECT_ID, USER_PHONE_ID, sku(true, "LIFETIME", 2L, false, 9L), 1L, NOW))
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
        assertThat(service.validateLockedSku(PROJECT_ID, USER_PHONE_ID, yearly, 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);
        assertThat(service.validateLockedSku(PROJECT_ID, USER_PHONE_ID, yearly, 1L, NOW))
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
                PROJECT_ID, USER_PHONE_ID, sku(true, "MONTH", 2L, false, 9L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);
        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, sku(true, "WEEK", 2L, false, 9L), 1L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);
    }

    @Test
    void disabledOrderLimitDoesNotRejectQuantityAboveMax() {
        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, sku(false, null, null, false, 1L), 2L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);
        verify(orderMapper, never()).sumEffectivePurchaseQuantity(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyShort(), org.mockito.ArgumentMatchers.anyShort(),
                org.mockito.ArgumentMatchers.anyShort());
    }

    @Test
    void orderLimitWithNullMaxQuantityDoesNotThrowNpe() {
        assertThat(service.validateLockedSku(
                PROJECT_ID, USER_PHONE_ID, sku(false, null, null, true, null), 2L, NOW))
                .isEqualTo(SkuPurchaseLimitService.Violation.NONE);
    }

    private static PlatformSkuDO sku(boolean userEnabled, String period, Long userQuantity,
                                     boolean orderEnabled, Long maxQuantity) {
        return PlatformSkuDO.builder().id(SKU_ID).skuCode("SKU_LIMIT")
                .userLimitEnabled(userEnabled).userLimitPeriod(period).userLimitQuantity(userQuantity)
                .orderLimitEnabled(orderEnabled).maxQuantity(maxQuantity).build();
    }
}
