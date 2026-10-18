package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminCommandService.CreateProjectCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminCommandService.UpdateProjectCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminQueryService;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminQueryService.ProjectDetail;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminQueryService.ProjectPageQuery;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingCommandService.L3PricingPreview;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingCommandService.ListingLine;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingQueryService.ProjectListingListItem;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.FormulaRuleReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.HideRuleReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.ServiceFeeRuleReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.TaxMetadataReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingRequestMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.PricingPreview;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.ProjectOverrides;
import com.aifuli.module.supplychain.interfaceapi.admin.BuyerAdminController.BuyerProductCreatePricingReqVO;
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import com.aifuli.module.supplychain.tradecore.profile.domain.L3AdjustMode;
import com.aifuli.module.supplychain.tradecore.profile.domain.ProjectType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService;
import com.aifuli.module.supplychain.infrastructure.identity.SystemAssignedBusinessCodeGuard;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PROJECT_CREATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PROJECT_DELETE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PROJECT_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PROJECT_UPDATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PROJECT_UPDATE_STATUS_EXPRESSION;

/**
 * 项目管理端 REST。列表多值筛选、simple 投影、PATCH 状态；不提供 buyers 子资源、不开票、不返回密钥。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#7-项目}。
 */
@Tag(name = "管理后台 - 供应链项目")
@RestController
@RequestMapping("/supply-chain/projects")
@Validated
@RequiredArgsConstructor
public class ProjectAdminController {

    private static final String VIEW_SIMPLE = "simple";

    /** 项目商品分页的全字段文档示例；设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。 */
    private static final String PROJECT_PRODUCTS_OVERRIDE_EXAMPLE = """
            {
              "code": 0,
              "msg": "",
              "data": {
                "total": 1,
                "list": [{
                  "listingCode": "PLT_01M1ZEGA2965H3D93AV7HKNHEN",
                  "platformSkuCode": "SKU_01M1ZEGA2965H3D93AV7HKNHEN",
                  "skuName": "月卡",
                  "spuCode": "SPU_01M1ZEGA2965H3D93AV7HKNHEN",
                  "spuName": "会员卡",
                  "productType": "EQUITY_PRODUCT",
                  "productTypeName": "虚拟权益商品",
                  "productClassification": "VIRTUAL_CARD",
                  "categoryName": "会员权益",
                  "marketPriceCents": 49000,
                  "suggestPriceCents": 10000,
                  "suggestedSalePriceCents": 12000,
                  "platformBasePriceCents": 9000,
                  "distPriceCents": 9500,
                  "projectPriceCents": 9800,
                  "finalPriceCents": 9800,
                  "priceSource": "L3",
                  "priceSourceName": "福利项目",
                  "projectPriceCovered": true,
                  "targetSchemaCode": "VOUCHER_DELIVERY_TARGET",
                  "targetSchemaVersion": 1,
                  "projectPricingConfig": {
                    "distributionInherited": false,
                    "distributionRule": {
                      "mode": "CUSTOM",
                      "base": "MARKET_PRICE",
                      "percentage": 85,
                      "operator": "SUBTRACT",
                      "operand": 1.50
                    },
                    "salesInherited": false,
                    "salesRule": {
                      "mode": "CUSTOM",
                      "base": "SUGGESTED_SALE_PRICE",
                      "percentage": 100,
                      "operator": "ADD",
                      "operand": 0
                    },
                    "serviceFeeInherited": false,
                    "serviceFeeRule": {
                      "percentage": 2,
                      "operator": "ADD",
                      "operand": 1.00,
                      "hideCopy": false,
                      "copy": "服务费"
                    },
                    "hideInherited": false,
                    "hideRule": {
                      "distributionThresholdPercent": 12.50,
                      "salesThresholdPercent": 10
                    },
                    "taxInherited": false,
                    "taxMetadata": {
                      "taxIncluded": true,
                      "taxRateCode": "SIX_PERCENT",
                      "taxRateLabel": "6%"
                    }
                  }
                }]
              }
            }
            """;

    private final ProjectAdminCommandService commandService;
    private final ProjectAdminQueryService queryService;
    private final ProjectListingCommandService listingCommandService;
    private final ProjectListingQueryService listingQueryService;
    private final GenericBatchPricingService genericPricingService;
    private final com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogAdminCoordinator catalogSaveCoordinator;

    @GetMapping
    @PreAuthorize(PROJECT_QUERY_EXPRESSION)
    @Operation(summary = "分页查询项目",
            description = "筛选用 projectName/projectCode/status[]/buyerCode/projectType[]/periodFrom/periodTo。"
                    + "分页行含只读 skuCount/projectPriceSkuCount；view=simple 返回 {projectCode,projectName}[]。"
                    + "不要走 GET /buyers/{code}/projects。")
    public CommonResult<?> page(
            @Parameter(description = "采购项目名称，模糊") @RequestParam(required = false) String projectName,
            @Parameter(description = "采购项目全局永久编码") @RequestParam(required = false) String projectCode,
            @Parameter(description = "项目状态枚举名，可多选") @RequestParam(required = false) List<String> status,
            @Parameter(description = "采购商永久业务编码") @RequestParam(required = false) String buyerCode,
            @Parameter(description = "项目类型封闭枚举，可多选") @RequestParam(required = false) List<String> projectType,
            @Parameter(description = "项目开始日起") @RequestParam(required = false) LocalDate periodFrom,
            @Parameter(description = "项目结束日止") @RequestParam(required = false) LocalDate periodTo,
            @Parameter(description = "投影：simple 为名称下拉") @RequestParam(required = false) String view,
            @Parameter(description = "页码，从 1 开始") @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数") @RequestParam(required = false) Integer pageSize) {
        if (VIEW_SIMPLE.equals(view)) {
            return success(queryService.listSimple(projectName, projectCode));
        }
        List<BuyerProjectStatus> statuses = status == null ? List.of()
                : status.stream().map(ProjectAdminController::parseProjectStatus).toList();
        return success(queryService.page(new ProjectPageQuery(
                projectName, projectCode, statuses, buyerCode, parseProjectTypes(projectType),
                periodFrom, periodTo, pageNo, pageSize)));
    }

    @GetMapping("/{projectCode}")
    @PreAuthorize(PROJECT_QUERY_EXPRESSION)
    @Operation(summary = "项目详情",
            description = "身份用 projectCode。含内部主键、只读 skuCount/projectPriceSkuCount；不返回 appId/appKey。")
    public CommonResult<ProjectDetail> get(@PathVariable String projectCode) {
        return success(queryService.get(projectCode));
    }

    @PostMapping
    @PreAuthorize(PROJECT_CREATE_EXPRESSION)
    @Operation(summary = "新增项目",
            description = "系统生成 PRJ_ 编码。catalogMode 恒 MANAGED。创建允许 DRAFT 或 ACTIVE。")
    public CommonResult<String> create(@Valid @RequestBody ProjectSaveReqVO request) {
        SystemAssignedBusinessCodeGuard.rejectSubmitted(ScopedBusinessCodeService.Kind.PRJ,
                request.isProjectCodeSubmitted());
        return success(commandService.create(toCreateCommand(request)));
    }

    @PutMapping("/{projectCode}")
    @PreAuthorize(PROJECT_UPDATE_EXPRESSION)
    @Operation(summary = "整档替换项目档案",
            description = "身份用 projectCode。可选传 status 变更状态；未传保持原状态。非草稿改 buyerCode 拒绝。")
    public CommonResult<Boolean> update(@PathVariable String projectCode,
                                        @Valid @RequestBody ProjectSaveReqVO request) {
        commandService.update(toUpdateCommand(projectCode, request));
        return success(true);
    }

    @PatchMapping("/{projectCode}")
    @PreAuthorize(PROJECT_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "部分更新项目",
            description = "目前仅支持 status：ACTIVE / SUSPENDED / CLOSED。身份用 projectCode。")
    public CommonResult<Boolean> patch(@PathVariable String projectCode,
                                       @Valid @RequestBody PatchProjectReqVO request) {
        if (request.getStatus() != null) {
            commandService.changeStatus(projectCode, parseProjectStatus(request.getStatus()), getLoginUserId());
        }
        return success(true);
    }

    @GetMapping("/{projectCode}/products")
    @PreAuthorize(PROJECT_QUERY_EXPRESSION)
    @Operation(summary = "分页查询项目商品",
            description = "只返回未移除行。SPU、类型与分类取平台商品主数据，marketPriceCents 取 SKU 市场划线价；"
                    + "projectPricingConfig 为当前项目综合定价版本保存的五组覆盖配置；"
                    + "priceSourceName 在项目专属分销价时为项目名称，否则为项目关联分销商名称；"
                    + "finalPriceCents 为旧冻结版本单价，distPriceCents 为当前有效 L2。金额单位 CNY 分。",
            responses = @ApiResponse(responseCode = "200", description = "项目商品分页响应",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ProjectProductsPageResponse.class,
                                    example = PROJECT_PRODUCTS_OVERRIDE_EXAMPLE))))
    public CommonResult<PageResult<ProjectListingListItem>> pageProducts(
            @Parameter(description = "采购项目全局永久编码") @PathVariable String projectCode,
            @Parameter(description = "页码，从 1 开始") @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数") @RequestParam(required = false) Integer pageSize,
            @Parameter(description = "商品类型编码，可重复传参或逗号拼接；省略时不筛选")
            @RequestParam(required = false) List<String> productType) {
        return success(listingQueryService.page(projectCode, productType, pageNo, pageSize));
    }

    /**
     * 项目商品分页的 OpenAPI 专用响应模型，仅用于展开通用返回与分页泛型中的实际字段。
     * 运行时仍由 {@link #pageProducts} 返回原有 CommonResult/PageResult JSON。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。
     */
    @Schema(name = "ProjectProductsPageResponse", description = "项目商品分页响应")
    public record ProjectProductsPageResponse(
            @Schema(description = "业务结果码，0表示成功", example = "0") Integer code,
            @Schema(description = "业务结果提示，成功时为空字符串") String msg,
            @Schema(description = "当前项目的未移除商品分页数据") ProjectProductsPageData data) {
    }

    /** 项目商品分页数据；设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#72-项目商品}。 */
    @Schema(name = "ProjectProductsPageData", description = "项目商品分页数据")
    public record ProjectProductsPageData(
            @Schema(description = "当前项目未移除商品总数", example = "1") Long total,
            @Schema(description = "当前页项目商品，按项目商品更新时间倒序") List<ProjectListingListItem> list) {
    }

    @PostMapping("/{projectCode}/products")
    @PreAuthorize(PROJECT_UPDATE_EXPRESSION)
    @Operation(summary = "从分销商品发布到项目目录",
            description = "SKU 必须已是所属 Buyer 的未移除分销商品。默认 l3AdjustMode=FOLLOW_L2。"
                    + "行内 pricing 可在同一事务保存项目综合定价；省略则全继承。编号 PLT_。金额单位 CNY 分。")
    public CommonResult<com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogSaveRespVO> addProducts(@PathVariable String projectCode,
                                                    @Valid @RequestBody AddProjectProductsReqVO request) {
        List<ListingLine> lines = request.getItems().stream()
                .map(ProjectAdminController::toListingLine)
                .toList();
        return success(catalogSaveCoordinator.saveProjectProducts(projectCode, lines, getLoginUserId()));
    }

    @PatchMapping("/{projectCode}/products/{listingCode}")
    @PreAuthorize(PROJECT_UPDATE_EXPRESSION)
    @Operation(summary = "改项目商品L3覆盖",
            description = "FOLLOW_L2 不写 L3；EQUAL_L1/CUSTOM 发新版本。不回放已发布版本。金额单位 CNY 分。")
    public CommonResult<Boolean> patchProduct(@PathVariable String projectCode,
                                              @PathVariable String listingCode,
                                              @Valid @RequestBody PatchProjectListingReqVO request) {
        listingCommandService.patchL3(
                projectCode, listingCode,
                ClosedEnums.parseRequired(L3AdjustMode.class, request.getL3AdjustMode()),
                request.getPercent(), request.getAmountCents(), getLoginUserId());
        return success(true);
    }

    @PostMapping("/{projectCode}/products/pricing/preview")
    @PreAuthorize(PROJECT_QUERY_EXPRESSION)
    @Operation(summary = "批量试算项目综合定价",
            description = "只读试算当前项目作用域内继承或覆盖后的综合定价；同一分销商其它项目不受影响，税务仅校验展示属性。")
    public CommonResult<List<PricingPreview>> previewProductPricing(
            @Parameter(description = "采购项目全局永久编码") @PathVariable String projectCode,
            @Valid @RequestBody ProjectProductBatchPricingReqVO request) {
        return success(genericPricingService.previewProject(projectCode, request.getListingCodes(),
                GenericPricingRequestMapper.toProjectOverrides(request)));
    }

    @PutMapping("/{projectCode}/products/pricing")
    @PreAuthorize(PROJECT_UPDATE_EXPRESSION)
    @Operation(summary = "批量保存项目综合定价",
            description = "仅发布路径项目下选中商品的综合定价版本；单事务全量成功或回滚，税务两字段必须整体继承或整体覆盖。"
                    + "保存确认提交后刷新受影响自动目录并返回逐目录结果。")
    public CommonResult<com.aifuli.module.supplychain.tradecore.catalog.application.BuyerSelectionCatalogSaveRespVO> updateProductPricing(
            @Parameter(description = "采购项目全局永久编码") @PathVariable String projectCode,
            @Valid @RequestBody ProjectProductBatchPricingReqVO request) {
        return success(catalogSaveCoordinator.saveProjectPricing(projectCode, request.getListingCodes(),
                GenericPricingRequestMapper.toProjectOverrides(request), getLoginUserId()));
    }

    @DeleteMapping("/{projectCode}/products/{listingCode}")
    @PreAuthorize(PROJECT_DELETE_EXPRESSION)
    @Operation(summary = "逻辑移除项目商品",
            description = "status=90。历史冻结版本保留。身份用 projectCode 与 listingCode。")
    public CommonResult<Boolean> retireProduct(@PathVariable String projectCode,
                                               @PathVariable String listingCode) {
        listingCommandService.retire(projectCode, listingCode, getLoginUserId());
        return success(true);
    }

    private CreateProjectCommand toCreateCommand(ProjectSaveReqVO request) {
        return new CreateProjectCommand(
                request.getProjectName(), request.getBuyerCode(), request.getProjectType(),
                request.getStartDate(), request.getEndDate(), request.getSettlementPartyType(),
                request.getDescription(), request.getStatus(), getLoginUserId(), request.getSettlementBuyerCode());
    }

    private UpdateProjectCommand toUpdateCommand(String projectCode, ProjectSaveReqVO request) {
        return new UpdateProjectCommand(
                projectCode, request.getProjectName(), request.getBuyerCode(), request.getProjectType(),
                request.getStartDate(), request.getEndDate(), request.getSettlementPartyType(),
                request.getDescription(), request.getStatus(), getLoginUserId(), request.getSettlementBuyerCode());
    }

    /**
     * 项目状态不是档案封闭枚举，未知值包装为 ENUM_NOT_OPEN。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#42-其它封闭枚举}。
     */
    private static BuyerProjectStatus parseProjectStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw exception(ENUM_NOT_OPEN);
        }
        try {
            return BuyerProjectStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            ServiceException invalid = exception(ENUM_NOT_OPEN);
            invalid.initCause(ex);
            throw invalid;
        }
    }

    private static List<String> parseProjectTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        for (String value : raw) {
            ProjectType type = ClosedEnums.parse(ProjectType.class, value);
            if (type != null) {
                types.add(type.name());
            }
        }
        return types;
    }

    private static ListingLine toListingLine(ProjectProductItemReqVO item) {
        L3AdjustMode mode = ClosedEnums.parse(L3AdjustMode.class, item.getL3AdjustMode());
        return new ListingLine(
                item.getPlatformSkuCode(),
                mode == null ? L3AdjustMode.FOLLOW_L2 : mode,
                item.getPercent(),
                item.getAmountCents(),
                item.getPricing() == null ? null : GenericPricingRequestMapper.toBuyerOverrides(item.getPricing()));
    }

    @Data
    @Schema(description = "项目档案保存")
    public static class ProjectSaveReqVO {
        @JsonIgnore
        @Schema(hidden = true)
        private boolean projectCodeSubmitted;

        /** 只记录请求是否出现该字段；创建入口不接受任何手填值，包括 null 和空串。 */
        @JsonSetter("projectCode")
        @Schema(hidden = true)
        public void acceptProjectCode(String ignored) {
            projectCodeSubmitted = true;
        }

        @NotBlank
        @Size(max = 128)
        @Schema(description = "采购项目名称")
        private String projectName;
        @NotBlank
        @Schema(description = "采购商永久业务编码")
        private String buyerCode;
        @Schema(description = "项目类型封闭枚举")
        private String projectType;
        @Schema(description = "项目开始日")
        private LocalDate startDate;
        @Schema(description = "项目结束日")
        private LocalDate endDate;
        @Schema(description = "结算方，本期仅DISTRIBUTOR")
        private String settlementPartyType;
        /** 采购商永久业务编码；选择已启用结算方，启动后不可修改。 */
        @Size(max = 32)
        @Schema(description = "采购商永久业务编码（结算方）；创建省略默认关联分销商，更新省略保持原值")
        private String settlementBuyerCode;
        @Size(max = 500)
        @Schema(description = "项目说明")
        private String description;
        @Schema(description = "创建时允许 DRAFT 或 ACTIVE；整档更新时可选传入目标状态，空表示保持原状态")
        private String status;
    }

    @Data
    @Schema(description = "项目部分更新")
    public static class PatchProjectReqVO {
        @Schema(description = "项目状态：ACTIVE / SUSPENDED / CLOSED")
        private String status;
    }

    @Data
    @Schema(description = "从分销商品批量发布到项目")
    public static class AddProjectProductsReqVO {
        @NotEmpty
        @Schema(description = "待发布标品行")
        private List<@Valid ProjectProductItemReqVO> items;
    }

    @Data
    @Schema(description = "项目商品发布行")
    public static class ProjectProductItemReqVO {
        @NotBlank
        @Schema(description = "平台SKU编码")
        private String platformSkuCode;
        @Schema(description = "L3调价方式：FOLLOW_L2 / EQUAL_L1 / CUSTOM，默认 FOLLOW_L2")
        private String l3AdjustMode;
        @Schema(description = "CUSTOM时的整数百分比，100等于L1")
        private Integer percent;
        @Schema(description = "CUSTOM时加减的CNY分")
        private Long amountCents;
        @Valid
        @Schema(description = "可选的项目综合定价覆盖；与新增项目商品在同一事务保存。字段与分销商品行内定价相同")
        private BuyerProductCreatePricingReqVO pricing;
    }

    @Data
    @Schema(description = "改项目商品L3覆盖")
    public static class PatchProjectListingReqVO {
        @NotBlank
        @Schema(description = "L3调价方式：FOLLOW_L2 / EQUAL_L1 / CUSTOM")
        private String l3AdjustMode;
        @Schema(description = "CUSTOM时的整数百分比，100等于L1")
        private Integer percent;
        @Schema(description = "CUSTOM时加减的CNY分")
        private Long amountCents;
    }

    @Data
    @Schema(description = "项目SKU通用批量定价请求；五组配置可分别继承。"
            + "五个继承标记字段均为必填，缺字段或缺组合规则返回 400 而非 500；契约见 "
            + "docs/superpowers/specs/2026-09-15-generic-batch-pricing-design.md#61-项目批量定价请求契约约束2026-09-24-增补")
    public static class ProjectProductBatchPricingReqVO {
        @NotEmpty
        @Size(max = GenericBatchPricingService.MAX_BATCH)
        @Schema(description = "当前项目下的项目商品编码列表", requiredMode = Schema.RequiredMode.REQUIRED)
        private List<@NotBlank String> listingCodes;
        @NotNull
        @Schema(description = "是否继承分销商分销价：true 继承上游、false 表示覆盖并须提交 distributionRule。必填，缺字段返回 400",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean distributionInherited;
        @Valid
        @Schema(description = "分销价覆盖规则；distributionInherited=false 时必填且须完整")
        private FormulaRuleReqVO distributionRule;
        @NotNull
        @Schema(description = "是否继承分销商销售价：true 继承上游、false 表示覆盖并须提交 salesRule。必填，缺字段返回 400",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean salesInherited;
        @Valid
        @Schema(description = "销售价覆盖规则；salesInherited=false 时必填且须完整")
        private FormulaRuleReqVO salesRule;
        @NotNull
        @Schema(description = "是否继承分销商服务费：true 继承上游、false 表示覆盖并须提交 serviceFeeRule。必填，缺字段返回 400",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean serviceFeeInherited;
        @Valid
        @Schema(description = "服务费覆盖规则；serviceFeeInherited=false 时必填且须完整")
        private ServiceFeeRuleReqVO serviceFeeRule;
        @NotNull
        @Schema(description = "是否继承分销商利润隐藏阈值：true 继承上游、false 表示覆盖并须提交 hideRule。必填，缺字段返回 400",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean hideInherited;
        @Valid
        @Schema(description = "利润隐藏阈值覆盖；hideInherited=false 时必填且须完整")
        private HideRuleReqVO hideRule;
        @NotNull
        @Schema(description = "是否继承分销商税务：true 继承上游、false 表示覆盖并须在 taxMetadata 中同时提交 taxIncluded 与 taxRateCode，"
                + "税务组不完整则在边界自动降级为继承。必填，缺字段返回 400",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean taxInherited;
        @Valid
        @Schema(description = "税务展示属性；taxInherited=false 时须同时提交 taxIncluded 与 taxRateCode，任一缺失整体降级为继承")
        private TaxMetadataReqVO taxMetadata;

        /** 组合约束：xxxInherited=false 时对应覆盖配置必须存在，否则在参数绑定阶段返回 400。 */
        @AssertTrue(message = "distributionInherited=false 时必须提交 distributionRule")
        public boolean isDistributionRuleProvided() {
            return !Boolean.FALSE.equals(distributionInherited) || distributionRule != null;
        }

        @AssertTrue(message = "salesInherited=false 时必须提交 salesRule")
        public boolean isSalesRuleProvided() {
            return !Boolean.FALSE.equals(salesInherited) || salesRule != null;
        }

        @AssertTrue(message = "serviceFeeInherited=false 时必须提交 serviceFeeRule")
        public boolean isServiceFeeRuleProvided() {
            return !Boolean.FALSE.equals(serviceFeeInherited) || serviceFeeRule != null;
        }

        @AssertTrue(message = "hideInherited=false 时必须提交 hideRule")
        public boolean isHideRuleProvided() {
            return !Boolean.FALSE.equals(hideInherited) || hideRule != null;
        }
    }
}
