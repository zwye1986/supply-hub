package com.aifuli.module.supplychain.border.fulfillment.application;

import com.aifuli.module.supplychain.api.order.dto.SupplyChainAftersaleStatus;
import com.aifuli.module.supplychain.api.order.dto.SupplyChainOrderStatus;
import com.aifuli.module.supplychain.border.order.dal.mysql.SupplyChainOrderMapper;
import com.aifuli.module.supplychain.border.order.domain.SupplyChainOrderTypes;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.domain.PurchaseLimitPeriod;
import com.aifuli.module.supplychain.tradecore.catalog.domain.PurchaseLimitPeriod.PeriodWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 标准商品 SKU 限购校验器。
 *
 * <p>调用方必须先锁 SKU、再把锁定快照传入本服务统计有效订单，确保同一 SKU 的并发请求不能同时越过
 * 额度判断。买家手机号与履约手机号只作为查询条件，不进入日志。失败、取消和全部退款由订单状态投影自动释放额度。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkuPurchaseLimitService {

    private final SupplyChainOrderMapper orderMapper;

    /**
     * 校验锁定态 SKU 的限购规则（每订单数量、每人周期、绑定手机号周期）。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#35-sku-限购计算并发与释放规则}。
     */
    public Violation validateLockedSku(Long projectId, String buyerUserId, String fulfillmentAccountValue,
                                       PlatformSkuDO lockedSku, long quantity, LocalDateTime acceptedAt) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(lockedSku, "lockedSku");
        Objects.requireNonNull(lockedSku.getId(), "lockedSku.id");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (buyerUserId == null || buyerUserId.isBlank() || quantity <= 0) {
            throw new IllegalArgumentException("限购校验参数不完整");
        }
        if (Boolean.TRUE.equals(lockedSku.getOrderLimitEnabled())
                && lockedSku.getMaxQuantity() != null
                && quantity > lockedSku.getMaxQuantity()) {
            log.info("SKU每订单限购拒绝 projectId={}, skuCode={}, quantity={}, limit={}",
                    projectId, lockedSku.getSkuCode(), quantity, lockedSku.getMaxQuantity());
            return Violation.ORDER_LIMIT_EXCEEDED;
        }
        if (Boolean.TRUE.equals(lockedSku.getUserLimitEnabled())) {
            if (lockedSku.getUserLimitQuantity() == null || lockedSku.getUserLimitQuantity() <= 0) {
                throw new IllegalStateException("启用的SKU每人限购配置缺少有效数量");
            }
            PurchaseLimitPeriod period = PurchaseLimitPeriod.fromCode(lockedSku.getUserLimitPeriod());
            PeriodWindow window = period.windowAt(acceptedAt);
            Long queried = orderMapper.sumEffectivePurchaseQuantity(
                    SupplyChainOrderTypes.ORDER_TYPE_CARD, projectId, buyerUserId, lockedSku.getId(),
                    window.startInclusive(), window.endExclusive(),
                    SupplyChainOrderStatus.FAILED.getCode(), SupplyChainOrderStatus.CANCELLED.getCode(),
                    SupplyChainAftersaleStatus.FULLY_REFUNDED.getCode());
            long used = queried == null ? 0L : queried;
            if (used > lockedSku.getUserLimitQuantity() - quantity) {
                log.info("SKU每人限购拒绝 projectId={}, skuCode={}, period={}, used={}, quantity={}, limit={}",
                        projectId, lockedSku.getSkuCode(), period, used, quantity, lockedSku.getUserLimitQuantity());
                return Violation.USER_LIMIT_EXCEEDED;
            }
        }
        if (!Boolean.TRUE.equals(lockedSku.getPhoneBindLimitEnabled())) {
            return Violation.NONE;
        }
        if (fulfillmentAccountValue == null || fulfillmentAccountValue.isBlank()) {
            log.info("SKU绑定手机号限购缺少履约手机号 projectId={}, skuCode={}",
                    projectId, lockedSku.getSkuCode());
            return Violation.PHONE_BIND_TARGET_REQUIRED;
        }
        if (lockedSku.getPhoneBindLimitQuantity() == null || lockedSku.getPhoneBindLimitQuantity() <= 0) {
            throw new IllegalStateException("启用的SKU绑定手机号限购配置缺少有效数量");
        }
        PurchaseLimitPeriod phonePeriod = PurchaseLimitPeriod.fromCode(lockedSku.getPhoneBindLimitPeriod());
        PeriodWindow phoneWindow = phonePeriod.windowAt(acceptedAt);
        Long phoneQueried = orderMapper.sumEffectivePhoneBindPurchaseQuantity(
                SupplyChainOrderTypes.ORDER_TYPE_CARD, projectId, fulfillmentAccountValue, lockedSku.getId(),
                phoneWindow.startInclusive(), phoneWindow.endExclusive(),
                SupplyChainOrderStatus.FAILED.getCode(), SupplyChainOrderStatus.CANCELLED.getCode(),
                SupplyChainAftersaleStatus.FULLY_REFUNDED.getCode());
        long phoneUsed = phoneQueried == null ? 0L : phoneQueried;
        if (phoneUsed > lockedSku.getPhoneBindLimitQuantity() - quantity) {
            log.info("SKU绑定手机号限购拒绝 projectId={}, skuCode={}, period={}, used={}, quantity={}, limit={}",
                    projectId, lockedSku.getSkuCode(), phonePeriod, phoneUsed, quantity,
                    lockedSku.getPhoneBindLimitQuantity());
            return Violation.PHONE_BIND_LIMIT_EXCEEDED;
        }
        return Violation.NONE;
    }

    public enum Violation {
        NONE,
        USER_LIMIT_EXCEEDED,
        ORDER_LIMIT_EXCEEDED,
        PHONE_BIND_LIMIT_EXCEEDED,
        PHONE_BIND_TARGET_REQUIRED
    }
}
