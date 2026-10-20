package com.aifuli.module.supplychain.tradecore.catalog.pricing;

/** SKU建议价状态，独立于下游交易定价状态。设计见2026-10-19建议价设计§2.2。 */
public enum SuggestedPriceStatus { READY, DEPENDENCY_MISSING, CALCULATION_FAILED }
