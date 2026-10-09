package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.domain.ListingAvailability;
import com.fasterxml.jackson.core.JsonProcessingException;
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

    private BuyerAvailabilityBasis() {
    }

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
