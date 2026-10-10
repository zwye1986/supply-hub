package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerCatalogRefreshOutcome;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;

import java.time.LocalDateTime;

/**
 * 单次目录刷新的不可变真实结果：执行码、真实生命周期、真实供给投影、稳定原因与评估时间分开。
 * 技术失败由管理编排映射 FAILED，刷新门面自身继续抛出；不用执行码冒充生命周期。
 * 设计见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#task-3-实施接口与事务边界。
 */
public record BuyerSelectionCatalogRefreshResult(BuyerCatalogRefreshOutcome refreshOutcome,
                                                 BuyerListingStatus publicationState,
                                                 ListingAvailability availabilityStatus,
                                                 Reason reasonCode,
                                                 LocalDateTime evaluatedAt) {

    /** 封闭等待／失败原因；REQUIRED事实等原始原因保留在可用性basis中。 */
    public enum Reason {
        PRICING_NOT_READY,
        PRICING_DEPENDENCY_STALE,
        PRODUCT_NOT_SELLABLE,
        BUYER_PRODUCT_REMOVED,
        QUALIFICATION_UNKNOWN,
        QUALIFICATION_UNAVAILABLE,
        SYSTEM_OPERATOR_NOT_CONFIGURED
    }
}
