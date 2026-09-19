package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 履约操作封闭标准结果分类，对应 {@code sc_fulfillment_operation.result_class}
 * 与 {@code sc_status_mapping_definition.fact_type}。
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#731-operationexecutorport-契约模型}。</p>
 */
@Getter
@RequiredArgsConstructor
public enum OperationOutcomeClass {

    /** 供应商已受理，处于处理中或异步等待中（需后续回调或查单推进）。 */
    ACCEPTED("已受理", false),

    /** 供应商履约事实成功。 */
    SUCCEEDED("成功", true),

    /** 部分成功（一期不支持，触发转 MANUAL）。 */
    PARTIALLY_SUCCEEDED("部分成功", true),

    /** 供应商明确业务拒绝或失败（终态失败）。 */
    REJECTED("明确拒绝", true),

    /** 供应商侧未找到订单（查单专用事实）。 */
    NOT_FOUND("订单不存在", false),

    /** 网络超时、连接失败或未知异常，需保持原状态由定时器查单恢复。 */
    RESULT_UNKNOWN("结果未知", false),

    /** 协议严重不一致或数据损坏，需人工介入处置。 */
    MANUAL_REQUIRED("需人工处理", false);

    /** 中文名称，与 DDL CHECK / COMMENT 对齐。 */
    private final String chineseName;

    /** 是否为外部操作协议终态。 */
    private final boolean terminal;

    /**
     * 根据名称安全转换为枚举值。
     */
    public static OperationOutcomeClass fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (OperationOutcomeClass outcome : values()) {
            if (outcome.name().equalsIgnoreCase(name.trim())) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("Unknown OperationOutcomeClass: " + name);
    }
}
