package com.aifuli.module.supplychain.tradecore.profile.domain;

/**
 * L2 调价方式封闭枚举。
 *
 * <p>本期不存在运行时模板层；{@link #FOLLOW_TEMPLATE} 仅保留历史协议名，语义是清除手工
 * L2 覆盖并继承当前 L1。应用服务不得因此查询历史模板价预留表。</p>
 *
 * <p>设计见
 * {@code docs/superpowers/specs/2026-09-12-supply-chain-prd-scope-alignment-design.md#52-l2-与-follow_template}。
 * </p>
 */
public enum L2AdjustMode {
    /** 清除手工 L2 覆盖并继承当前 L1；历史枚举名不代表存在模板价格。 */
    FOLLOW_TEMPLATE,
    /** 将当时的 L1 固化为手工 L2 覆盖。 */
    EQUAL_L1,
    /** 按受控百分比与固定金额计算并固化手工 L2 覆盖。 */
    CUSTOM
}
