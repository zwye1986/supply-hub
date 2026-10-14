package com.aifuli.module.supplychain.border.fulfillment.application.huashengflow;

/**
 * 华昇订单履约失败的平台稳定原因码。
 *
 * <p>职责：分事实保存三个维度中的“平台稳定失败原因”维度——
 * 供应商原始状态／错误码保存在原始观察（{@code sc_raw_status_observation}），
 * 平台稳定原因写入 BOrder 终结原因列与状态日志，价格差异独立留存在金额与结算状态列。
 *
 * <p>不变量：原因码封闭集合，不把金额不匹配伪称为供应商取消原因；
 * 缺少完整判据时不得使用对应终结原因（见各码注释）。
 *
 * <p>设计路径与章节锚点：
 * docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#33-原因记录。
 */
public enum HuashengStableFailureReason {

    /** 供应商原单已取消且零交付、退款成功的可信取消；需要 QUERY/回调的 refundStatus=SUCCESS 与 isTake=N 证明。 */
    HUASHENG_SUPPLIER_CANCELLED("供应商已取消"),

    /** 供应商原单终态失败且零交付、退款成功；协议事实保留 FAILED。 */
    HUASHENG_SUPPLIER_FAILED("供应商履约失败"),

    /** 供应商未建单的最终履约失败；需要首次创建拒绝、四次有效 7014 查询及共同安全检查完整判据。 */
    HUASHENG_NOT_CREATED("供应商未建单，订单履约失败"),

    /** 供应商在订单创建前的明确请求拒绝（参数校验失败、前置安全配置拒绝、账号/绑定拒绝）。 */
    HUASHENG_REQUEST_REJECTED("供应商拒绝请求"),

    /** 初始授权暂时故障耗尽发送前预算且证明没有创建业务请求。 */
    HUASHENG_AUTH_UNAVAILABLE("授权不可用"),

    /** 实报金额与冻结报价不符的独立差异事实；不覆盖冻结报价，也不阻止已证实的零交付失败关单。 */
    HUASHENG_PRICE_MISMATCH("价格差异");

    private final String chineseDescription;

    HuashengStableFailureReason(String chineseDescription) {
        this.chineseDescription = chineseDescription;
    }

    public String chineseDescription() {
        return chineseDescription;
    }
}
