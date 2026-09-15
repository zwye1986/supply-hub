package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 标准履约受理拒单码；持久化使用 {@link #name()}，与 {@code SupplyChainErrorCodeConstants} 同名对齐。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum AcceptanceRejectionCode {
    /** 一期履约数量必须为一。 */
    QUANTITY_NOT_SUPPORTED("一期履约数量必须为一"),
    /** 履约目标不符合已发布 Schema。 */
    INVALID_TARGET("履约目标不符合已发布 Schema"),
    /** 采购商商品版本已失效。 */
    LISTING_VERSION_EXPIRED("采购商商品版本已失效"),
    /** 标准商品当前不可售。 */
    SKU_NOT_SALEABLE("标准商品当前不可售"),
    /** 可兑现供给不足。 */
    INVENTORY_INSUFFICIENT("可兑现供给不足"),
    /** 没有可路由的供应商。 */
    NO_ROUTABLE_SUPPLIER("没有可路由的供应商"),
    /** 采购商资金账户未开户。 */
    BUYER_ACCOUNT_NOT_OPENED("采购商资金账户未开户"),
    /** 采购商可用余额不足；一期不受理路径抛出，保留给二期恢复余额门槛。 */
    BUYER_BALANCE_INSUFFICIENT("采购商可用余额不足"),
    /** 当前项目下该用户购买数量已达到 SKU 周期限购上限。 */
    USER_PURCHASE_LIMIT_EXCEEDED("当前项目下该用户购买数量已达到SKU周期限购上限"),
    /** 本次购买数量超过 SKU 每订单限购上限。 */
    ORDER_PURCHASE_LIMIT_EXCEEDED("本次购买数量超过SKU每订单限购上限"),
    /** 当前项目下该履约手机号购买数量已达到 SKU 周期限购上限。 */
    PHONE_BIND_PURCHASE_LIMIT_EXCEEDED("当前项目下该履约手机号购买数量已达到SKU周期限购上限"),
    /** 绑定手机号限购要求履约目标为手机号。 */
    PHONE_BIND_TARGET_REQUIRED("绑定手机号限购要求履约目标为手机号");

    /** 中文说明，与错误目录 / DDL rejection_code 语义一致。 */
    private final String chineseName;
}
