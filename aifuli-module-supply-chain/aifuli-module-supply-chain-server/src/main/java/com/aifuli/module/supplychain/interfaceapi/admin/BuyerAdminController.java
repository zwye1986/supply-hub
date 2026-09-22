package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.framework.common.util.monitor.TracerUtils;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerInvoiceService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerInvoiceService.BuyerInvoiceView;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerInvoiceService.SaveInvoiceCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService.ContactLine;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService.CreateBuyerCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminCommandService.UpdateBuyerCommand;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminQueryService;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminQueryService.BuyerChildItem;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminQueryService.BuyerDetail;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminQueryService.BuyerListItem;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerAdminQueryService.BuyerPageQuery;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService.L2PricingPreview;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductCommandService.ProductLine;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BuyerProductQueryService.BuyerProductListItem;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.FormulaRuleReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingAdminVos.ServiceFeeRuleReqVO;
import com.aifuli.module.supplychain.interfaceapi.admin.pricing.GenericPricingRequestMapper;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService;
import com.aifuli.module.supplychain.tradecore.catalog.pricing.GenericBatchPricingService.PricingPreview;
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import com.aifuli.module.supplychain.tradecore.profile.domain.L2AdjustMode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_STATUS_CONFLICT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BUYER_CREATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BUYER_DELETE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BUYER_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BUYER_UPDATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.BUYER_UPDATE_STATUS_EXPRESSION;

/**
 * 分销商管理端 REST。列表多值筛选、simple / parent-options、PATCH 状态；期初走采购商账本。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#6-分销商buyer}。
 */
@Tag(name = "管理后台 - 供应链分销商")
@RestController
@RequestMapping("/supply-chain/buyers")
@Validated
@RequiredArgsConstructor
public class BuyerAdminController {

    private static final String VIEW_SIMPLE = "simple";
    private static final String VIEW_PARENT_OPTIONS = "parent-options";

    private final BuyerAdminCommandService commandService;
    private final BuyerAdminQueryService queryService;
    private final BuyerProductCommandService productCommandService;
    private final BuyerProductQueryService productQueryService;
    private final GenericBatchPricingService genericPricingService;
    private final BuyerInvoiceService invoiceService;

    @GetMapping
    @PreAuthorize(BUYER_QUERY_EXPRESSION)
    @Operation(summary = "分页查询分销商",
            description = "默认分页按组织树返回：根列表只有一级，children 嵌套二级和三级。"
                    + "pageNo/pageSize/total 只计一级。筛选用 buyerName/buyerCode/distributionLevels/"
                    + "cooperationModes/sellableCategories/status 与 updatedFrom/To。"
                    + "命中二级或三级时带回一级祖先，children 只保留命中分支。"
                    + "view=simple 返回 {buyerCode,buyerName}[]；"
                    + "view=parent-options 收 excludeBuyerCode、forLevel。金额单位 CNY 分。",
            responses = @ApiResponse(responseCode = "200", description = "默认分销商分页响应",
                    content = @Content(schema = @Schema(implementation = BuyerPageResponse.class))))
    public CommonResult<?> page(
            @Parameter(description = "采购商标准名称，模糊") @RequestParam(required = false) String buyerName,
            @Parameter(description = "采购商永久业务编码") @RequestParam(required = false) String buyerCode,
            @Parameter(description = "组织等级，可多选：1 / 2 / 3")
            @RequestParam(required = false) List<Integer> distributionLevels,
            @Parameter(description = "合作模式枚举名，可多选") @RequestParam(required = false) List<String> cooperationModes,
            @Parameter(description = "可售品类枚举名，可多选") @RequestParam(required = false) List<String> sellableCategories,
            @Parameter(description = "管理状态枚举名，可多选：ACTIVE / SUSPENDED / BLACKLIST")
            @RequestParam(required = false) List<String> status,
            @Parameter(description = "最后修改时间起，Asia/Shanghai") @RequestParam(required = false) LocalDateTime updatedFrom,
            @Parameter(description = "最后修改时间止，Asia/Shanghai") @RequestParam(required = false) LocalDateTime updatedTo,
            @Parameter(description = "投影：simple 为名称下拉，parent-options 为上级下拉")
            @RequestParam(required = false) String view,
            @Parameter(description = "上级下拉排除的分销商编码及其子孙")
            @RequestParam(required = false) String excludeBuyerCode,
            @Parameter(description = "上级下拉目标等级：2 只要一级，3 只要二级")
            @RequestParam(required = false) Integer forLevel,
            @Parameter(description = "页码，从 1 开始") @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数") @RequestParam(required = false) Integer pageSize) {
        if (VIEW_SIMPLE.equals(view)) {
            return success(queryService.listSimple(buyerName, buyerCode));
        }
        if (VIEW_PARENT_OPTIONS.equals(view)) {
            return success(queryService.listParentOptions(excludeBuyerCode, forLevel));
        }
        List<BuyerStatus> statuses = status == null ? List.of()
                : status.stream().map(BuyerAdminController::parseBuyerStatus).toList();
        return success(queryService.page(new BuyerPageQuery(
                buyerName, buyerCode, distributionLevels, cooperationModes, sellableCategories,
                statuses, updatedFrom, updatedTo, pageNo, pageSize)));
    }

    /**
     * 默认分页视图的 OpenAPI 响应模型。运行时仍由 {@link #page} 兼容简易与上级下拉投影，
     * 此模型只负责避免 Springdoc 将默认响应退化为 {@code CommonResultObject}。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#62-列表与档案}。
     */
    @Schema(name = "BuyerPageResponse", description = "分销商默认分页响应")
    public static class BuyerPageResponse extends CommonResult<PageResult<BuyerListItem>> {
    }

    @GetMapping("/{buyerCode}")
    @PreAuthorize(BUYER_QUERY_EXPRESSION)
    @Operation(summary = "分销商详情",
            description = "身份用 buyerCode。档案字段在查询权限内按原值回显，含内部主键和只读 accountBalanceCents；"
                    + "下级不嵌套，日志仍禁止记录完整敏感字段。")
    public CommonResult<BuyerDetail> get(@PathVariable String buyerCode) {
        return success(queryService.get(buyerCode));
    }

    @GetMapping("/{buyerCode}/invoice")
    @PreAuthorize(BUYER_QUERY_EXPRESSION)
    @Operation(summary = "查询分销商开票信息",
            description = "身份用 buyerCode。无开票行时用档案预填返回且不落库；已保存行明文回传。不回写档案。")
    public CommonResult<BuyerInvoiceView> getInvoice(@PathVariable String buyerCode) {
        return success(invoiceService.get(buyerCode));
    }

    @PutMapping("/{buyerCode}/invoice")
    @PreAuthorize(BUYER_UPDATE_EXPRESSION)
    @Operation(summary = "保存分销商开票信息",
            description = "身份用 buyerCode。upsert 开票表，开户账号明文存储，不回写分销商档案。"
                    + "titleType 为 ENTERPRISE / PERSONAL。")
    public CommonResult<BuyerInvoiceView> saveInvoice(@PathVariable String buyerCode,
                                                        @Valid @RequestBody InvoiceSaveReqVO request) {
        return success(invoiceService.save(buyerCode, new SaveInvoiceCommand(
                request.getTitleType(), request.getInvoiceTitle(), request.getSocialCreditCode(),
                request.getPhone(), request.getBankName(), request.getBankAccount(),
                request.getInvoiceAddress()), getLoginUserId()));
    }

    @PostMapping
    @PreAuthorize(BUYER_CREATE_EXPRESSION)
    @Operation(summary = "新增分销商（含期初）",
            description = "系统生成 BYR_ 编码。openingBalanceCents 仅创建可写，空视为 0，单位 CNY 分。")
    public CommonResult<String> create(@Valid @RequestBody BuyerSaveReqVO request) {
        return success(commandService.create(toCreateCommand(request)));
    }

    @PutMapping("/{buyerCode}")
    @PreAuthorize(BUYER_UPDATE_EXPRESSION)
    @Operation(summary = "编辑分销商档案",
            description = "身份用 buyerCode。编辑再带 openingBalanceCents 拒绝。金额单位 CNY 分。")
    public CommonResult<Boolean> update(@PathVariable String buyerCode,
                                          @Valid @RequestBody BuyerSaveReqVO request) {
        commandService.update(toUpdateCommand(buyerCode, request));
        return success(true);
    }

    @PatchMapping("/{buyerCode}")
    @PreAuthorize(BUYER_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "部分更新分销商",
            description = "目前仅支持 status：ACTIVE / SUSPENDED / BLACKLIST。身份用 buyerCode。")
    public CommonResult<Boolean> patch(@PathVariable String buyerCode,
                                       @Valid @RequestBody PatchBuyerReqVO request) {
        if (request.getStatus() != null) {
            commandService.changeStatus(buyerCode, parseBuyerStatus(request.getStatus()), getLoginUserId());
        }
        return success(true);
    }

    @PatchMapping
    @PreAuthorize(BUYER_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "批量暂停或恢复分销商",
            description = "body.status 仅允许 ACTIVE / SUSPENDED，否则 BUYER_STATUS_CONFLICT。")
    public CommonResult<Boolean> patchBatch(@Valid @RequestBody BuyerBatchStatusReqVO request) {
        BuyerStatus target = parseBuyerStatus(request.getStatus());
        if (target != BuyerStatus.ACTIVE && target != BuyerStatus.SUSPENDED) {
            throw exception(BUYER_STATUS_CONFLICT);
        }
        for (String code : request.getBuyerCodes()) {
            commandService.changeStatus(code, target, getLoginUserId());
        }
        return success(true);
    }

    @GetMapping("/{buyerCode}/children")
    @PreAuthorize(BUYER_QUERY_EXPRESSION)
    @Operation(summary = "查询分销商全部下级",
            description = "返回直属下级和孙级的扁平列表，排除归档分销商，按创建时间倒序。身份用 buyerCode。")
    public CommonResult<List<BuyerChildItem>> children(@PathVariable String buyerCode) {
        return success(queryService.children(buyerCode));
    }

    @GetMapping("/{buyerCode}/products")
    @PreAuthorize(BUYER_QUERY_EXPRESSION)
    @Operation(summary = "分页查询分销商品",
            description = "只返回未移除行。可筛 productType（动态商品类型编码，命中关联平台 SPU，可多选）。"
                    + "重复键或逗号拼接表示命中任一类型。省略或空白不筛选，无命中返回空页。"
                    + "金额单位 CNY 分；pricingRule 为继承L1/等于L1/自定义公式。")
    public CommonResult<PageResult<BuyerProductListItem>> pageProducts(
            @PathVariable String buyerCode,
            @Parameter(description = "动态商品类型编码，可多选；兼容单个 productType。"
                    + "重复键 productType=A&productType=B，或逗号拼接 productType=A,B")
            @RequestParam(required = false) List<String> productType,
            @Parameter(description = "页码，从 1 开始") @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数") @RequestParam(required = false) Integer pageSize) {
        return success(productQueryService.page(buyerCode, productType, pageNo, pageSize));
    }

    @PostMapping("/{buyerCode}/products")
    @PreAuthorize(BUYER_UPDATE_EXPRESSION)
    @Operation(summary = "从标品关联分销商品",
            description = "默认 l2AdjustMode=FOLLOW_TEMPLATE，其语义为无手工覆盖时继承L1，不读取模板价。"
                    + "页面「按分销价=销售建议价」须显式传 EQUAL_L1，覆盖值取当前 L1 结算分，不是 C 端销售建议价。"
                    + "CUSTOM 为 round(L1结算分 × percent / 100) + amountCents，结果必须大于 0，否则 L2_OVERRIDE_INVALID。"
                    + "displayThresholdPercent 省略则落空；传入须为 0～100 的整数，越界同样 L2_OVERRIDE_INVALID。"
                    + "行内 pricing 省略时只关联并继承商品默认定价；传入时与关联处在同一事务，"
                    + "字段与批量保存分销商通用定价相同。任一条缺少平台默认定价则整单回滚，返回 PLATFORM_DEFAULT_PRICING_MISSING。"
                    + "编号 BPD_。已存在未移除行返回 BUYER_PRODUCT_DUPLICATE；已移除同行复活而非插入。金额单位 CNY 分。")
    public CommonResult<List<String>> addProducts(
            @Parameter(description = "采购商永久业务编码", example = "BYR_01ABC") @PathVariable String buyerCode,
                                                    @Valid @RequestBody AddBuyerProductsReqVO request) {
        List<ProductLine> lines = request.getItems().stream()
                .map(BuyerAdminController::toProductLine)
                .toList();
        return success(productCommandService.addProducts(buyerCode, lines, getLoginUserId()));
    }

    @PatchMapping("/{buyerCode}/products/{buyerProductCode}")
    @PreAuthorize(BUYER_UPDATE_EXPRESSION)
    @Operation(summary = "改分销商品L2覆盖",
            description = "FOLLOW_TEMPLATE 清手工覆盖并继承L1，不读取模板价。"
                    + "EQUAL_L1 覆盖值取当前 L1 结算分；CUSTOM 为 round(L1结算分 × percent / 100) + amountCents，"
                    + "结果必须大于 0。无 L1 时 EQUAL_L1/CUSTOM 拒绝。"
                    + "displayThresholdPercent 省略则不改该列；传入须为 0～100 的整数，越界返回 L2_OVERRIDE_INVALID。"
                    + "阈值本批只落库，不触发 C 端隐藏。金额单位 CNY 分。")
    public CommonResult<Boolean> patchProduct(
            @Parameter(description = "采购商永久业务编码", example = "BYR_01ABC") @PathVariable String buyerCode,
            @Parameter(description = "分销商品稳定业务编码", example = "BPD_01ABC") @PathVariable String buyerProductCode,
                                              @Valid @RequestBody PatchBuyerProductReqVO request) {
        productCommandService.patchL2(
                buyerCode, buyerProductCode,
                ClosedEnums.parseRequired(L2AdjustMode.class, request.getL2AdjustMode()),
                request.getPercent(), request.getAmountCents(),
                request.getDisplayThresholdPercent(), getLoginUserId());
        return success(true);
    }

    @PostMapping("/{buyerCode}/products/pricing/preview")
    @PreAuthorize(BUYER_QUERY_EXPRESSION)
    @Operation(summary = "批量试算分销商通用定价",
            description = "只读试算当前分销商作用域内的分销价、销售价、服务费与利润隐藏；税务仅校验展示属性，不参与金额计算。")
    public CommonResult<List<PricingPreview>> previewProductPricing(
            @Parameter(description = "采购商永久业务编码") @PathVariable String buyerCode,
            @Valid @RequestBody BuyerProductBatchPricingReqVO request) {
        return success(genericPricingService.previewBuyer(buyerCode, request.getBuyerProductCodes(),
                GenericPricingRequestMapper.toBuyerOverrides(request)));
    }

    @PutMapping("/{buyerCode}/products/pricing")
    @PreAuthorize(BUYER_UPDATE_EXPRESSION)
    @Operation(summary = "批量保存分销商通用定价",
            description = "仅更新路径分销商下选中商品的通用定价版本；单事务全量成功或回滚，税务仅用于结算展示。")
    public CommonResult<Boolean> updateProductPricing(
            @Parameter(description = "采购商永久业务编码") @PathVariable String buyerCode,
            @Valid @RequestBody BuyerProductBatchPricingReqVO request) {
        genericPricingService.saveBuyer(buyerCode, request.getBuyerProductCodes(),
                GenericPricingRequestMapper.toBuyerOverrides(request), getLoginUserId());
        return success(true);
    }

    @DeleteMapping("/{buyerCode}/products/{buyerProductCode}")
    @PreAuthorize(BUYER_DELETE_EXPRESSION)
    @Operation(summary = "逻辑移除分销商品",
            description = "status=90。已发布项目 listing 不回放删除。身份用 buyerCode 与 buyerProductCode。")
    public CommonResult<Boolean> retireProduct(@PathVariable String buyerCode,
                                               @PathVariable String buyerProductCode) {
        productCommandService.retire(buyerCode, buyerProductCode, getLoginUserId());
        return success(true);
    }

    private CreateBuyerCommand toCreateCommand(BuyerSaveReqVO request) {
        return new CreateBuyerCommand(
                request.getBuyerName(), toContacts(request.getContacts()),
                request.getParentBuyerCode(), request.getDistributionLevel(),
                request.getDistributorType(), request.getSupplyPreference(),
                request.getSettlementMethod(), request.getSettlementCycleDays(),
                request.getCooperationModes(), request.getIntegrationModes(),
                request.getCooperationStartedAt(), request.getCooperationNote(),
                request.getLegalEntityName(), request.getCreditCode(),
                request.getLegalPersonName(), request.getLegalPersonMobile(),
                request.getContactAddress(), request.getBankName(), request.getBankAccount(),
                request.getLicenseFileKey(), request.getIdFrontFileKey(), request.getIdBackFileKey(),
                request.getQualificationFileKey(), request.getLicenseFileKeys(),
                request.getQualificationFileKeys(), request.getSellableCategories(), request.getProductTypes(),
                request.getOpeningBalanceCents(), getLoginUserId(), traceId());
    }

    private UpdateBuyerCommand toUpdateCommand(String buyerCode, BuyerSaveReqVO request) {
        return new UpdateBuyerCommand(
                buyerCode, request.getBuyerName(), toContacts(request.getContacts()),
                request.getParentBuyerCode(), request.getDistributionLevel(),
                request.getDistributorType(), request.getSupplyPreference(),
                request.getSettlementMethod(), request.getSettlementCycleDays(),
                request.getCooperationModes(), request.getIntegrationModes(),
                request.getCooperationStartedAt(), request.getCooperationNote(),
                request.getLegalEntityName(), request.getCreditCode(),
                request.getLegalPersonName(), request.getLegalPersonMobile(),
                request.getContactAddress(), request.getBankName(), request.getBankAccount(),
                request.getLicenseFileKey(), request.getIdFrontFileKey(), request.getIdBackFileKey(),
                request.getQualificationFileKey(), request.getLicenseFileKeys(),
                request.getQualificationFileKeys(), request.getSellableCategories(), request.getProductTypes(),
                request.getOpeningBalanceCents(), getLoginUserId());
    }

    /**
     * 分销商管理状态不是档案封闭枚举，未知值包装为 ENUM_NOT_OPEN。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#42-其它封闭枚举}。
     */
    private static BuyerStatus parseBuyerStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw exception(ENUM_NOT_OPEN);
        }
        try {
            return BuyerStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            ServiceException invalid = exception(ENUM_NOT_OPEN);
            invalid.initCause(ex);
            throw invalid;
        }
    }

    private static List<ContactLine> toContacts(List<ContactReqVO> contacts) {
        if (contacts == null) {
            return List.of();
        }
        return contacts.stream()
                .map(c -> new ContactLine(c.getName(), c.getMobile(), c.getEmail()))
                .toList();
    }

    private static ProductLine toProductLine(BuyerProductItemReqVO item) {
        L2AdjustMode mode = ClosedEnums.parse(L2AdjustMode.class, item.getL2AdjustMode());
        return new ProductLine(
                item.getPlatformSkuCode(),
                mode == null ? L2AdjustMode.FOLLOW_TEMPLATE : mode,
                item.getPercent(),
                item.getAmountCents(),
                item.getDisplayThresholdPercent(),
                item.getPricing() == null ? null : GenericPricingRequestMapper.toBuyerOverrides(item.getPricing()));
    }

    private static String traceId() {
        String traceId = TracerUtils.getTraceId();
        return traceId == null || traceId.isBlank() ? "admin-" + UUID.randomUUID() : traceId;
    }

    @Data
    @Schema(description = "分销商档案保存")
    public static class BuyerSaveReqVO {
        @NotBlank @Size(max = 128)
        @Schema(description = "采购商标准名称")
        private String buyerName;
        private List<ContactReqVO> contacts;
        @Schema(description = "上级分销商编码，一级为空")
        private String parentBuyerCode;
        @Schema(description = "组织等级：1一级 2二级 3三级，默认1")
        private Integer distributionLevel;
        @Schema(description = "分销商类型：ENTERPRISE企业 PERSONAL个人 PLATFORM平台")
        private String distributorType;
        @Schema(description = "供货择优倾向，不改变路由引擎")
        private String supplyPreference;
        @Schema(description = "结算方式封闭枚举")
        private String settlementMethod;
        @Min(1) @Schema(description = "结算周期天数")
        private Integer settlementCycleDays;
        @Schema(description = "合作模式")
        private List<String> cooperationModes;
        @Schema(description = "对接方式：API、H5、MANUAL")
        private List<String> integrationModes;
        @Schema(description = "合作开始时间，Asia/Shanghai")
        private LocalDateTime cooperationStartedAt;
        @Size(max = 200) @Schema(description = "合作说明")
        private String cooperationNote;
        @Size(max = 256) @Schema(description = "公司名称")
        private String legalEntityName;
        @Size(max = 32) @Schema(description = "统一社会信用代码")
        private String creditCode;
        @Schema(description = "法人姓名")
        private String legalPersonName;
        private String legalPersonMobile;
        @Schema(description = "联系地址")
        private String contactAddress;
        @Schema(description = "开户行")
        private String bankName;
        private String bankAccount;
        @Schema(description = "兼容单执照fileKey，数组为空时当作单元素")
        private String licenseFileKey;
        private String idFrontFileKey;
        private String idBackFileKey;
        @Schema(description = "兼容单资质fileKey，数组为空时当作单元素")
        private String qualificationFileKey;
        @Size(max = 5)
        @Schema(description = "执照文件fileKey，最多5个")
        private List<String> licenseFileKeys;
        @Size(max = 5)
        @Schema(description = "资质文件fileKey，最多5个")
        private List<String> qualificationFileKeys;
        @Schema(description = "可售品类枚举名")
        private List<String> sellableCategories;
        @Schema(description = "动态商品类型编码")
        private List<String> productTypes;
        @Min(0)
        @Schema(description = "期初余额，单位CNY分，仅创建可写")
        private Long openingBalanceCents;
    }

    @Data
    @Schema(description = "开票信息保存")
    public static class InvoiceSaveReqVO {
        @Schema(description = "抬头类型：ENTERPRISE企业，PERSONAL个人；字典 supply_chain_invoice_title_type")
        private String titleType;
        @Size(max = 256)
        @Schema(description = "开票抬头")
        private String invoiceTitle;
        @Size(max = 32)
        @Schema(description = "统一社会信用代码")
        private String socialCreditCode;
        @Size(max = 32)
        @Schema(description = "电话")
        private String phone;
        @Size(max = 128)
        @Schema(description = "开户行")
        private String bankName;
        @Size(max = 64)
        @Schema(description = "开户账号")
        private String bankAccount;
        @Size(max = 512)
        @Schema(description = "开票地址")
        private String invoiceAddress;
    }

    @Data
    public static class ContactReqVO {
        private String name;
        private String mobile;
        private String email;
    }

    @Data
    @Schema(description = "分销商部分更新")
    public static class PatchBuyerReqVO {
        @Schema(description = "管理状态：ACTIVE / SUSPENDED / BLACKLIST")
        private String status;
    }

    @Data
    @Schema(description = "批量暂停或恢复")
    public static class BuyerBatchStatusReqVO {
        @NotEmpty
        @Schema(description = "采购商永久业务编码列表")
        private List<String> buyerCodes;
        @NotBlank
        @Schema(description = "管理状态，仅 ACTIVE / SUSPENDED")
        private String status;
    }

    @Data
    @Schema(description = "从标品批量关联分销商品")
    public static class AddBuyerProductsReqVO {
        @NotEmpty
        @Schema(description = "待关联标品行")
        private List<BuyerProductItemReqVO> items;
    }

    @Data
    @Schema(description = "分销商品关联行")
    public static class BuyerProductItemReqVO {
        @NotBlank
        @Schema(description = "平台SKU编码")
        private String platformSkuCode;
        @Schema(description = "L2调价方式：FOLLOW_TEMPLATE（清手工覆盖并继承L1，不读取模板价）/ EQUAL_L1 / CUSTOM，默认 FOLLOW_TEMPLATE")
        private String l2AdjustMode;
        @Schema(description = "CUSTOM 时的整数百分比，100 等于当前 L1 结算分；小于 1 返回 L2_OVERRIDE_INVALID。EQUAL_L1 可省略")
        private Integer percent;
        @Schema(description = "CUSTOM 时在百分比结果上加减的金额，单位 CNY 分，可为负；省略按 0。EQUAL_L1 可省略")
        private Long amountCents;
        @Min(0)
        @Max(100)
        @Schema(description = "利润空间低于该百分比时 C 端隐藏。0 到 100 的整数；省略则该列落空。本批只落库，不触发隐藏",
                example = "20")
        private Integer displayThresholdPercent;
        @Valid
        @Schema(description = "一并写入的分销商通用定价。省略则只关联并继承商品默认定价；传入时与关联同一事务")
        private BuyerProductCreatePricingReqVO pricing;
    }

    @Data
    @Schema(description = "新增分销商品时的通用定价覆盖。语义与批量保存分销商通用定价相同，不含商品编码")
    public static class BuyerProductCreatePricingReqVO {
        @Schema(description = "true时继承商品默认分销价")
        private boolean distributionInherited;
        @Valid
        @Schema(description = "分销价覆盖规则")
        private FormulaRuleReqVO distributionRule;
        @Schema(description = "true时继承商品默认销售价")
        private boolean salesInherited;
        @Valid
        @Schema(description = "销售价覆盖规则")
        private FormulaRuleReqVO salesRule;
        @Schema(description = "true时继承商品默认服务费")
        private boolean serviceFeeInherited;
        @Valid
        @Schema(description = "服务费覆盖规则")
        private ServiceFeeRuleReqVO serviceFeeRule;
        @Schema(description = "true时继承商品默认利润隐藏阈值")
        private boolean hideInherited;
        @Schema(description = "分销区利润隐藏阈值百分数，0到100，最多2位小数")
        private java.math.BigDecimal distributionHideThresholdPercent;
        @Schema(description = "销售区利润隐藏阈值百分数，0到100，最多2位小数")
        private java.math.BigDecimal salesHideThresholdPercent;
        @Schema(description = "true时继承商品默认税务；false时须同时提交 taxIncluded 与 taxRateCode，税务组不完整则自动降级为继承")
        private boolean taxInherited;
        @Schema(description = "是否含税；覆盖时须与 taxRateCode 同时提交，任一缺失则整体降级为继承")
        private Boolean taxIncluded;
        @Schema(description = "system动态字典supply_chain_tax_rate的启用值；覆盖时须与 taxIncluded 同时提交，缺失则整体降级为继承")
        private String taxRateCode;
    }

    @Data
    @Schema(description = "改分销商品L2覆盖")
    public static class PatchBuyerProductReqVO {
        @NotBlank
        @Schema(description = "L2调价方式：FOLLOW_TEMPLATE（清手工覆盖并继承L1，不读取模板价）/ EQUAL_L1 / CUSTOM")
        private String l2AdjustMode;
        @Schema(description = "CUSTOM 时的整数百分比，100 等于当前 L1 结算分；小于 1 返回 L2_OVERRIDE_INVALID。非 CUSTOM 可省略")
        private Integer percent;
        @Schema(description = "CUSTOM 时在百分比结果上加减的金额，单位 CNY 分，可为负；省略按 0。非 CUSTOM 可省略")
        private Long amountCents;
        @Min(0)
        @Max(100)
        @Schema(description = "利润空间低于该百分比时 C 端隐藏。0 到 100 的整数；省略则不改原值。本批只落库，不触发隐藏",
                example = "20")
        private Integer displayThresholdPercent;
    }

    @Data
    @Schema(description = "分销商SKU通用批量定价请求")
    public static class BuyerProductBatchPricingReqVO {
        @NotEmpty
        @Size(max = GenericBatchPricingService.MAX_BATCH)
        @Schema(description = "当前分销商下的分销商品编码列表", requiredMode = Schema.RequiredMode.REQUIRED)
        private List<@NotBlank String> buyerProductCodes;
        @Schema(description = "true时继承商品默认分销价")
        private boolean distributionInherited;
        @Valid @Schema(description = "分销价覆盖规则")
        private FormulaRuleReqVO distributionRule;
        @Schema(description = "true时继承商品默认销售价")
        private boolean salesInherited;
        @Valid @Schema(description = "销售价覆盖规则")
        private FormulaRuleReqVO salesRule;
        @Schema(description = "true时继承商品默认服务费")
        private boolean serviceFeeInherited;
        @Valid @Schema(description = "服务费覆盖规则")
        private ServiceFeeRuleReqVO serviceFeeRule;
        @Schema(description = "true时继承商品默认利润隐藏阈值")
        private boolean hideInherited;
        @Schema(description = "分销区利润隐藏阈值百分数，0到100，最多2位小数")
        private BigDecimal distributionHideThresholdPercent;
        @Schema(description = "销售区利润隐藏阈值百分数，0到100，最多2位小数")
        private BigDecimal salesHideThresholdPercent;
        @Schema(description = "true时继承商品默认税务；false时须同时提交 taxIncluded 与 taxRateCode，税务组不完整则自动降级为继承")
        private boolean taxInherited;
        @Schema(description = "是否含税；覆盖时须与 taxRateCode 同时提交，任一缺失则整体降级为继承")
        private Boolean taxIncluded;
        @Schema(description = "system动态字典supply_chain_tax_rate的启用值；覆盖时须与 taxIncluded 同时提交，缺失则整体降级为继承")
        private String taxRateCode;
    }
}
