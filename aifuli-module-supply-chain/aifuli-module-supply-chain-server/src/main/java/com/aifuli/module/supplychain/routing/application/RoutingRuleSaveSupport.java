package com.aifuli.module.supplychain.routing.application;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_CANDIDATE_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_CODE_MISMATCH;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_SCOPE_DUPLICATED;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ROUTING_RULE_WEIGHTS_INVALID;

/**
 * 路由规则页保存请求的无库校验与归一化（PRD 四级范围模型）。
 *
 * <p>不依赖 {@code interfaceapi} VO 与数据库；编码存在性、同范围唯一性、名称唯一等
 * 需要查库的校验由 CommandService 执行。设计见
 * {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#55-保存校验}。</p>
 */
public final class RoutingRuleSaveSupport {

    /** 供 CommandService 创建新候选；默认动态检查模式（全 UNSUPPORTED），见设计 §5.5。 */
    public static final String DEFAULT_INVENTORY_CHECK_MODE = "UNSUPPORTED";
    /** 供 CommandService 创建新候选；默认余额检查模式。 */
    public static final String DEFAULT_BALANCE_CHECK_MODE = "UNSUPPORTED";
    /** 供 CommandService 创建新候选；默认健康检查模式。 */
    public static final String DEFAULT_HEALTH_CHECK_MODE = "UNSUPPORTED";
    /** UNSUPPORTED 模式下最大年龄恒为空。 */
    public static final Integer DEFAULT_INVENTORY_MAX_AGE_SECONDS = null;
    /** UNSUPPORTED 模式下最大年龄恒为空。 */
    public static final Integer DEFAULT_BALANCE_MAX_AGE_SECONDS = null;
    /** UNSUPPORTED 模式下最大年龄恒为空。 */
    public static final Integer DEFAULT_HEALTH_MAX_AGE_SECONDS = null;

    /** 全局综合权重默认值（稳定40/价格30/时效30），与策略表 DDL 默认一致。 */
    public static final int DEFAULT_WEIGHT_STABILITY = 40;
    /** 全局综合权重默认值。 */
    public static final int DEFAULT_WEIGHT_PRICE = 30;
    /** 全局综合权重默认值。 */
    public static final int DEFAULT_WEIGHT_TIMELINESS = 30;

    /** 全局权重三权和恒定值。 */
    public static final int WEIGHT_SUM = 100;

    private static final int NAME_MAX_LENGTH = 128;
    private static final int PRIORITY_SIZE = 3;

    /**
     * 校验并归一化保存请求（纯结构校验，不查库）。
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
        RoutingRuleRouteMode routeMode = requireRouteMode(request.routeMode());
        RoutingRuleStatus status = requireStatus(request.status());
        RoutingRuleScopeMode distributorMode = normalizeScopeMode(request.distributorMode(),
                request.distributorCodes(), "适用分销商范围");
        RoutingRuleScopeMode projectMode = normalizeScopeMode(request.projectMode(),
                request.projectCodes(), "适用项目范围");
        RoutingRuleScopeMode productTypeMode = normalizeScopeMode(request.productTypeMode(),
                request.productTypeCodes(), "适用商品类型范围");
        RoutingRuleScopeMode supplierMode = normalizeScopeMode(request.supplierMode(),
                request.supplierCodes(), "适用供应商范围");
        List<String> distributorCodes = normalizeScopeCodes(distributorMode, request.distributorCodes(), "适用分销商");
        List<String> projectCodes = normalizeScopeCodes(projectMode, request.projectCodes(), "适用项目");
        List<String> productTypeCodes = normalizeScopeCodes(productTypeMode, request.productTypeCodes(), "适用商品类型");
        List<RoutingRuleStrategy> strategies = validatePriorities(request.priorities());
        WeightInput weights = validateWeights(request.weights(), strategies);

        String primarySupplierCode = blankToNull(request.primarySupplierCode());
        List<String> orderedSuppliers = normalizeCandidates(routeMode, supplierMode, primarySupplierCode,
                request.supplierCodes());
        // FIXED 模式下前端约定 supplierCodes=[primary]；归一化为单一候选。
        if (routeMode == RoutingRuleRouteMode.FIXED) {
            orderedSuppliers = List.of(primarySupplierCode);
        }
        String ruleCode = resolveRuleCode(operation, pathRuleCode, request.ruleCode());

        return new NormalizedRulePatch(ruleCode, name, routeMode, status,
                distributorMode, distributorCodes, projectMode, projectCodes,
                productTypeMode, productTypeCodes, supplierMode, orderedSuppliers,
                primarySupplierCode, strategies, weights,
                request.maxSupplyPriceCents());
    }

    private static String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("规则名称不能为空");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("规则名称长度不能超过128");
        }
        return trimmed;
    }

    private static RoutingRuleRouteMode requireRouteMode(RoutingRuleRouteMode routeMode) {
        if (routeMode == null) {
            throw new IllegalArgumentException("路由模式不能为空");
        }
        return routeMode;
    }

    private static RoutingRuleStatus requireStatus(RoutingRuleStatus status) {
        if (status == null) {
            throw exception(ENUM_NOT_OPEN);
        }
        return status;
    }

    /** 范围模式缺省时按编码列表推导：空=ALL，非空=SPECIFIED（对齐前端提交约定）。 */
    private static RoutingRuleScopeMode normalizeScopeMode(RoutingRuleScopeMode mode, List<String> rawCodes,
                                                           String label) {
        if (mode != null) {
            return mode;
        }
        return trimNonBlank(rawCodes).isEmpty() ? RoutingRuleScopeMode.ALL : RoutingRuleScopeMode.SPECIFIED;
    }

    /** SPECIFIED 时编码必填、去空白、去重；ALL 时必须为空。 */
    private static List<String> normalizeScopeCodes(RoutingRuleScopeMode mode, List<String> rawCodes, String label) {
        List<String> codes = trimNonBlank(rawCodes);
        if (mode == RoutingRuleScopeMode.SPECIFIED) {
            if (codes.isEmpty()) {
                throw new IllegalArgumentException(label + "指定范围不能为空");
            }
            requireDistinct(codes, label);
            return codes;
        }
        if (!codes.isEmpty()) {
            throw new IllegalArgumentException(label + "为全部时不能携带编码列表");
        }
        return List.of();
    }

    private static List<RoutingRuleStrategy> validatePriorities(List<PriorityInput> priorities) {
        if (priorities == null || priorities.size() != PRIORITY_SIZE) {
            throw exception(ROUTING_RULE_CANDIDATE_INVALID);
        }
        List<RoutingRuleStrategy> strategies = new ArrayList<>(PRIORITY_SIZE);
        Set<RoutingRuleStrategy> seen = EnumSet.noneOf(RoutingRuleStrategy.class);
        for (PriorityInput item : priorities) {
            if (item == null || item.strategy() == null || !seen.add(item.strategy())) {
                throw exception(ROUTING_RULE_CANDIDATE_INVALID);
            }
            strategies.add(item.strategy());
        }
        if (!seen.equals(EnumSet.allOf(RoutingRuleStrategy.class))) {
            throw exception(ROUTING_RULE_CANDIDATE_INVALID);
        }
        return List.copyOf(strategies);
    }

    /** 含 COMPOSITE 时权重必填且和=100；否则缺省 40/30/30，显式提供也校验和=100。 */
    private static WeightInput validateWeights(WeightInput weights, List<RoutingRuleStrategy> strategies) {
        boolean composite = strategies.contains(RoutingRuleStrategy.COMPOSITE);
        if (weights == null || (weights.stability() == null && weights.price() == null
                && weights.timeliness() == null)) {
            if (composite) {
                throw exception(ROUTING_RULE_WEIGHTS_INVALID);
            }
            return new WeightInput(DEFAULT_WEIGHT_STABILITY, DEFAULT_WEIGHT_PRICE, DEFAULT_WEIGHT_TIMELINESS);
        }
        if (weights.stability() == null || weights.price() == null || weights.timeliness() == null
                || weights.stability() < 0 || weights.price() < 0 || weights.timeliness() < 0
                || weights.stability() + weights.price() + weights.timeliness() != WEIGHT_SUM) {
            throw exception(ROUTING_RULE_WEIGHTS_INVALID);
        }
        return new WeightInput(weights.stability(), weights.price(), weights.timeliness());
    }

    /** AUTO+SPECIFIED 至少2个互异候选（失败降级）；FIXED 必须有主供应商。 */
    private static List<String> normalizeCandidates(RoutingRuleRouteMode routeMode,
                                                    RoutingRuleScopeMode supplierMode,
                                                    String primarySupplierCode,
                                                    List<String> supplierCodes) {
        if (routeMode == RoutingRuleRouteMode.FIXED) {
            if (primarySupplierCode == null) {
                throw exception(ROUTING_RULE_CANDIDATE_INVALID);
            }
            return List.of(primarySupplierCode);
        }
        if (supplierMode != RoutingRuleScopeMode.SPECIFIED) {
            // AUTO+ALL：候选动态解析，不接受候选列表。
            List<String> nonBlank = trimNonBlank(supplierCodes);
            if (!nonBlank.isEmpty()) {
                throw exception(ROUTING_RULE_CANDIDATE_INVALID);
            }
            return List.of();
        }
        List<String> ordered = trimNonBlank(supplierCodes);
        if (ordered.size() < 2) {
            throw exception(ROUTING_RULE_CANDIDATE_INVALID);
        }
        requireDistinct(ordered, "候选供应商");
        return ordered;
    }

    private static void requireDistinct(List<String> codes, String label) {
        Set<String> distinct = new HashSet<>();
        for (String code : codes) {
            if (!distinct.add(code)) {
                throw exception(ROUTING_RULE_SCOPE_DUPLICATED);
            }
        }
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
            String ruleCode,
            RoutingRuleRouteMode routeMode,
            RoutingRuleStatus status,
            RoutingRuleScopeMode distributorMode,
            List<String> distributorCodes,
            RoutingRuleScopeMode projectMode,
            List<String> projectCodes,
            RoutingRuleScopeMode productTypeMode,
            List<String> productTypeCodes,
            RoutingRuleScopeMode supplierMode,
            List<String> supplierCodes,
            String primarySupplierCode,
            List<PriorityInput> priorities,
            WeightInput weights,
            Long maxSupplyPriceCents) {
    }

    public record PriorityInput(String id, RoutingRuleStrategy strategy) {
    }

    public record WeightInput(Integer stability, Integer price, Integer timeliness) {
    }

    /**
     * 校验通过后的规则补丁字段（PRD 四级范围模型），供 CommandService 拼装草稿与发布。
     */
    public record NormalizedRulePatch(
            String ruleCode,
            String name,
            RoutingRuleRouteMode routeMode,
            RoutingRuleStatus status,
            RoutingRuleScopeMode distributorMode,
            List<String> distributorCodes,
            RoutingRuleScopeMode projectMode,
            List<String> projectCodes,
            RoutingRuleScopeMode productTypeMode,
            List<String> productTypeCodes,
            RoutingRuleScopeMode supplierMode,
            List<String> orderedSupplierCodes,
            String primarySupplierCode,
            List<RoutingRuleStrategy> strategies,
            WeightInput weights,
            Long maxSupplyPriceCents) {

        public NormalizedRulePatch {
            distributorCodes = List.copyOf(distributorCodes == null ? List.of() : distributorCodes);
            projectCodes = List.copyOf(projectCodes == null ? List.of() : projectCodes);
            productTypeCodes = List.copyOf(productTypeCodes == null ? List.of() : productTypeCodes);
            orderedSupplierCodes = List.copyOf(orderedSupplierCodes == null ? List.of() : orderedSupplierCodes);
            strategies = List.copyOf(strategies == null ? List.of() : strategies);
        }

        /** 四级范围规范化键：同范围唯一性校验用（mode+有序codes）。 */
        public String scopeKey() {
            return distributorMode + ":" + String.join(",", distributorCodes)
                    + "|" + projectMode + ":" + String.join(",", projectCodes)
                    + "|" + productTypeMode + ":" + String.join(",", productTypeCodes)
                    + "|" + supplierMode + ":" + String.join(",", orderedSupplierCodes);
        }
    }
}
