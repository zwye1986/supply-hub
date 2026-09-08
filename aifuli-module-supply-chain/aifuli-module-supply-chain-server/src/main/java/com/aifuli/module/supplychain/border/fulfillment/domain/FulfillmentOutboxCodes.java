package com.aifuli.module.supplychain.border.fulfillment.domain;

/**
 * 标准履约受理产生的 Outbox 聚合类型、事件类型与投递目标稳定编码。
 *
 * <p>详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#721-标准履约稳定编码与-java-领域枚举}。</p>
 */
public final class FulfillmentOutboxCodes {

    private FulfillmentOutboxCodes() {
    }

    public static final String AGGREGATE_B_ORDER = "B_ORDER";
    public static final String AGGREGATE_SUPPLY_CHAIN_ORDER = "SUPPLY_CHAIN_ORDER";
    public static final String AGGREGATE_BUYER_ORDER = "BUYER_ORDER";

    public static final String EVENT_FULFILLMENT_OPERATION_READY = "FULFILLMENT_OPERATION_READY";
    public static final String EVENT_SUPPLY_CHAIN_ORDER_CHANGED = "SUPPLY_CHAIN_ORDER_CHANGED";
    public static final String EVENT_BUYER_ORDER_SUMMARY_CHANGED = "BUYER_ORDER_SUMMARY_CHANGED";

    public static final String TARGET_FULFILLMENT_OPERATION_WORKER = "fulfillment-operation-worker";
    public static final String TARGET_SUPPLY_CHAIN_ORDER_EVENT = "supply-chain-order-event";
    public static final String TARGET_BUYER_EVENT_RPC = "buyer-event-rpc";

    public static final String PAYLOAD_ORDER_STATUS_PROCESSING = "PROCESSING";
}
