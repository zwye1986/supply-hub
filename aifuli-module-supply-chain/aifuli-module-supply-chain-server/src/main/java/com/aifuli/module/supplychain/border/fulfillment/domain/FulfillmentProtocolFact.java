package com.aifuli.module.supplychain.border.fulfillment.domain;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * 履约协议事实纯标记接口。
 *
 * <p>挂载于 {@link OperationOutcome}，承载供应商特定的协议步骤纯事实（例如华昇多步执行事实）。
 * 仅包含领域纯值，严禁依赖 DAL、Mapper、具体异常或资金动作。
 * 设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#31-边界与责任矩阵}。</p>
 */
@JsonSerialize(using = FulfillmentInternalOnlySerializer.class)
public interface FulfillmentProtocolFact {
}
