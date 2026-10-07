package com.aifuli.module.supplychain.supplieradapter.huasheng.failure;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics.HuashengFailureKind;
import java.util.Optional;
/** 保留真实原因，只同包检查；禁止外部表达原链。见 docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §9.1、§14.2。 */
@com.fasterxml.jackson.databind.annotation.JsonSerialize(using = HuashengInternalOnlySerializer.class)
public final class HuashengProtectedFailure {
    private final HuashengFailureKind failureKind;
    private final Throwable originalCause;
    private final Optional<HuashengExchangeEvidence> safeEvidence;
    private HuashengProtectedFailure(HuashengFailureKind kind, Throwable cause, Optional<HuashengExchangeEvidence> evidence) {
        this.failureKind=kind; this.originalCause=cause; this.safeEvidence=evidence;
    }
    /**
     * 捕获一次协议失败：固定失败类别 + 原始异常同一引用 + 已脱敏证据容器。
     *
     * <p>职责：保留真实内部因果（含完整 cause/suppressed 链），是 §9.1 单向诊断投影的唯一起点。
     *
     * <p>不变量：originalCause 保存同一 Throwable 引用，不改其 message/cause、不删因果、不另存正文；
     * 原始正文只存在于本对象，公开 API 无 Throwable/getCause/getMessage getter，仅同 failure 包可经
     * {@code originalCauseForInternalInspection()} 检查；toString 固定失败类别，不迭代原 cause 链；
     * 本对象不实现 Serializable。
     *
     * <p>关键决策：capture 不访问原 message/toString，也不复制或打印入参；三参均非空，否则以固定
     * 安全说明拒绝；证据容器只允许安全枚举与已脱敏证据，空证据明确为 {@code Optional.empty()}
     * 而非伪造端点事实。
     *
     * <p>设计：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §9.1、§14.2。
     *
     * @param kind 失败类别，非空
     * @param cause 原始异常，非空，同一引用被保留
     * @param evidence 已脱敏通信证据容器，非空
     * @return 仅同包/内部链消费的受保护失败对象
     */
    public static HuashengProtectedFailure capture(HuashengFailureKind kind, Throwable cause, Optional<HuashengExchangeEvidence> evidence) {
        java.util.Objects.requireNonNull(kind,"缺少失败类别");
        java.util.Objects.requireNonNull(cause,"缺少内部原因");
        java.util.Objects.requireNonNull(evidence,"缺少安全证据容器");
        return new HuashengProtectedFailure(kind,cause,evidence);
    }
    Throwable originalCauseForInternalInspection() { return originalCause; }
    HuashengFailureKind failureKind() { return failureKind; }
    Optional<HuashengExchangeEvidence> safeEvidence() { return safeEvidence; }
    @Override public String toString() { return "HuashengProtectedFailure["+failureKind+"]"; }
}
