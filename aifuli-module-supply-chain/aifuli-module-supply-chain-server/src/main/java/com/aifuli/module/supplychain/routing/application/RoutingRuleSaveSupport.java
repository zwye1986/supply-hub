package com.aifuli.module.supplychain.routing.application;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_BUYER_REQUIRED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_CANDIDATE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_CODE_MISMATCH;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_FULFILLMENT_MISMATCH;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_SCOPE_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_STRATEGY_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_SUPPLIER_ALL_NOT_OPEN;

/**
 * 路由规则页保存请求的无库校验与归一化。
 * <p>不依赖 {@code interfaceapi} VO；CommandService 将 HTTP 体映射为 {@link SaveRequest} 后再调用。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#4-请求体与页面字段}。
 */
public final class RoutingRuleSaveSupport {

    /** 新候选默认库存检查模式，见设计 §4.2。 */
    public static final String DEFAULT_INVENTORY_CHECK_MODE = "UNSUPPORTED";
    /** 新候选默认余额检查模式，见设计 §4.2。 */
    public static final String DEFAULT_BALANCE_CHECK_MODE = "UNSUPPORTED";
    /** 新候选默认健康检查模式，见设计 §4.2。 */
    public static final String DEFAULT_HEALTH_CHECK_MODE = "UNSUPPORTED";
    /** 新候选默认库存检查最大年龄（秒），UNSUPPORTED 时必须为空。 */
    public static final Integer DEFAULT_INVENTORY_MAX_AGE_SECONDS = null;
    /** 新候选默认余额检查最大年龄（秒），UNSUPPORTED 时必须为空。 */
    public static final Integer DEFAULT_BALANCE_MAX_AGE_SECONDS = null;
    /** 新候选默认健康检查最大年龄（秒），UNSUPPORTED 时必须为空。 */
    public static final Integer DEFAULT_HEALTH_MAX_AGE_SECONDS = null;

    private static final int NAME_MAX_LENGTH = 128;
    private static final Set<String> FULFILLMENT_TYPES = Set.of(
            "DIRECT_RECHARGE", "VOUCHER_CODE", "CARD_CODE_SECRET", "VOUCHER_URL", "ACCOUNT_BOUND_BENEFIT");
    private static final int EXPECTED_WEIGHT_STABILITY = 40;
    private static final int EXPECTED_WEIGHT_PRICE = 30;
    private static final int EXPECTED_WEIGHT_TIMELINESS = 30;

    /**
     * 校验并归一化保存请求。
     *
     * @param request      应用层输入（非 interfaceapi VO）
     * @param pathRuleCode UPDATE 时路径上的规则编码；CREATE 时忽略
     * @param operation    CREATE 或 UPDATE
     * @return 可交给 CommandService 落库补丁的归一化结果
     */
    public NormalizedRulePatch validateAndNormalize(SaveRequest request, String pathRuleCode, Operation operation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(operation, "operation");

        String name = requireName(request.name());
        String buyerCode = requireSingleBuyer(request.distributorCodes());
        rejectScopeNotOpen(request.projectCodes(), request.projectMode());
        rejectScopeNotOpen(request.productTypeCodes(), request.productTypeMode());
        if (request.supplierMode() == RoutingRuleScopeMode.ALL) {
            throw exception(ROUTING_RULE_SUPPLIER_ALL_NOT_OPEN);
        }
        if (request.distributorMode() == RoutingRuleScopeMode.ALL) {
            throw exception(ROUTING_RULE_BUYER_REQUIRED);
        }
        if (request.distributorMode() != null && request.distributorMode() != RoutingRuleScopeMode.SPECIFIED) {
            throw exception(ROUTING_RULE_BUYER_REQUIRED);
        }
        validatePriorities(request.priorities());
        validateWeights(request.weights());
        if (request.status() == RoutingRuleStatus.DISABLED) {
            throw exception(ROUTING_RULE_STRATEGY_NOT_OPEN);
        }

        RoutingRuleRouteMode routeMode = requireRouteMode(request.routeMode());
        String skuCode = requireNonBlank(request.skuCode(), "平台SKU编码不能为空");
        String fulfillmentType = requireFulfillmentType(request.fulfillmentType());
        List<String> orderedSuppliers = normalizeCandidates(routeMode, request.primarySupplierCode(),
                request.supplierCodes());
        String ruleCode = resolveRuleCode(operation, pathRuleCode, request.ruleCode());

        return new NormalizedRulePatch(
                name, buyerCode, skuCode, fulfillmentType, routeMode, List.copyOf(orderedSuppliers), ruleCode);
    }

    private static String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            // 无专用错误码；Controller @NotBlank 也会拦截。设计见 §4.1 name。
            throw new IllegalArgumentException("规则名称不能为空");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("规则名称长度不能超过128");
        }
        return trimmed;
    }

    private static String requireSingleBuyer(List<String> distributorCodes) {
        List<String> codes = trimNonBlank(distributorCodes);
        if (codes.size() != 1) {
            throw exception(ROUTING_RULE_BUYER_REQUIRED);
        }
        return codes.getFirst();
    }

    private static void rejectScopeNotOpen(List<String> codes, RoutingRuleScopeMode mode) {
        if (mode == RoutingRuleScopeMode.SPECIFIED) {
            throw exception(ROUTING_RULE_SCOPE_NOT_OPEN);
        }
        List<String> nonBlank = trimNonBlank(codes);
        if (!nonBlank.isEmpty()) {
            throw exception(ROUTING_RULE_SCOPE_NOT_OPEN);
        }
    }

    private static void validatePriorities(List<PriorityInput> priorities) {
        if (priorities == null) {
            return;
        }
        if (priorities.size() != 3
                || priorities.getFirst() == null
                || priorities.getFirst().strategy() != RoutingRuleStrategy.STABLE) {
            throw exception(ROUTING_RULE_STRATEGY_NOT_OPEN);
        }
        EnumSet<RoutingRuleStrategy> seen = EnumSet.noneOf(RoutingRuleStrategy.class);
        for (PriorityInput item : priorities) {
            if (item == null || item.strategy() == null || !seen.add(item.strategy())) {
                throw exception(ROUTING_RULE_STRATEGY_NOT_OPEN);
            }
        }
        if (!seen.equals(EnumSet.allOf(RoutingRuleStrategy.class))) {
            throw exception(ROUTING_RULE_STRATEGY_NOT_OPEN);
        }
    }

    private static void validateWeights(WeightInput weights) {
        if (weights == null) {
            return;
        }
        if (!Objects.equals(weights.stability(), EXPECTED_WEIGHT_STABILITY)
                || !Objects.equals(weights.price(), EXPECTED_WEIGHT_PRICE)
                || !Objects.equals(weights.timeliness(), EXPECTED_WEIGHT_TIMELINESS)) {
            throw exception(ROUTING_RULE_STRATEGY_NOT_OPEN);
        }
    }

    private static RoutingRuleRouteMode requireRouteMode(RoutingRuleRouteMode routeMode) {
        if (routeMode == null) {
            throw new IllegalArgumentException("路由模式不能为空");
        }
        return routeMode;
    }

    private static String requireFulfillmentType(String fulfillmentType) {
        String trimmed = blankToNull(fulfillmentType);
        if (trimmed == null || !FULFILLMENT_TYPES.contains(trimmed)) {
            throw exception(ROUTING_RULE_FULFILLMENT_MISMATCH);
        }
        return trimmed;
    }

    private static List<String> normalizeCandidates(RoutingRuleRouteMode routeMode,
                                                    String primarySupplierCode,
                                                    List<String> supplierCodes) {
        if (routeMode == RoutingRuleRouteMode.FIXED) {
            String primary = blankToNull(primarySupplierCode);
            if (primary == null) {
                throw exception(ROUTING_RULE_CANDIDATE_INVALID);
            }
            return List.of(primary);
        }
        List<String> ordered = trimNonBlank(supplierCodes);
        Set<String> distinct = new HashSet<>();
        for (String code : ordered) {
            if (!distinct.add(code)) {
                throw exception(ROUTING_RULE_CANDIDATE_INVALID);
            }
        }
        if (ordered.size() < 2) {
            throw exception(ROUTING_RULE_CANDIDATE_INVALID);
        }
        return ordered;
    }

    private static String resolveRuleCode(Operation operation, String pathRuleCode, String bodyRuleCode) {
        if (operation == Operation.CREATE) {
            return null;
        }
        String path = blankToNull(pathRuleCode);
        if (path == null) {
            throw new IllegalArgumentException("更新路径规则编码不能为空");
        }
        String body = blankToNull(bodyRuleCode);
        if (body != null && !body.equals(path)) {
            throw exception(ROUTING_RULE_CODE_MISMATCH);
        }
        return path;
    }

    private static String requireNonBlank(String value, String message) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            throw new IllegalArgumentException(message);
        }
        return trimmed;
    }

    private static List<String> trimNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            String trimmed = blankToNull(value);
            if (trimmed != null) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public enum Operation {
        CREATE,
        UPDATE
    }

    /**
     * 应用层保存入参。由 Controller/Command 从 HTTP VO 映射，避免 application 依赖 interfaceapi。
     */
    public record SaveRequest(
            String name,
            List<String> distributorCodes,
            String skuCode,
            String fulfillmentType,
            RoutingRuleRouteMode routeMode,
            String primarySupplierCode,
            List<String> supplierCodes,
            RoutingRuleScopeMode supplierMode,
            RoutingRuleScopeMode distributorMode,
            RoutingRuleStatus status,
            String ruleCode,
            List<String> projectCodes,
            RoutingRuleScopeMode projectMode,
            List<String> productTypeCodes,
            RoutingRuleScopeMode productTypeMode,
            List<PriorityInput> priorities,
            WeightInput weights) {
    }

    public record PriorityInput(String id, RoutingRuleStrategy strategy) {
    }

    public record WeightInput(Integer stability, Integer price, Integer timeliness) {
    }

    /**
     * 校验通过后的规则补丁字段，供 CommandService 拼装草稿与发布。
     */
    public record NormalizedRulePatch(
            String name,
            String buyerCode,
            String skuCode,
            String fulfillmentType,
            RoutingRuleRouteMode routeMode,
            List<String> orderedSupplierCodes,
            String ruleCode) {
    }
}
