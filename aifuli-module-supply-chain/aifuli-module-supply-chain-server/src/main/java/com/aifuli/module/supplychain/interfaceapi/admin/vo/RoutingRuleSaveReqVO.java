package com.aifuli.module.supplychain.interfaceapi.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 管理端路由规则保存请求体（POST 新增 / PUT 整资源替换）。
 * <p>业务拒绝以 {@code RoutingRuleSaveSupport} 为准；本 VO 仅做基础 Bean Validation。
 * 设计见 {@code docs/superpowers/specs/2026-09-21-routing-rule-admin-write-design.md#41-routingrulesavereqvo-postput}。
 */
@Data
@Schema(description = "路由规则保存请求")
public class RoutingRuleSaveReqVO {

    @NotBlank(message = "规则名称不能为空")
    @Size(max = 128, message = "规则名称长度不能超过128")
    @Schema(description = "标准商品路由规则中文名称；trim 后 1–128 字",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "话费固定规则")
    private String name;

    @Schema(description = "适用分销商业务编码；必须恰好一个",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "[\"BYR_1\"]")
    private List<String> distributorCodes;

    @NotBlank(message = "平台SKU编码不能为空")
    @Schema(description = "平台 SKU 业务编码",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "SKU_1")
    private String skuCode;

    @NotBlank(message = "履约类型不能为空")
    @Schema(description = "履约类型条件：DIRECT_RECHARGE直充，VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益；须与 SKU 档案一致",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "DIRECT_RECHARGE",
            allowableValues = {"DIRECT_RECHARGE", "VOUCHER_CODE", "CARD_CODE_SECRET", "VOUCHER_URL", "ACCOUNT_BOUND_BENEFIT"})
    private String fulfillmentType;

    @NotBlank(message = "路由模式不能为空")
    @Schema(description = "路由模式：FIXED 固定一个主供应商，AUTO 不少于两个有序候选",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "FIXED",
            allowableValues = {"FIXED", "AUTO"})
    private String routeMode;

    @Schema(description = "FIXED 时的唯一候选供应商业务编码；AUTO 时忽略", example = "SPL_A")
    private String primarySupplierCode;

    @Schema(description = "AUTO 时有序候选供应商业务编码，去重后长度≥2；顺序即降级顺序；FIXED 时忽略",
            example = "[\"SPL_A\",\"SPL_B\"]")
    private List<String> supplierCodes;

    @Schema(description = "供应商范围；缺省或 SPECIFIED 才接受，ALL 拒绝",
            allowableValues = {"SPECIFIED", "ALL"}, example = "SPECIFIED")
    private String supplierMode;

    @Schema(description = "分销商范围；缺省按 distributorCodes 推导，若传必须为 SPECIFIED",
            allowableValues = {"SPECIFIED", "ALL"}, example = "SPECIFIED")
    private String distributorMode;

    @Schema(description = "启停展示值；仅允许 ENABLED 或省略，DISABLED 请走删除/启停",
            allowableValues = {"ENABLED", "DISABLED"}, example = "ENABLED")
    private String status;

    @Schema(description = "策略版本内稳定规则编码；PUT 可选且须与路径一致，POST 忽略", example = "RR20260922001")
    private String ruleCode;

    @Schema(description = "指定项目业务编码；非空拒绝", example = "[]")
    private List<String> projectCodes;

    @Schema(description = "项目范围；SPECIFIED 拒绝，缺省按 ALL 理解",
            allowableValues = {"ALL", "SPECIFIED"}, example = "ALL")
    private String projectMode;

    @Schema(description = "指定商品类型编码；非空拒绝", example = "[]")
    private List<String> productTypeCodes;

    @Schema(description = "商品类型范围；SPECIFIED 拒绝，缺省按 ALL 理解",
            allowableValues = {"ALL", "SPECIFIED"}, example = "ALL")
    private String productTypeMode;

    @Valid
    @Schema(description = "优先级占位；省略则忽略；若出现则 priorities[0].strategy 须为 STABLE，且三条为 STABLE/PRICE/COMPOSITE 各一")
    private List<PriorityItemReqVO> priorities;

    @Valid
    @Schema(description = "综合权重展示占位；省略则忽略；若出现必须为 stability=40、price=30、timeliness=30")
    private WeightReqVO weights;

    @Data
    @Schema(description = "优先级占位项")
    public static class PriorityItemReqVO {

        @Schema(description = "占位项稳定标识，等于策略枚举名", example = "STABLE")
        private String id;

        @Schema(description = "优先级策略：STABLE与引擎一致，PRICE/COMPOSITE仅占位",
                allowableValues = {"STABLE", "PRICE", "COMPOSITE"}, example = "STABLE")
        private String strategy;
    }

    @Data
    @Schema(description = "综合权重展示占位")
    public static class WeightReqVO {

        @Schema(description = "稳定性评分权重展示占位，不落库", example = "40")
        private Integer stability;

        @Schema(description = "价格权重展示占位，不落库", example = "30")
        private Integer price;

        @Schema(description = "时效评分权重展示占位，不落库", example = "30")
        private Integer timeliness;
    }
}
