package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.framework.common.pojo.PageResult;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductTypeAdminService;
import com.aifuli.module.supplychain.tradecore.catalog.application.ProductTypeAdminService.ProductTypeView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

/** 数据驱动的商品类型管理接口，不承载电影、点餐等类型专属业务逻辑。 */
@Tag(name = "管理后台 - 供应链商品类型")
@RestController
@RequestMapping("/supply-chain/product-types")
@Validated
@RequiredArgsConstructor
public class ProductTypeAdminController {

    private final ProductTypeAdminService service;

    @GetMapping
    @PreAuthorize(PRODUCT_TYPE_QUERY_EXPRESSION)
    @Operation(summary = "分页查询商品类型", description = "用于后台卡片和商品列表动态 Tab。")
    public CommonResult<PageResult<ProductTypeView>> page(
            @Parameter(description = "商品类型编码或名称模糊搜索", example = "权益")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "商品类型状态：0草稿，10启用，20停用", example = "10")
            @RequestParam(required = false) Short status,
            @Parameter(description = "页码，从1开始", example = "1") @RequestParam(required = false) Integer pageNo,
            @Parameter(description = "每页条数，默认20", example = "20") @RequestParam(required = false) Integer pageSize) {
        return success(service.page(keyword, status, pageNo, pageSize));
    }

    @GetMapping("/{typeCode}")
    @PreAuthorize(PRODUCT_TYPE_QUERY_EXPRESSION)
    @Operation(summary = "查询商品类型详情")
    public CommonResult<ProductTypeView> detail(
            @Parameter(description = "商品类型永久业务编码", example = "PT_01ABC") @PathVariable String typeCode) {
        return success(service.detail(typeCode));
    }

    @PostMapping
    @PreAuthorize(PRODUCT_TYPE_CREATE_EXPRESSION)
    @Operation(summary = "创建商品类型", description = "typeCode 为空时生成 PT_+ULID。")
    public CommonResult<String> create(@Valid @RequestBody ProductTypeSaveReqVO request) {
        return success(service.create(new ProductTypeAdminService.CreateCommand(request.typeCode, request.typeName,
                request.sort, request.remark, getLoginUserId())));
    }

    @PutMapping("/{typeCode}")
    @PreAuthorize(PRODUCT_TYPE_UPDATE_EXPRESSION)
    @Operation(summary = "更新商品类型")
    public CommonResult<Boolean> update(
            @Parameter(description = "商品类型永久业务编码", example = "PT_01ABC") @PathVariable String typeCode,
            @Valid @RequestBody ProductTypeSaveReqVO request) {
        service.update(typeCode, new ProductTypeAdminService.UpdateCommand(request.typeName, request.sort,
                request.remark, getLoginUserId()));
        return success(true);
    }

    @PutMapping("/{typeCode}/status")
    @PreAuthorize(PRODUCT_TYPE_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "启用或停用商品类型", description = "停用仅阻止新增或启用商品，不影响历史商品读取。")
    public CommonResult<Boolean> status(
            @Parameter(description = "商品类型永久业务编码", example = "PT_01ABC") @PathVariable String typeCode,
            @Valid @RequestBody CatalogStatusSwitchReqVO request) {
        service.changeStatus(typeCode, request.requireSwitchTarget(), getLoginUserId());
        return success(true);
    }

    @DeleteMapping("/{typeCode}")
    @PreAuthorize(PRODUCT_TYPE_DELETE_EXPRESSION)
    @Operation(summary = "删除商品类型", description = "存在未归档 SPU 引用时拒绝删除。")
    public CommonResult<Boolean> delete(
            @Parameter(description = "商品类型永久业务编码", example = "PT_01ABC") @PathVariable String typeCode) {
        service.delete(typeCode, getLoginUserId());
        return success(true);
    }

    @Data
    @Schema(description = "商品类型保存请求")
    public static class ProductTypeSaveReqVO {
        @Size(max = 32) @Schema(description = "商品类型编码；创建时可空") private String typeCode;
        @NotBlank @Size(max = 128) @Schema(description = "商品类型名称") private String typeName;
        @Schema(description = "排序") private Integer sort;
        @Size(max = 512) @Schema(description = "备注") private String remark;
    }
}
