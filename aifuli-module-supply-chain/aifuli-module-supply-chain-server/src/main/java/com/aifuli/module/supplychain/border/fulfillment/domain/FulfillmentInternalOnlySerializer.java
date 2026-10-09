package com.aifuli.module.supplychain.border.fulfillment.domain;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * 履约领域内部敏感对象禁止序列化器。
 *
 * <p>在任何字段写出之前固定安全拒绝，不依赖供应商协议层 failure 包或具体异常。
 * 设计详见 {@code docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#33-冻结投影与首单-checkpoint}。</p>
 */
public final class FulfillmentInternalOnlySerializer extends JsonSerializer<Object> {

    public FulfillmentInternalOnlySerializer() {
    }

    @Override
    public void serialize(Object value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
        throw new IOException("履约领域敏感对象禁止序列化");
    }
}
