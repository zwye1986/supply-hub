package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengInternalAttempt;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengProtectedFailure;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;
/** 严格金额纯组件。见 docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §5.2、§14.2。 */
public final class HuashengMoneyParser {
    private static final int MAX_TEXT_CHARACTERS = 32;
    private static final Pattern PRICE_LEXICAL = Pattern.compile("[0-9]+(\\.[0-9]{1,4})?");
    private static final Pattern FACE_LEXICAL = Pattern.compile("[0-9]+(\\.[0-9]{1,2})?");
    private HuashengMoneyParser() { }
    /**
     * 严格解析 amount/totalAmount 面值原文为不可变 {@link HuashengFaceAmount}（仅保存 rawYuan）。
     *
     * <p>职责：只做词法接受检查并保留原文。面值不提供分值，不因 raw-only 面值新增 Long 分上限。
     *
     * <p>不变量：接受词法 {@code [0-9]+(\.[0-9]{1,2})?}，最多 32 字符、最多 2 位小数；不 trim、
     * 不接受负数/正号/指数/逗号/空白/NaN/Infinity；32 个 9 的整数面值仍合法。所有非法输入统一
     * 进入 {@code Failed} 分支且不外抛解析异常；失败证据恒为 {@code Optional.empty()}，不伪造发送事实。
     *
     * <p>关键决策：面值与成本分离，禁止从面值推导成本或把面值换算为分值；词法拒绝创建固定说明
     * IllegalArgumentException 作为真实校验原因（causeKind 投影为 VALIDATION），不捕获 Error/OOME。
     *
     * <p>设计：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §5.2、§14.2。
     *
     * @param rawYuan 待解析面值原文；不得记录到日志或对外诊断
     * @return 成功携带 {@link HuashengFaceAmount}，失败为 kind/reason 均 MONEY_INVALID 的内部失败
     */
    public static HuashengInternalAttempt<HuashengFaceAmount> parseFaceAmount(String rawYuan) {
        if (!validLexical(rawYuan, FACE_LEXICAL)) return invalid(new IllegalArgumentException("金额非法"));
        return HuashengInternalAttempt.parsed(new HuashengFaceAmount(rawYuan));
    }
    /**
     * 严格解析 price/totalPrice 金额原文为不可变 {@link HuashengPrice}（rawYuan + 分值）。
     *
     * <p>职责：把 ASCII 定点金额原文精确换算为 0…Long.MAX_VALUE 之间的分值，不改变原文语义。
     * 仅合法金额进入 {@code Parsed} 分支；所有非法输入统一进入 {@code Failed} 分支且不外抛解析异常。
     *
     * <p>不变量：接受词法 {@code [0-9]+(\.[0-9]{1,4})?}，最多 32 字符，不 trim、不接受负数/正号/
     * 指数/逗号/空白/NaN；用 BigDecimal 原文精确解析，元×100 后 HALF_UP 只舍入一次（0.0049→0、
     * 0.0050→1、9.9999→1000），longValueExact；0 分是合法协议金额事实，不在 parser 内改成 1 分；
     * 失败证据恒为 {@code Optional.empty()}，不伪造发送事实。
     *
     * <p>关键决策：先词法限长再创建 BigDecimal，防止超大文本触发无界分配；整笔先乘 100 再舍入，
     * 禁止 round(price)×num 或从面值/总面额推导成本；捕获 NumberFormatException/ArithmeticException
     * 保存原对象为内部因果，词法拒绝创建固定说明 IllegalArgumentException 作为真实校验原因，
     * 不捕获 Error/OOME。
     *
     * <p>设计：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §5.2、§14.2。
     *
     * @param rawYuan 待解析金额原文；不得记录到日志或对外诊断
     * @return 成功携带 {@link HuashengPrice}，失败为 kind/reason 均 MONEY_INVALID、causeKind 投影 VALIDATION 的内部失败
     */
    public static HuashengInternalAttempt<HuashengPrice> parsePrice(String rawYuan) {
        if (!validLexical(rawYuan, PRICE_LEXICAL)) return invalid(new IllegalArgumentException("金额非法"));
        try {
            // 整笔先乘100，再只舍入一次；ASCII词法已保证非负，不做面值成本推导。
            long minorUnits = new BigDecimal(rawYuan).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            return HuashengInternalAttempt.parsed(new HuashengPrice(rawYuan, minorUnits));
        } catch (NumberFormatException | ArithmeticException original) {
            return invalid(original);
        }
    }
    private static boolean validLexical(String raw, Pattern lexical) {
        // BigDecimal创建前先限长，不trim、不接受Unicode数字或指数。
        return raw != null && raw.length() <= MAX_TEXT_CHARACTERS && lexical.matcher(raw).matches();
    }
    private static <T> HuashengInternalAttempt<T> invalid(Throwable original) {
        // 纯字符串helper没有外呼，禁止为了端点字段制造发送事实。
        return HuashengInternalAttempt.failed(HuashengProtectedFailure.capture(HuashengFailureKind.MONEY_INVALID, original, Optional.empty()));
    }
}
