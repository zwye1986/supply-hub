package com.aifuli.module.supplychain.border.fulfillment.domain;

import lombok.Builder;

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
public record OperationAssetFact(
        int sequenceNo,
        String assetType,
        String externalAssetId,
        String cardNo,
        String cardSecret,
        LocalDateTime validFrom,
        LocalDateTime validUntil
) {
    public OperationAssetFact {
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
