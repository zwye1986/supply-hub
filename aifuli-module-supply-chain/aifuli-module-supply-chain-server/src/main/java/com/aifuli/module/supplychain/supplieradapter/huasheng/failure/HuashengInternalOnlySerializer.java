package com.aifuli.module.supplychain.supplieradapter.huasheng.failure;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

/** 内部对象在任何字段写出之前固定安全拒绝。见 docs/superpowers/specs/2026-10-04-huasheng-http-token-parser-design.md §9.1、§14.2。 */
public final class HuashengInternalOnlySerializer extends JsonSerializer<Object> {
    public HuashengInternalOnlySerializer() { }
    @Override public void serialize(Object value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
        throw new IOException("内部协议对象禁止序列化");
    }
}
