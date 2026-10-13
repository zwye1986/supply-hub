package com.aifuli.module.supplychain.border.fulfillment.application;

import static com.aifuli.module.supplychain.border.fulfillment.domain.ProjectStandardAssetAuditReason.*;

import com.aifuli.module.supplychain.api.fulfillment.dto.ProjectStandardOrderAssetRespDTO;
import com.aifuli.module.supplychain.border.fulfillment.dal.dataobject.FulfillmentAssetDO;
import com.aifuli.module.supplychain.border.fulfillment.domain.*;
import com.aifuli.module.supplychain.infrastructure.security.EnvelopeCryptoPort;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import com.aifuli.module.supplychain.border.fulfillment.application.huashengflow.HuashengType201AssetCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Arrays;

/**
 * 封闭解码器保持生产密文AAD；失败不输出明文，所有解密缓冲区finally清零。 设计见
 * docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#4-资产结果与封闭载荷。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectStandardAssetPayloadDecoder {
    private final EnvelopeCryptoPort crypto;
    private final ObjectMapper json;
    private final SupplyChainDatabaseClock clock;

    /** 仅供通过归属、类型、生命周期校验的资产调用；不修改数据库资产状态。 */
    public ProjectStandardOrderAssetRespDTO decode(FulfillmentAssetDO asset) {
        long startedAt = System.nanoTime();
        log.debug(
                "[项目资产解密][开始] assetNo={}, assetType={}, sequenceNo={}",
                asset.getAssetNo(),
                asset.getAssetType(),
                asset.getSequenceNo());
        byte[] plaintext;
        try {
            plaintext =
                    crypto.decrypt(
                            asset.getEncryptedPayload(),
                            asset.getKeyVersion(),
                            (asset.getBOrderId()
                                            + "|"
                                            + asset.getSequenceNo()
                                            + "|"
                                            + asset.getAssetType())
                                    .getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException failure) {
            log.debug(
                    "[项目资产解密][完成] assetNo={}, result={}, elapsedMs={}",
                    asset.getAssetNo(),
                    DECRYPT_FAILED,
                    (System.nanoTime() - startedAt) / 1_000_000);
            throw new ProjectStandardAssetQueryFailure(DECRYPT_FAILED, failure);
        }
        try {
            JsonNode payload =
                    // 仅本次reader拒绝任意层重复键，避免后值覆盖前值；不得修改共享mapper配置。
                    json.reader()
                            .with(
                                    com.fasterxml.jackson.core.JsonParser.Feature
                                            .STRICT_DUPLICATE_DETECTION)
                            .with(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .FAIL_ON_TRAILING_TOKENS)
                            .readTree(plaintext);
            if (payload == null || !payload.isObject())
                throw new IllegalArgumentException("资产载荷须为对象");
            // 已知字段即使不属于当前类型也须先验类型；缺省允许，显式null不得视作缺省。
            // 设计见
            // docs/superpowers/specs/2026-10-02-project-standard-query-asset-design.md#4-资产结果与封闭载荷。
            for (String field :
                    new String[] {
                        "assetType", "cardCode", "cardSecret", "voucherCode", "voucherUrl"
                    }) {
                string(payload, field);
            }
            String declared = string(payload, "assetType");
            if (declared != null && !declared.equals(asset.getAssetType()))
                throw new IllegalArgumentException("资产类型不一致");
            var dto = new ProjectStandardOrderAssetRespDTO();
            switch (FulfillmentType.valueOf(asset.getAssetType())) {
                case CARD_CODE_SECRET -> {
                    String code = string(payload, "cardCode"),
                            secret = string(payload, "cardSecret");
                    if (blank(code) && blank(secret)) throw new IllegalArgumentException("缺少交付值");
                    dto.setCardCode(code);
                    dto.setCardSecret(secret);
                }
                case VOUCHER_CODE -> dto.setVoucherCode(alias(payload, "voucherCode"));
                case VOUCHER_URL -> dto.setVoucherUrl(alias(payload, "voucherUrl"));
                default -> throw new IllegalArgumentException("不支持资产类型");
            }
            // 任一新schema标记出现均严格验证，禁止坏新载荷降级为旧资产。
            // 设计：docs/superpowers/specs/2026-10-09-huasheng-type201-production-closure-design.md#72-task7授权查询与有效期展示契约。
            if (payload.has("payloadSchemaVersion") || payload.has("supplierPayload")) {
                var card = new HuashengType201AssetCodec().readStoredPayload(plaintext,
                        asset.getSequenceNo(), asset.getAssetType(), asset.getExternalAssetId(),
                        asset.getValidFrom(), asset.getValidUntil());
                dto.setValidTimeRaw(card.validTimeRaw());
                dto.setValidTimeState(card.validTimeState());
            }
            dto.setAssetNo(asset.getAssetNo());
            dto.setSequenceNo(asset.getSequenceNo());
            dto.setAssetType(asset.getAssetType());
            dto.setMaskedValue(asset.getMaskedValue());
            dto.setValidFrom(offset(asset.getValidFrom()));
            dto.setValidUntil(offset(asset.getValidUntil()));
            dto.setLifecycleStatus(
                    FulfillmentAssetStatus.fromCode(asset.getLifecycleStatus()).name());
            log.debug(
                    "[项目资产解密][完成] assetNo={}, result={}, elapsedMs={}",
                    asset.getAssetNo(),
                    AUTHORIZED_QUERY,
                    (System.nanoTime() - startedAt) / 1_000_000);
            return dto;
        } catch (Exception failure) {
            log.debug(
                    "[项目资产解密][完成] assetNo={}, result={}, elapsedMs={}",
                    asset.getAssetNo(),
                    PAYLOAD_FAILED,
                    (System.nanoTime() - startedAt) / 1_000_000);
            // JSON解析异常可能携带输入片段；保留安全校验因果摘要，不传播敏感原值。
            throw new ProjectStandardAssetQueryFailure(PAYLOAD_FAILED,
                    failure instanceof com.fasterxml.jackson.core.JsonParseException
                            ? new com.fasterxml.jackson.core.JsonParseException(null, "资产载荷解析失败")
                            : new IllegalArgumentException("资产载荷校验失败"));
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static String string(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("交付字段须为字符串");
        return value.textValue();
    }

    private static String alias(JsonNode payload, String field) {
        String current = string(payload, field), legacy = string(payload, "cardCode");
        if (current != null && legacy != null && !current.equals(legacy))
            throw new IllegalArgumentException("同义交付字段冲突");
        String value = current != null ? current : legacy;
        if (blank(value)) throw new IllegalArgumentException("缺少交付值");
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null
                ? null
                : clock.toInstant(value)
                        .atZone(SupplyChainDatabaseClock.DATABASE_ZONE)
                        .toOffsetDateTime();
    }
}
