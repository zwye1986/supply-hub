package com.aifuli.module.supplychain.supplieradapter.huasheng.failure;
import com.aifuli.module.supplychain.supplieradapter.huasheng.protocol.HuashengExchangeEvidence;
import java.util.Optional;
/** 单向安全诊断；仅固定枚举与安全证据。见 docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §9.1、§14.2。 */
@com.fasterxml.jackson.databind.annotation.JsonSerialize(using = HuashengFailureDiagnostics.SafeSerializer.class)
public final class HuashengFailureDiagnostics {
    public enum HuashengFailureKind { MONEY_INVALID, ENVELOPE_INVALID, DATA_MISSING, IDENTITY_MISMATCH, QUANTITY_MISMATCH, STATE_CONFLICT, CARD_PAYLOAD_INVALID, RESPONSE_TOO_LARGE, PREFLIGHT, HTTP_ERROR, TRANSPORT_FAILURE }
    public enum HuashengFailureReasonCode { MONEY_INVALID, ENVELOPE_INVALID, DATA_MISSING, IDENTITY_MISMATCH, QUANTITY_MISMATCH, STATE_CONFLICT, CARD_PAYLOAD_INVALID, RESPONSE_TOO_LARGE, PREFLIGHT, HTTP_ERROR, TRANSPORT_FAILURE }
    public enum HuashengCauseKind { JSON_PARSE, IO, TIMEOUT, CRYPTO, VALIDATION, OTHER }
    private final HuashengFailureKind failureKind;
    private final HuashengFailureReasonCode reasonCode;
    private final HuashengCauseKind causeKind;
    private final Optional<HuashengExchangeEvidence> evidence;
    HuashengFailureDiagnostics(HuashengFailureKind kind, HuashengFailureReasonCode reason,
            HuashengCauseKind cause, Optional<HuashengExchangeEvidence> evidence) {
        this.failureKind=java.util.Objects.requireNonNull(kind,"缺少失败类别");
        this.reasonCode=java.util.Objects.requireNonNull(reason,"缺少固定原因码");
        this.causeKind=java.util.Objects.requireNonNull(cause,"缺少原因类别");
        this.evidence=java.util.Objects.requireNonNull(evidence,"缺少安全证据容器");
    }
    public HuashengFailureKind failureKind() { return failureKind; }
    public HuashengFailureReasonCode reasonCode() { return reasonCode; }
    public HuashengCauseKind causeKind() { return causeKind; }
    public Optional<HuashengExchangeEvidence> evidence() { return evidence; }
    @Override public String toString() { return "HuashengFailureDiagnostics["+failureKind+","+reasonCode+","+causeKind+"]"; }
    /** 固定四字段编码，不要求调用者安装Optional模块，也不改共享Mapper。 */
    static final class SafeSerializer extends com.fasterxml.jackson.databind.JsonSerializer<HuashengFailureDiagnostics> {
        @Override public void serialize(HuashengFailureDiagnostics value, com.fasterxml.jackson.core.JsonGenerator generator,
                com.fasterxml.jackson.databind.SerializerProvider serializers) throws java.io.IOException {
            generator.writeStartObject();
            generator.writeStringField("failureKind",value.failureKind.name());
            generator.writeStringField("reasonCode",value.reasonCode.name());
            generator.writeStringField("causeKind",value.causeKind.name());
            generator.writeFieldName("evidence");
            if (value.evidence.isPresent()) serializers.defaultSerializeValue(value.evidence.get(),generator);
            else generator.writeNull();
            generator.writeEndObject();
        }
    }
}
