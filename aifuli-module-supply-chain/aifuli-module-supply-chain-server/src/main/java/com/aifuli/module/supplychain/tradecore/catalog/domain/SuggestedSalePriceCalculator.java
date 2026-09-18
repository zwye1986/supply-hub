package com.aifuli.module.supplychain.tradecore.catalog.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import static com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule.Base.MARKET_PRICE;
import static com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule.Base.SUGGESTED_SALE_PRICE;

/**
 * SKU 销售建议价纯计算器。中间值使用十进制定点数，最终统一 HALF_UP 到 CNY 分。
 *
 * <p>该计算器不读取数据库。复合保存和批量更新必须共用它，禁止分别在 Controller 或前端实现另一套舍入规则。</p>
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#221-销售建议价规则039-changeset}。</p>
 */
public final class SuggestedSalePriceCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private SuggestedSalePriceCalculator() {
    }

    /**
     * 校验并计算规则，同时返回已补齐服务端基数快照的规范规则。
     *
     * @param requestedRule 调用方提交的规则，服务端字段 basePriceSnapshotCents 会被忽略并重新生成
     * @param marketPriceCents 当前市场价，单位 CNY 分
     * @param previousSuggestedSalePriceCents 修改前销售建议价；创建场景为空
     */
    public static Calculation calculate(SuggestedSalePriceRule requestedRule, long marketPriceCents,
                                        Long previousSuggestedSalePriceCents) {
        Objects.requireNonNull(requestedRule, "suggested sale price rule is required");
        if (marketPriceCents < 0 || requestedRule.mode() == null) {
            throw new IllegalArgumentException("market price and rule mode must be valid");
        }
        return switch (requestedRule.mode()) {
            case EQUAL_MARKET_PRICE -> new Calculation(marketPriceCents,
                    SuggestedSalePriceRule.equalMarketPrice());
            case FIXED -> fixed(requestedRule.fixedPriceCents());
            case CUSTOM -> custom(requestedRule, marketPriceCents, previousSuggestedSalePriceCents);
        };
    }

    private static Calculation fixed(Long fixedPriceCents) {
        if (fixedPriceCents == null || fixedPriceCents < 0) {
            throw new IllegalArgumentException("fixed price must be non-negative");
        }
        return new Calculation(fixedPriceCents, SuggestedSalePriceRule.fixed(fixedPriceCents));
    }

    private static Calculation custom(SuggestedSalePriceRule requestedRule, long marketPriceCents,
                                      Long previousSuggestedSalePriceCents) {
        if (requestedRule.base() == null || requestedRule.operator() == null) {
            throw new IllegalArgumentException("custom rule base and operator are required");
        }
        validatePercentage(requestedRule.percentage());
        validateOperand(requestedRule.operator(), requestedRule.operand());

        long basePriceCents;
        Long snapshot = null;
        if (requestedRule.base() == MARKET_PRICE) {
            basePriceCents = marketPriceCents;
        } else if (requestedRule.base() == SUGGESTED_SALE_PRICE) {
            if (previousSuggestedSalePriceCents == null || previousSuggestedSalePriceCents < 0) {
                throw new IllegalArgumentException("previous suggested sale price is required");
            }
            basePriceCents = previousSuggestedSalePriceCents;
            snapshot = previousSuggestedSalePriceCents;
        } else {
            throw new IllegalArgumentException("unsupported suggested sale price base");
        }

        BigDecimal percentageResult = BigDecimal.valueOf(basePriceCents, 2)
                .multiply(requestedRule.percentage())
                .divide(ONE_HUNDRED);
        BigDecimal resultYuan = switch (requestedRule.operator()) {
            case ADD -> percentageResult.add(requestedRule.operand());
            case SUBTRACT -> percentageResult.subtract(requestedRule.operand());
            case MULTIPLY -> percentageResult.multiply(requestedRule.operand());
            case DIVIDE -> percentageResult.divide(requestedRule.operand(), 12, RoundingMode.HALF_UP);
        };
        long resultCents = toCents(resultYuan);
        SuggestedSalePriceRule normalized = new SuggestedSalePriceRule(
                SuggestedSalePriceRule.Mode.CUSTOM, requestedRule.base(), requestedRule.percentage(),
                requestedRule.operator(), requestedRule.operand(), null, snapshot);
        return new Calculation(resultCents, normalized);
    }

    private static void validatePercentage(BigDecimal percentage) {
        if (percentage == null || percentage.signum() <= 0 || scale(percentage) > 2) {
            throw new IllegalArgumentException("percentage must be positive with at most 2 decimal places");
        }
    }

    private static void validateOperand(SuggestedSalePriceRule.Operator operator, BigDecimal operand) {
        if (operand == null || operand.signum() < 0) {
            throw new IllegalArgumentException("operand must be non-negative");
        }
        int maxScale = operator == SuggestedSalePriceRule.Operator.ADD
                || operator == SuggestedSalePriceRule.Operator.SUBTRACT ? 2 : 4;
        if (scale(operand) > maxScale) {
            throw new IllegalArgumentException("operand decimal places exceed operator limit");
        }
        if (operator == SuggestedSalePriceRule.Operator.DIVIDE && operand.signum() == 0) {
            throw new IllegalArgumentException("division operand must not be zero");
        }
    }

    private static long toCents(BigDecimal yuan) {
        BigDecimal cents = yuan.movePointRight(2).setScale(0, RoundingMode.HALF_UP);
        if (cents.signum() < 0) {
            throw new IllegalArgumentException("suggested sale price result must not be negative");
        }
        return cents.longValueExact();
    }

    private static int scale(BigDecimal value) {
        return Math.max(0, value.stripTrailingZeros().scale());
    }

    public record Calculation(long suggestedSalePriceCents, SuggestedSalePriceRule normalizedRule) {
    }
}
