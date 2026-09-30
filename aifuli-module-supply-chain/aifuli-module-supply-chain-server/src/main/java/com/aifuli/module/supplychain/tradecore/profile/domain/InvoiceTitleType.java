package com.aifuli.module.supplychain.tradecore.profile.domain;

/**
 * 开票抬头类型。
 * 对应系统数据字典：{@code supply_chain_invoice_title_type}（开票抬头类型）。
 * 设计见 {@code docs/superpowers/specs/2026-09-07-供应链管理端开票信息设计.md#31-抬头类型数据字典-supply_chain_invoice_title_type}。
 */
public enum InvoiceTitleType {
    ENTERPRISE,
    PERSONAL
}
