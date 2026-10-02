package com.aifuli.module.supplychain.infrastructure.time;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 将供应链数据库上海本地时间显式编码为绝对epoch毫秒，不依赖JVM默认时区。
 * 仅通过字段注解用于限频管理响应，不改变其他独立进程的共享Jackson行为。
 * 设计见 {@code docs/superpowers/specs/2026-09-30-direct-recharge-mobile-spu-frequency-design.md#7-管理端契约与权限}。
 */
public final class ShanghaiEpochMillisSerializer extends JsonSerializer<LocalDateTime> {
    @Override
    public void serialize(LocalDateTime value, JsonGenerator generator, SerializerProvider provider) throws IOException {
        generator.writeNumber(value.atZone(SupplyChainDatabaseClock.DATABASE_ZONE).toInstant().toEpochMilli());
    }
}
