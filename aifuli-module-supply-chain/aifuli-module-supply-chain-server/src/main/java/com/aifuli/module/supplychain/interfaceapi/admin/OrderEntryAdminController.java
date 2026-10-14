package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.apilog.core.annotation.ApiAccessLog;
import com.aifuli.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.api.auth.ProjectAuthOperations;
import com.aifuli.module.supplychain.api.fulfillment.dto.ProjectStandardOrderCreateRespDTO;
import com.aifuli.module.supplychain.application.facade.ProjectStandardOrderCommandFacade;
import com.aifuli.module.supplychain.interfaceapi.open.project.ProjectStandardHttpRequestParser;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver;
import com.aifuli.module.supplychain.tradecore.buyer.application.BuyerProjectAccessResolver.ProjectSnapshot;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminQueryService;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminQueryService.ProjectListItem;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAdminQueryService.ProjectPageQuery;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectCapabilityAdminService;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectCapabilityAdminService.CapabilityState;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProjectStatus;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProjectListingQueryService.ProjectListingListItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.BUYER_AUTH_INVALID;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ORDER_CREATE_EXPRESSION;

/**
 * 管理员真实标准订单录入：RBAC 替代应用认证，项目归属来自服务端，严格 v2 正文复用共享受理门面。
 * 候选查询不代表最终可售，不放宽项目管理权限，不返回应用凭证、不记录履约目标正文。
 * 设计见 docs/superpowers/specs/2026-10-14-admin-order-entry-design.md#3-http-契约。
 */
@Slf4j
@RestController
@RequestMapping("/supply-chain/orders/entry/projects")
@Tag(name = "管理后台 - 标准订单录入")
@Validated
@RequiredArgsConstructor
public class OrderEntryAdminController {
    private final ProjectAdminQueryService projectQueries;
    private final ProjectListingQueryService listingQueries;
    private final BuyerProjectAccessResolver projectResolver;
    private final ProjectCapabilityAdminService capabilities;
    private final ProjectStandardHttpRequestParser parser;
    private final ProjectStandardOrderCommandFacade commands;

    @GetMapping
    @PreAuthorize(ORDER_CREATE_EXPRESSION)
    @Operation(summary = "分页查询启用的录入候选项目")
    public CommonResult<PageResult<ProjectListItem>> projects(
            @Size(max = 128) @RequestParam(required = false) String projectName,
            @Min(1) @RequestParam(required = false) Integer pageNo,
            @Min(1) @Max(100) @RequestParam(required = false) Integer pageSize) {
        return success(projectQueries.page(new ProjectPageQuery(projectName, null,
                List.of(BuyerProjectStatus.ACTIVE), null, null, null, null, pageNo, pageSize)));
    }

    @GetMapping("/{projectCode}/products")
    @PreAuthorize(ORDER_CREATE_EXPRESSION)
    @Operation(summary = "分页查询项目关联商品与真实目标 Schema")
    public CommonResult<PageResult<ProjectListingListItem>> products(
            @PathVariable String projectCode,
            @Min(1) @RequestParam(required = false) Integer pageNo,
            @Min(1) @Max(100) @RequestParam(required = false) Integer pageSize) {
        requireEntryProject(projectCode);
        return success(listingQueries.page(projectCode, pageNo, pageSize));
    }

    /**
     * 原字节严格解析后才进入真实资金/路由受理；返回只表示回执结局，不将 ACCEPTED 宣称履约成功。
     * 异常交给全局安全边界，不在入口重复打印失败摘要或堆栈，见设计 §2、§3。
     */
    @PostMapping(value = "/{projectCode}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(ORDER_CREATE_EXPRESSION)
    @ApiAccessLog(requestEnable = false, responseEnable = false)
    @Operation(summary = "录入真实单件标准订单")
    public CommonResult<ProjectStandardOrderCreateRespDTO> create(@PathVariable String projectCode,
            HttpServletRequest httpRequest, @RequestBody byte[] rawBody) {
        if (httpRequest.getQueryString() != null) {
            throw exception(GlobalErrorCodeConstants.BAD_REQUEST);
        }
        var request = parser.parseCreateV2(rawBody);
        ProjectSnapshot project = requireEntryProject(projectCode);
        log.info("[adminOrderEntry][管理员提交真实标准订单] adminId={}, projectCode={}, buyerOrderNo={}, platformSkuCode={}",
                getLoginUserId(), project.projectCode(), request.getBuyerOrderNo(), request.getPlatformSkuCode());
        var response = commands.createFromAdmin(project, request);
        log.info("[adminOrderEntryResult][管理员录入返回受理回执] adminId={}, projectCode={}, buyerOrderNo={}, receiptStatus={}, receiptVersion={}, orderNo={}, idempotentReplay={}",
                getLoginUserId(), project.projectCode(), response.getBuyerOrderNo(), response.getReceiptStatus(),
                response.getReceiptVersion(), response.getOrderNo(), response.getIdempotentReplay());
        return success(response);
    }

    /** 只检查业务能力，不构造假 appId/keyVersion，也不依赖商品分类推测准入。设计见 §2。 */
    private ProjectSnapshot requireEntryProject(String projectCode) {
        ProjectSnapshot project = projectResolver.requireActiveByCode(projectCode);
        boolean enabled = capabilities.list(project.projectCode()).stream().anyMatch(capability ->
                ProjectAuthOperations.CAPABILITY_STANDARD_PRODUCT.equals(capability.capabilityCode())
                        && CapabilityState.ENABLED.name().equals(capability.status()));
        if (!enabled) { throw exception(BUYER_AUTH_INVALID); }
        return project;
    }
}
