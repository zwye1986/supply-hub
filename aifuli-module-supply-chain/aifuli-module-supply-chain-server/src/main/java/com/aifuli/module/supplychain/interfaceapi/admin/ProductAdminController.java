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
import com.aifuli.module.supplychain.tradecore.catalog.domain.SuggestedSalePriceRule;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.*;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_SUGGESTED_PRICE_RULE_INVALID;

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

    private static final String SPU_SAVE_REQUEST_EXAMPLE = """
            {
              "spuCode": "SPU_DEMO_001",
              "spuName": "爱奇艺黄金会员月卡",
              "subtitle": "官方直充，到账快",
              "brandId": 1,
              "brandCode": "BRD_DEMO_001",
              "primaryCategoryId": 10,
              "secondaryCategoryIds": [11, 12],
              "sellableCategory": "EQUITY",
              "productType": "EQUITY_PRODUCT",
              "productClassification": "VIRTUAL_CARD",
              "coverUrl": "https://cdn.example.com/products/iqiyi-month-cover.png",
              "sliderImageUrls": [
                "https://cdn.example.com/products/iqiyi-month-banner-1.png",
                "https://cdn.example.com/products/iqiyi-month-banner-2.png"
              ],
              "useInstruction": "购买后请输入需要充值的爱奇艺账号，预计5分钟内到账",
              "description": "<p>爱奇艺黄金会员月卡，支持电视剧、电影和综艺会员内容。</p>",
              "deliveryType": "VIRTUAL",
              "fulfillmentType": "DIRECT_RECHARGE",
              "minQuantity": 1,
              "maxQuantity": 10,
              "status": "10",
              "userLimitEnabled": true,
              "userLimitPeriod": "MONTH",
              "userLimitQuantity": 2,
              "orderLimitEnabled": true,
              "phoneBindLimitEnabled": true,
              "phoneBindLimitPeriod": "DAY",
              "phoneBindLimitQuantity": 1,
              "skus": [
                {
                  "skuCode": "SKU_DEMO_001",
                  "skuName": "爱奇艺黄金会员月卡",
                  "mainImageUrl": "https://cdn.example.com/products/iqiyi-month-main.png",
                  "detailImageUrls": [
                    "https://cdn.example.com/products/iqiyi-month-detail-1.png",
                    "https://cdn.example.com/products/iqiyi-month-detail-2.png"
                  ],
                  "description": "爱奇艺黄金会员直充月卡",
                  "fulfillmentType": "DIRECT_RECHARGE",
                  "faceValue": 10000,
                  "marketPrice": 12000,
                  "suggestedSalePrice": 6100,
                  "suggestedSalePriceRule": {
                    "mode": "CUSTOM",
                    "base": "MARKET_PRICE",
                    "percentage": 50,
                    "operator": "ADD",
                    "operand": 1.00,
                    "fixedPriceCents": 6100
                  },
                  "taxIncluded": true,
                  "taxRate": 0.0600,
                  "minQuantity": 1,
                  "maxQuantity": 10,
                  "userLimitEnabled": true,
                  "userLimitPeriod": "MONTH",
                  "userLimitQuantity": 2,
                  "orderLimitEnabled": true,
                  "phoneBindLimitEnabled": true,
                  "phoneBindLimitPeriod": "DAY",
                  "phoneBindLimitQuantity": 1,
                  "skuInventory": 1000,
                  "status": "10"
                }
              ]
            }
            """;

    private final ProductCatalogQueryService queryService;
    private final ProductCatalogCommandService commandService;

    @GetMapping("/spus")
    @PreAuthorize(PRODUCT_QUERY_EXPRESSION)
    @Operation(summary = "分页查询平台 SPU",
            description     = "普通商品类型 Tab 由商品类型表动态驱动；电影和点餐没有专属业务分支。"
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
            @Parameter(description = "商品分类供货命中码：字典枚举名或类目 categoryCode",
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
            @Parameter(description = "商品类型编码，可多选；兼容单个 productType。"
                    + "重复键 productType=A&productType=B，或逗号拼接 productType=A,B",
                    example = "EQUITY_PRODUCT")
            @RequestParam(required = false) List<String> productType,
            @Parameter(description = "主类目内部主键", example = "10") @RequestParam(required = false) Long categoryId,
            @Parameter(description = "品牌内部主键", example = "1") @RequestParam(required = false) Long brandId,
            @Parameter(description = "SKU商品状态：0草稿，10已上架，20已下架；字典supply_chain_sku_status", example = "10") @RequestParam(required = false) Short status,
            @Parameter(description = "下架方式：MANUAL_UNSHELVE手动下架，SYSTEM_UNSHELVE系统下架", example = "MANUAL_UNSHELVE") @RequestParam(required = false) String unshelveMode,
            @Parameter(description = "兑换方式：CARD_CODE_SECRET卡号卡密，DIRECT_RECHARGE直充；字典supply_chain_fulfillment_type", example = "CARD_CODE_SECRET") @RequestParam(required = false) String fulfillmentType,
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
            description = "在同一事务中创建一个 SPU 和至少一个 SKU；目标与结果 Schema 由服务端按履约方式生成；"
                    + "SKU销售建议价规则由服务端校验、计算并与物化金额一并保存。")
    public CommonResult<String> create(
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = SpuSaveReqVO.class),
                            examples = @ExampleObject(name = "完整商品保存示例",
                                    summary = "每个 SPU、SKU 与建议价规则字段均提供具体值",
                                    value = SPU_SAVE_REQUEST_EXAMPLE)))
            SpuSaveReqVO request) {
        return success(commandService.createProduct(toSaveCommand(request, getLoginUserId())));
    }

    @PutMapping("/spus/{spuCode}")
    @PreAuthorize(PRODUCT_UPDATE_EXPRESSION)
    @Operation(summary = "复合更新 SPU 与 SKU",
            description = "按 SKU 编码新增、更新；遗漏且无有效引用的 SKU 自动停用；目标与结果 Schema 由服务端按履约方式生成；"
                    + "SKU销售建议价规则由服务端校验、计算并与物化金额一并保存。")
    public CommonResult<Boolean> update(
            @Parameter(description = "平台SPU永久业务编码", example = "SPU_01ABC") @PathVariable String spuCode,
            @Valid @RequestBody
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = SpuSaveReqVO.class),
                            examples = @ExampleObject(name = "完整商品保存示例",
                                    summary = "每个 SPU、SKU 与建议价规则字段均提供具体值",
                                    value = SPU_SAVE_REQUEST_EXAMPLE)))
            SpuSaveReqVO request) {
        commandService.updateProduct(spuCode, toSaveCommand(request, getLoginUserId()));
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

    /**
     * 把管理端复合保存请求转成领域命令：收下 SKU 图片别名，并把 SPU 根级限购下沉到未显式提交的 SKU。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线}（d35）。
     */
    static SaveSpuCommand toSaveCommand(SpuSaveReqVO request, Long operatorId) {
        String spuFulfillmentType = trimToNull(request.fulfillmentType);
        List<SaveSkuLine> skus = request.skus.stream()
                .map(line -> toSkuLine(line, request, spuFulfillmentType))
                .toList();
        return new SaveSpuCommand(request.spuCode, request.spuName, request.subtitle, request.brandId,
                request.brandCode,
                request.primaryCategoryId, request.secondaryCategoryIds, request.sellableCategory,
                request.productType, request.productClassification, request.coverUrl, request.sliderImageUrls,
                request.useInstruction, request.description, request.deliveryType, spuFulfillmentType,
                request.minQuantity, request.maxQuantity, request.status, skus, operatorId);
    }

    private static SaveSkuLine toSkuLine(SkuItemSaveReqVO line, SpuSaveReqVO request, String spuFulfillmentType) {
        String skuFulfillment = hasText(line.fulfillmentType) ? line.fulfillmentType.trim() : spuFulfillmentType;
        Boolean userLimitEnabled = firstNonNull(line.userLimitEnabled, request.userLimitEnabled);
        String userLimitPeriod = firstText(line.userLimitPeriod, request.userLimitPeriod);
        Long userLimitQuantity = firstNonNull(line.userLimitQuantity, request.userLimitQuantity);
        Boolean orderLimitEnabled = firstNonNull(line.orderLimitEnabled, request.orderLimitEnabled);
        Boolean phoneBindLimitEnabled = firstNonNull(line.phoneBindLimitEnabled, request.phoneBindLimitEnabled);
        Long phoneBindLimitQuantity = firstNonNull(line.phoneBindLimitQuantity, request.phoneBindLimitQuantity);
        String phoneBindLimitPeriod = firstText(line.phoneBindLimitPeriod, request.phoneBindLimitPeriod);
        if (Boolean.TRUE.equals(phoneBindLimitEnabled) && !hasText(phoneBindLimitPeriod)) {
            phoneBindLimitPeriod = userLimitPeriod;
        }
        return new SaveSkuLine(line.skuCode, line.skuName,
                line.mainImageUrl, line.detailImageUrls, line.description,
                skuFulfillment, line.faceValue, line.marketPrice, line.suggestedSalePrice,
                toSuggestedSalePriceRule(line.suggestedSalePriceRule),
                line.taxIncluded, line.taxRate, line.minQuantity, line.maxQuantity,
                userLimitEnabled, userLimitPeriod, userLimitQuantity,
                orderLimitEnabled, phoneBindLimitEnabled, phoneBindLimitPeriod,
                phoneBindLimitQuantity, line.skuInventory, line.status);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String firstText(String preferred, String fallback) {
        return hasText(preferred) ? preferred.trim() : trimToNull(fallback);
    }

    private static <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }

    @Data
    @Schema(description = "SPU 与 SKU 复合保存请求")
    public static class SpuSaveReqVO {
        @Size(max = 32) @Schema(description = "SPU 编码；创建时可空", example = "SPU_DEMO_001")
        private String spuCode;
        @NotBlank @Size(max = 256) @Schema(description = "SPU 名称", example = "爱奇艺黄金会员月卡")
        private String spuName;
        @Size(max = 256) @Schema(description = "副标题", example = "官方直充，到账快")
        private String subtitle;
        @Schema(description = "品牌内部主键；与 brandCode 二选一", example = "1") private Long brandId;
        @Size(max = 32) @Schema(description = "平台品牌永久编码；与 brandId 二选一", example = "BRD_DEMO_001")
        private String brandCode;
        @NotNull
        @Schema(description = "商品分类内部主键；来源于 /supply-chain/categories 启用分类树，不是system数据字典",
                example = "10")
        private Long primaryCategoryId;
        @Schema(description = "辅助分类内部主键列表", example = "[11,12]")
        private List<Long> secondaryCategoryIds;
        @NotBlank @Size(max = 32) @Schema(description = "可售品类", example = "EQUITY")
        private String sellableCategory;
        @NotBlank @Size(max = 32) @Schema(description = "数据驱动商品类型编码", example = "EQUITY_PRODUCT")
        private String productType;
        @NotBlank @Size(max = 32)
        @Schema(description = "供货匹配商品分类：system字典supply_chain_product_classification枚举名，或/supply-chain/categories的categoryCode",
                example = "VIRTUAL_CARD")
        private String productClassification;
        @Size(max = 512) @Schema(description = "封面图地址",
                example = "https://cdn.example.com/products/iqiyi-month-cover.png")
        private String coverUrl;
        @Schema(description = "轮播图地址列表",
                example = "[\"https://cdn.example.com/products/iqiyi-month-banner-1.png\",\"https://cdn.example.com/products/iqiyi-month-banner-2.png\"]")
        private List<String> sliderImageUrls;
        @Size(max = 2000) @Schema(description = "使用说明", example = "购买后请输入需要充值的爱奇艺账号，预计5分钟内到账")
        private String useInstruction;
        @Schema(description = "图文详情", example = "<p>爱奇艺黄金会员月卡，支持电视剧、电影和综艺会员内容。</p>")
        private String description;
        @Size(max = 32) @Schema(description = "配送模式；默认 VIRTUAL", example = "VIRTUAL")
        private String deliveryType;
        @Size(max = 32)
        @Schema(description = "履约类型：DIRECT_RECHARGE直充，VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益；仅虚拟卡券类型必传，未传时由首个 SKU 推断", example = "DIRECT_RECHARGE")
        private String fulfillmentType;
        @Positive @Schema(description = "SPU默认单次最低起购量", example = "1") private Long minQuantity;
        @Positive @Schema(description = "SPU默认单次最高限购量", example = "10") private Long maxQuantity;
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(description = "状态：0草稿，10启用，20停用；默认10；字典值以字符串返回", type = "string", example = "10")
        private Short status;
        @Schema(description = "是否启用每人限购：1启用，0停用", example = "true")
        private Boolean userLimitEnabled;
        @Size(max = 16)
        @Schema(description = "每人限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天",
                example = "MONTH")
        private String userLimitPeriod;
        @Positive @Schema(description = "每人每周期最多购买数量", example = "2")
        private Long userLimitQuantity;
        @Schema(description = "是否启用每订单限购：1启用，0停用", example = "true")
        private Boolean orderLimitEnabled;
        @Schema(description = "是否启用绑定手机号限购：1启用，0停用", example = "true")
        private Boolean phoneBindLimitEnabled;
        @Size(max = 16)
        @Schema(description = "绑定手机号限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天",
                example = "DAY")
        private String phoneBindLimitPeriod;
        @Positive @Schema(description = "同一履约手机号每周期最多购买数量", example = "1")
        private Long phoneBindLimitQuantity;
        @Valid @NotEmpty
        @Schema(description = "至少一个 SKU",
                example = "[{\"skuCode\":\"SKU_DEMO_001\",\"skuName\":\"爱奇艺黄金会员月卡\",\"mainImageUrl\":\"https://cdn.example.com/products/iqiyi-month-main.png\",\"detailImageUrls\":[\"https://cdn.example.com/products/iqiyi-month-detail-1.png\"],\"description\":\"爱奇艺黄金会员直充月卡\",\"fulfillmentType\":\"DIRECT_RECHARGE\",\"faceValue\":10000,\"marketPrice\":12000,\"suggestedSalePrice\":6100,\"suggestedSalePriceRule\":{\"mode\":\"CUSTOM\",\"base\":\"MARKET_PRICE\",\"percentage\":50,\"operator\":\"ADD\",\"operand\":1.00,\"fixedPriceCents\":6100},\"taxIncluded\":true,\"taxRate\":0.0600,\"minQuantity\":1,\"maxQuantity\":10,\"userLimitEnabled\":true,\"userLimitPeriod\":\"MONTH\",\"userLimitQuantity\":2,\"orderLimitEnabled\":true,\"phoneBindLimitEnabled\":true,\"phoneBindLimitPeriod\":\"DAY\",\"phoneBindLimitQuantity\":1,\"skuInventory\":1000,\"status\":\"10\"}]")
        private List<SkuItemSaveReqVO> skus;
    }

    @Data
    @Schema(description = "SKU 保存项")
    public static class SkuItemSaveReqVO {
        @Size(max = 32) @Schema(description = "SKU 编码；新增时可空", example = "SKU_DEMO_001")
        private String skuCode;
        @NotBlank @Size(max = 256) @Schema(description = "SKU 名称", example = "爱奇艺黄金会员月卡")
        private String skuName;
        @Size(max = 512) @Schema(description = "SKU主图地址",
                example = "https://cdn.example.com/products/iqiyi-month-main.png")
        @JsonAlias("picUrl")
        private String mainImageUrl;
        @Schema(description = "SKU详情图地址列表(JSON数组)",
                example = "[\"https://cdn.example.com/products/iqiyi-month-detail-1.png\",\"https://cdn.example.com/products/iqiyi-month-detail-2.png\"]")
        @JsonAlias("detailPics")
        private List<@NotBlank @Size(max = 512) String> detailImageUrls;
        @Size(max = 200) @Schema(description = "SKU商品描述", example = "爱奇艺黄金会员直充月卡")
        private String description;
        @Size(max = 32)
        @Schema(description = "履约类型：DIRECT_RECHARGE直充，VOUCHER_CODE券码，CARD_CODE_SECRET卡号卡密，VOUCHER_URL券链接，ACCOUNT_BOUND_BENEFIT账户绑定权益",
                example = "DIRECT_RECHARGE", deprecated = true)
        private String fulfillmentType;
        @NotNull @Min(0) @Schema(description = "面值，CNY分", example = "10000") private Long faceValue;
        @Min(0) @Schema(description = "市场价，CNY分；默认0", example = "12000") private Long marketPrice;
        @Min(0) @Schema(description = "销售建议价，CNY分；默认0", example = "6100")
        private Long suggestedSalePrice;
        @Valid
        @Schema(description = "SKU销售建议价生成规则；物化结果保存在suggestedSalePrice",
                example = "{\"mode\":\"CUSTOM\",\"base\":\"MARKET_PRICE\",\"percentage\":50,\"operator\":\"ADD\",\"operand\":1.00,\"fixedPriceCents\":6100}")
        private SuggestedSalePriceRuleReqVO suggestedSalePriceRule;
        @Schema(description = "是否含税：1含税，0不含税；仅记录展示，不参与价格计算；标准商品结算以分销商/项目通用定价为准",
                example = "true")
        private Boolean taxIncluded;
        @DecimalMin("0.0000") @DecimalMax("1.0000")
        @Schema(description = "税率，如0.0600表示6%；仅记录展示，不参与价格计算；标准商品结算以分销商/项目通用定价为准",
                example = "0.0600")
        private BigDecimal taxRate;
        @Positive @Schema(description = "SKU单次最小起购数量", example = "1") private Long minQuantity;
        @Positive @Schema(description = "SKU单次最大限购数量", example = "10") private Long maxQuantity;
        @Schema(description = "是否启用每人限购：1启用，0停用", example = "true")
        private Boolean userLimitEnabled;
        @Size(max = 16)
        @Schema(description = "每人限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天",
                example = "MONTH")
        private String userLimitPeriod;
        @Positive @Schema(description = "每人每周期最多购买数量", example = "2") private Long userLimitQuantity;
        @Schema(description = "是否启用每订单限购：1启用，0停用", example = "true")
        private Boolean orderLimitEnabled;
        @Schema(description = "是否启用绑定手机号限购：1启用，0停用", example = "true")
        private Boolean phoneBindLimitEnabled;
        @Size(max = 16)
        @Schema(description = "绑定手机号限购周期：LIFETIME终身，YEAR每年，MONTH每月，WEEK每周，DAY每天",
                example = "DAY")
        private String phoneBindLimitPeriod;
        @Positive @Schema(description = "同一履约手机号每周期最多购买数量", example = "1")
        private Long phoneBindLimitQuantity;
        @Min(0) @Schema(description = "SKU库存数量，管理端创建或编辑商品时设置", example = "1000")
        private Long skuInventory;
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(description = "SKU 状态；默认继承 SPU；字典值以字符串返回", type = "string", example = "10")
        private Short status;
    }

    @Data
    @Schema(description = "SKU销售建议价生成规则")
    public static class SuggestedSalePriceRuleReqVO {
        @NotBlank
        @Schema(description = "建议价模式（字典supply_chain_suggested_sale_price_mode）：EQUAL_MARKET_PRICE等于市场价、CUSTOM自定义设置、FIXED固定值",
                example = "CUSTOM")
        private String mode;
        @Schema(description = "CUSTOM基数：MARKET_PRICE市场价、SUGGESTED_SALE_PRICE修改前销售建议价",
                example = "MARKET_PRICE")
        private String base;
        @Schema(description = "CUSTOM百分比，大于0且最多2位小数", example = "50")
        private BigDecimal percentage;
        @Schema(description = "CUSTOM运算符（字典supply_chain_suggested_sale_price_operator）：ADD加、SUBTRACT减、MULTIPLY乘、DIVIDE除",
                example = "ADD")
        private String operator;
        @Schema(description = "CUSTOM运算数；加减单位元且最多2位小数，乘除为系数且最多4位小数",
                example = "1.00")
        private BigDecimal operand;
        @Min(0) @Schema(description = "FIXED固定销售建议价，单位CNY分", example = "6100")
        private Long fixedPriceCents;
    }

    private static SuggestedSalePriceRule toSuggestedSalePriceRule(SuggestedSalePriceRuleReqVO request) {
        if (request == null) {
            return null;
        }
        try {
            return new SuggestedSalePriceRule(
                    SuggestedSalePriceRule.Mode.valueOf(request.mode.trim()),
                    request.base == null || request.base.isBlank() ? null
                            : SuggestedSalePriceRule.Base.valueOf(request.base.trim()),
                    request.percentage,
                    request.operator == null || request.operator.isBlank() ? null
                            : SuggestedSalePriceRule.Operator.valueOf(request.operator.trim()),
                    request.operand, request.fixedPriceCents, null);
        } catch (RuntimeException invalidEnum) {
            throw exception(PRODUCT_SUGGESTED_PRICE_RULE_INVALID);
        }
    }

    @Data
    @Schema(description = "商品批量上下架请求")
    public static class ProductBatchStatusReqVO {
        @Schema(description = "选中的平台 SPU 编码列表")
        private List<String> spuCodes;
        @Schema(description = "选中的平台 SKU 编码列表")
        private List<String> skuCodes;
        @NotNull(message = "目标状态不能为空")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(description = "目标状态：10上架，20下架；字典值以字符串传递", type = "string",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "10")
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
        @Schema(description = "是否含税：1含税，0不含税；仅记录展示，不参与价格计算；标准商品结算以分销商/项目通用定价为准",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean taxIncluded;
        @DecimalMin("0.0000")
        @DecimalMax("1.0000")
        @Schema(description = "税率，如0.0600表示6%；仅记录展示，不参与价格计算；标准商品结算以分销商/项目通用定价为准", example = "0.0600")
        private BigDecimal taxRate;
    }
}
