package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;

/**
 * 华昇供应商业务错误码封闭枚举（共 43 个常量：42 个已知业务码 + 1 个 UNKNOWN 兜底）。
 *
 * <p>职责：对华昇 OpenAPI 定义的全部已知返回码（code != 0）进行强类型分类定义，
 * 保留原始数字码（rawCode），并为未知或未声明错误码提供安全的 UNKNOWN 兜底。
 *
 * <p>不变量：42 个已知错误码严格对应官方文档定义，不写区间；无无条件的 retryable 布尔字段；
 * 4002 与 7003 同名“经销商禁用”区分为 {@link #DEALER_DISABLED} 与 {@link #ORDER_DEALER_DISABLED}；
 * 目录码（5001–5005、6001–6005）及未来新增未知码统一归为 {@link #UNKNOWN}，原始 rawCode 严格保真。
 *
 * <p>关键决策：SUCCESS(0) 常量存在但解析器永不输出 Business(0, ...)；未匹配错误码统一映射为 UNKNOWN，
 * 不把未知码改写为 -1。
 *
 * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#73-错误码表与策略边界、§15.2.4。
 */
public enum HuashengErrorCode {
    SUCCESS(0),
    SERVER_ERROR(50),
    PARAM_VALIDATION(51),
    TOKEN_UNAUTHORIZED(61),
    SECURITY_VALIDATION(66),
    TOKEN_FAILURE(4001),
    DEALER_DISABLED(4002),
    AGENT_NOT_FOUND(4003),
    DEALER_QUERY_FAILED(7001),
    DEALER_NOT_FOUND(7002),
    ORDER_DEALER_DISABLED(7003),
    CARD_QUERY_FAILED(7004),
    CARD_NOT_FOUND(7005),
    CARD_OFF_SALE(7006),
    BRAND_NOT_FOUND(7007),
    BRAND_OFF_SALE(7008),
    DEALER_CARD_QUERY_FAILED(7009),
    CARD_NOT_ALLOCATED_TO_DEALER(7010),
    DEALER_CARD_OFF_SALE(7011),
    ORDER_QUERY_FAILED(7012),
    ORDER_DETAIL_QUERY_FAILED(7013),
    ORDER_NOT_FOUND(7014),
    ORDER_STATUS_INVALID(7015),
    BALANCE_FLOW_ERROR(7016),
    SYSTEM_BUSY(7017),
    BALANCE_INSUFFICIENT(7018),
    BALANCE_UPDATE_ERROR(7019),
    BALANCE_CHANGE_ERROR(7020),
    STOCK_DEDUCTION_ERROR(7021),
    STOCK_INSUFFICIENT(7022),
    ORDER_CREATION_ERROR(7023),
    BRAND_QUERY_ERROR(7024),
    ORDER_IDENTIFIER_CONFLICT(7025),
    CONFIRM_ERROR(7101),
    ALREADY_CANCELLED(7201),
    CARD_OBTAINED_CANNOT_CANCEL(7202),
    ORDER_STATUS_UPDATE_FAILED(7203),
    ORDER_STATUS_ALREADY_CHANGED(7204),
    STOCK_REFUND_FAILED(7205),
    BALANCE_REFUND_FAILED(7206),
    CARD_QUERY_ERROR(7301),
    TAKE_STATUS_UPDATE_ERROR(7302),
    UNKNOWN(null);

    private final Integer rawCode;

    HuashengErrorCode(Integer rawCode) {
        this.rawCode = rawCode;
    }

    /**
     * 将供应商原始整型错误码解析为强类型错误码枚举。
     *
     * <p>职责：将华昇 OpenAPI 返回的数字错误码（code != 0）映射到已知的枚举常量；
     * 对未在文档中声明或未识别的错误码，安全归入 {@link #UNKNOWN} 兜底。
     *
     * <p>不变量：42 个已知错误码数值精确 1:1 匹配；4002 与 7003 同名错误分别映射为 {@link #DEALER_DISABLED}
     * 与 {@link #ORDER_DEALER_DISABLED}；目录类错误码（5001–5005、6001–6005）及任何未匹配码恒返回 {@link #UNKNOWN}；
     * 绝不抛出异常。
     *
     * <p>关键决策：不在此处将原始错误码改写为 -1 或其他魔法值；原始 rawCode 仍由外层载荷单独保真；
     * 映射结果供后续分类器做安全诊断投影，不包含任何外部网络重试判定。
     *
     * <p>设计路径与章节锚点：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md#73-错误码表与策略边界、§15.2.4。
     *
     * @param rawCode 供应商返回的原始整型错误码数值
     * @return 对应的已知错误码枚举；若未知则返回 {@link #UNKNOWN}
     */
    public static HuashengErrorCode fromRawCode(int rawCode) {
        for (HuashengErrorCode code : values()) {
            if (code.rawCode != null && code.rawCode == rawCode) {
                return code;
            }
        }
        return UNKNOWN;
    }

    Integer rawCode() {
        return rawCode;
    }
}
