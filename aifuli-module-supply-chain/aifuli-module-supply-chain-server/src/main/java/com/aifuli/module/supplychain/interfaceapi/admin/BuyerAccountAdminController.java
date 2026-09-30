package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.util.monitor.TracerUtils;
import com.aifuli.module.supplychain.tradecore.account.application.BuyerAccountOpeningService;
import com.aifuli.module.supplychain.tradecore.account.domain.Money;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HexFormat;
import java.util.UUID;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ADMIN_EXPRESSION;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

@Tag(name = "管理后台 - 供应链采购商资金账户")
@RestController
@RequestMapping("/supply-chain/buyer-accounts")
@Validated
@RequiredArgsConstructor
@PreAuthorize(ADMIN_EXPRESSION)
public class BuyerAccountAdminController {

    private final BuyerAccountOpeningService openingService;

    @PostMapping("/opening-balance")
    @Operation(summary = "一次性创建采购商 CNY 账户和期初余额",
            description = "M1 不提供普通入金接口；同一采购商只能成功执行一次")
    public CommonResult<BuyerAccountOpeningService.AccountSnapshot> open(
            @Valid @RequestBody OpeningBalanceReqVO request) {
        return success(openingService.open(new BuyerAccountOpeningService.OpeningBalanceCommand(
                request.getBuyerCode(), Money.cny(request.getInitialBalance()), getLoginUserId(),
                request.getEvidenceRef(), HexFormat.of().parseHex(request.getEvidenceDigest()),
                request.getAuditNote(), traceId(), null)));
    }

    private static String traceId() {
        String traceId = TracerUtils.getTraceId();
        return traceId == null || traceId.isBlank() ? "admin-" + UUID.randomUUID() : traceId;
    }

    @Data
    @Schema(description = "采购商账户期初余额请求")
    public static class OpeningBalanceReqVO {
        @NotBlank @Size(max = 32) private String buyerCode;
        @Min(0) @Schema(description = "期初余额，单位 CNY 分；一期允许为 0") private long initialBalance;
        @NotBlank @Size(max = 512) @Schema(description = "受控审批或财务依据引用，不保存原文件")
        private String evidenceRef;
        @NotBlank @Pattern(regexp = "[0-9a-fA-F]{64}")
        @Schema(description = "依据文件或记录的 SHA-256 十六进制摘要")
        private String evidenceDigest;
        @NotBlank @Size(max = 1024) @Schema(description = "脱敏审计说明")
        private String auditNote;
    }
}
