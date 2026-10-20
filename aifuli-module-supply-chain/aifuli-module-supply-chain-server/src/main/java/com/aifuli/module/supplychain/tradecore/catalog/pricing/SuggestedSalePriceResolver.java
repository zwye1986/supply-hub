package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.SkuSuggestedPriceStateDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.SkuSuggestedPriceStateMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceCalculator;
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferEligibilityService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 当前建议价唯一解析入口。供应价规则始终按同一时刻的有效供给重算，状态表只提供最近成功参考，
 * 因此GET保持只读且不会在Timer延迟时泄漏旧价。批量查询一次加载状态和供给，避免逐SKU查库。
 * 设计见 docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#4-数据模型与唯一取值入口。
 */
@Service @RequiredArgsConstructor
public class SuggestedSalePriceResolver {
    private final SupplierOfferEligibilityService eligibilityService;
    private final SkuSuggestedPriceStateMapper stateMapper;

    public Map<Long, Resolution> resolve(Collection<PlatformSkuDO> skus, LocalDateTime now) {
        if (skus.isEmpty()) return Map.of();
        List<Long> dynamicIds = skus.stream().filter(SuggestedSalePriceResolver::isDynamic)
                .map(PlatformSkuDO::getId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Long> supplies = dynamicIds.isEmpty() ? Map.of()
                : eligibilityService.resolveMinimumSupplyPrices(dynamicIds, now);
        Map<Long, SkuSuggestedPriceStateDO> states = dynamicIds.isEmpty() ? Map.of()
                : stateMapper.selectList(new LambdaQueryWrapper<SkuSuggestedPriceStateDO>()
                    .in(SkuSuggestedPriceStateDO::getPlatformSkuId, dynamicIds)).stream()
                    .collect(Collectors.toMap(SkuSuggestedPriceStateDO::getPlatformSkuId, state -> state));
        Map<Long, Resolution> result = new HashMap<>();
        for (PlatformSkuDO sku : skus) {
            result.put(sku.getId(), evaluate(sku, supplies.get(sku.getId()), states.get(sku.getId()), now));
        }
        return result;
    }

    public Resolution resolve(PlatformSkuDO sku, LocalDateTime now) {
        return resolve(List.of(sku), now).get(sku.getId());
    }

    public static boolean isDynamic(PlatformSkuDO sku) {
        return sku.getSuggestedSalePriceRuleJson() != null && sku.getSuggestedSalePriceRuleJson().usesSupplyPrice();
    }

    /** 同一已加载事实的纯投影。预期的公式结果异常降为失败，数据库失败发生在此方法外并继续上抛。 */
    public static Resolution evaluate(PlatformSkuDO sku, Long supply, SkuSuggestedPriceStateDO old,
                                      LocalDateTime now) {
        SuggestedSalePriceRule rule = sku.getSuggestedSalePriceRuleJson();
        if (!isDynamic(sku)) {
            return new Resolution(sku.getSuggestedSalePrice(), SuggestedPriceStatus.READY, null,
                    rule == null ? null : rule.basePriceSnapshotCents(), null, null, rule);
        }
        Long last = old == null ? null : old.getLastValidPriceCents();
        LocalDateTime lastAt = old == null ? null : old.getLastValidCalculatedAt();
        if (supply == null) {
            return new Resolution(null, SuggestedPriceStatus.DEPENDENCY_MISSING,
                    SuggestedPriceUnavailableReason.EFFECTIVE_SUPPLY_PRICE_MISSING.name(), null, last, lastAt,
                    withoutSnapshot(rule));
        }
        try {
            var calculation = SuggestedSalePriceCalculator.calculate(rule, sku.getMarketPrice(), null, supply);
            return new Resolution(calculation.suggestedSalePriceCents(), SuggestedPriceStatus.READY, null, supply,
                    last, lastAt, calculation.normalizedRule());
        } catch (IllegalArgumentException | ArithmeticException calculationFailure) {
            return new Resolution(null, SuggestedPriceStatus.CALCULATION_FAILED,
                    SuggestedPriceUnavailableReason.SUGGESTED_PRICE_CALCULATION_FAILED.name(), supply, last, lastAt,
                    new SuggestedSalePriceRule(rule.mode(), rule.base(), rule.percentage(), rule.operator(),
                            rule.operand(), null, supply));
        }
    }

    public static SuggestedSalePriceRule withoutSnapshot(SuggestedSalePriceRule rule) {
        return new SuggestedSalePriceRule(rule.mode(), rule.base(), rule.percentage(), rule.operator(),
                rule.operand(), rule.fixedPriceCents(), null);
    }

    /** 仅作变化检测，不是安全摘要；十进制表示与客户端快照不改变人工公式身份。 */
    public static String ruleDigest(SuggestedSalePriceRule rule) {
        String canonical = rule.mode() + "|" + rule.base() + "|" + decimal(rule.percentage()) + "|"
                + rule.operator() + "|" + decimal(rule.operand()) + "|" + rule.fixedPriceCents();
        return DigestUtils.md5DigestAsHex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String decimal(BigDecimal number) {
        return number == null ? "" : number.stripTrailingZeros().toPlainString();
    }

    public record Resolution(Long amountCents, SuggestedPriceStatus status, String unavailableReason,
                             Long basePriceSnapshotCents, Long lastValidPriceCents,
                             LocalDateTime lastValidCalculatedAt, SuggestedSalePriceRule normalizedRule) { }
}
