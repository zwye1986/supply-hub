package com.aifuli.module.supplychain.tradecore.profile.domain;

/**
 * 历史商品类型常量，仅保留给兼容代码引用；运行时准入以 sc_product_type 为准。为避免与 L2 结算价或
 * {@link com.aifuli.module.supplychain.tradecore.supply.domain.SupplyMode} 混淆，类型名用 Tag 后缀。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端表单字典与标品标签设计.md#22-商品类型-producttypetag}。
 */
public enum ProductTypeTag {
    EQUITY_PRODUCT,
    MOVIE,
    DINING,
    JD,
    VIP
}
