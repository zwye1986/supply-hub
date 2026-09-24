package com.aifuli.module.supplychain.interfaceapi.admin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 管理端路由规则保存请求体（POST 新增 / PUT 整资源替换，PRD 四级范围模型）。
 * <p>业务拒绝以 {@code RoutingRuleSaveSupport} 为准；本 VO 仅做基础 Bean Validation。
 * 设计见 {@code docs/superpowers/specs/2026-09-23-order-frontend-alignment-and-routing-prd-model-design.md#56-rest-契约}。</p>
 */
@Data
@Schema(description = "路由规则保存请求")
public class RoutingRuleSaveReqVO {

    @NotBlank(message = "规则名称不能为空")
    @Size(max = 128, message = "规则名称长度不能超过128")
    @Schema(description = "路由规则中文名称；trim 后 1–128 字，启用集内唯一",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "话费直充华东专项")
    private String name;

    @Schema(description = "适用分销商业务编码；distributorMode=SPECIFIED 时必填，空列表等同 ALL",
            example = "[\"BYR_1\"]")
    private List<String> distributorCodes;

    @Schema(description = "分销商范围：ALL 全部 / SPECIFIED 指定；缺省按 distributorCodes 推导",
            allowableValues = {"ALL", "SPECIFIED"}, example = "SPECIFIED")
    private String distributorMode;

    @Schema(description = "指定项目业务编码；projectMode=SPECIFIED 时必填", example = "[\"PRJ_1\"]")
    private List<String> projectCodes;

    @Schema(description = "项目范围：ALL 全部 / SPECIFIED 指定；缺省按 projectCodes 推导",
            allowableValues = {"ALL", "SPECIFIED"}, example = "ALL")
    private String projectMode;

    @Schema(description = "指定商品类型编码（sc_product_type.type_code）；productTypeMode=SPECIFIED 时必填",
            example = "[\"MOVIE\"]")
    private List<String> productTypeCodes;

    @Schema(description = "商品类型范围：ALL 全部 / SPECIFIED 指定；缺省按 productTypeCodes 推导",
            allowableValues = {"ALL", "SPECIFIED"}, example = "ALL")
    private String productTypeMode;

    @NotBlank(message = "路由模式不能为空")
    @Schema(description = "路由模式：FIXED 固定主供应商，AUTO 自动路由多候选失败降级",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "AUTO",
            allowableValues = {"FIXED", "AUTO"})
    private String routeMode;

    @Schema(description = "FIXED 时的主供应商业务编码，必填", example = "SPL_A")
    private String primarySupplierCode;

    @Schema(description = "AUTO+SPECIFIED 时的有序候选供应商业务编码，去重后长度≥2，顺序即候选顺序；"
            + "AUTO+ALL 忽略；FIXED 由前端约定回填 [primarySupplierCode]",
            example = "[\"SPL_A\",\"SPL_B\"]")
    private List<String> supplierCodes;

    @Schema(description = "供应商范围：ALL 全部（候选按 SKU 有效报价动态解析）/ SPECIFIED 指定",
            allowableValues = {"SPECIFIED", "ALL"}, example = "SPECIFIED")
    private String supplierMode;

    @Schema(description = "启停状态：ENABLED 启用 / DISABLED 停用（真实状态，不再等同删除）",
            allowableValues = {"ENABLED", "DISABLED"}, example = "ENABLED")
    private String status;

    @Schema(description = "策略版本内稳定规则编码；PUT 可选且须与路径一致，POST 忽略", example = "RR20260922001")
    private String ruleCode;

    @Valid
    @Schema(description = "三个优先级位次，顺序即引擎排序键优先级；三条须为 STABLE/PRICE/COMPOSITE 各一且不重复",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<PriorityItemReqVO> priorities;

    @Valid
    @Schema(description = "综合排序评分权重（全局共享，保存后同步所有规则）；"
            + "优先级含 COMPOSITE 时必填且三权和=100")
    private WeightReqVO weights;

    @Schema(description = "最高可接受供货价，单位为分；空表示不检查价格阈值（前端本期不展示该字段）",
            example = "4980")
    private Long maxSupplyPriceCents;

    @Data
    @Schema(description = "优先级位次项")
    public static class PriorityItemReqVO {

        @Schema(description = "占位项稳定标识（前端拖拽 key）", example = "STABLE")
        private String id;

        @Schema(description = "优先级策略：STABLE 稳定优先 / PRICE 价格优先 / COMPOSITE 综合排序",
                allowableValues = {"STABLE", "PRICE", "COMPOSITE"}, example = "STABLE")
        private String strategy;
    }

    @Data
    @Schema(description = "综合排序评分权重（全局共享）")
    public static class WeightReqVO {

        @Schema(description = "稳定性评分权重%", example = "40")
        private Integer stability;

        @Schema(description = "价格权重%", example = "30")
        private Integer price;

        @Schema(description = "时效评分权重%；三者之和必须等于100", example = "30")
        private Integer timeliness;
    }
}
