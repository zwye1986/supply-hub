package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.profile.domain.L2AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.L3AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.PriceSource;
import org.springframework.stereotype.Component;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.L2_OVERRIDE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.L3_OVERRIDE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PLATFORM_SETTLEMENT_PRICE_MISSING;

/**
 * 管理端有效 B 端结算价解析。有效价顺序为 L3 &gt; 手工 L2 &gt; L1；
 * {@code EQUAL_L1} 取 L1 的 B 端结算分而非 C 端市场价；{@code FOLLOW_TEMPLATE}/{@code FOLLOW_L2}
 * 返回 null 表示清除覆盖或不写 L3。历史模板价预留表不得参与解析。
 * 设计见 docs/superpowers/specs/2026-09-12-supply-chain-prd-scope-alignment-design.md#5-销售价与分销价无模板
 */
@Component
public class SettlementPriceResolver {

    /**
     * 解析得到的有效 B 端结算单价（分）及其来源层级。
     */
    public record ResolvedPrice(long unitPriceCents, PriceSource source) {
    }

    /**
     * 按 L3、手工 L2、L1 顺序取第一个非空价；L1=0 视为已定价而非缺失。
     * 三级皆空时抛 {@code PLATFORM_SETTLEMENT_PRICE_MISSING}。
     */
    public ResolvedPrice resolve(Long l3Cents, Long l2OverrideCents, Long l1Cents) {
        if (l3Cents != null) {
            return new ResolvedPrice(l3Cents, PriceSource.L3);
        }
        if (l2OverrideCents != null) {
            return new ResolvedPrice(l2OverrideCents, PriceSource.L2);
        }
        if (l1Cents != null) {
            return new ResolvedPrice(l1Cents, PriceSource.L1);
        }
        throw exception(PLATFORM_SETTLEMENT_PRICE_MISSING);
    }

    /**
     * 按 L2 调价方式计算覆盖金额。{@code FOLLOW_TEMPLATE} 返回 null 表示清除覆盖并继承 L1，
     * 不表示读取模板价格。
     * {@code EQUAL_L1}/{@code CUSTOM} 结果必须 &gt;0，否则 {@code L2_OVERRIDE_INVALID}。
     */
    public Long applyL2(L2AdjustMode mode, long l1Cents, Integer percent, Long amountCents) {
        return switch (mode) {
            case FOLLOW_TEMPLATE -> null;
            case EQUAL_L1 -> requirePositive(l1Cents, true);
            case CUSTOM -> requirePositive(custom(l1Cents, percent, amountCents, true), true);
        };
    }

    /**
     * 按 L3 调价方式计算覆盖金额。{@code FOLLOW_L2} 返回 null 表示不写 L3。
     * {@code EQUAL_L1}/{@code CUSTOM} 结果必须 &gt;0，否则 {@code L3_OVERRIDE_INVALID}。
     */
    public Long applyL3(L3AdjustMode mode, long l1Cents, Integer percent, Long amountCents) {
        return switch (mode) {
            case FOLLOW_L2 -> null;
            case EQUAL_L1 -> requirePositive(l1Cents, false);
            case CUSTOM -> requirePositive(custom(l1Cents, percent, amountCents, false), false);
        };
    }

    private static long custom(long l1Cents, Integer percent, Long amountCents, boolean l2) {
        if (percent == null || percent < 1) {
            throw exception(l2 ? L2_OVERRIDE_INVALID : L3_OVERRIDE_INVALID);
        }
        long add = amountCents == null ? 0L : amountCents;
        return Math.round(l1Cents * (percent / 100.0d)) + add;
    }

    private static long requirePositive(long cents, boolean l2) {
        if (cents <= 0) {
            throw exception(l2 ? L2_OVERRIDE_INVALID : L3_OVERRIDE_INVALID);
        }
        return cents;
    }
}
