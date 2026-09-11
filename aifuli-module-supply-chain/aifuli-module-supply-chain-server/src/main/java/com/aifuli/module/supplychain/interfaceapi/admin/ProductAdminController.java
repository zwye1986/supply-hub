package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.SaveSkuLine;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService.SaveSpuCommand;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService.SpuDetail;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService.SpuListItem;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogQueryService.SpuPageQuery;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.*;

/**
 * 平台商品 SPU+SKU 复合管理入口。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#41-平台商品管理productadmincontroller}。
 */
@Tag(name = "管理后台 - 供应链平台商品")
@RestController
@RequestMapping("/supply-chain/products")
@Validated
@RequiredArgsConstructor
public class ProductAdminController {

    private final ProductCatalogQueryService queryService;
    private final ProductCatalogCommandService commandService;

    @GetMapping("/spus")
    @PreAuthorize(PRODUCT_QUERY_EXPRESSION)
    @Operation(summary = "分页查询平台 SPU",
            description = "普通商品类型 Tab 由商品类型表动态驱动；电影和点餐没有专属业务分支。"
                    + "tabCode 传入未知的动态类型编码时，返回 1050001081 商品类型不存在。")
    public CommonResult<PageResult<SpuListItem>> page(
            @Parameter(description = "业务 Tab 标识。固定复合 Tab：ALL 全部、VIRTUAL_CARD 虚拟卡密、"
                    + "VIRTUAL_RECHARGE 虚拟直充、JD 京东；其余取值按商品类型编码过滤",
                    example = "VIRTUAL_CARD")
            @RequestParam(required = false) String tabCode,
            @Parameter(description = "SPU 编码、名称或副标题模糊搜索", example = "话费")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "品牌内部主键", example = "1")
            @RequestParam(required = false) Long brandId,
            @Parameter(description = "主类目内部主键", example = "10")
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "可售品类封闭枚举：EQUITY、PHYSICAL、MOVIE、JD、DINING、TAKEOUT、"
                    + "SUPERMARKET、HEMA、RTMART、LIFE", example = "EQUITY")
            @RequestParam(required = false) String sellableCategory,
            @Parameter(description = "商品类型编码，取值来自商品类型表", example = "EQUITY_PRODUCT")
            @RequestParam(required = false) String productType,
            @Parameter(description = "商品分类封闭枚举：ELECTRONICS、VIRTUAL_CARD、MOVIE_TICKET",
                    example = "VIRTUAL_CARD")
            @RequestParam(required = false) String productClassification,
            @Parameter(description = "SPU 状态：0草稿，10启用，20停用", example = "10")
            @RequestParam(required = false) Short status,
            @Parameter(description = "页码，从1开始", example = "1") @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数，默认20", example = "20") @RequestParam(required = false) Integer pageSize) {
        return success(queryService.page(new SpuPageQuery(tabCode, keyword, brandId, categoryId, sellableCategory,
                productType, productClassification, status, pageNo, pageSize)));
    }

    @GetMapping("/spus/{spuCode}")
    @PreAuthorize(PRODUCT_QUERY_EXPRESSION)
    @Operation(summary = "查询平台 SPU 与 SKU 详情")
    public CommonResult<SpuDetail> detail(@PathVariable String spuCode) {
        return success(queryService.detail(spuCode));
    }

    @PostMapping("/spus")
    @PreAuthorize(PRODUCT_CREATE_EXPRESSION)
    @Operation(summary = "复合创建 SPU 与 SKU", description = "在同一事务中创建一个 SPU 和至少一个 SKU。")
    public CommonResult<String> create(@Valid @RequestBody SpuSaveReqVO request) {
        return success(commandService.createProduct(toCommand(request)));
    }

    @PutMapping("/spus/{spuCode}")
    @PreAuthorize(PRODUCT_UPDATE_EXPRESSION)
    @Operation(summary = "复合更新 SPU 与 SKU", description = "按 SKU 编码新增、更新；遗漏且无有效引用的 SKU 自动停用。")
    public CommonResult<Boolean> update(@PathVariable String spuCode, @Valid @RequestBody SpuSaveReqVO request) {
        commandService.updateProduct(spuCode, toCommand(request));
        return success(true);
    }

    @PutMapping("/spus/{spuCode}/status")
    @PreAuthorize(PRODUCT_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "变更 SPU 状态", description = "SPU 与 SKU 状态独立，SPU 下架不改写 SKU 状态。")
    public CommonResult<Boolean> changeSpuStatus(@PathVariable String spuCode,
                                                  @Valid @RequestBody CatalogStatusSwitchReqVO request) {
        commandService.changeSpuStatus(spuCode, request.requireSwitchTarget(), getLoginUserId());
        return success(true);
    }

    @DeleteMapping("/spus/{spuCode}")
    @PreAuthorize(PRODUCT_DELETE_EXPRESSION)
    @Operation(summary = "逻辑删除 SPU", description = "报价、分销商品、采购商上架或项目商品存在有效引用时拒绝。")
    public CommonResult<Boolean> deleteSpu(@PathVariable String spuCode) {
        commandService.deleteSpu(spuCode, getLoginUserId());
        return success(true);
    }

    @PutMapping("/skus/{skuCode}/status")
    @PreAuthorize(PRODUCT_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "变更单个 SKU 状态")
    public CommonResult<Boolean> changeSkuStatus(@PathVariable String skuCode,
                                                  @Valid @RequestBody CatalogStatusSwitchReqVO request) {
        commandService.changeSkuStatus(skuCode, request.requireSwitchTarget(), getLoginUserId());
        return success(true);
    }

    @DeleteMapping("/skus/{skuCode}")
    @PreAuthorize(PRODUCT_DELETE_EXPRESSION)
    @Operation(summary = "逻辑删除单个 SKU", description = "先执行四类有效业务引用校验。")
    public CommonResult<Boolean> deleteSku(@PathVariable String skuCode) {
        commandService.deleteSku(skuCode, getLoginUserId());
        return success(true);
    }

    private SaveSpuCommand toCommand(SpuSaveReqVO request) {
        List<SaveSkuLine> skus = request.skus.stream().map(line -> new SaveSkuLine(line.skuCode, line.skuName,
                line.fulfillmentType, line.faceValue, line.marketPrice, line.suggestedSalePrice, line.minQuantity,
                line.maxQuantity, line.targetSchemaCode, line.targetSchemaVersion, line.resultSchemaCode,
                line.resultSchemaVersion, line.status)).toList();
        return new SaveSpuCommand(request.spuCode, request.spuName, request.subtitle, request.brandId,
                request.primaryCategoryId, request.secondaryCategoryIds, request.sellableCategory,
                request.productType, request.productClassification, request.coverUrl, request.sliderImageUrls,
                request.useInstruction, request.description, request.deliveryType, request.minQuantity,
                request.maxQuantity, request.status, skus, getLoginUserId());
    }

    @Data
    @Schema(description = "SPU 与 SKU 复合保存请求")
    public static class SpuSaveReqVO {
        @Size(max = 32) @Schema(description = "SPU 编码；创建时可空") private String spuCode;
        @NotBlank @Size(max = 256) @Schema(description = "SPU 名称") private String spuName;
        @Size(max = 256) @Schema(description = "副标题") private String subtitle;
        @NotNull @Schema(description = "品牌内部主键") private Long brandId;
        @Schema(description = "主分类内部主键") private Long primaryCategoryId;
        @Schema(description = "辅助分类内部主键列表") private List<Long> secondaryCategoryIds;
        @NotBlank @Size(max = 32) @Schema(description = "可售品类") private String sellableCategory;
        @NotBlank @Size(max = 32) @Schema(description = "数据驱动商品类型编码") private String productType;
        @NotBlank @Size(max = 32) @Schema(description = "商品分类") private String productClassification;
        @Size(max = 512) @Schema(description = "封面图地址") private String coverUrl;
        @Schema(description = "轮播图地址列表") private List<String> sliderImageUrls;
        @Size(max = 2000) @Schema(description = "使用说明") private String useInstruction;
        @Schema(description = "图文详情") private String description;
        @Size(max = 32) @Schema(description = "配送模式；默认 VIRTUAL") private String deliveryType;
        @Positive @Schema(description = "SPU 最低起购量；默认1") private Long minQuantity;
        @Positive @Schema(description = "SPU 单次最高购买量；默认9999999") private Long maxQuantity;
        @Schema(description = "状态：0草稿，10启用，20停用；默认10") private Short status;
        @Valid @NotEmpty @Schema(description = "至少一个 SKU") private List<SkuItemSaveReqVO> skus;
    }

    @Data
    @Schema(description = "SKU 保存项")
    public static class SkuItemSaveReqVO {
        @Size(max = 32) @Schema(description = "SKU 编码；新增时可空") private String skuCode;
        @NotBlank @Size(max = 256) @Schema(description = "SKU 名称") private String skuName;
        @NotBlank @Size(max = 32) @Schema(description = "履约方式") private String fulfillmentType;
        @NotNull @Min(0) @Schema(description = "面值，CNY分") private Long faceValue;
        @Min(0) @Schema(description = "市场价，CNY分；默认0") private Long marketPrice;
        @Min(0) @Schema(description = "销售建议价，CNY分；默认0") private Long suggestedSalePrice;
        @Positive @Schema(description = "SKU 最低起购量；默认1") private Long minQuantity;
        @Positive @Schema(description = "SKU 单次最高购买量；默认9999999") private Long maxQuantity;
        @Size(max = 64) @Schema(description = "目标参数 Schema 编码；与版本成对") private String targetSchemaCode;
        @Positive @Schema(description = "目标参数 Schema 版本；与编码成对") private Integer targetSchemaVersion;
        @NotBlank @Size(max = 64) @Schema(description = "履约结果 Schema 编码，不允许 DEFAULT") private String resultSchemaCode;
        @NotNull @Positive @Schema(description = "履约结果 Schema 版本") private Integer resultSchemaVersion;
        @Schema(description = "SKU 状态；默认继承 SPU") private Short status;
    }
}
