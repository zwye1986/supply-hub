package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.catalog.application.BrandAdminService;
import com.aifuli.module.supplychain.tradecore.catalog.application.BrandAdminService.BrandView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.*;

/** 商品中心品牌管理接口。设计见产品中心详细设计 4.3 节。 */
@Tag(name = "管理后台 - 供应链商品品牌")
@RestController
@RequestMapping("/supply-chain/brands")
@Validated
@RequiredArgsConstructor
public class BrandAdminController {

    private final BrandAdminService service;

    @GetMapping
    @PreAuthorize(BRAND_QUERY_EXPRESSION)
    @Operation(summary = "分页查询商品品牌")
    public CommonResult<PageResult<BrandView>> page(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) Short status,
                                                     @RequestParam(required = false) Integer pageNo,
                                                     @RequestParam(required = false) Integer pageSize) {
        return success(service.page(keyword, status, pageNo, pageSize));
    }

    @GetMapping("/{brandCode}")
    @PreAuthorize(BRAND_QUERY_EXPRESSION)
    @Operation(summary = "查询商品品牌详情")
    public CommonResult<BrandView> detail(@PathVariable String brandCode) {
        return success(service.detail(brandCode));
    }

    @PostMapping
    @PreAuthorize(BRAND_CREATE_EXPRESSION)
    @Operation(summary = "创建商品品牌", description = "brandCode 为空时生成 BRD_+ULID；品牌名称全局唯一。")
    public CommonResult<String> create(@Valid @RequestBody BrandSaveReqVO request) {
        return success(service.create(new BrandAdminService.CreateCommand(request.brandCode, request.brandName,
                request.englishName, request.logoUrl, request.sort, request.status, getLoginUserId())));
    }

    @PutMapping("/{brandCode}")
    @PreAuthorize(BRAND_UPDATE_EXPRESSION)
    @Operation(summary = "更新商品品牌")
    public CommonResult<Boolean> update(@PathVariable String brandCode,
                                         @Valid @RequestBody BrandSaveReqVO request) {
        service.update(brandCode, new BrandAdminService.UpdateCommand(request.brandName, request.englishName,
                request.logoUrl, request.sort, getLoginUserId()));
        return success(true);
    }

    @PutMapping("/{brandCode}/status")
    @PreAuthorize(BRAND_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "变更商品品牌状态")
    public CommonResult<Boolean> status(@PathVariable String brandCode,
                                        @Valid @RequestBody CatalogStatusSwitchReqVO request) {
        service.changeStatus(brandCode, request.requireSwitchTarget(), getLoginUserId());
        return success(true);
    }

    @DeleteMapping("/{brandCode}")
    @PreAuthorize(BRAND_DELETE_EXPRESSION)
    @Operation(summary = "删除商品品牌", description = "存在未归档 SPU 引用时拒绝删除。")
    public CommonResult<Boolean> delete(@PathVariable String brandCode) {
        service.delete(brandCode, getLoginUserId());
        return success(true);
    }

    @Data
    @Schema(description = "品牌保存请求")
    public static class BrandSaveReqVO {
        @Size(max = 32) @Schema(description = "品牌编码；创建时可空") private String brandCode;
        @NotBlank @Size(max = 128) @Schema(description = "品牌名称") private String brandName;
        @Size(max = 128) @Schema(description = "品牌英文名称") private String englishName;
        @Size(max = 512) @Schema(description = "品牌 Logo 地址") private String logoUrl;
        @Schema(description = "同级排序") private Integer sort;
        @Schema(description = "创建状态：0草稿，10启用") private Short status;
    }
}
