package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerSkuPricingVersionDO;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericPricingCalculator.PricingStatus;

import java.util.Objects;

/**
 * 自动商业快照的共享价格链口径：READY 存量标记不单独授权，必须核真实来源与依赖指针。
 * 刷新 worker 与人工恢复共用本判定，避免两处口径漂移；缺价格可恢复，已指向不存在或跨归属版本为损坏引用。
 * 设计见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#task-3-实施接口与事务边界。
 */
public final class BuyerPricingReadiness {

    /** NOT_READY缺价格可等待；READY可用于自动发布；STALE依赖已前进需重算；BROKEN为损坏引用技术失败。 */
    public enum State { NOT_READY, READY, STALE, BROKEN }

    public record Result(State state, BuyerSkuPricingVersionDO version) { }

    private BuyerPricingReadiness() {
    }

    public static Result evaluate(BuyerSkuPricingDO identity, BuyerSkuPricingVersionDO version,
                                  Long platformCurrentVersionId) {
        if (identity == null || identity.getCurrentVersionId() == null) {
            return new Result(State.NOT_READY, null);
        }
        if (version == null || !Objects.equals(identity.getId(), version.getPricingId())) {
            return new Result(State.BROKEN, null);
        }
        if (!PricingStatus.READY.name().equals(identity.getPricingStatus())
                || !PricingStatus.READY.name().equals(version.getPricingStatus())
                || version.getDistributionPriceCents() == null || version.getDistributionPriceCents() < 0
                || !Money.CURRENCY_CNY.equals(version.getCurrency())) {
            return new Result(State.NOT_READY, version);
        }
        if (!Objects.equals(version.getPlatformPricingVersionId(), platformCurrentVersionId)) {
            return new Result(State.STALE, version);
        }
        return new Result(State.READY, version);
    }
}
