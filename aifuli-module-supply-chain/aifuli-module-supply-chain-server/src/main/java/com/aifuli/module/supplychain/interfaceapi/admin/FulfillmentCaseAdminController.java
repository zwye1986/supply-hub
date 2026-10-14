package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentCaseAdminService;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentCaseAdminService.CaseActionView;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentCaseAdminService.CaseDetailView;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentCaseAdminService.ConfirmFailureCommand;
import com.aifuli.module.supplychain.border.fulfillment.application.FulfillmentCaseAdminService.VerifySubmitCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.FULFILLMENT_CASE_CONFIRM_FAILURE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.FULFILLMENT_CASE_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.FULFILLMENT_CASE_VERIFY_EXPRESSION;

/**
 * 履约异常 Case 人工核实与失败关单管理端入口（两步受控命令）。
 *
 * <p>GET／命令响应 no-store；操作人取自登录态，客户端不能传 operatorId；
 * 不接受外部订单状态、金额、退款勾选或证据文件替代服务端事实。
 *
 * <p>设计：docs/superpowers/specs/2026-10-13-huasheng-error-classification-and-failure-closure-design.md#61-范围与两步命令。
 */
@Slf4j
@Tag(name = "管理后台 - 供应链履约异常Case")
@RestController
@RequestMapping("/supply-chain/fulfillment-cases")
@Validated
@RequiredArgsConstructor
public class FulfillmentCaseAdminController {

    private final FulfillmentCaseAdminService caseAdminService;

    /** 真实内部 ADMIN 身份与现有细粒度 RBAC 同时必需；响应禁止缓存。设计 §6.4、§8。 */
    private static Long actor() {
        var user = com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUser();
        if (user == null || user.getId() == null || user.getId() <= 0
                || !java.util.Objects.equals(user.getUserType(), com.aifuli.framework.common.enums.UserTypeEnum.ADMIN.getValue())) {
            throw new org.springframework.security.access.AccessDeniedException("内部运营身份必需");
        }
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                && servlet.getResponse() != null) servlet.getResponse().setHeader("Cache-Control", "no-store");
        return user.getId();
    }

    @GetMapping("/{caseNo}")
    @PreAuthorize(FULFILLMENT_CASE_QUERY_EXPRESSION)
    @Operation(summary = "查询履约异常Case详情",
            description = "只读：Case、原单状态、原因、价差与资金处置摘要及可操作条件；不外呼。")
    public CommonResult<CaseDetailView> getCase(
            @Parameter(description = "履约异常处置单编号")
            @Size(max = 32) @PathVariable String caseNo) {
        actor();
        return success(caseAdminService.getCaseDetail(caseNo));
    }

    public record VerifySubmitRequest(
            @NotBlank @Size(max = 32) String actionNo,
            @NotNull Long expectedCaseVersion,
            @NotNull Long expectedGeneration,
            @Size(max = 512) String reason) {
    }

    @PostMapping("/{caseNo}/verify")
    @PreAuthorize(FULFILLMENT_CASE_VERIFY_EXPRESSION)
    @Operation(summary = "提交一次原单核实",
            description = "HTTP 成功只表示命令已受理；异步执行恰一次 QUERY，不执行 PLACE/CONFIRM/TAKE/取消。")
    public CommonResult<CaseActionView> submitVerify(
            @Size(max = 32) @PathVariable String caseNo,
            @Valid @RequestBody VerifySubmitRequest request) {
        Long actorId = actor();
        return success(caseAdminService.submitVerify(new VerifySubmitCommand(
                caseNo, request.actionNo(), request.expectedCaseVersion(), request.expectedGeneration(),
                request.reason(), actorId)));
    }

    @GetMapping("/{caseNo}/actions/{actionNo}")
    @PreAuthorize(FULFILLMENT_CASE_QUERY_EXPRESSION)
    @Operation(summary = "查询核实任务及结果",
            description = "返回实际 caseVersion、generation、核实时间及 freshUntil，供失败确认使用。")
    public CommonResult<CaseActionView> getAction(
            @Size(max = 32) @PathVariable String caseNo,
            @Size(max = 32) @PathVariable String actionNo) {
        actor();
        return success(caseAdminService.getAction(caseNo, actionNo));
    }

    public record ConfirmFailureRequest(
            @NotBlank @Size(max = 32) String actionNo,
            @NotBlank @Size(max = 32) String verifyActionNo,
            @NotNull Long expectedCaseVersion,
            @NotNull Long expectedGeneration,
            @Size(max = 512) String reason) {
    }

    @PostMapping("/{caseNo}/confirm-failure")
    @PreAuthorize(FULFILLMENT_CASE_CONFIRM_FAILURE_EXPRESSION)
    @Operation(summary = "确认失败关单",
            description = "仅接受新鲜核实结果；服务端复核可信取消/失败/未建单判据后同事务关单并释放预留。")
    public CommonResult<CaseActionView> confirmFailure(
            @Size(max = 32) @PathVariable String caseNo,
            @Valid @RequestBody ConfirmFailureRequest request) {
        Long actorId = actor();
        return success(caseAdminService.confirmFailure(new ConfirmFailureCommand(
                caseNo, request.actionNo(), request.verifyActionNo(),
                request.expectedCaseVersion(), request.expectedGeneration(),
                request.reason(), actorId)));
    }
}
