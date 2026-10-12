package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectCapabilityAdminService;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectCapabilityAdminService.CapabilityView;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectCapabilityAdminService.ChangeResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.*;

/**
 * 项目接入能力管理；只有标准商品写端点，操作人取登录态，复用项目查询/编辑权限。
 * 见 docs/superpowers/specs/2026-10-12-project-standard-product-capability-design.md#3-http-合同。
 */
@Tag(name = "管理后台 - 项目接入能力")
@RestController
@RequestMapping("/supply-chain/projects/{projectCode}/capabilities")
@Validated
@RequiredArgsConstructor
public class ProjectCapabilityAdminController {
    private final ProjectCapabilityAdminService service;

    @GetMapping
    @PreAuthorize(PROJECT_QUERY_EXPRESSION)
    @Operation(summary = "查询项目业务接入能力")
    public CommonResult<List<CapabilityView>> list(@PathVariable String projectCode) {
        return success(service.list(projectCode));
    }

    @PostMapping("/standard-product/enable")
    @PreAuthorize(PROJECT_UPDATE_EXPRESSION)
    @Operation(summary = "开启项目标准商品接入能力")
    public CommonResult<ChangeResult> enable(@PathVariable String projectCode, @Valid @RequestBody EnableRequest request) {
        return success(service.enable(projectCode, request.expectedVersion(), getLoginUserId()));
    }

    @PostMapping("/standard-product/disable")
    @PreAuthorize(PROJECT_UPDATE_EXPRESSION)
    @Operation(summary = "关闭项目标准商品下单与卡密访问，保留订单摘要查询")
    public CommonResult<ChangeResult> disable(@PathVariable String projectCode, @Valid @RequestBody DisableRequest request) {
        return success(service.disable(projectCode, request.expectedVersion(), request.confirmed(), request.reason(), getLoginUserId()));
    }

    /** expectedVersion=null 为从未开通，不接受客户端传操作人。 */
    public record EnableRequest(@PositiveOrZero Long expectedVersion) { }
    /** 关闭原因；开启时为空（对应审计 DDL reason），显式确认不能由前端默认状态代替。 */
    public record DisableRequest(@PositiveOrZero Long expectedVersion, boolean confirmed,
                                 @NotBlank @Size(max = 256) String reason) { }
}
