package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 供给投影 basis 摘要与 JSON 的共享口径：自动刷新 worker 与人工恢复在同一事务内写安全投影时共用，
 * 保证 digest、basisVersion 递增与 basisJson 序列化两处不漂移。字段集合固定：
 * listing 身份、当前买方定价版本、真实投影状态、投影原因与买家聚合资格（含各项目来源摘要）。
 * 设计见 docs/superpowers/specs/2026-10-09-saleable-catalog-order-acceptance-design.md#task-3-实施接口与事务边界。
 */
public final class BuyerAvailabilityBasis {

    private static final ObjectMapper SOURCE_READER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private BuyerAvailabilityBasis() {
    }

    /**
     * 读取评估实际使用的定价身份，绝不把 basisVersion 变化计数当作来源。
     * 先校验已声明字段，再区分正常未评估与明确空来源；损坏引用必须传播系统失败。
     * 设计见 {@code docs/superpowers/specs/2026-10-12-catalog-source-freshness-correction-design.md#3-字段语义与来源契约}。
     */
    public static PricingSource readPricingSource(String basisJson, Long listingId) {
        if (basisJson == null) return new PricingSource(false, null);
        final JsonNode basis;
        try {
            basis = SOURCE_READER.readTree(basisJson);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Catalog basis source cannot be parsed", failure);
        }
        if (basis == null || !basis.isObject()) {
            throw new IllegalStateException("Catalog basis source must be an object");
        }
        JsonNode owner = basis.get("listingId");
        if (owner != null && !positiveLong(owner).equals(listingId)) {
            throw new IllegalStateException("Catalog basis source ownership is invalid");
        }
        JsonNode source = basis.get("sourceBuyerPricingVersionId");
        Long sourceId = source == null || source.isNull() ? null : positiveLong(source);
        return new PricingSource(owner != null && source != null, sourceId);
    }

    private static Long positiveLong(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalStateException("Catalog basis identity must be a positive Long integer");
        }
        return value.longValue();
    }

    /** verifiable=false 为正常缺失；true/null 表示评估时确实没有价格来源。 */
    public record PricingSource(boolean verifiable, Long sourceBuyerPricingVersionId) { }

    /** SHA-256 摘要：basis 变化才递增 basisVersion；不含评估时间，避免无变化重评估产生游标风暴。 */
    public static byte[] digest(Long listingId, Long pricingVersionId, ListingAvailability availability,
                                String reason, CurrentSupplyEligibilityReader.BuyerEligibility eligibility) {
        return sha256(canonical(listingId, pricingVersionId, availability, reason, eligibility));
    }

    public static String canonical(Long listingId, Long pricingVersionId, ListingAvailability availability,
                                   String reason, CurrentSupplyEligibilityReader.BuyerEligibility eligibility) {
        StringBuilder canonical = new StringBuilder("listing=").append(listingId)
                .append("|pricingVersion=").append(pricingVersionId == null ? "" : pricingVersionId)
                .append("|availability=").append(availability)
                .append("|reason=").append(reason == null ? "" : reason);
        if (eligibility != null) {
            canonical.append("|qualification=").append(eligibility.qualification());
            for (CurrentSupplyEligibilityReader.ProjectEligibility project : eligibility.projects()) {
                canonical.append("|project=").append(project.projectId()).append(':')
                        .append(project.qualification()).append(':').append(project.sourceSummary());
            }
        }
        return canonical.toString();
    }

    public static String json(Long listingId, Long pricingVersionId, ListingAvailability availability,
                              String reason, CurrentSupplyEligibilityReader.BuyerEligibility eligibility,
                              ObjectMapper objectMapper) {
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("listingId", listingId);
        basis.put("sourceBuyerPricingVersionId", pricingVersionId);
        basis.put("availability", availability.name());
        basis.put("reason", reason);
        if (eligibility != null) {
            basis.put("qualification", eligibility.qualification().name());
            basis.put("projects", eligibility.projects().stream().map(project -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("projectId", project.projectId());
                item.put("qualification", project.qualification().name());
                item.put("source", project.sourceSummary());
                item.put("reasons", project.reasonCodes());
                return item;
            }).toList());
        }
        try {
            return objectMapper.writeValueAsString(basis);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Catalog basis summary serialization failed", failure);
        }
    }

    public static byte[] sha256(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is not available", failure);
        }
    }
}
