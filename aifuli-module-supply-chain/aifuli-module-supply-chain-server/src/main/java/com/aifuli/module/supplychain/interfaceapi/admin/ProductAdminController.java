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
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

import java.math.BigDecimal;
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

    @GetMapping("/management-page")
    @PreAuthorize(PRODUCT_QUERY_EXPRESSION)
    @Operation(summary = "管理端分页查询商品聚合列表",
            description = "支撑运营管理端商品列表原型：SPU平铺展开多SKU明细、多维筛选及供货价、税率、税额、供应商换行聚合展示。")
    public CommonResult<PageResult<ProductCatalogQueryService.ProductManagementSpuItem>> managementPage(
            @Parameter(description = "业务 Tab 标识", example = "VIRTUAL_CARD") @RequestParam(required = false) String tabCode,
            @Parameter(description = "SPU 编码、名称模糊搜索", example = "爱奇艺") @RequestParam(required = false) String keyword,
            @Parameter(description = "商品类型编码", example = "PT_CARD") @RequestParam(required = false) String productType,
            @Parameter(description = "主类目内部主键", example = "10") @RequestParam(required = false) Long categoryId,
            @Parameter(description = "品牌内部主键", example = "1") @RequestParam(required = false) Long brandId,
            @Parameter(description = "SPU 状态：0草稿，10已上架，20已下架", example = "10") @RequestParam(required = false) Short status,
            @Parameter(description = "下架方式：MANUAL_UNSHELVE手动下架，SYSTEM_UNSHELVE系统下架", example = "MANUAL_UNSHELVE") @RequestParam(required = false) String unshelveMode,
            @Parameter(description = "兑换方式：CARD_CODE_SECRET卡号卡密，DIRECT_RECHARGE虚拟直充", example = "CARD_CODE_SECRET") @RequestParam(required = false) String fulfillmentType,
            @Parameter(description = "市场价最小金额，单位CNY分", example = "1000") @RequestParam(required = false) Long marketPriceMin,
            @Parameter(description = "市场价最大金额，单位CNY分", example = "5000") @RequestParam(required = false) Long marketPriceMax,
            @Parameter(description = "供货价最小金额，单位CNY分", example = "800") @RequestParam(required = false) Long supplyPriceMin,
            @Parameter(description = "供货价最大金额，单位CNY分", example = "4000") @RequestParam(required = false) Long supplyPriceMax,
            @Parameter(description = "供应商编码", example = "SUP_01ABC") @RequestParam(required = false) String supplierCode,
            @Parameter(description = "更新时间开始，Asia/Shanghai") @RequestParam(required = false) java.time.LocalDateTime updateTimeStart,
            @Parameter(description = "更新时间结束，Asia/Shanghai") @RequestParam(required = false) java.time.LocalDateTime updateTimeEnd,
            @Parameter(description = "页码，从1开始", example = "1") @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数，默认20", example = "20") @RequestParam(required = false) Integer pageSize) {
        return success(queryService.managementPage(new ProductCatalogQueryService.ProductManagementPageQuery(
                tabCode, keyword, productType, categoryId, brandId, status, unshelveMode, fulfillmentType,
                marketPriceMin, marketPriceMax, supplyPriceMin, supplyPriceMax, supplierCode,
                updateTimeStart, updateTimeEnd, pageNo, pageSize)));
    }

    @PutMapping("/batch-status")
    @PreAuthorize(PRODUCT_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "批量变更平台商品状态（批量上下架）",
            description = "支持多选 SPU 或多选 SKU 进行批量上架或下架操作；下架时统一记录下架方式。")
    public CommonResult<Boolean> batchChangeStatus(@Valid @RequestBody ProductBatchStatusReqVO request) {
        commandService.batchChangeStatus(new ProductCatalogCommandService.BatchStatusCommand(
                request.getSpuCodes(), request.getSkuCodes(), request.getTargetStatus(),
                request.getUnshelveMode(), getLoginUserId()));
        return success(true);
    }

    @PutMapping("/skus/tax-metadata")
    @PreAuthorize(PRODUCT_UPDATE_EXPRESSION)
    @Operation(summary = "批量记录SKU税务元数据",
            description = "是否含税与税率只保存和展示，不参与市场价、供货价、L1/L2/L3或最终结算价计算。")
    public CommonResult<Boolean> batchUpdateSkuTaxMetadata(
            @Valid @RequestBody SkuTaxMetadataBatchReqVO request) {
        commandService.batchUpdateSkuTaxMetadata(new ProductCatalogCommandService.SkuTaxMetadataBatchCommand(
                request.getSkuCodes(), request.getTaxIncluded(), request.getTaxRate(), getLoginUserId()));
        return success(true);
    }

    @GetMapping("/spus/{spuCode}")
    @PreAuthorize(PRODUCT_QUERY_EXPRESSION)
    @Operation(summary = "查询平台 SPU 与 SKU 详情")
    public CommonResult<SpuDetail> detail(
            @Parameter(description = "平台SPU永久业务编码", example = "SPU_01ABC") @PathVariable String spuCode) {
        return success(queryService.detail(spuCode));
    }

    @PostMapping("/spus")
    @PreAuthorize(PRODUCT_CREATE_EXPRESSION)
    @Operation(summary = "复合创建 SPU 与 SKU",
            description = "在同一事务中创建一个 SPU 和至少一个 SKU；目标与结果 Schema 由服务端按履约方式生成。")
    public CommonResult<String> create(@Valid @RequestBody SpuSaveReqVO request) {
        return success(commandService.createProduct(toCommand(request)));
    }

    @PutMapping("/spus/{spuCode}")
    @PreAuthorize(PRODUCT_UPDATE_EXPRESSION)
    @Operation(summary = "复合更新 SPU 与 SKU",
            description = "按 SKU 编码新增、更新；遗漏且无有效引用的 SKU 自动停用；目标与结果 Schema 由服务端按履约方式生成。")
    public CommonResult<Boolean> update(
            @Parameter(description = "平台SPU永久业务编码", example = "SPU_01ABC") @PathVariable String spuCode,
            @Valid @RequestBody SpuSaveReqVO request) {
        commandService.updateProduct(spuCode, toCommand(request));
        return success(true);
    }

    @PutMapping("/spus/{spuCode}/status")
    @PreAuthorize(PRODUCT_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "变更 SPU 状态", description = "SPU 与 SKU 状态独立，SPU 下架不改写 SKU 状态。")
    public CommonResult<Boolean> changeSpuStatus(
            @Parameter(description = "平台SPU永久业务编码", example = "SPU_01ABC")
            @PathVariable String spuCode,
            @Valid @RequestBody CatalogStatusSwitchReqVO request) {
        commandService.changeSpuStatus(spuCode, request.requireSwitchTarget(), getLoginUserId());
        return success(true);
    }

    @DeleteMapping("/spus/{spuCode}")
    @PreAuthorize(PRODUCT_DELETE_EXPRESSION)
    @Operation(summary = "逻辑删除 SPU", description = "报价、分销商品、采购商上架或项目商品存在有效引用时拒绝。")
    public CommonResult<Boolean> deleteSpu(
            @Parameter(description = "平台SPU永久业务编码", example = "SPU_01ABC") @PathVariable String spuCode) {
        commandService.deleteSpu(spuCode, getLoginUserId());
        return success(true);
    }

    @PutMapping("/skus/{skuCode}/status")
    @PreAuthorize(PRODUCT_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "变更单个 SKU 状态")
    public CommonResult<Boolean> changeSkuStatus(
            @Parameter(description = "平台SKU永久业务编码", example = "SKU_01ABC")
            @PathVariable String skuCode,
            @Valid @RequestBody CatalogStatusSwitchReqVO request) {
        commandService.changeSkuStatus(skuCode, request.requireSwitchTarget(), getLoginUserId());
        return success(true);
    }

    @DeleteMapping("/skus/{skuCode}")
    @PreAuthorize(PRODUCT_DELETE_EXPRESSION)
    @Operation(summary = "逻辑删除单个 SKU", description = "先执行四类有效业务引用校验。")
    public CommonResult<Boolean> deleteSku(
            @Parameter(description = "平台SKU永久业务编码", example = "SKU_01ABC") @PathVariable String skuCode) {
        commandService.deleteSku(skuCode, getLoginUserId());
        return success(true);
    }

    private SaveSpuCommand toCommand(SpuSaveReqVO request) {
        List<SaveSkuLine> skus = request.skus.stream().map(line -> new SaveSkuLine(line.skuCode, line.skuName,
                line.fulfillmentType, line.faceValue, line.marketPrice, line.suggestedSalePrice,
                line.taxIncluded, line.taxRate, line.minQuantity, line.maxQuantity,
                line.userLimitEnabled, line.userLimitPeriod, line.userLimitQuantity,
                line.orderLimitEnabled, line.phoneBindLimitEnabled, line.phoneBindLimitPeriod,
                line.phoneBindLimitQuantity, line.status)).toList();
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
        @Positive @Schema(description = "SPU默认单次最低起购量") private Long minQuantity;
        @Positive @Schema(description = "SPU默认单次最高限购量") private Long maxQuantity;
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
        @Schema(description = "是否含税：1含税，0不含税；仅记录展示，不参与价格计算") private Boolean taxIncluded;
        @DecimalMin("0.0000") @DecimalMax("1.0000")
        @Schema(description = "税率，如0.0600表示6%；仅记录展示，不参与价格计算") private BigDecimal taxRate;
        @Positive @Schema(description = "SKU单次最小起购数量") private Long minQuantity;
        @Positive @Schema(description = "SKU单次最大限购数量") private Long maxQuantity;
        @Schema(description = "是否启用每人限购：1启用，0停用") private Boolean userLimitEnabled;
        @Size(max = 16)
        @Schema(description = "每人限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天")
        private String userLimitPeriod;
        @Positive @Schema(description = "每人每周期最多购买数量") private Long userLimitQuantity;
        @Schema(description = "是否启用每订单限购：1启用，0停用") private Boolean orderLimitEnabled;
        @Schema(description = "是否启用绑定手机号限购：1启用，0停用") private Boolean phoneBindLimitEnabled;
        @Size(max = 16)
        @Schema(description = "绑定手机号限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天")
        private String phoneBindLimitPeriod;
        @Positive @Schema(description = "同一履约手机号每周期最多购买数量") private Long phoneBindLimitQuantity;
        @Schema(description = "SKU 状态；默认继承 SPU") private Short status;
    }

    @Data
    @Schema(description = "商品批量上下架请求")
    public static class ProductBatchStatusReqVO {
        @Schema(description = "选中的平台 SPU 编码列表")
        private List<String> spuCodes;
        @Schema(description = "选中的平台 SKU 编码列表")
        private List<String> skuCodes;
        @NotNull(message = "目标状态不能为空")
        @Schema(description = "目标状态：10上架，20下架", requiredMode = Schema.RequiredMode.REQUIRED, example = "10")
        private Short targetStatus;
        @Schema(description = "下架方式：MANUAL_UNSHELVE手动下架，SYSTEM_UNSHELVE系统下架；下架时可选，缺省为 MANUAL_UNSHELVE", example = "MANUAL_UNSHELVE")
        private String unshelveMode;
    }

    @Data
    @Schema(description = "SKU税务展示元数据批量记录请求")
    public static class SkuTaxMetadataBatchReqVO {
        @NotEmpty
        @Size(max = 200)
        @Schema(description = "平台SKU永久业务编码列表", requiredMode = Schema.RequiredMode.REQUIRED)
        private List<@NotBlank String> skuCodes;
        @NotNull
        @Schema(description = "是否含税：1含税，0不含税；仅记录展示，不参与价格计算",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean taxIncluded;
        @DecimalMin("0.0000")
        @DecimalMax("1.0000")
        @Schema(description = "税率，如0.0600表示6%；仅记录展示，不参与价格计算", example = "0.0600")
        private BigDecimal taxRate;
    }
}
