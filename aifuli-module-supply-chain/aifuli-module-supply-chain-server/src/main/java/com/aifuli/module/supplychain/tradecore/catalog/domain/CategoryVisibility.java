package com.aifuli.module.supplychain.tradecore.catalog.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 商品分类前端展示可见性，对应 {@code sc_product_category.visible} DDL COMMENT
 * 「前端展示可见性: 1-显示, 0-隐藏」。
 *
 * <p>与 {@code status} 正交：{@code status} 控制类目是否可用于业务表单，{@code visible} 只控制前端是否展示。
 * 节点对外的 {@code effectiveVisible} 还要求全部祖先同样可见。</p>
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#33-商品分类树管理无限级同级防重与层级重算}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum CategoryVisibility {
    HIDDEN((short) 0, "隐藏"),
    VISIBLE((short) 1, "显示");

    private final short code;
    private final String chineseName;

    /** 判定单个节点自身是否可见；{@code null} 视为不可见，由调用方决定是否回落到默认值。 */
    public static boolean isVisible(Short code) {
        return code != null && code == VISIBLE.code;
    }
}
