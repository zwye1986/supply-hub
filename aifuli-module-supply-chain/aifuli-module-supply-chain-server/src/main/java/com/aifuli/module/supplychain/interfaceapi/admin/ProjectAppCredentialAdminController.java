package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAppCredentialAdminService;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAppCredentialAdminService.ApplicationCredentials;
import com.aifuli.module.supplychain.tradecore.buyer.application.ProjectAppCredentialAdminService.IssuedCredential;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
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
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PROJECT_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.PROJECT_UPDATE_EXPRESSION;

/**
 * 项目应用凭证管理入口。发码响应一次性回显 appKey 且禁止 HTTP 缓存；列表只含脱敏提示。
 * 设计见 docs/superpowers/specs/2026-09-29-project-app-issuance-platform-signing-callback-design.md#3-项目应用管理。
 */
@Tag(name = "管理后台 - 项目应用凭证")
@RestController
@RequestMapping("/supply-chain/projects/{projectCode}/application")
@Validated
@RequiredArgsConstructor
public class ProjectAppCredentialAdminController {

    private final ProjectAppCredentialAdminService service;

    @PostMapping("/enable")
    @PreAuthorize(PROJECT_UPDATE_EXPRESSION)
    @Operation(summary = "开通项目应用并一次性获取 appKey")
    public ResponseEntity<CommonResult<IssuedCredential>> enable(@PathVariable String projectCode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(success(service.enable(projectCode, getLoginUserId())));
    }

    @PostMapping("/rotate")
    @PreAuthorize(PROJECT_UPDATE_EXPRESSION)
    @Operation(summary = "轮换项目 appKey 并一次性获取新密钥")
    public ResponseEntity<CommonResult<IssuedCredential>> rotate(@PathVariable String projectCode) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(success(service.rotate(projectCode, getLoginUserId())));
    }

    @PostMapping("/revoke")
    @PreAuthorize(PROJECT_UPDATE_EXPRESSION)
    @Operation(summary = "吊销项目全部活动 appKey")
    public CommonResult<Boolean> revoke(@PathVariable String projectCode,
                                        @Valid @RequestBody RevokeRequest request) {
        service.revoke(projectCode, request.confirmed(), request.reason(), getLoginUserId());
        return success(true);
    }

    @GetMapping("/credentials")
    @PreAuthorize(PROJECT_QUERY_EXPRESSION)
    @Operation(summary = "查询项目应用与脱敏凭证版本")
    public CommonResult<ApplicationCredentials> credentials(@PathVariable String projectCode) {
        return success(service.listCredentials(projectCode));
    }

    /** 吊销须由管理员显式确认并说明原因。 */
    public record RevokeRequest(boolean confirmed, @NotBlank String reason) {
    }
}
