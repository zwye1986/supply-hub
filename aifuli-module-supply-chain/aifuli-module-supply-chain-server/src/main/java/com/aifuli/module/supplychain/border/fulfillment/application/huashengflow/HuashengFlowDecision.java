package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

/**
 * 华昇单步骤事实推进决策枚举。
 * 不允许返回“重下PLACE”（新业务号的再次购买）；RETRY_PRE_SEND 仅限保持原业务身份的原步骤重试。
 * 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#6-协议事实推进规则、
 * docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#4-错误分类规则。
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
    /**
     * 可信供应商取消且零交付、退款成功的最终收敛：BOrder CANCELLED、平台 FAILED、释放本单预留。
     * 设计：2026-10-13 华昇错误分类设计 §3.2。
     */
    FINAL_CANCELLATION,
    /**
     * 可信未建单的最终履约失败：第 4 次有效 7014 查询后自动收敛，
     * BOrder FAILED、原因 HUASHENG_NOT_CREATED、释放本单预留；不要求 code=0 或伪造退款。
     * 设计：2026-10-13 华昇错误分类设计 §5.4。
     */
    FINAL_NOT_CREATED,
    /**
     * 首次创建前的发送前重试：保持原业务身份与原步骤，退避后重新认领；
     * 仅限持久证明本单没有业务副作用，且未超发送前总认领预算。
     * 设计：2026-10-13 华昇错误分类设计 §4.5。
     */
    RETRY_PRE_SEND,
    /** 异常/冲突/超限转人工保留。 */
    MANUAL
}
