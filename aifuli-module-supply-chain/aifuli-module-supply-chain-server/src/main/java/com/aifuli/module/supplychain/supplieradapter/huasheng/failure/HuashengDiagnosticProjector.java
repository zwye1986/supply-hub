package com.aifuli.module.supplychain.supplieradapter.huasheng.failure;
import static com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.*;
/** 只做安全投影，不能复制原输入。见 docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §9.1、§14.2。 */
public final class HuashengDiagnosticProjector {
    private HuashengDiagnosticProjector() { }
    /**
     * 单向安全投影：把内部受保护失败转换为不含任何原链引用的不可变 {@link HuashengFailureDiagnostics}。
     *
     * <p>职责：只按原 cause 的受控类型匹配产出 causeKind，不读取原 message/toString、不复制
     * stack/suppressed、不输出原链；投影结果公开后无法恢复内部因果。
     *
     * <p>不变量：匹配顺序固定——JsonProcessingException→JSON_PARSE（必须在 IO 前）、
     * SocketTimeoutException→TIMEOUT、其余 IOException→IO、GeneralSecurityException→CRYPTO、
     * IllegalArgumentException/ArithmeticException→VALIDATION、其余→OTHER；kind 透传 failureKind，
     * reason 1:1 映射为 valueOf(failure.failureKind().name())；证据容器按原引用透传，不复制或重算。
     *
     * <p>关键决策：不遍历原因链、不包装新异常类型；新增包装异常分类或原因链遍历属后片契约，
     * 不由实现者猜测。内部原始对象保留至本次失败处理结束，无永久诊断仓库。
     *
     * <p>设计：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §9.1、§14.2、§15.2。
     *
     * @param failure 受保护失败，非空；原始原因仅作类型匹配
     * @return 完全脱离原链的公开安全诊断
     */
    public static HuashengFailureDiagnostics toSafeDiagnostics(HuashengProtectedFailure failure) {
        java.util.Objects.requireNonNull(failure,"缺少内部失败值");
        Throwable original = failure.originalCauseForInternalInspection();
        HuashengCauseKind kind;
        if (original instanceof com.fasterxml.jackson.core.JsonProcessingException) kind=HuashengCauseKind.JSON_PARSE;
        else if (original instanceof java.net.SocketTimeoutException) kind=HuashengCauseKind.TIMEOUT;
        else if (original instanceof java.io.IOException) kind=HuashengCauseKind.IO;
        else if (original instanceof java.security.GeneralSecurityException) kind=HuashengCauseKind.CRYPTO;
        else if (original instanceof IllegalArgumentException || original instanceof ArithmeticException) kind=HuashengCauseKind.VALIDATION;
        else kind=HuashengCauseKind.OTHER;
        return new HuashengFailureDiagnostics(failure.failureKind(), HuashengFailureReasonCode.valueOf(failure.failureKind().name()),
            kind, failure.safeEvidence());
    }
}
