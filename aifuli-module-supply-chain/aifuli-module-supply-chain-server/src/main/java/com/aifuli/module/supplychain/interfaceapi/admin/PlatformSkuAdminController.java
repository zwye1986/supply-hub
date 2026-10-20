package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuQueryService.SkuListItem;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuQueryService.SkuPageQuery;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.FormulaRuleReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.ServiceFeeRuleReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingRequestMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.PlatformPricingDetail;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.PricingPreview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.Data;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PLATFORM_SKU_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PRODUCT_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PRODUCT_UPDATE_EXPRESSION;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

import java.math.BigDecimal;
import java.util.List;

/**
 * 管理端标品 SKU 池。集合 GET，禁止 {@code /page}。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#44-标品-sku-分页}。
 */
@Tag(name = "管理后台 - 供应链标品SKU")
@RestController
@RequestMapping("/supply-chain/products/skus")
@Validated
@RequiredArgsConstructor
public class PlatformSkuAdminController {

    private final PlatformSkuQueryService queryService;
    private final GenericBatchPricingService genericPricingService;

    @GetMapping
    @PreAuthorize(PLATFORM_SKU_QUERY_EXPRESSION)
    @Operation(summary = "标品 SKU 分页",
            description = "仅返回启用 SKU 与启用 SPU。keyword 为包含匹配，同时命中 SKU 名称、SKU 编码、SPU 名称、SPU 编码；"
                    + "省略或空白不筛选。"
                    + "supplierCode 只返回商品类型相同、分类命中供货配置且尚无未退役 Offer 的 SKU；"
                    + "支持父分类覆盖子分类、最具体优先，不按可售品类匹配，歧义配置不展示。"
                    + "buyerCode 只排除该分销商未移除的分销商品，已移除的 SKU 会再次出现；"
                    + "不按分销商档案的可售品类或商品类型收窄，类型筛选请传 productType。"
                    + "projectCode 只返回所属分销商已有分销商品且项目目录尚未关联的 SKU。"
                    + "categoryName 是 SPU 主类目名称，归档或未挂类目为空，只展示、不参与筛选。"
                    + "supplyPriceCents 是当前最低有效供货价，单位 CNY 分：供应商启用、Offer 已发布；"
                    + "当前版本有效则用版本单价，否则回退合作商品身份列，多条取最低；没有有效报价为空，0 是有效价格。"
                    + "不要调用 /page。")
    public CommonResult<PageResult<SkuListItem>> page(
            @Parameter(description = "包含匹配 SKU 名称、SKU 编码、SPU 名称、SPU 编码。省略或空白不筛选",
                    example = "话费")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "可售品类编码", example = "EQUITY")
            @RequestParam(required = false) String sellableCategory,
            @Parameter(description = "商品类型编码，取值来自商品类型表", example = "EQUITY_PRODUCT")
            @RequestParam(required = false) String productType,
            @Parameter(description = "商品分类编码", example = "VIRTUAL_CARD")
            @RequestParam(required = false) String productClassification,
            @Parameter(description = "供应商编码；筛选可新增报价的SKU", example = "SUP_01ABC")
            @RequestParam(required = false) String supplierCode,
            @Parameter(description = "分销商永久业务编码。只排除该分销商未移除的分销商品；已移除 SKU 会再次出现。"
                    + "不按档案可售品类或商品类型过滤", example = "BYR_01ABC")
            @RequestParam(required = false) String buyerCode,
            @Parameter(description = "项目编码；筛选项目目录尚未关联的SKU", example = "PRJ_01ABC")
            @RequestParam(required = false) String projectCode,
            @Parameter(description = "页码，从1开始", example = "1")
            @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数，默认20", example = "20")
            @RequestParam(required = false) Integer pageSize) {
        return success(queryService.page(new SkuPageQuery(
                keyword, sellableCategory, productType, productClassification,
                supplierCode, buyerCode, projectCode, pageNo, pageSize)));
    }

    @GetMapping("/{skuCode}/pricing")
    @PreAuthorize(PRODUCT_QUERY_EXPRESSION)
    @Operation(summary = "查询商品SKU当前完整默认定价",
            description = "按平台SKU永久业务编码返回当前版本、五组完整配置、计算快照、结果与发布信息；"
                    + "未定价正常返回UNPRICED及空版本。该查询不重算、不签发报价凭证。")
    public CommonResult<PlatformPricingDetail> getPricing(
            @Parameter(description = "平台SKU永久业务编码", required = true, example = "SKU_01ABC")
            @PathVariable String skuCode) {
        return success(genericPricingService.getPlatformPricing(skuCode));
    }

    @PostMapping("/pricing/preview")
    @PreAuthorize(PLATFORM_SKU_QUERY_EXPRESSION)
    @Operation(summary = "批量试算商品SKU默认定价",
            description = "只读试算商品默认层五组完整配置；SPU与SKU均须启用。税务仅作结算展示，不含税仍须提交NON_TAXABLE。")
    public CommonResult<List<PricingPreview>> previewPricing(
            @Valid @RequestBody PlatformSkuBatchPricingReqVO request) {
        return success(genericPricingService.previewPlatform(request.getPlatformSkuCodes(),
                GenericPricingRequestMapper.toPlatformConfig(request)));
    }

    @PutMapping("/pricing")
    @PreAuthorize(PRODUCT_UPDATE_EXPRESSION)
    @Operation(summary = "批量保存商品SKU默认定价",
            description = "SPU与SKU均须启用；单事务全量成功或回滚，保存后为继承的分销商和项目发布新版本。"
                    + "税务仅作结算展示，不含税仍须提交NON_TAXABLE；未启用返回明确业务错误。")
    public CommonResult<Boolean> savePricing(@Valid @RequestBody PlatformSkuBatchPricingReqVO request) {
        genericPricingService.savePlatform(request.getPlatformSkuCodes(),
                GenericPricingRequestMapper.toPlatformConfig(request), getLoginUserId());
        return success(true);
    }

    /**
     * 商品默认层无税务继承来源，两字段始终必填；不含税不是省略税率编码的条件。
     * 仅用于结算展示，不替代 SKU 主数据的旧 tax_rate 字段，也不参与价格计算。
     * 设计见 {@code docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#4-税务展示属性}。
     */
    @Data
    @Schema(description = "平台商品SKU默认批量定价请求")
    public static class PlatformSkuBatchPricingReqVO {
        @NotEmpty @Size(max = GenericBatchPricingService.MAX_BATCH)
        @Schema(description = "平台SKU编码列表", requiredMode = Schema.RequiredMode.REQUIRED)
        private List<@NotBlank String> platformSkuCodes;
        @Valid @Schema(description = "默认分销价规则", requiredMode = Schema.RequiredMode.REQUIRED)
        private FormulaRuleReqVO distributionRule;
        @Valid @Schema(description = "默认销售价规则", requiredMode = Schema.RequiredMode.REQUIRED)
        private FormulaRuleReqVO salesRule;
        @Valid @Schema(description = "默认服务费规则", requiredMode = Schema.RequiredMode.REQUIRED)
        private ServiceFeeRuleReqVO serviceFeeRule;
        @Schema(description = "默认分销区利润隐藏阈值")
        private BigDecimal distributionHideThresholdPercent;
        @Schema(description = "默认销售区利润隐藏阈值")
        private BigDecimal salesHideThresholdPercent;
        @NotNull
        @Schema(description = "是否含税，须与税率编码同时提交；不含税时编码为NON_TAXABLE；仅用于结算展示，不参与价格计算",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean taxIncluded;
        @NotBlank
        @Schema(description = "system动态字典supply_chain_tax_rate的启用编码；不含税仍必填NON_TAXABLE，含税时选择启用编码",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String taxRateCode;
    }
}
