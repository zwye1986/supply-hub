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
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PLATFORM_SKU_QUERY_EXPRESSION;
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
            description = "仅返回启用 SKU 与启用 SPU。keyword 匹配 SKU 或 SPU 名称。"
                    + "supplierCode 只返回命中供货配置且尚无未退役 Offer 的 SKU；"
                    + "buyerCode 只返回该分销商尚未关联的平台 SKU；"
                    + "projectCode 只返回所属分销商已有分销商品且项目目录尚未关联的 SKU。"
                    + "不要调用 /page。")
    public CommonResult<PageResult<SkuListItem>> page(
            @Parameter(description = "SKU名称或SPU名称模糊搜索", example = "话费")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "可售品类编码", example = "EQUITY")
            @RequestParam(required = false) String sellableCategory,
            @Parameter(description = "商品类型编码，取值来自商品类型表", example = "EQUITY_PRODUCT")
            @RequestParam(required = false) String productType,
            @Parameter(description = "商品分类编码", example = "VIRTUAL_CARD")
            @RequestParam(required = false) String productClassification,
            @Parameter(description = "供应商编码；筛选可新增报价的SKU", example = "SUP_01ABC")
            @RequestParam(required = false) String supplierCode,
            @Parameter(description = "分销商编码；筛选尚未关联的SKU", example = "BUYER_01ABC")
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

    @PostMapping("/pricing/preview")
    @PreAuthorize(PLATFORM_SKU_QUERY_EXPRESSION)
    @Operation(summary = "批量试算商品SKU默认定价",
            description = "只读试算商品默认层五组配置；税务仅作结算展示。")
    public CommonResult<List<PricingPreview>> previewPricing(
            @Valid @RequestBody PlatformSkuBatchPricingReqVO request) {
        return success(genericPricingService.previewPlatform(request.getPlatformSkuCodes(),
                GenericPricingRequestMapper.toPlatformConfig(request)));
    }

    @PutMapping("/pricing")
    @PreAuthorize(PRODUCT_UPDATE_EXPRESSION)
    @Operation(summary = "批量保存商品SKU默认定价",
            description = "单事务全量成功或回滚，保存后为继承的分销商和项目发布新版本。")
    public CommonResult<Boolean> savePricing(@Valid @RequestBody PlatformSkuBatchPricingReqVO request) {
        genericPricingService.savePlatform(request.getPlatformSkuCodes(),
                GenericPricingRequestMapper.toPlatformConfig(request), getLoginUserId());
        return success(true);
    }

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
        @Schema(description = "是否含税，须与税率编码同时提交", requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean taxIncluded;
        @NotBlank @Schema(description = "system动态字典supply_chain_tax_rate的启用值")
        private String taxRateCode;
    }
}
