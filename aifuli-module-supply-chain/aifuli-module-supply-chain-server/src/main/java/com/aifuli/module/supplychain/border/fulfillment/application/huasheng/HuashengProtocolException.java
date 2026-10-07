package com.aifuli.module.supplychain.border.fulfillment.application.huasheng;
import com.aifuli.module.supplychain.supplieradapter.huasheng.failure.HuashengFailureDiagnostics;
/** 公开异常仅持安全诊断，不接收原链。见 docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §9.1、§14.2。 */
@com.fasterxml.jackson.databind.annotation.JsonSerialize(using = HuashengProtocolException.SafeSerializer.class)
public final class HuashengProtocolException extends RuntimeException {
    private final HuashengFailureDiagnostics diagnostics;
    private HuashengProtocolException(HuashengFailureDiagnostics diagnostics) {
        super("HUA_SHENG 协议失败", null, false, true); this.diagnostics=diagnostics;
    }
    /**
     * 从安全诊断构造公开协议异常；是失败处理链的唯一对外异常入口。
     *
     * <p>职责：把 {@link HuashengFailureDiagnostics} 包装为可被全局异常日志安全打印的公开异常，
     * 供应用 facade 在失败终止时对外抛出。
     *
     * <p>不变量：diagnostics 非空；异常 message 固定为“HUA_SHENG 协议失败”，cause 恒为 null 且
     * suppression 关闭（构造参数 {@code super(message, null, false, true)}）；任何 {@code initCause}
     * 调用以固定文本拒绝（见 {@link #initCause(Throwable)}），addSuppressed 不保存原链；只保留安全
     * 自身栈，无 ProtectedFailure/InternalAttempt/原 cause 引用。
     *
     * <p>关键决策：安全异常不能反向被协议包引用；原始因果仍保留在 {@code HuashengProtectedFailure}
     * 中供同包受控检查，本异常只是单向投影终点的公开表达。普通/ANY Mapper 编码由类型级固定安全
     * serializer 保证，不反射 Throwable 私有字段。
     *
     * <p>设计：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §9.1、§14.2。
     *
     * @param diagnostics 安全诊断，非空
     * @return 只含安全诊断的公开异常
     */
    public static HuashengProtocolException fromDiagnostics(HuashengFailureDiagnostics diagnostics) { return new HuashengProtocolException(java.util.Objects.requireNonNull(diagnostics,"缺少安全诊断")); }
    /**
     * 固定安全拒绝覆盖原始原因：本公开异常不允许挂载原链。
     *
     * <p>关键决策：不调用传入 Throwable 的任何方法（toString/message/stack），不返回或保存其引用；
     * 拒绝异常文本固定为「不允许覆盖安全异常的原始原因」。默认 {@code Throwable.initCause} 的拒绝
     * 消息会调用入参的 toString 并拼进新 IllegalStateException 文本，本覆写切断这条泄漏路径
     * （Q-SEC-01）。
     *
     * <p>设计：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §9.1、§14.2、§14.7。
     *
     * @param cause 拒绝挂载的原链，本方法不读取、不保存
     * @return 永不返回（始终抛出固定文本的 IllegalStateException）
     */
    @Override
    public final Throwable initCause(Throwable cause) { throw new IllegalStateException("不允许覆盖安全异常的原始原因"); }
    @com.fasterxml.jackson.annotation.JsonProperty("diagnostics")
    public HuashengFailureDiagnostics diagnostics() { return diagnostics; }
    @Override public String toString() { return "HuashengProtocolException["+diagnostics+"]"; }
    /** 不反射Throwable私有字段，固定安全编码在普通/ANY Mapper保持相同。 */
    static final class SafeSerializer extends com.fasterxml.jackson.databind.JsonSerializer<HuashengProtocolException> {
        @Override public void serialize(HuashengProtocolException value, com.fasterxml.jackson.core.JsonGenerator generator,
                com.fasterxml.jackson.databind.SerializerProvider serializers) throws java.io.IOException {
            generator.writeStartObject();
            generator.writeStringField("message","HUA_SHENG 协议失败");
            generator.writeFieldName("diagnostics"); serializers.defaultSerializeValue(value.diagnostics,generator);
            generator.writeEndObject();
        }
    }
}
