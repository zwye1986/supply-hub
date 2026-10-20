package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.SkuSuggestedPriceStateDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.SkuSuggestedPriceStateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 定价根锁内保存动态建议价事实，绝不回写SKU行或重复派发依赖事件。
 * 调用方负责先锁平台定价根及锁后重读，按升序处理整批；所有NULL使用DO显式更新策略落库。
 * 设计见 docs/superpowers/specs/2026-10-19-suggested-sale-price-supply-base-design.md#52-锁与写入责任。
 */
@Service @RequiredArgsConstructor
public class SuggestedSalePriceStateService {
    private final SkuSuggestedPriceStateMapper mapper;

    @Transactional(transactionManager = "supplyChainTransactionManager", propagation = Propagation.MANDATORY,
            rollbackFor = Exception.class)
    public SkuSuggestedPriceStateDO refresh(PlatformSkuDO sku, SuggestedSalePriceResolver.Resolution result,
                                           LocalDateTime nextCheckAt, LocalDateTime now) {
        SkuSuggestedPriceStateDO old = mapper.selectById(sku.getId());
        if (!SuggestedSalePriceResolver.isDynamic(sku)) {
            if (old != null && mapper.deleteById(sku.getId()) != 1) {
                throw new IllegalStateException("suggested price state disappeared");
            }
            return null;
        }
        String digest = SuggestedSalePriceResolver.ruleDigest(sku.getSuggestedSalePriceRuleJson());
        if (old != null && Objects.equals(old.getRuleDigest(), digest)
                && Objects.equals(old.getCalculationStatus(), result.status().name())
                && Objects.equals(old.getEffectivePriceCents(), result.amountCents())
                && Objects.equals(old.getBasePriceSnapshotCents(), result.basePriceSnapshotCents())
                && Objects.equals(old.getUnavailableReason(), result.unavailableReason())
                && Objects.equals(old.getNextCheckAt(), nextCheckAt)) return old;

        boolean successful = result.status() == SuggestedPriceStatus.READY;
        boolean changedSuccess = successful && (old == null
                || !SuggestedPriceStatus.READY.name().equals(old.getCalculationStatus())
                || !Objects.equals(old.getLastValidPriceCents(), result.amountCents())
                || !Objects.equals(old.getRuleDigest(), digest));
        SkuSuggestedPriceStateDO next = SkuSuggestedPriceStateDO.builder().platformSkuId(sku.getId())
                .ruleDigest(digest).calculationStatus(result.status().name()).effectivePriceCents(result.amountCents())
                .basePriceSnapshotCents(result.basePriceSnapshotCents()).unavailableReason(result.unavailableReason())
                .lastValidPriceCents(successful ? result.amountCents() : old == null ? null : old.getLastValidPriceCents())
                .lastValidCalculatedAt(changedSuccess ? now : old == null ? null : old.getLastValidCalculatedAt())
                .nextCheckAt(nextCheckAt).calculatedAt(now).revision(old == null ? 1 : Math.addExact(old.getRevision(), 1))
                .build();
        int updated = old == null ? mapper.insert(next) : mapper.updateById(next);
        if (updated != 1) throw new IllegalStateException("suggested price state write failed");
        return next;
    }
}
