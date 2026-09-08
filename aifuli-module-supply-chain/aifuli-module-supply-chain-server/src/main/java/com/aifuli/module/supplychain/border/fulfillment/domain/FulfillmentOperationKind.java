package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 履约操作类型，对应 {@code sc_fulfillment_operation.operation_kind}。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum FulfillmentOperationKind {
    /** 履约：向供应商发起主履约副作用。 */
    FULFILL("履约"),
    /** 查单：查询供应商侧订单结果。 */
    QUERY_ORDER("查单"),
    /** 查资产：查询供应商侧已交付资产。 */
    QUERY_ASSET("查资产"),
    /** 作废资产：作废已交付资产。 */
    VOID_ASSET("作废资产"),
    /** 解绑资产：解绑账户绑定类权益。 */
    UNBIND_ASSET("解绑资产");

    /** 中文名称，与 DDL COMMENT 一致。 */
    private final String chineseName;
}
