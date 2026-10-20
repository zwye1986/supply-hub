package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.framework.common.util.monitor.TracerUtils;
import com.aifuli.module.supplychain.tradecore.profile.domain.ClosedEnums;
import com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService;
import com.aifuli.module.supplychain.infrastructure.identity.SystemAssignedBusinessCodeGuard;
import com.aifuli.module.supplychain.tradecore.supply.application.OfferFulfillmentScoreService;
import com.aifuli.module.supplychain.tradecore.supply.application.OfferFulfillmentScoreService.ScoreUpdateResult;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierInvoiceService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierInvoiceService.SaveInvoiceCommand;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierInvoiceService.SupplierInvoiceView;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService.DraftLine;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierCooperativeProductService.OfferListItem;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierOfferPublicationService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService.ContactLine;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService.CreateSupplierProfileCommand;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileCommandService.UpdateSupplierProfileCommand;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileQueryService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileQueryService.SupplierDetail;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileQueryService.SupplierPageQuery;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierProfileQueryService.SupplyScopeView;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierSupplyScopeService;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierSupplyScopeService.ScopeLine;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferFulfillmentScore;
import com.aifuli.module.supplychain.tradecore.supply.domain.OfferStatus;
import com.aifuli.module.supplychain.tradecore.supply.domain.SupplierStatus;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.ENUM_NOT_OPEN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SUPPLIER_STATUS_CONFLICT;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.SUPPLIER_CREATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.SUPPLIER_DELETE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.SUPPLIER_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.SUPPLIER_UPDATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.SUPPLIER_UPDATE_STATUS_EXPRESSION;

/**
 * 供应商管理端 REST。列表多值筛选、{@code view=simple}、PATCH 状态。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#5-供应商}。
 */
@Tag(name = "管理后台 - 供应链供应商档案")
@RestController
@RequestMapping("/supply-chain/suppliers")
@Validated
@RequiredArgsConstructor
public class SupplierAdminController {

    private final SupplierProfileCommandService commandService;
    private final SupplierProfileQueryService queryService;
    private final SupplierSupplyScopeService scopeService;
    private final SupplierCooperativeProductService cooperativeProductService;
    private final SupplierInvoiceService invoiceService;
    private final OfferFulfillmentScoreService offerFulfillmentScoreService;
    /** B1 上架即发布编排（§6.6）：合作商品一步发布为可履约供给版本。 */
    private final SupplierOfferPublicationService offerPublicationService;

    @GetMapping
    @PreAuthorize(SUPPLIER_QUERY_EXPRESSION)
    @Operation(summary = "分页查询供应商",
            description = "筛选用多值 status/sellableCategories/supplyModes 与 updatedFrom/To。"
                    + "view=simple 只返回正常（ACTIVE）的 {supplierCode,supplierName}[]，不强制分页。"
                    + "身份用 supplierCode，金额单位 CNY 分。")
    public CommonResult<?> page(
            @Parameter(description = "供应商标准名称，模糊") @RequestParam(required = false) String supplierName,
            @Parameter(description = "供应商永久业务编码") @RequestParam(required = false) String supplierCode,
            @Parameter(description = "合作状态枚举名，可多选：ACTIVE / SUSPENDED / BLACKLIST")
            @RequestParam(required = false) List<String> status,
            @Parameter(description = "可售品类枚举名，可多选") @RequestParam(required = false) List<String> sellableCategories,
            @Parameter(description = "供货模式枚举名，可多选") @RequestParam(required = false) List<String> supplyModes,
            @Parameter(description = "最后修改时间起，Asia/Shanghai") @RequestParam(required = false) LocalDateTime updatedFrom,
            @Parameter(description = "最后修改时间止，Asia/Shanghai") @RequestParam(required = false) LocalDateTime updatedTo,
            @Parameter(description = "投影：simple 为名称下拉") @RequestParam(required = false) String view,
            @Parameter(description = "页码，从 1 开始") @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数") @RequestParam(required = false) Integer pageSize) {
        if ("simple".equals(view)) {
            return success(queryService.listSimple(supplierName, supplierCode));
        }
        List<SupplierStatus> statuses = status == null ? List.of()
                : status.stream().map(SupplierAdminController::parseSupplierStatus).toList();
        return success(queryService.page(new SupplierPageQuery(
                supplierName, supplierCode, statuses, sellableCategories, supplyModes,
                updatedFrom, updatedTo, pageNo, pageSize)));
    }

    @GetMapping("/{supplierCode}")
    @PreAuthorize(SUPPLIER_QUERY_EXPRESSION)
    @Operation(summary = "供应商详情",
            description = "身份用 supplierCode。档案敏感字段按管理端权限原值回显；"
                    + "spuCount 为未退役 Offer 关联的平台 SPU 去重数；"
                    + "accountBalanceCents 为当前账本余额；deliveryRegions 返回行政区划编码及名称；金额单位 CNY 分。")
    public CommonResult<SupplierDetail> get(
            @Parameter(description = "供应商永久业务编码") @PathVariable String supplierCode) {
        return success(queryService.get(supplierCode));
    }

    @GetMapping("/{supplierCode}/invoice")
    @PreAuthorize(SUPPLIER_QUERY_EXPRESSION)
    @Operation(summary = "查询供应商开票信息",
            description = "身份用 supplierCode。无开票行时用档案预填返回且不落库；已保存行明文回传。不回写档案。")
    public CommonResult<SupplierInvoiceView> getInvoice(@PathVariable String supplierCode) {
        return success(invoiceService.get(supplierCode));
    }

    @PutMapping("/{supplierCode}/invoice")
    @PreAuthorize(SUPPLIER_UPDATE_EXPRESSION)
    @Operation(summary = "保存供应商开票信息",
            description = "身份用 supplierCode。upsert 开票表，开户账号明文存储，不回写供应商档案。"
                    + "titleType 为 ENTERPRISE / PERSONAL。")
    public CommonResult<SupplierInvoiceView> saveInvoice(@PathVariable String supplierCode,
                                                           @Valid @RequestBody InvoiceSaveReqVO request) {
        return success(invoiceService.save(supplierCode, toInvoiceCommand(request), getLoginUserId()));
    }

    @PostMapping
    @PreAuthorize(SUPPLIER_CREATE_EXPRESSION)
    @Operation(summary = "新增供应商（含期初）",
            description = "系统生成 SPL_ 编码。openingBalanceCents 仅创建可写，单位 CNY 分。")
    public CommonResult<String> create(@Valid @RequestBody SupplierSaveReqVO request) {
        SystemAssignedBusinessCodeGuard.rejectSubmitted(ScopedBusinessCodeService.Kind.SPL,
                request.isSupplierCodeSubmitted());
        return success(commandService.create(new CreateSupplierProfileCommand(
                request.resolvedSupplierName(), toContacts(request.getContacts()),
                request.getDeliveryRegionType(), request.getDeliveryRegionAdcodes(),
                request.getAftersalesSupported(), request.getShippingSlaCode(),
                request.getShippingSlaCustom(), request.getSettlementMethod(), request.getSettlementCycleDays(),
                request.getIntegrationModes(), request.getOnboardedAt(),
                request.getStatus() == null ? SupplierStatus.ACTIVE : parseSupplierStatus(request.getStatus()),
                request.getCooperationNote(), request.getLegalEntityName(), request.getCreditCode(),
                request.getLegalPersonName(), request.getLegalPersonIdNo(), request.getLegalPersonMobile(), request.getContactAddress(),
                request.getBankName(), request.getBankAccount(), request.getLicenseFileKey(),
                request.getIdFrontFileKey(), request.getIdBackFileKey(), request.getQualificationFileKey(),
                request.getLicenseFileKeys(), request.getQualificationFileKeys(),
                request.getOpeningBalanceCents() == null ? 0L : request.getOpeningBalanceCents(),
                getLoginUserId(), traceId())));
    }

    @PutMapping("/{supplierCode}")
    @PreAuthorize(SUPPLIER_UPDATE_EXPRESSION)
    @Operation(summary = "编辑供应商档案",
            description = "身份用 supplierCode。编辑再带 openingBalanceCents 拒绝。金额单位 CNY 分。")
    public CommonResult<Boolean> update(@PathVariable String supplierCode,
                                          @Valid @RequestBody SupplierSaveReqVO request) {
        commandService.update(new UpdateSupplierProfileCommand(
                supplierCode, request.resolvedSupplierName(), toContacts(request.getContacts()),
                request.getDeliveryRegionType(), request.getDeliveryRegionAdcodes(),
                request.getAftersalesSupported(), request.getShippingSlaCode(),
                request.getShippingSlaCustom(), request.getSettlementMethod(), request.getSettlementCycleDays(),
                request.getIntegrationModes(), request.getOnboardedAt(),
                request.getCooperationNote(), request.getLegalEntityName(), request.getCreditCode(),
                request.getLegalPersonName(), request.getLegalPersonIdNo(), request.getLegalPersonMobile(), request.getContactAddress(),
                request.getBankName(), request.getBankAccount(), request.getLicenseFileKey(),
                request.getIdFrontFileKey(), request.getIdBackFileKey(), request.getQualificationFileKey(),
                request.getLicenseFileKeys(), request.getQualificationFileKeys(),
                request.getOpeningBalanceCents(), getLoginUserId()));
        return success(true);
    }

    @PatchMapping("/{supplierCode}")
    @PreAuthorize(SUPPLIER_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "部分更新供应商",
            description = "目前仅支持 status：ACTIVE / SUSPENDED / BLACKLIST。身份用 supplierCode。")
    public CommonResult<Boolean> patch(@PathVariable String supplierCode,
                                       @Valid @RequestBody PatchSupplierReqVO request) {
        if (request.getStatus() != null) {
            commandService.changeStatus(supplierCode, parseSupplierStatus(request.getStatus()), getLoginUserId());
        }
        return success(true);
    }

    @PatchMapping
    @PreAuthorize(SUPPLIER_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "批量暂停或恢复供应商",
            description = "body.status 仅允许 ACTIVE / SUSPENDED，否则 SUPPLIER_STATUS_CONFLICT。")
    public CommonResult<Boolean> patchBatch(@Valid @RequestBody SupplierBatchStatusReqVO request) {
        SupplierStatus target = parseSupplierStatus(request.resolvedTargetStatus());
        if (target != SupplierStatus.ACTIVE && target != SupplierStatus.SUSPENDED) {
            throw exception(SUPPLIER_STATUS_CONFLICT);
        }
        for (String code : request.getSupplierCodes()) {
            commandService.changeStatus(code, target, getLoginUserId());
        }
        return success(true);
    }

    @PutMapping("/{supplierCode}/status")
    @PreAuthorize(SUPPLIER_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "变更供应商合作状态",
            description = "兼容别名，新页面不要调用。请改用 PATCH /{supplierCode}。")
    public CommonResult<Boolean> changeStatus(@PathVariable String supplierCode,
                                                 @Valid @RequestBody ChangeStatusReqVO request) {
        commandService.changeStatus(supplierCode, parseSupplierStatus(request.resolvedTargetStatus()),
                getLoginUserId());
        return success(true);
    }

    @PutMapping("/status/batch")
    @PreAuthorize(SUPPLIER_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "批量暂停或恢复",
            description = "兼容别名，新页面不要调用。请改用 PATCH 集合。优先读 status，否则 targetStatus。")
    public CommonResult<Boolean> batchStatus(@Valid @RequestBody SupplierBatchStatusReqVO request) {
        return patchBatch(request);
    }

    @GetMapping("/{supplierCode}/supply-scopes")
    @PreAuthorize(SUPPLIER_QUERY_EXPRESSION)
    @Operation(summary = "查询供货配置",
            description = "身份用 supplierCode。行含类目三元组与 supplyModes，不暴露 DO。")
    public CommonResult<List<SupplyScopeView>> listScopes(@PathVariable String supplierCode) {
        return success(queryService.listSupplyScopes(supplierCode));
    }

    @PutMapping("/{supplierCode}/supply-scopes")
    @PreAuthorize(SUPPLIER_UPDATE_EXPRESSION)
    @Operation(summary = "保存供货配置",
            description = "全量替换。身份用 supplierCode。空 supplyModes 为 SUPPLY_SCOPE_MODE_INVALID；"
                    + "重复三码组合为 SUPPLIER_SCOPE_CONFLICT；未知枚举码为 ENUM_NOT_OPEN。")
    public CommonResult<Boolean> saveScopes(@PathVariable String supplierCode,
                                              @Valid @RequestBody SaveScopesReqVO request) {
        scopeService.replaceAll(supplierCode, request.getLines().stream()
                .map(line -> new ScopeLine(line.getSellableCategory(), line.getProductType(),
                        line.getProductClassification(), line.getSupplyModes(),
                        line.getMaxQuantity(), line.getMinQuantity()))
                .toList(), getLoginUserId());
        return success(true);
    }

    @PutMapping("/{supplierCode}/fulfillment-scores")
    @PreAuthorize(SUPPLIER_UPDATE_EXPRESSION)
    @Operation(summary = "保存供应商合作商品履约能力评分",
            description = "人工录入稳定性与时效性评分，挂在未退役 Offer 身份列。"
                    + "可只改其中一列，省略的列保持原值。"
                    + "省略 platformSkuCodes 则覆盖该供应商全部未退役 Offer；给出列表则只覆盖这些 SKU。"
                    + "改价 PUT .../offers/{offerNo} 不碰评分。")
    public CommonResult<ScoreUpdateResult> saveFulfillmentScores(
            @Parameter(description = "供应商永久业务编码") @PathVariable String supplierCode,
            @Valid @RequestBody FulfillmentScoreSaveReqVO request) {
        int updated = offerFulfillmentScoreService.saveSupplierScores(
                supplierCode, request.getStabilityScore(), request.getTimelinessScore(),
                request.getPlatformSkuCodes(), getLoginUserId());
        return success(new ScoreUpdateResult(updated));
    }

    @GetMapping("/{supplierCode}/offers")
    @PreAuthorize(SUPPLIER_QUERY_EXPRESSION)
    @Operation(summary = "合作商品列表（不含已移除）",
            description = "分页，默认 pageNo=1 pageSize=20，按 createdAt 倒序。可筛 sellableCategory/productType/productClassification。"
                    + "身份用 supplierCode。金额单位 CNY 分。含可空稳定性/时效性评分。不暴露 DO。")
    public CommonResult<PageResult<OfferListItem>> listOffers(
            @PathVariable String supplierCode,
            @Parameter(description = "可售品类枚举名") @RequestParam(required = false) String sellableCategory,
            @Parameter(description = "动态商品类型编码") @RequestParam(required = false) String productType,
            @Parameter(description = "商品分类：字典枚举名或类目 categoryCode") @RequestParam(required = false) String productClassification,
            @Parameter(description = "页码，默认1") @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数，默认20") @RequestParam(required = false) Integer pageSize) {
        return success(cooperativeProductService.page(supplierCode, sellableCategory, productType,
                productClassification, pageNo, pageSize));
    }

    @PostMapping("/{supplierCode}/offers")
    @PreAuthorize(SUPPLIER_UPDATE_EXPRESSION)
    @Operation(summary = "批量新增合作商品",
            description = "body.items 优先，兼容 lines。新增默认 SUSPENDED，正式上架需另行发布。供货上限为空则继承供货配置；"
                    + "最低起购量为空固定取1，不继承供货配置预留字段。"
                    + "供货范围要求商品类型相同，支持父分类覆盖子分类、最具体优先，不按可售品类匹配；"
                    + "无匹配返回 SUPPLIER_OFFER_OUT_OF_SCOPE，最深层配置歧义返回 SUPPLIER_SCOPE_CONFLICT。")
    public CommonResult<List<String>> createOffers(@PathVariable String supplierCode,
                                                     @Valid @RequestBody OfferItemsReqVO request) {
        return success(cooperativeProductService.createPublished(supplierCode,
                toDraftLines(request.resolveItems()), getLoginUserId()));
    }

    @PutMapping("/{supplierCode}/offers/{offerNo}")
    @PreAuthorize(SUPPLIER_UPDATE_EXPRESSION)
    @Operation(summary = "编辑合作商品",
            description = "校验价格与供货配置后更新身份列，不写报价版本。"
                    + "供货范围要求商品类型相同，支持父分类覆盖子分类、最具体优先，不按可售品类匹配；"
                    + "无匹配返回 SUPPLIER_OFFER_OUT_OF_SCOPE，最深层配置歧义返回 SUPPLIER_SCOPE_CONFLICT。")
    public CommonResult<Boolean> updateOffer(@PathVariable String supplierCode,
                                               @PathVariable String offerNo,
                                               @Valid @RequestBody DraftReqVO request) {
        cooperativeProductService.updateCommercial(supplierCode, offerNo, toDraftLine(request),
                getLoginUserId());
        return success(true);
    }

    @PatchMapping("/{supplierCode}/offers/{offerNo}")
    @PreAuthorize(SUPPLIER_UPDATE_EXPRESSION)
    @Operation(summary = "上架或下架合作商品",
            description = "仅 PUBLISHED↔SUSPENDED；RETIRED 请用 DELETE。")
    public CommonResult<Boolean> patchOffer(@PathVariable String supplierCode,
                                              @PathVariable String offerNo,
                                              @Valid @RequestBody PatchOfferReqVO request) {
        cooperativeProductService.changeOfferStatus(supplierCode, offerNo,
                ClosedEnums.parseRequired(OfferStatus.class, request.getStatus()), getLoginUserId());
        return success(true);
    }

    @PostMapping("/{supplierCode}/offers/{offerNo}/list-on-shelf")
    @PreAuthorize(SUPPLIER_UPDATE_EXPRESSION)
    @Operation(summary = "上架即发布合作商品（B1）",
            description = "一步把合作商品发布为可履约供给版本：服务端自动引用该供应商唯一已发布连接器版本（C3）、"
                    + "自动开通/复用 REMOTE_ON_DEMAND 库存（c3）、继承身份行结算价（g3）、注入适配器默认执行画像（d2）。"
                    + "前端只传 offerNo（路径）+ 可选 validTo（空＝长期有效，k1），无发布对话框。"
                    + "连接器缺失返回 SUPPLIER_NO_PUBLISHED_CONNECTOR、能力缺口返回 OFFER_CAPABILITY_MISSING，前端 message.error 透传明确文案。"
                    + "设计见 docs/superpowers/specs/2026-10-03-supplier-offer-publication-admin-design.md#66-上架即发布服务b1核心。")
    public CommonResult<SupplierOfferPublicationService.PublishResult> listOfferOnShelf(
            @PathVariable String supplierCode,
            @PathVariable String offerNo,
            @Valid @RequestBody(required = false) ListOnShelfReqVO request) {
        LocalDateTime validTo = request == null ? null : request.getValidTo();
        return success(offerPublicationService.listOnShelf(
                new SupplierOfferPublicationService.ListOnShelfCommand(
                        supplierCode, offerNo, validTo, getLoginUserId(), traceId(), null, null)));
    }

    @DeleteMapping("/{supplierCode}/offers/{offerNo}")
    @PreAuthorize(SUPPLIER_DELETE_EXPRESSION)
    @Operation(summary = "移除合作商品",
            description = "逻辑移除为 RETIRED，列表不再返回。")
    public CommonResult<Boolean> deleteOffer(@PathVariable String supplierCode,
                                               @PathVariable String offerNo) {
        cooperativeProductService.retire(supplierCode, offerNo, getLoginUserId());
        return success(true);
    }

    @PostMapping("/{supplierCode}/offers/batch-draft")
    @PreAuthorize(SUPPLIER_UPDATE_EXPRESSION)
    @Operation(summary = "批量新增合作商品草稿",
            description = "兼容别名，新页面不要调用。委托新增即上架。")
    public CommonResult<List<String>> createDrafts(@PathVariable String supplierCode,
                                                      @Valid @RequestBody BatchDraftReqVO request) {
        return success(cooperativeProductService.createPublished(supplierCode,
                toDraftLines(request.getLines()), getLoginUserId()));
    }

    @PutMapping("/{supplierCode}/offers/{offerNo}/retire")
    @PreAuthorize(SUPPLIER_DELETE_EXPRESSION)
    @Operation(summary = "移除合作商品",
            description = "兼容别名，新页面不要调用。请改用 DELETE /{supplierCode}/offers/{offerNo}。")
    public CommonResult<Boolean> retire(@PathVariable String supplierCode, @PathVariable String offerNo) {
        cooperativeProductService.retire(supplierCode, offerNo, getLoginUserId());
        return success(true);
    }

    private static List<DraftLine> toDraftLines(List<DraftReqVO> lines) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream().map(SupplierAdminController::toDraftLine).toList();
    }

    private static DraftLine toDraftLine(DraftReqVO line) {
        return new DraftLine(
                line.getPlatformSkuCode(), line.getPriceMode(), line.getUnitPriceCents(),
                line.getDiscountBp(), line.getSuggestedPriceCents(), line.getSupplyMode(),
                line.getMaxQuantity(), line.getMinQuantity(), line.getExternalSkuCode());
    }

    /**
     * 供应商合作状态不是档案封闭枚举，未知值包装为 ENUM_NOT_OPEN。
     * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#42-其它封闭枚举}。
     */
    private static SupplierStatus parseSupplierStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw exception(ENUM_NOT_OPEN);
        }
        try {
            return SupplierStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            ServiceException invalid = exception(ENUM_NOT_OPEN);
            invalid.initCause(ex);
            throw invalid;
        }
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return null;
    }

    private static List<ContactLine> toContacts(List<ContactReqVO> contacts) {
        if (contacts == null) {
            return List.of();
        }
        return contacts.stream()
                .map(c -> new ContactLine(c.getName(), c.getMobile(), c.getEmail()))
                .toList();
    }

    private static String traceId() {
        String traceId = TracerUtils.getTraceId();
        return traceId == null || traceId.isBlank() ? "admin-" + UUID.randomUUID() : traceId;
    }

    private static SaveInvoiceCommand toInvoiceCommand(InvoiceSaveReqVO request) {
        return new SaveInvoiceCommand(
                request.getTitleType(), request.getInvoiceTitle(), request.getSocialCreditCode(),
                request.getPhone(), request.getBankName(), request.getBankAccount(),
                request.getInvoiceAddress());
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
    @Schema(name = "SupplierSaveReqVO", description = "供应商档案保存")
    public static class SupplierSaveReqVO {
        @JsonIgnore
        @Schema(hidden = true)
        private boolean supplierCodeSubmitted;

        /** 只记录请求是否出现该字段；创建入口不接受任何手填值，包括 null 和空串。 */
        @JsonSetter("supplierCode")
        @Schema(hidden = true)
        public void acceptSupplierCode(String ignored) {
            supplierCodeSubmitted = true;
        }

        /** 供应商标准名称 */
        @Size(max = 128)
        @Schema(description = "供应商标准名称", example = "爱奇艺")
        private String supplierName;
        /** 供应商标准名称（yudao 表单常用 name，与 supplierName 同义） */
        @Size(max = 128)
        @Schema(description = "供应商标准名称（与 supplierName 同义，同时出现时以本字段为准）", example = "爱奇艺")
        private String name;

        /**
         * 列表与档案标题使用的供应商名称，不得回落到公司名称。
         */
        @JsonIgnore
        @Schema(hidden = true)
        public String resolvedSupplierName() {
            if (name != null && !name.isBlank()) {
                return name.trim();
            }
            if (supplierName != null && !supplierName.isBlank()) {
                return supplierName.trim();
            }
            return null;
        }

        @AssertTrue(message = "供应商名称不能为空")
        @JsonIgnore
        @Schema(hidden = true)
        public boolean isSupplierNamePresent() {
            return resolvedSupplierName() != null;
        }

        @Schema(description = "运营联系人")
        private List<ContactReqVO> contacts;
        /** 配送区域：NATIONWIDE全国，DESIGNATED指定区域 */
        @NotBlank
        @Schema(description = "配送区域：NATIONWIDE全国，DESIGNATED指定区域",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "NATIONWIDE")
        private String deliveryRegionType;
        /** 国标行政区划代码 */
        @Schema(description = "国标行政区划代码", example = "[\"310000\"]")
        private List<String> deliveryRegionAdcodes;
        @NotNull
        @Schema(description = "是否支持售后：1是，0否")
        private Boolean aftersalesSupported;
        @Schema(description = "发货时效封闭枚举")
        private String shippingSlaCode;
        @Schema(description = "发货时效自定义说明，仅 CUSTOM 使用")
        private String shippingSlaCustom;
        @Schema(description = "结算方式封闭枚举")
        private String settlementMethod;
        @Min(1)
        @Schema(description = "结算周期天数")
        private Integer settlementCycleDays;
        @Schema(description = "对接方式：API、H5、MANUAL")
        private List<String> integrationModes;
        @JsonProperty("onboardAt")
        @JsonAlias("onboardedAt")
        @Schema(description = "接入时间，UTC")
        private LocalDateTime onboardedAt;
        @Schema(description = "合作状态：ACTIVE正常，SUSPENDED暂停供货，BLACKLIST黑名单", example = "ACTIVE")
        private String status;
        /** 合作说明 */
        @Size(max = 200)
        @Schema(description = "合作说明")
        private String cooperationNote;
        /** 供应商签约法律主体名称 */
        @NotBlank
        @Size(max = 256)
        @Schema(description = "供应商签约法律主体名称", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "测试科技有限公司")
        private String legalEntityName;
        /** 统一社会信用代码 */
        @NotBlank
        @Size(max = 32)
        @Schema(description = "统一社会信用代码", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "91110000000000001X")
        private String creditCode;
        /** 法人姓名 */
        @Schema(description = "法人姓名", example = "李四")
        private String legalPersonName;
        /** 法人身份证号 */
        @Size(max = 32)
        @Schema(description = "法人身份证号", example = "310101199001011234")
        private String legalPersonIdNo;
        /** 法人手机号 */
        @Schema(description = "法人手机号", example = "13900139000")
        private String legalPersonMobile;
        /** 联系地址 */
        @Schema(description = "联系地址", example = "上海市浦东新区")
        private String contactAddress;
        /** 开户行 */
        @Schema(description = "开户行", example = "测试银行")
        private String bankName;
        /** 开户账号 */
        @Schema(description = "开户账号", example = "6222021234567890")
        private String bankAccount;
        @Schema(description = "营业执照文件引用")
        private String licenseFileKey;
        /** 法人身份证正面文件引用 */
        @Schema(description = "法人身份证正面文件引用")
        private String idFrontFileKey;
        /** 法人身份证反面文件引用 */
        @Schema(description = "法人身份证反面文件引用")
        private String idBackFileKey;
        @Schema(description = "资质文件引用")
        private String qualificationFileKey;
        @Size(max = 5)
        @Schema(description = "执照文件fileKey，最多5个")
        private List<String> licenseFileKeys;
        @Size(max = 5)
        @Schema(description = "资质文件fileKey，最多5个")
        private List<String> qualificationFileKeys;
        @Schema(description = "能力标签，忽略不落库")
        private List<String> capabilityTags;
        @Min(0)
        @Schema(description = "预存可用额度，单位为分")
        private Long openingBalanceCents;
    }

    @Data
    @Schema(name = "ContactReqVO", description = "供应商运营联系人")
    public static class ContactReqVO {
        /** 联系人姓名 */
        @Schema(description = "联系人姓名", example = "张三")
        private String name;
        /** 联系人手机号 */
        @Schema(description = "联系人手机号", example = "13800138000")
        private String mobile;
        /** 联系人邮箱 */
        @Schema(description = "联系人邮箱", example = "a@b.c")
        private String email;
    }

    @Data
    @Schema(description = "供应商状态变更（兼容别名）")
    public static class ChangeStatusReqVO {
        @Schema(description = "合作状态，优先于 targetStatus：ACTIVE / SUSPENDED / BLACKLIST")
        private String status;
        @Schema(description = "目标合作状态：ACTIVE / SUSPENDED / BLACKLIST")
        private String targetStatus;

        @JsonIgnore
        @Schema(hidden = true)
        public String resolvedTargetStatus() {
            return firstNonBlank(status, targetStatus);
        }

        @AssertTrue(message = "合作状态不能为空")
        @JsonIgnore
        @Schema(hidden = true)
        public boolean isTargetStatusPresent() {
            return resolvedTargetStatus() != null;
        }
    }

    @Data
    @Schema(description = "供应商部分更新")
    public static class PatchSupplierReqVO {
        @Schema(description = "合作状态：ACTIVE / SUSPENDED / BLACKLIST")
        private String status;
    }

    @Data
    @Schema(description = "批量暂停或恢复")
    public static class SupplierBatchStatusReqVO {
        @NotEmpty
        @Schema(description = "供应商永久业务编码列表")
        private List<String> supplierCodes;
        @Schema(description = "合作状态，优先于 targetStatus：仅 ACTIVE / SUSPENDED")
        private String status;
        @Schema(description = "兼容字段，status 为空时使用")
        private String targetStatus;

        @JsonIgnore
        @Schema(hidden = true)
        public String resolvedTargetStatus() {
            return firstNonBlank(status, targetStatus);
        }

        @AssertTrue(message = "合作状态不能为空")
        @JsonIgnore
        @Schema(hidden = true)
        public boolean isTargetStatusPresent() {
            return resolvedTargetStatus() != null;
        }
    }

    @Data
    @Schema(description = "全量保存供货配置")
    public static class SaveScopesReqVO {
        @NotNull
        @Schema(description = "供货配置行，三元组唯一")
        private List<ScopeReqVO> lines;
    }

    @Data
    @Schema(description = "供货配置行")
    public static class ScopeReqVO {
        @NotBlank
        @Schema(description = "可售品类枚举名")
        private String sellableCategory;
        @NotBlank
        @Schema(description = "动态商品类型编码")
        private String productType;
        @NotBlank
        @Schema(description = "商品分类：字典枚举名或类目 categoryCode")
        private String productClassification;
        @NotEmpty
        @Schema(description = "供货模式枚举名列表，不可为空")
        private List<String> supplyModes;
        @NotNull @Min(1)
        @Schema(description = "供货上限")
        private Long maxQuantity;
        @NotNull @Min(1)
        @Schema(description = "允许最低起订量，预留字段")
        private Long minQuantity;
    }

    @Data
    @Schema(description = "批量新增合作商品")
    public static class OfferItemsReqVO {
        @Schema(description = "合作商品行，优先于 lines")
        private List<DraftReqVO> items;
        @Schema(description = "兼容 batch-draft 的 lines")
        private List<DraftReqVO> lines;

        List<DraftReqVO> resolveItems() {
            if (items != null && !items.isEmpty()) {
                return items;
            }
            if (lines != null && !lines.isEmpty()) {
                return lines;
            }
            return List.of();
        }
    }

    @Data
    @Schema(description = "合作商品上架或下架")
    public static class PatchOfferReqVO {
        @NotBlank
        @Schema(description = "目标状态：仅 PUBLISHED / SUSPENDED")
        private String status;
    }

    @Data
    @Schema(description = "上架即发布（B1）请求体：前端只传可选 validTo，其余服务端派生")
    public static class ListOnShelfReqVO {
        @Schema(description = "履约版本失效时间（UTC）；空＝长期有效（k1）。须晚于 validFrom（=上架时刻）")
        private LocalDateTime validTo;
    }

    @Data
    @Schema(description = "兼容批量新增")
    public static class BatchDraftReqVO {
        @NotEmpty
        @Schema(description = "合作商品草稿行", requiredMode = Schema.RequiredMode.REQUIRED)
        private List<DraftReqVO> lines;
    }

    @Data
    @Schema(description = "合作商品行")
    public static class DraftReqVO {
        @NotBlank
        @Schema(description = "平台SKU永久业务编码")
        private String platformSkuCode;
        @NotBlank
        @Schema(description = "供应价模式（字典supply_chain_offer_price_mode）：FIXED固定值，REALTIME实时更新，DISCOUNT按供货折扣")
        private String priceMode;
        @Schema(description = "草稿供应价，CNY分；REALTIME为空")
        private Long unitPriceCents;
        @Schema(description = "折扣基点，10000=100%")
        private Integer discountBp;
        /**
         * 历史客户端提示字段；折扣准入读取SKU权威值。
         * 设计见 docs/superpowers/specs/2026-10-11-supplier-offer-sale-price-alignment-design.md#4-服务端折扣准入。
         */
        @Schema(description = "历史兼容建议价提示，CNY分，可缺省；服务端折扣准入读取SKU销售建议价")
        private Long suggestedPriceCents;
        @NotBlank
        @Schema(description = "该Offer供货模式")
        private String supplyMode;
        @Min(1)
        @Schema(description = "草稿供货上限，空则继承供货配置")
        private Long maxQuantity;
        @Min(1)
        @Schema(description = "草稿最低起购量，空则取1，不继承供货配置预留字段")
        private Long minQuantity;
        @NotBlank
        @Schema(description = "供应商外部商品编码，选合作商品时录入")
        private String externalSkuCode;
    }

    @Data
    @Schema(description = "供应商合作商品履约能力评分保存")
    public static class FulfillmentScoreSaveReqVO {
        @Min(OfferFulfillmentScore.MIN)
        @Max(OfferFulfillmentScore.MAX)
        @Schema(description = OfferFulfillmentScore.STABILITY_COMMENT, example = "80")
        private Integer stabilityScore;
        @Min(OfferFulfillmentScore.MIN)
        @Max(OfferFulfillmentScore.MAX)
        @Schema(description = OfferFulfillmentScore.TIMELINESS_COMMENT, example = "70")
        private Integer timelinessScore;
        @Schema(description = "平台SKU永久业务编码列表；省略则覆盖该供应商全部未退役 Offer",
                example = "[\"MOVIE_100\"]")
        private List<String> platformSkuCodes;
    }
}
