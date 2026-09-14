package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.util.monitor.TracerUtils;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerListingPublicationService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductCatalogCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.domain.BuyerListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BRAND_LEGACY_API_DEPRECATED;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ADMIN_EXPRESSION;

@Tag(name = "管理后台 - 供应链 M1 主数据")
@RestController
@RequestMapping("/supply-chain/master-data")
@Validated
@RequiredArgsConstructor
@PreAuthorize(ADMIN_EXPRESSION)
@Slf4j
public class M1MasterDataAdminController {

    private final BuyerCommandService buyerCommandService;
    private final ProductCatalogCommandService catalogCommandService;
    private final BuyerListingPublicationService listingPublicationService;

    @PostMapping("/buyers")
    @Operation(summary = "创建采购商及首个租户绑定")
    public CommonResult<String> createBuyer(@Valid @RequestBody CreateBuyerReqVO request) {
        return success(buyerCommandService.createBuyer(new BuyerCommandService.CreateBuyerCommand(
                request.getBuyerCode(), request.getBuyerName(), request.getShortName(),
                request.getContactName(), request.getContactMobile(), request.getSourceSystem(),
                request.getExternalTenantId(), request.getValidFrom(), getLoginUserId())));
    }

    @PutMapping("/buyers/status")
    @Operation(summary = "变更采购商管理状态")
    public CommonResult<Boolean> changeBuyerStatus(@Valid @RequestBody ChangeBuyerStatusReqVO request) {
        buyerCommandService.changeStatus(request.getBuyerCode(),
                BuyerStatus.valueOf(request.getTargetStatus()), getLoginUserId());
        return success(true);
    }

    /**
     * 已退役的品牌创建入口。保留 HTTP 映射用于向尚未迁移的调用方返回稳定错误，禁止继续委托写服务。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#45-存量主数据接口治理m1masterdataadmincontroller}。
     */
    @PostMapping("/brands")
    @Deprecated
    @Operation(summary = "旧品牌创建接口（已废弃）", deprecated = true,
            description = "接口已终止写入，固定返回 BRAND_LEGACY_API_DEPRECATED；请迁移至 POST /supply-chain/brands。")
    public CommonResult<String> createBrand(@RequestBody(required = false) CreateBrandReqVO request) {
        log.warn("拒绝已废弃的品牌创建接口 operatorId={}", getLoginUserId());
        throw exception(BRAND_LEGACY_API_DEPRECATED);
    }

    @PostMapping("/categories")
    @Deprecated
    @Operation(summary = "创建平台商品类目草稿（兼容接口）", deprecated = true,
            description = "请迁移至 POST /supply-chain/categories；本接口委托同一分类服务。")
    public CommonResult<String> createCategory(@Valid @RequestBody CreateCategoryReqVO request) {
        return success(catalogCommandService.createCategory(new ProductCatalogCommandService.CreateCategoryCommand(
                request.getCategoryCode(), request.getCategoryName(), request.getParentCategoryCode(),
                request.getSort(), getLoginUserId())));
    }

    @PostMapping("/spus")
    @Deprecated
    @Operation(summary = "创建平台标准商品 SPU 草稿（兼容接口）", deprecated = true,
            description = "请迁移至 POST /supply-chain/products/spus。")
    public CommonResult<String> createSpu(@Valid @RequestBody CreateSpuReqVO request) {
        return success(catalogCommandService.createSpu(new ProductCatalogCommandService.CreateSpuCommand(
                request.getSpuCode(), request.getBrandCode(), request.getPrimaryCategoryCode(),
                request.getSellableCategory(), request.getProductType(), request.getProductClassification(),
                request.getSpuName(), request.getSubtitle(), request.getCoverUrl(),
                request.getUseInstruction(), getLoginUserId())));
    }

    @PostMapping("/skus")
    @Deprecated
    @Operation(summary = "创建平台标准商品 SKU 草稿（兼容接口）", deprecated = true,
            description = "请迁移至 POST /supply-chain/products/spus 复合创建；目标与结果 Schema 由服务端按履约类型生成。")
    public CommonResult<String> createSku(@Valid @RequestBody CreateSkuReqVO request) {
        return success(catalogCommandService.createSku(new ProductCatalogCommandService.CreateSkuCommand(
                request.getSkuCode(), request.getSpuCode(), request.getSkuName(), request.getFulfillmentType(),
                request.getFaceValue(), "CNY", getLoginUserId())));
    }

    @PutMapping("/catalog-entities/status")
    @Deprecated
    @Operation(summary = "变更品牌、类目、SPU 或 SKU 的管理状态（兼容接口）", deprecated = true,
            description = "请迁移至各商品中心资源的 /{code}/status 端点。")
    public CommonResult<Boolean> changeCatalogEntityStatus(
            @Valid @RequestBody ChangeCatalogEntityStatusReqVO request) {
        CatalogEntityStatus status = CatalogEntityStatus.valueOf(request.getTargetStatus());
        switch (request.getEntityType()) {
            case "BRAND" -> catalogCommandService.changeBrandStatus(request.getEntityCode(), status, getLoginUserId());
            case "CATEGORY" -> catalogCommandService.changeCategoryStatus(request.getEntityCode(), status, getLoginUserId());
            case "SPU" -> catalogCommandService.changeSpuStatus(request.getEntityCode(), status, getLoginUserId());
            case "SKU" -> catalogCommandService.changeSkuStatus(request.getEntityCode(), status, getLoginUserId());
            default -> throw new IllegalArgumentException("Unsupported catalog entity type: " + request.getEntityType());
        }
        return success(true);
    }

    @PostMapping("/listings")
    @Operation(summary = "创建采购商商品草稿")
    public CommonResult<String> createListing(@Valid @RequestBody CreateListingReqVO request) {
        return success(catalogCommandService.createBuyerListing(
                new ProductCatalogCommandService.CreateBuyerListingCommand(request.getBuyerCode(),
                        request.getPlatformSkuCode(), request.getBuyerSkuCode(), getLoginUserId())));
    }

    @PostMapping("/listings/publish")
    @Operation(summary = "发布采购商商品不可变商业版本")
    public CommonResult<PublishListingRespVO> publishListing(@Valid @RequestBody PublishListingReqVO request) {
        BuyerListingPublicationService.PublishResult result = listingPublicationService.publish(
                new BuyerListingPublicationService.PublishListingCommand(request.getListingNo(),
                        request.getExpectedListingVersion(), request.getBuyerSettlementUnitPrice(),
                        request.getDisplayName(), request.getValidFrom(), request.getValidTo(),
                        request.getPublishReason(), getLoginUserId(), traceId(), null, null));
        return success(new PublishListingRespVO(result.listingNo(), result.listingVersion(),
                result.aggregateVersion(), result.catalogCursor()));
    }

    @PutMapping("/listings/status")
    @Operation(summary = "暂停、恢复或退役采购商商品")
    public CommonResult<Boolean> changeListingStatus(@Valid @RequestBody ChangeListingStatusReqVO request) {
        listingPublicationService.changeStatus(new BuyerListingPublicationService.ChangeListingStatusCommand(
                request.getListingNo(), request.getExpectedListingVersion(),
                BuyerListingStatus.valueOf(request.getTargetStatus()), getLoginUserId(),
                traceId(), null, null));
        return success(true);
    }

    private static String traceId() {
        String traceId = TracerUtils.getTraceId();
        return traceId == null || traceId.isBlank() ? "admin-" + UUID.randomUUID() : traceId;
    }

    @Schema(description = "创建采购商及租户绑定请求")
    @Data
    public static class CreateBuyerReqVO {
        @NotBlank @Size(max = 32) @Schema(description = "采购商永久编码", example = "WUSHENG")
        private String buyerCode;
        @NotBlank @Size(max = 128) @Schema(description = "采购商标准名称", example = "吾盛")
        private String buyerName;
        @NotBlank @Size(max = 64) @Schema(description = "采购商列表简称", example = "吾盛")
        private String shortName;
        @Size(max = 64) @Schema(description = "运营联系人")
        private String contactName;
        @Size(max = 32) @Schema(description = "运营联系人手机号", example = "138****0000")
        private String contactMobile;
        @NotBlank @Size(max = 32) @Schema(description = "调用来源系统", example = "AIFULI_MALL")
        private String sourceSystem;
        @NotBlank @Size(max = 128) @Schema(description = "来源系统租户标识", example = "tenant-wusheng")
        private String externalTenantId;
        @Schema(description = "绑定生效时间；空表示立即生效")
        private LocalDateTime validFrom;
    }

    @Schema(description = "变更采购商状态请求")
    @Data
    public static class ChangeBuyerStatusReqVO {
        @NotBlank @Schema(description = "采购商编码", example = "WUSHENG")
        private String buyerCode;
        @NotBlank @Schema(description = "目标状态：ACTIVE、SUSPENDED、ARCHIVED", example = "SUSPENDED")
        private String targetStatus;
    }

    @Schema(description = "创建品牌请求")
    @Data
    public static class CreateBrandReqVO {
        @NotBlank @Size(max = 32) @Schema(description = "品牌编码", example = "CMCC")
        private String brandCode;
        @NotBlank @Size(max = 128) @Schema(description = "品牌名称", example = "中国移动")
        private String brandName;
        @Size(max = 128) @Schema(description = "品牌英文名称")
        private String englishName;
        @Size(max = 512) @Schema(description = "品牌 Logo 地址")
        private String logoUrl;
        @NotNull @Schema(description = "后台排序", example = "0")
        private Integer sort;
    }

    @Schema(description = "创建商品类目请求")
    @Data
    public static class CreateCategoryReqVO {
        @NotBlank @Size(max = 32) @Schema(description = "类目编码", example = "DIRECT_RECHARGE")
        private String categoryCode;
        @NotBlank @Size(max = 128) @Schema(description = "类目名称", example = "话费直充")
        private String categoryName;
        @Size(max = 32) @Schema(description = "父类目编码；根类目为空")
        private String parentCategoryCode;
        @NotNull @Schema(description = "同级排序", example = "0")
        private Integer sort;
    }

    @Schema(description = "创建平台 SPU 请求")
    @Data
    public static class CreateSpuReqVO {
        @NotBlank @Size(max = 32) @Schema(description = "SPU 编码", example = "TELCO_RECHARGE")
        private String spuCode;
        @NotBlank @Schema(description = "品牌编码", example = "CMCC")
        private String brandCode;
        @Size(max = 32) @Schema(description = "主类目编码，历史字段，不用于供货命中或加购筛选，可不填")
        private String primaryCategoryCode;
        @NotBlank @Schema(description = "可售品类枚举名，供货命中用", example = "MOVIE")
        private String sellableCategory;
        @NotBlank @Schema(description = "动态商品类型编码，供货命中用", example = "MOVIE")
        private String productType;
        @NotBlank @Schema(description = "商品分类枚举名，供货命中用", example = "MOVIE_TICKET")
        private String productClassification;
        @NotBlank @Size(max = 256) @Schema(description = "SPU 标准名称", example = "话费直充")
        private String spuName;
        @Size(max = 512) @Schema(description = "SPU 副标题")
        private String subtitle;
        @Size(max = 512) @Schema(description = "封面地址")
        private String coverUrl;
        @Schema(description = "使用说明")
        private String useInstruction;
    }

    @Schema(description = "创建平台 SKU 请求")
    @Data
    public static class CreateSkuReqVO {
        @NotBlank @Size(max = 32) @Schema(description = "SKU 编码", example = "TELCO_100")
        private String skuCode;
        @NotBlank @Schema(description = "所属 SPU 编码", example = "TELCO_RECHARGE")
        private String spuCode;
        @NotBlank @Size(max = 256) @Schema(description = "SKU 名称", example = "话费直充 100 元")
        private String skuName;
        @NotBlank @Schema(description = "履约类型", example = "DIRECT_RECHARGE")
        private String fulfillmentType;
        @Min(0) @Schema(description = "标准面值，单位 CNY 分", example = "10000")
        private long faceValue;
    }

    @Schema(description = "变更品牌、类目、SPU 或 SKU 状态请求")
    @Data
    public static class ChangeCatalogEntityStatusReqVO {
        @NotBlank @Schema(description = "实体类型：BRAND、CATEGORY、SPU、SKU", example = "SKU")
        private String entityType;
        @NotBlank @Schema(description = "实体业务编码", example = "TELCO_100")
        private String entityCode;
        @NotBlank @Schema(description = "目标状态：ACTIVE、INACTIVE、ARCHIVED", example = "ACTIVE")
        private String targetStatus;
    }

    @Schema(description = "创建采购商商品请求")
    @Data
    public static class CreateListingReqVO {
        @NotBlank @Schema(description = "采购商编码", example = "WUSHENG")
        private String buyerCode;
        @NotBlank @Schema(description = "平台 SKU 编码", example = "TELCO_100")
        private String platformSkuCode;
        @NotBlank @Size(max = 64) @Schema(description = "采购商商品编码", example = "WS-TELCO-100")
        private String buyerSkuCode;
    }

    @Schema(description = "发布采购商商品版本请求")
    @Data
    public static class PublishListingReqVO {
        @NotBlank @Schema(description = "采购商商品业务号")
        private String listingNo;
        @Min(0) @Schema(description = "预期聚合版本", example = "0")
        private long expectedListingVersion;
        @Min(0) @Schema(description = "采购商结算单价，单位 CNY 分", example = "9800")
        private long buyerSettlementUnitPrice;
        @NotBlank @Size(max = 256) @Schema(description = "商城展示名称", example = "话费直充 100 元")
        private String displayName;
        @NotNull @Schema(description = "版本生效时间")
        private LocalDateTime validFrom;
        @Schema(description = "版本失效时间；空表示长期有效")
        private LocalDateTime validTo;
        @NotBlank @Size(max = 512) @Schema(description = "发布原因", example = "M1 联调商品首发")
        private String publishReason;
    }

    @Schema(description = "变更采购商商品状态请求")
    @Data
    public static class ChangeListingStatusReqVO {
        @NotBlank @Schema(description = "采购商商品业务号")
        private String listingNo;
        @Min(0) @Schema(description = "预期聚合版本")
        private long expectedListingVersion;
        @NotBlank @Schema(description = "目标状态：PUBLISHED、SUSPENDED、RETIRED", example = "SUSPENDED")
        private String targetStatus;
    }

    @Schema(description = "采购商商品发布结果")
    public record PublishListingRespVO(
            @Schema(description = "采购商商品业务号") String listingNo,
            @Schema(description = "新发布商业版本") int listingVersion,
            @Schema(description = "采购商商品聚合版本") long aggregateVersion,
            @Schema(description = "商品增量游标") long catalogCursor) {
    }
}
