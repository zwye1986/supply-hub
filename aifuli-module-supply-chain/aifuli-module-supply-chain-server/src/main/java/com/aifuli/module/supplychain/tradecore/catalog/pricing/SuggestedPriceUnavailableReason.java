package com.aifuli.module.supplychain.tradecore.catalog.pricing;

/** 建议价可恢复业务原因；数据库异常不能转换成这些状态。 */
public enum SuggestedPriceUnavailableReason { EFFECTIVE_SUPPLY_PRICE_MISSING, SUGGESTED_PRICE_CALCULATION_FAILED }
