package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.*;
import com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaAdminService;
import com.aifuli.module.supplychain.tradecore.supply.application.MerchantQuotaAdminService.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.*;

/**
 * 商户共享额度管理入口：身份不可变、登录操作人不可伪造，查询/创建/调整分别授权。
 * 设计：docs/superpowers/specs/2026-10-19-merchant-quota-admin-design.md#3-管理接口。
 */
@RestController @RequestMapping("/supply-chain/merchant-quotas") @Validated @RequiredArgsConstructor
@Tag(name = "管理后台 - 商户共享额度")
public class MerchantQuotaAdminController {
    private final MerchantQuotaAdminService service;

    @GetMapping @PreAuthorize(MERCHANT_QUOTA_QUERY_EXPRESSION)
    @Operation(summary = "分页查询商户额度，平台与账号精确匹配")
    public CommonResult<PageResult<QuotaView>> page(@RequestParam(required = false) String platformCode,
            @Size(max = 128) @RequestParam(required = false) String merchantAccount,
            @Min(1) @RequestParam(defaultValue = "1") int pageNo,
            @Min(1) @Max(100) @RequestParam(defaultValue = "10") int pageSize) {
        return success(service.page(platformCode, merchantAccount, pageNo, pageSize));
    }

    @GetMapping("/{id}") @PreAuthorize(MERCHANT_QUOTA_QUERY_EXPRESSION)
    @Operation(summary = "查询商户额度详情")
    public CommonResult<QuotaView> get(@PathVariable long id) { return success(service.get(id)); }

    @PostMapping @PreAuthorize(MERCHANT_QUOTA_CREATE_EXPRESSION)
    @Operation(summary = "创建商户共享额度，不提供生产默认值")
    public CommonResult<ChangeView> create(@Valid @RequestBody CreateRequest input) {
        return success(service.create(input.platformCode(), input.merchantAccount(), input.rateLimitPerSecond(), input.concurrencyLimit(), input.reason(), getLoginUserId()));
    }

    @PutMapping("/{id}") @PreAuthorize(MERCHANT_QUOTA_UPDATE_EXPRESSION)
    @Operation(summary = "按版本调整商户额度，不重置已有占用")
    public CommonResult<ChangeView> update(@PathVariable long id, @Valid @RequestBody UpdateRequest input) {
        return success(service.update(id, input.rateLimitPerSecond(), input.concurrencyLimit(), input.expectedVersion(), input.reason(), getLoginUserId()));
    }

    @GetMapping("/{id}/history") @PreAuthorize(MERCHANT_QUOTA_QUERY_EXPRESSION)
    @Operation(summary = "分页查询真实配置历史，不补造存量历史")
    public CommonResult<HistoryView> history(@PathVariable long id,
            @Min(1) @RequestParam(defaultValue = "1") int pageNo,
            @Min(1) @Max(100) @RequestParam(defaultValue = "10") int pageSize) {
        return success(service.history(id, pageNo, pageSize));
    }

    /** 无operatorId、expectedVersion字段：创建身份只接受平台与精确账号。 */
    public record CreateRequest(/** 供应商平台码 */ @NotBlank String platformCode,
                                /** 非秘密供应商商户账号，原值精确匹配 */ @NotBlank @Size(max = 128) String merchantAccount,
                                /** 每秒成功准入上限 */ @NotNull @Min(1) Integer rateLimitPerSecond,
                                /** 同步调用并发占用上限 */ @NotNull @Min(1) Integer concurrencyLimit,
                                /** 配置变更原因 */ @NotBlank @Size(max = 255) String reason) { }
    /** 无身份与操作人字段，防止调额请求顺带迁移商户身份。 */
    public record UpdateRequest(/** 每秒成功准入上限 */ @NotNull @Min(1) Integer rateLimitPerSecond,
                                /** 同步调用并发占用上限 */ @NotNull @Min(1) Integer concurrencyLimit,
                                /** 乐观锁版本号 */ @NotNull @Min(0) Long expectedVersion,
                                /** 配置变更原因 */ @NotBlank @Size(max = 255) String reason) { }
}
