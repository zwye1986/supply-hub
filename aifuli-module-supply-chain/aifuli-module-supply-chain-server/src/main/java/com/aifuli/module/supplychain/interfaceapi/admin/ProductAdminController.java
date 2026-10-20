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
import com.aifuli.module.supplychain.tradecore.supply.application.OfferFulfillmentScoreService;
import com.aifuli.module.supplychain.tradecore.supply.application.OfferFulfillmentScoreService.ScoreUpdateResult;
import com.aifuli.module.supplychain.tradecore.supply.application.OfferFulfillmentScoreService.SkuFulfillmentScoreView;
import com.aifuli.module.supplychain.tradecore.supply.application.OfferFulfillmentScoreService.SkuSupplierScoreLine;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferFulfillmentScore;
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
import jakarta.validation.constraints.Max;
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
    private final OfferFulfillmentScoreService offerFulfillmentScoreService;

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
            description = "支撑运营管理端商品列表，以及新增分销商品选品。"
                    + "keyword 包含匹配 SPU 名称、SPU 编码、副标题、SKU 名称、SKU 编码；只命中 SKU 时不返回未命中规格。"
                    + "普通商品管理列表返回全部未归档 SKU，无正常供应商未退役 Offer 时供应商列表为空、供货价为空；"
                    + "新增分销商品选品只返回至少有一个正常供应商未退役 Offer 的 SKU，供应商列表也只展示正常供应商。"
                    + "buyerCode 排除该分销商未移除的分销商品，已移除 SKU 会再次出现，且不按档案商品类型收窄。"
                    + "excludeAddedSupplierOffers=true 时排除该供应商未退役的供应商品，已退役 SKU 会再次出现；"
                    + "该新增供应商品场景允许尚无供应商的 SKU，按供货配置商品类型及分类匹配，支持父分类覆盖子分类、最具体优先，"
                    + "不按可售品类匹配，歧义配置不展示；省略该开关时 supplierCode 仍只保留正常供应商已供商品。"
                    + "supplyPrice 为当前最低有效供货价：有效报价版本优先，否则回退合作商品身份列，单位 CNY 分。")
    public CommonResult<PageResult<ProductCatalogQueryService.ProductManagementSpuItem>> managementPage(
            @Parameter(description = "业务 Tab 标识", example = "VIRTUAL_CARD") @RequestParam(required = false) String tabCode,
            @Parameter(description = "包含匹配 SPU 名称、SPU 编码、副标题、SKU 名称、SKU 编码。省略或空白不筛选",
                    example = "爱奇艺") @RequestParam(required = false) String keyword,
            @Parameter(description = "商品类型编码，可多选；兼容单个 productType。"
                    + "重复键 productType=A&productType=B，或逗号拼接 productType=A,B",
                    example = "EQUITY_PRODUCT")
            @RequestParam(required = false) List<String> productType,
            @Parameter(description = "主类目内部主键，可多选；兼容单个 categoryId。"
                    + "重复键 categoryId=10&categoryId=20，或逗号拼接 categoryId=10,20。"
                    + "选中节点含未归档子孙类目",
                    example = "10")
            @RequestParam(required = false) List<String> categoryId,
            @Parameter(description = "品牌内部主键", example = "1") @RequestParam(required = false) Long brandId,
            @Parameter(description = "SKU商品状态：0草稿，10已上架，20已下架；字典supply_chain_sku_status", example = "10") @RequestParam(required = false) Short status,
            @Parameter(description = "下架方式：MANUAL_UNSHELVE手动下架，SYSTEM_UNSHELVE系统下架", example = "MANUAL_UNSHELVE") @RequestParam(required = false) String unshelveMode,
            @Parameter(description = "兑换方式：CARD_CODE_SECRET卡号卡密，DIRECT_RECHARGE直充；字典supply_chain_fulfillment_type", example = "CARD_CODE_SECRET") @RequestParam(required = false) String fulfillmentType,
            @Parameter(description = "市场价最小金额，单位CNY分", example = "1000") @RequestParam(required = false) Long marketPriceMin,
            @Parameter(description = "市场价最大金额，单位CNY分", example = "5000") @RequestParam(required = false) Long marketPriceMax,
            @Parameter(description = "供货价最小金额，单位CNY分", example = "800") @RequestParam(required = false) Long supplyPriceMin,
            @Parameter(description = "供货价最大金额，单位CNY分", example = "4000") @RequestParam(required = false) Long supplyPriceMax,
            @Parameter(description = "供应商编码。省略 excludeAddedSupplierOffers 或为 false 时，"
                    + "只保留该正常供应商已有未退役供应商品的 SPU；供应商状态非正常时返回空页", example = "SUP_01ABC")
            @RequestParam(required = false) String supplierCode,
            @Parameter(description = "是否排除该供应商已添加且未退役的供应商品。省略或 false 不排除，"
                    + "supplierCode 仍表示只看该供应商已供商品。"
                    + "true 时必须同时传 supplierCode，去掉未退役 Offer，已退役 SKU 会再次出现，"
                    + "且不再按已供商品收窄；分页前按供货配置的商品类型和分类收窄，支持父分类覆盖子分类，"
                    + "最具体优先，不按可售品类匹配，歧义配置不展示。"
                    + "supplierCode 空白返回 SUPPLIER_CODE_REQUIRED；供应商不存在返回 SUPPLIER_NOT_FOUND",
                    example = "true")
            @RequestParam(required = false) Boolean excludeAddedSupplierOffers,
            @Parameter(description = "分销商永久业务编码。传入后排除该分销商未移除的分销商品；已移除 SKU 会再次出现。"
                    + "不按档案商品类型过滤", example = "BYR_01ABC")
            @RequestParam(required = false) String buyerCode,
            @Parameter(description = "更新时间开始，Asia/Shanghai") @RequestParam(required = false) java.time.LocalDateTime updateTimeStart,
            @Parameter(description = "更新时间结束，Asia/Shanghai") @RequestParam(required = false) java.time.LocalDateTime updateTimeEnd,
            @Parameter(description = "页码，从1开始", example = "1") @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数，默认20", example = "20") @RequestParam(required = false) Integer pageSize) {
        return success(queryService.managementPage(new ProductCatalogQueryService.ProductManagementPageQuery(
                tabCode, keyword, productType, categoryId, brandId, status, unshelveMode, fulfillmentType,
                marketPriceMin, marketPriceMax, supplyPriceMin, supplyPriceMax, supplierCode,
                updateTimeStart, updateTimeEnd, pageNo, pageSize, buyerCode, excludeAddedSupplierOffers)));
    }

    @PutMapping("/batch-status")
    @PreAuthorize(PRODUCT_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "批量变更平台商品状态（批量上下架）",
            description = "支持多选 SPU 或多选 SKU 进行批量上架或下架操作；下架时统一记录下架方式。"
                    + "入参整体校验：未选择商品、编码为空白、或 SPU 与 SKU 项数之和超过 200 时整体拒绝，不产生任何写入。"
                    + "缺省或false按逐实体独立事务处理；syncSkuOnShelf=true仅允许SPU同步上架，后台展开全部未归档SKU，展开总量不超过200。"
                    + "同步模式一个SPU及全部未归档SKU原子提交，组间独立；任何成员变化或校验失败整组回滚，回执按SPU组计数并提供groups完整范围。"
                    + "响应携带成功列表与失败明细，旧逐实体模式的成功项数与失败项数之和恒等于入参编码总数，前端据此渲染「成功 N 项、失败 M 项」。")
    public CommonResult<BatchStatusResultVO> batchChangeStatus(@Valid @RequestBody ProductBatchStatusReqVO request) {
        ProductCatalogCommandService.BatchStatusResult result = commandService.batchChangeStatus(
                new ProductCatalogCommandService.BatchStatusCommand(
                        request.getSpuCodes(), request.getSkuCodes(), request.getTargetStatus(),
                        request.getUnshelveMode(), getLoginUserId(), request.getSyncSkuOnShelf()));
        return success(toBatchStatusResultVO(result));
    }

    /** 领域结果转对外 VO，保持 admin 包内响应 VO 一律由 {@code @Data} 无参构造 + setter 组装的惯例。 */
    private static BatchStatusResultVO toBatchStatusResultVO(ProductCatalogCommandService.BatchStatusResult result) {
        BatchStatusResultVO vo = new BatchStatusResultVO();
        vo.setSyncSkuOnShelf(result.syncSkuOnShelf());
        vo.setGroups(result.groups().stream().map(group -> {
            BatchStatusGroupVO item = new BatchStatusGroupVO();
            item.setSpuCode(group.spuCode());
            item.setSkuCodes(group.skuCodes());
            item.setSucceeded(group.succeeded());
            item.setFailedEntityType(group.failedEntityType() == null ? null : group.failedEntityType().getCode());
            item.setFailedEntityCode(group.failedEntityCode());
            return item;
        }).toList());
        vo.setSucceeded(result.succeeded());
        vo.setFailed(result.failed().stream()
                .map(ProductAdminController::toBatchStatusFailedItemVO)
                .toList());
        return vo;
    }

    /** {@code entityType} 取枚举 {@code code} 而非 {@code name()}，避免枚举常量重命名静默改变前端契约。 */
    private static BatchStatusFailedItemVO toBatchStatusFailedItemVO(
            ProductCatalogCommandService.BatchStatusFailedItem item) {
        BatchStatusFailedItemVO vo = new BatchStatusFailedItemVO();
        vo.setEntityType(item.entityType().getCode());
        vo.setCode(item.code());
        vo.setErrorCode(item.errorCode());
        vo.setErrorMessage(item.errorMessage());
        vo.setFailedEntityType(item.failedEntityType() == null ? null : item.failedEntityType().getCode());
        vo.setFailedEntityCode(item.failedEntityCode());
        return vo;
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

    @GetMapping("/skus/{skuCode}/fulfillment-scores")
    @PreAuthorize(PRODUCT_QUERY_EXPRESSION)
    @Operation(summary = "查询SKU当前合作供应商履约能力评分",
            description = "弹窗读取未退役 Offer 上的稳定性/时效性评分。无合作供应商时 items 为空，不 404。"
                    + "management-page 的 suppliers[] 不加分数。")
    public CommonResult<SkuFulfillmentScoreView> getSkuFulfillmentScores(
            @Parameter(description = "平台SKU永久业务编码") @PathVariable String skuCode) {
        return success(offerFulfillmentScoreService.getSkuScores(skuCode));
    }

    @PutMapping("/skus/{skuCode}/fulfillment-scores")
    @PreAuthorize(PRODUCT_UPDATE_EXPRESSION)
    @Operation(summary = "保存SKU当前合作供应商履约能力评分",
            description = "只更新提交行；未出现的当前合作供应商保持原值。"
                    + "行内可只改一列分数，省略的列保持原值。"
                    + "supplierCode 对该 SKU 无未退役 Offer 则整单拒绝。")
    public CommonResult<ScoreUpdateResult> saveSkuFulfillmentScores(
            @Parameter(description = "平台SKU永久业务编码") @PathVariable String skuCode,
            @Valid @RequestBody SkuFulfillmentScoreSaveReqVO request) {
        List<SkuSupplierScoreLine> lines = request.getItems() == null ? List.of()
                : request.getItems().stream()
                .map(item -> new SkuSupplierScoreLine(
                        item.getSupplierCode(), item.getStabilityScore(), item.getTimelinessScore()))
                .toList();
        int updated = offerFulfillmentScoreService.saveSkuScores(skuCode, lines, getLoginUserId());
        return success(new ScoreUpdateResult(updated));
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
        @Schema(description = "SKU 状态；新增缺省继承 SPU，编辑已有 SKU 缺省保留独立状态；字典值以字符串返回", type = "string", example = "10")
        private Short status;
    }

    @Data
    @Schema(description = "SKU销售建议价生成规则")
    public static class SuggestedSalePriceRuleReqVO {
        @NotBlank
        @Schema(description = "建议价模式（字典supply_chain_suggested_sale_price_mode）：EQUAL_MARKET_PRICE等于市场价、CUSTOM自定义设置、FIXED固定值",
                example = "CUSTOM")
        private String mode;
        @Schema(description = "CUSTOM基数（新选项字典supply_chain_suggested_sale_price_base）：MARKET_PRICE市场价；SUGGESTED_SALE_PRICE修改前销售建议价仅保留历史兼容",
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
        @Schema(description = "是否同步上架 SPU 与全部未归档 SKU；缺省false保持逐实体模式；true仅允许目标ACTIVE、非空SPU、空skuCodes")
        private Boolean syncSkuOnShelf;
        /**
         * 两个编码列表各自可选（可只选 SPU 或只选 SKU），故不加 {@code @NotEmpty}；
         * 「至少选择一项」与「两表项数之和 ≤ 200」由服务层统一判定并抛
         * {@code PRODUCT_BATCH_INPUT_INVALID}，因为跨字段约束无法由单字段注解表达。
         * 此处的 {@code @Size} 与 {@code @NotBlank} 只是入参层粗筛，与同类
         * {@code SkuTaxMetadataBatchReqVO} 的写法一致。
         */
        @Size(max = 200)
        @Schema(description = "选中的平台 SPU 编码列表，单项 ≤200 且不得为空白；与 skuCodes 之和 ≤200")
        private List<@NotBlank String> spuCodes;
        @Size(max = 200)
        @Schema(description = "选中的平台 SKU 编码列表，单项 ≤200 且不得为空白；与 spuCodes 之和 ≤200")
        private List<@NotBlank String> skuCodes;
        @NotNull(message = "目标状态不能为空")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        @Schema(description = "目标状态：10上架，20下架；字典值以字符串传递", type = "string",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "10")
        private Short targetStatus;
        @Schema(description = "下架方式：MANUAL_UNSHELVE手动下架，SYSTEM_UNSHELVE系统下架；下架时可选，缺省为 MANUAL_UNSHELVE", example = "MANUAL_UNSHELVE")
        private String unshelveMode;
    }

    @Data
    @Schema(description = "商品批量上下架结果")
    public static class BatchStatusResultVO {
        @Schema(description = "是否为同步上架模式；true时成功与失败按SPU组计数")
        private Boolean syncSkuOnShelf;
        @Schema(description = "同步模式完整处理范围与整组结果；旧逐实体模式为空数组")
        private List<BatchStatusGroupVO> groups;
        @Schema(description = "成功处理项业务编码；同步模式为SPU组编码，旧模式为SPU与SKU混合")
        private List<String> succeeded;

        @Schema(description = "失败明细列表")
        private List<BatchStatusFailedItemVO> failed;
    }

    /**
     * 同步回执必须提供后台解析的完整成员范围，不能用列表页可见规格替代。
     * 失败组允许空范围，成功组至少包含一个未归档 SKU。
     * 设计见 {@code docs/superpowers/specs/2026-10-11-platform-product-batch-shelf-sync-design.md#5-结果与页面交互}。
     */
    @Data
    @Schema(description = "商品同步上架分组结果")
    public static class BatchStatusGroupVO {
        @Schema(description = "平台SPU永久业务编码") private String spuCode;
        @Schema(description = "本次完整未归档SKU业务编码范围") private List<String> skuCodes;
        @Schema(description = "整组是否提交成功；false时整组回滚") private Boolean succeeded;
        @Schema(description = "失败原因实体类型：SPU或SKU；成功时为空") private String failedEntityType;
        @Schema(description = "失败原因实体编码；SKU须属于本组范围，SPU须等于spuCode") private String failedEntityCode;
    }

    @Data
    @Schema(description = "商品批量上下架失败明细项")
    public static class BatchStatusFailedItemVO {
        @Schema(description = "同步模式失败原因实体类型：SPU或SKU；旧模式为空")
        private String failedEntityType;
        @Schema(description = "同步模式失败原因实体编码；code仍为失败SPU组编码")
        private String failedEntityCode;
        @Schema(description = "实体类型：SPU 或 SKU，取值来自 CatalogEntityType", example = "SPU")
        private String entityType;
        @Schema(description = "失败项业务编码", example = "SPU_01ABC")
        private String code;
        @Schema(description = "稳定错误码，与 SupplyChainErrorCodeConstants 对齐", example = "1050001079")
        private Integer errorCode;
        @Schema(description = "错误消息", example = "平台 SPU 不存在")
        private String errorMessage;
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

    @Data
    @Schema(description = "SKU履约能力评分保存")
    public static class SkuFulfillmentScoreSaveReqVO {
        @Valid
        @Schema(description = "当前合作供应商评分行", example = "[{\"supplierCode\":\"SPL_A\",\"stabilityScore\":80,\"timelinessScore\":70}]")
        private List<SkuFulfillmentScoreItemReqVO> items;
    }

    @Data
    @Schema(description = "SKU履约能力评分行")
    public static class SkuFulfillmentScoreItemReqVO {
        @Schema(description = "供应商永久业务编码", example = "SPL_A")
        private String supplierCode;
        @Min(OfferFulfillmentScore.MIN)
        @Max(OfferFulfillmentScore.MAX)
        @Schema(description = OfferFulfillmentScore.STABILITY_COMMENT, example = "80")
        private Integer stabilityScore;
        @Min(OfferFulfillmentScore.MIN)
        @Max(OfferFulfillmentScore.MAX)
        @Schema(description = OfferFulfillmentScore.TIMELINESS_COMMENT, example = "70")
        private Integer timelinessScore;
    }
}
