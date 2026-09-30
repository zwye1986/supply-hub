package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.tradecore.supply.application.SupplierDailyQuotaAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.ADMIN_EXPRESSION;

/**
 * 供应商 SKU 自然日累计供货配额管理端入口；无配置表示无限额。
 * 设计见 {@code docs/superpowers/specs/2026-09-24-standard-order-routing-execution-design.md#4-每日累计配额与并发}。
 */
@Tag(name = "管理后台 - 供应商自然日供货配额")
@RestController
@RequestMapping("/supply-chain/supplier-config/daily-quotas")
@Validated
@RequiredArgsConstructor
public class SupplierDailyQuotaAdminController {

    private final SupplierDailyQuotaAdminService service;

    @GetMapping
    @PreAuthorize(ADMIN_EXPRESSION)
    @Operation(summary = "查询供应商 SKU 自然日累计供货配额")
    public CommonResult<SupplierDailyQuotaAdminService.QuotaView> get(
            @RequestParam String supplierCode, @RequestParam String skuCode) {
        return success(service.get(supplierCode, skuCode));
    }

    @PutMapping
    @PreAuthorize(ADMIN_EXPRESSION)
    @Operation(summary = "设置供应商 SKU 自然日累计供货配额")
    public CommonResult<SupplierDailyQuotaAdminService.QuotaView> put(
            @Valid @RequestBody PutQuotaReqVO request) {
        return success(service.put(request.getSupplierCode(), request.getSkuCode(),
                request.getDailyLimit(), getLoginUserId()));
    }

    @DeleteMapping
    @PreAuthorize(ADMIN_EXPRESSION)
    @Operation(summary = "删除供应商 SKU 自然日累计供货配额，恢复无限额")
    public CommonResult<Boolean> delete(@RequestParam String supplierCode,
                                        @RequestParam String skuCode) {
        service.delete(supplierCode, skuCode, getLoginUserId());
        return success(true);
    }

    @Data
    public static class PutQuotaReqVO {
        @NotBlank @Schema(description = "供应商永久业务编码") private String supplierCode;
        @NotBlank @Schema(description = "平台SKU永久业务编码") private String skuCode;
        @Min(1) @Schema(description = "供应商对该平台SKU的自然日累计供货数量上限")
        private long dailyLimit;
    }
}
