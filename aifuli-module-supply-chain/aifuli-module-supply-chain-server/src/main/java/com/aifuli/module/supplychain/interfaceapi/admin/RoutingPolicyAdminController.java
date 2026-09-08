package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.util.monitor.TracerUtils;
import com.aifuli.module.supplychain.routing.application.RoutingPolicyPublicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ADMIN_EXPRESSION;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - 供应链供应商路由策略")
@RestController
@RequestMapping("/supply-chain/routing-policies")
@Validated
@RequiredArgsConstructor
@PreAuthorize(ADMIN_EXPRESSION)
public class RoutingPolicyAdminController {

    private final RoutingPolicyPublicationService publicationService;

    @PostMapping("/drafts")
    @Operation(summary = "创建标准商品路由策略不可变草稿")
    public CommonResult<RoutingPolicyPublicationService.DraftResult> createDraft(
            @Valid @RequestBody CreateDraftReqVO request) {
        List<RoutingPolicyPublicationService.RuleDraft> rules = request.getRules().stream()
                .map(rule -> new RoutingPolicyPublicationService.RuleDraft(
                        rule.getRuleCode(), rule.getRuleName(), rule.getBuyerCode(),
                        rule.getPlatformSkuCode(), rule.getFulfillmentType(), rule.getRulePriority(),
                        rule.getCandidates().stream().map(candidate ->
                                new RoutingPolicyPublicationService.CandidateDraft(
                                        candidate.getSupplierCode(), candidate.getCandidatePriority(),
                                        candidate.getInventoryCheckMode(), candidate.getInventoryMaxAgeSeconds(),
                                        candidate.getBalanceCheckMode(), candidate.getBalanceMaxAgeSeconds(),
                                        candidate.getHealthCheckMode(), candidate.getHealthMaxAgeSeconds()))
                                .toList()))
                .toList();
        return success(publicationService.createDraft(
                new RoutingPolicyPublicationService.CreateDraftCommand(
                        request.getPolicyCode(), request.getPolicyName(), request.getVersionName(),
                        request.getValidFrom(), request.getValidTo(), rules, getLoginUserId())));
    }

    @PostMapping("/versions/{versionId}/publish")
    @Operation(summary = "发布标准商品路由策略版本")
    public CommonResult<RoutingPolicyPublicationService.PublishResult> publish(
            @PathVariable Long versionId, @Valid @RequestBody PublishReqVO request) {
        return success(publicationService.publish(new RoutingPolicyPublicationService.PublishCommand(
                versionId, request.getExpectedVersion(), request.getPublishReason(), getLoginUserId(),
                traceId(), null, null)));
    }

    private static String traceId() {
        String traceId = TracerUtils.getTraceId();
        return traceId == null || traceId.isBlank() ? "admin-" + UUID.randomUUID() : traceId;
    }

    @Data
    @Schema(description = "标准商品路由策略草稿")
    public static class CreateDraftReqVO {
        @NotBlank @Size(max = 64) private String policyCode;
        @NotBlank @Size(max = 128) private String policyName;
        @NotBlank @Size(max = 128) private String versionName;
        @NotNull private LocalDateTime validFrom;
        private LocalDateTime validTo;
        @Valid @NotEmpty private List<RuleReqVO> rules;
    }

    @Data
    public static class RuleReqVO {
        @NotBlank @Size(max = 64) private String ruleCode;
        @NotBlank @Size(max = 128) private String ruleName;
        @NotBlank @Size(max = 32) private String buyerCode;
        @NotBlank @Size(max = 32) private String platformSkuCode;
        @NotBlank @Pattern(regexp = "DIRECT_RECHARGE|VOUCHER_CODE|CARD_CODE_SECRET|VOUCHER_URL|ACCOUNT_BOUND_BENEFIT")
        private String fulfillmentType;
        @Min(0) private int rulePriority;
        @Valid @NotEmpty private List<CandidateReqVO> candidates;
    }

    @Data
    public static class CandidateReqVO {
        @NotBlank @Size(max = 32) private String supplierCode;
        @Min(0) private int candidatePriority;
        @NotBlank @Pattern(regexp = "REQUIRED|OPTIONAL|UNSUPPORTED") private String inventoryCheckMode;
        private Integer inventoryMaxAgeSeconds;
        @NotBlank @Pattern(regexp = "REQUIRED|OPTIONAL|UNSUPPORTED") private String balanceCheckMode;
        private Integer balanceMaxAgeSeconds;
        @NotBlank @Pattern(regexp = "REQUIRED|OPTIONAL|UNSUPPORTED") private String healthCheckMode;
        private Integer healthMaxAgeSeconds;
    }

    @Data
    public static class PublishReqVO {
        @Min(0) private long expectedVersion;
        @NotBlank @Size(max = 512) private String publishReason;
    }
}
