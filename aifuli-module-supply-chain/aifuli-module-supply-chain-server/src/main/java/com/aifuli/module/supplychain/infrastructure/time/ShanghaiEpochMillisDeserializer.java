package com.aifuli.module.supplychain.infrastructure.time;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 限频POST时间筛选只接受JSON整数epoch毫秒，并固定转换成数据库上海本地时间。
 * 字符串/浮点值不能被宽松转换为零；GET仍由DateTimeFormat绑定ISO上海本地时间。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限}。
 */
public final class ShanghaiEpochMillisDeserializer extends JsonDeserializer<LocalDateTime> {
    @Override
    public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            throw JsonMappingException.from(parser, "限频时间必须为整数epoch毫秒");
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(parser.getLongValue()), SupplyChainDatabaseClock.DATABASE_ZONE);
    }
}
