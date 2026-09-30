package com.aifuli.module.supplychain.routing.domain;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 一次路由计算的不可变输入；时间和动态事实由调用方显式传入。
 *
 * <p>projectId 可空（受理链路未解析到项目时），deliveryAdcode 可空（无收货地区时跳过区域筛选）；
 * 范围匹配在 application 层完成，
 * 引擎只消费匹配后的候选与排序配置。
 * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#3-规则匹配}
 * 与 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#5-配送区域}。</p>
 */
public record RoutingInput(Long buyerId, Long projectId, Long platformSkuId, String fulfillmentType,
                           long quantity, LocalDateTime businessTime,
                           Map<String, CandidateFacts> factsBySupplierCode, String deliveryAdcode) {

    /** 兼容现有无收货地区的标准虚拟商品受理输入。 */
    public RoutingInput(Long buyerId, Long projectId, Long platformSkuId, String fulfillmentType,
                        long quantity, LocalDateTime businessTime,
                        Map<String, CandidateFacts> factsBySupplierCode) {
        this(buyerId, projectId, platformSkuId, fulfillmentType, quantity,
                businessTime, factsBySupplierCode, null);
    }

    public RoutingInput {
        if (buyerId == null || platformSkuId == null || fulfillmentType == null
                || fulfillmentType.isBlank() || quantity <= 0 || businessTime == null) {
            throw new IllegalArgumentException("Routing input is incomplete");
        }
        factsBySupplierCode = factsBySupplierCode == null
                ? Map.of() : Map.copyOf(factsBySupplierCode);
        deliveryAdcode = deliveryAdcode == null || deliveryAdcode.isBlank()
                ? null : deliveryAdcode.trim();
        if (deliveryAdcode != null && !deliveryAdcode.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("收货行政区划编码必须为六位数字");
        }
    }

    public CandidateFacts factsFor(String supplierCode) {
        return factsBySupplierCode.getOrDefault(supplierCode, CandidateFacts.empty());
    }

    public enum FactStatus {
        AVAILABLE,
        UNAVAILABLE,
        UNKNOWN,
        ERROR
    }

    /** amount 仅用于余额事实；库存和健康事实可为空。 */
    public record Fact(FactStatus status, Long amount,
                       LocalDateTime observedAt, LocalDateTime validUntil) {
        public Fact {
            if (status == null || observedAt == null || validUntil == null
                    || !validUntil.isAfter(observedAt) || (amount != null && amount < 0)) {
                throw new IllegalArgumentException("Routing fact is invalid");
            }
        }
    }

    public record CandidateFacts(Fact inventory, Fact balance, Fact health) {
        public static CandidateFacts empty() {
            return new CandidateFacts(null, null, null);
        }
    }
}
