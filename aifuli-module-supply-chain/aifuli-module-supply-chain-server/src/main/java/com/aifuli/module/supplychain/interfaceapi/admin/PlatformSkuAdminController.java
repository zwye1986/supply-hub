package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuQueryService.SkuListItem;
import com.aifuli.module.supplychain.tradecore.catalog.application.PlatformSkuQueryService.SkuPageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PLATFORM_SKU_QUERY_EXPRESSION;

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
}
