package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

/**
 * 华昇单步骤事实推进决策枚举。
 * 不允许返回“重下PLACE”。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#6-协议事实推进规则。
 */
public enum HuashengFlowDecision {
    /** 推进至受控确认步骤。 */
    NEXT_CONFIRM,
    /** 推进至取券步骤。 */
    NEXT_TAKE,
    /** 安排等待/查单恢复。 */
    WAIT_QUERY,
    /** 最终履约成功，触发资金实扣。 */
    FINAL_SUCCESS,
    /** 最终履约明确失败且已退款，触发资金释放。 */
    FINAL_REJECTION,
    /** 异常/冲突/超限转人工保留。 */
    MANUAL
}
