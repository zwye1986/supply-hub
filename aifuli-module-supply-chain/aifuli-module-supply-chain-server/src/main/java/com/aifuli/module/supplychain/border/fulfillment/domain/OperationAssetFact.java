package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Builder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 适配器解析出的单项已交付资产事实（如卡号、卡密、券码等）。
 *
 * <p>对应数据库表 {@code sc_fulfillment_asset} 的待持久化交付资产事实；
 * 由领域层统一进行 KMS 信封加密与指纹计算后写入，适配器层严禁直接访问 DAL。</p>
 *
 * <p>设计详见 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#731-operationexecutorport-契约模型}
 * 以及 {@code docs/superpowers/specs/2026-08-07-supply-chain-phase1-detailed-design.md#10-adapter-元数据调用审计与敏感资产}。</p>
 */
@Builder
@JsonSerialize(using = OperationAssetFact.InternalOnlySerializer.class)
public record OperationAssetFact(
        int sequenceNo,
        String assetType,
        String externalAssetId,
        String cardNo,
        String cardSecret,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        byte[] protectedPayload
) {
    /** 保留旧构造入口与原trim规则。设计见 docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#71-task2桥接与持久基础边界。 */
    public OperationAssetFact(int sequenceNo, String assetType, String externalAssetId, String cardNo,
            String cardSecret, LocalDateTime validFrom, LocalDateTime validUntil) {
        this(sequenceNo, assetType, externalAssetId, cardNo, cardSecret, validFrom, validUntil, null);
    }

    public OperationAssetFact {
        if (protectedPayload != null) {
            // 只有封闭payload路径保留原值；schema/标准字段一致性在持久入口再核验，不赋予终结资格。
            protectedPayload = protectedPayload.clone();
        } else {
            if (sequenceNo < 1) {
                sequenceNo = 1;
            }
            externalAssetId = (externalAssetId == null || externalAssetId.isBlank()) ? null : externalAssetId.trim();
            cardNo = (cardNo == null || cardNo.isBlank()) ? null : cardNo.trim();
            cardSecret = (cardSecret == null || cardSecret.isBlank()) ? null : cardSecret.trim();
            if (assetType == null || assetType.isBlank()) {
                if (cardSecret == null && cardNo != null) {
                    assetType = "VOUCHER_CODE";
                } else {
                    assetType = "CARD_CODE_SECRET";
                }
            }
        }
    }

    /** 华昇type201专用纯事实工厂；不trim，不承担资金授权。设计见 docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#71-task2桥接与持久基础边界。 */
    public static OperationAssetFact huashengType201(String code, String pass, byte[] protectedPayload) {
        if (code == null || code.isBlank() || pass == null || pass.isBlank()
                || protectedPayload == null || protectedPayload.length == 0) {
            throw new IllegalArgumentException("华昇type201资产无效");
        }
        return new OperationAssetFact(1, FulfillmentType.CARD_CODE_SECRET.code(), null, code, pass, null, null, protectedPayload);
    }

    public boolean hasProtectedPayload() { return protectedPayload != null; }

    @Override
    public byte[] protectedPayload() { return protectedPayload == null ? null : protectedPayload.clone(); }

    @Override
    public String toString() { return "OperationAssetFact[redacted]"; }

    /** 内部事实不能成为HTTP/日志的通用JSON数据；写字段之前固定拒绝，不引入华昇协议层依赖。 */
    public static final class InternalOnlySerializer extends JsonSerializer<OperationAssetFact> {
        @Override
        public void serialize(OperationAssetFact value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            throw new IOException("内部资产事实禁止序列化");
        }
    }

    /** Lombok补齐字段与build，但不允许默认builder诊断泄漏卡密。设计见上述 §7.1。 */
    public static class OperationAssetFactBuilder {
        @Override
        public String toString() { return "OperationAssetFactBuilder[redacted]"; }
    }
}
