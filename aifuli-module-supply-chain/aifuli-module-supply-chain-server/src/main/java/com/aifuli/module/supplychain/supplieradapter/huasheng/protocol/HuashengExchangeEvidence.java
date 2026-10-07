package com.aifuli.module.supplychain.supplieradapter.huasheng.protocol;
/** 不可变安全通信证据，只传事实不推导资金动作；金额失败不制造发送证据。见 docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §14.2。 */
@com.fasterxml.jackson.databind.annotation.JsonSerialize(using = HuashengExchangeEvidence.SafeSerializer.class)
public final class HuashengExchangeEvidence {
    private final HuashengEndpoint endpoint;
    private final java.util.OptionalInt httpStatus;
    private final java.util.OptionalInt rawBusinessCode;
    private final long durationMs;
    private final long requestBytes;
    private final long responseBytes;
    private final int physicalSendCount;
    private final boolean wasBusinessCallStarted;
    /**
     * 构造一条不可变的安全通信证据。
     *
     * <p>职责：只承载 §7.2 已定义的纯协议安全字段，作为失败诊断与通信事实的可审计载体；
     * 不推导资金动作，不制造发送记录。
     *
     * <p>不变量：endpoint/httpStatus/rawBusinessCode 引用非空；durationMs/requestBytes/responseBytes/
     * physicalSendCount 非负；不含秘密、原单号、自由文本或反查句柄；rawBusinessCode 保留原整数，
     * 未知码不归一；所有字段 final，构造后不可变。
     *
     * <p>关键决策：金额解析失败不制造发送证据（对应证据容器为 {@code Optional.empty()}）；
     * 总 tokenSendCount 与 attempts 编排由后片应用结果负责，本证据不冻结实际 HTTP 状态/次数编排；
     * 安全编码由类型级固定 serializer 完成，OptionalInt 为空时明确输出 null。
     *
     * <p>设计：docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §7.2、§14.2。
     *
     * @param endpoint 端点，非空
     * @param httpStatus HTTP 状态容器，非空
     * @param rawBusinessCode 原始业务码容器，非空
     * @param durationMs 耗时毫秒，非负
     * @param requestBytes 请求字节数，非负
     * @param responseBytes 响应字节数，非负
     * @param physicalSendCount 物理发送次数，非负
     * @param wasBusinessCallStarted 是否已发起业务调用
     * @throws IllegalArgumentException 计量字段为负时以固定安全说明拒绝
     */
    public HuashengExchangeEvidence(HuashengEndpoint endpoint, java.util.OptionalInt httpStatus,
            java.util.OptionalInt rawBusinessCode, long durationMs, long requestBytes, long responseBytes,
            int physicalSendCount, boolean wasBusinessCallStarted) {
        java.util.Objects.requireNonNull(endpoint,"缺少端点");
        java.util.Objects.requireNonNull(httpStatus,"缺少HTTP状态容器");
        java.util.Objects.requireNonNull(rawBusinessCode,"缺少业务码容器");
        if (durationMs<0 || requestBytes<0 || responseBytes<0 || physicalSendCount<0) throw new IllegalArgumentException("安全证据计量非法");
        this.endpoint=endpoint; this.httpStatus=httpStatus; this.rawBusinessCode=rawBusinessCode;
        this.durationMs=durationMs; this.requestBytes=requestBytes; this.responseBytes=responseBytes;
        this.physicalSendCount=physicalSendCount; this.wasBusinessCallStarted=wasBusinessCallStarted;
    }
    public HuashengEndpoint endpoint() { return endpoint; }
    public java.util.OptionalInt httpStatus() { return httpStatus; }
    public java.util.OptionalInt rawBusinessCode() { return rawBusinessCode; }
    public long durationMs() { return durationMs; }
    public long requestBytes() { return requestBytes; }
    public long responseBytes() { return responseBytes; }
    public int physicalSendCount() { return physicalSendCount; }
    public boolean wasBusinessCallStarted() { return wasBusinessCallStarted; }
    @Override public String toString() {
        return "HuashengExchangeEvidence["+endpoint+","+httpStatus+","+rawBusinessCode+","+durationMs+","+requestBytes+","+responseBytes+","+physicalSendCount+","+wasBusinessCallStarted+"]";
    }
    /** 固定安全字段编码，OptionalInt为空时明确null，不依赖全局Mapper模块。 */
    static final class SafeSerializer extends com.fasterxml.jackson.databind.JsonSerializer<HuashengExchangeEvidence> {
        @Override public void serialize(HuashengExchangeEvidence value, com.fasterxml.jackson.core.JsonGenerator generator,
                com.fasterxml.jackson.databind.SerializerProvider serializers) throws java.io.IOException {
            generator.writeStartObject();
            generator.writeStringField("endpoint",value.endpoint.name());
            generator.writeFieldName("httpStatus");
            if (value.httpStatus.isPresent()) generator.writeNumber(value.httpStatus.getAsInt()); else generator.writeNull();
            generator.writeFieldName("rawBusinessCode");
            if (value.rawBusinessCode.isPresent()) generator.writeNumber(value.rawBusinessCode.getAsInt()); else generator.writeNull();
            generator.writeNumberField("durationMs",value.durationMs);
            generator.writeNumberField("requestBytes",value.requestBytes);
            generator.writeNumberField("responseBytes",value.responseBytes);
            generator.writeNumberField("physicalSendCount",value.physicalSendCount);
            generator.writeBooleanField("wasBusinessCallStarted",value.wasBusinessCallStarted);
            generator.writeEndObject();
        }
    }
}
