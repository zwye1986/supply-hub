package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryCommandService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryQueryService.CategoryDetail;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryQueryService.CategoryNode;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryQueryService.ManagementCategoryNode;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.aifuli.framework.common.pojo.CommonResult.success;
import static com.aifuli.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.CATEGORY_CREATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.CATEGORY_DELETE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.CATEGORY_QUERY_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.CATEGORY_UPDATE_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.CATEGORY_UPDATE_STATUS_EXPRESSION;
import static com.aifuli.module.supplychain.interfaceapi.admin.SupplyChainAdminPermissions.READ_OPTIONS_EXPRESSION;

/**
 * 管理端启用类目树。集合 GET，禁止 {@code /tree}。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#43-类目树}。
 */
@Tag(name = "管理后台 - 供应链类目")
@RestController
@RequestMapping("/supply-chain/categories")
@Validated
@RequiredArgsConstructor
public class CategoryAdminController {

    private final CategoryQueryService queryService;
    private final CategoryCommandService commandService;

    @GetMapping
    @PreAuthorize(READ_OPTIONS_EXPRESSION)
    @Operation(summary = "启用类目树",
            description = "返回已启用类目树，子节点嵌在 children。可售品类为一级 id，商品类型为该品类直接子，"
                    + "商品分类为该类型直接子。身份用类目内部主键。不要调用 /tree。")
    public CommonResult<List<CategoryNode>> tree() {
        return success(queryService.tree());
    }

    @GetMapping("/management-tree")
    @PreAuthorize(CATEGORY_QUERY_EXPRESSION)
    @Operation(summary = "查询商品分类管理树",
            description = "包含草稿、停用节点、显示状态和关联商品数。支持状态与关键字筛选，采用树枝保留模式保留命中节点祖先链路。")
    public CommonResult<List<ManagementCategoryNode>> managementTree(
            @Parameter(description = "分类编码或名称模糊搜索", example = "电子")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "分类状态：0草稿，10启用，20停用", example = "10")
            @RequestParam(required = false) Short status) {
        return success(queryService.managementTree(keyword, status));
    }

    @GetMapping("/{categoryCode}")
    @PreAuthorize(CATEGORY_QUERY_EXPRESSION)
    @Operation(summary = "查询商品分类详情")
    public CommonResult<CategoryDetail> detail(
            @Parameter(description = "平台类目永久编码", example = "CAT_01ABC")
            @PathVariable String categoryCode) {
        return success(queryService.detail(categoryCode));
    }

    @PostMapping
    @PreAuthorize(CATEGORY_CREATE_EXPRESSION)
    @Operation(summary = "创建商品分类", description = "categoryCode 为空时生成 CAT_+ULID；同一父级下名称唯一。")
    public CommonResult<String> create(@Valid @RequestBody CategorySaveReqVO request) {
        return success(commandService.create(new CategoryCommandService.CreateCommand(request.categoryCode,
                request.categoryName, request.parentCategoryCode, request.sort, request.visible, request.status,
                getLoginUserId())));
    }

    @PutMapping("/{categoryCode}")
    @PreAuthorize(CATEGORY_UPDATE_EXPRESSION)
    @Operation(summary = "更新商品分类",
            description = "parentCategoryCode 三态：不传表示不变更父级，空字符串表示挂到根，非空编码表示挂到该父级。"
                    + "仅在实际变更父级时防循环并递归重算整棵子树层级。")
    public CommonResult<Boolean> update(
            @Parameter(description = "平台类目永久编码", example = "CAT_01ABC")
            @PathVariable String categoryCode,
            @Valid @RequestBody CategorySaveReqVO request) {
        commandService.update(categoryCode, new CategoryCommandService.UpdateCommand(request.categoryName,
                request.parentCategoryCode, request.sort, request.visible, getLoginUserId()));
        return success(true);
    }

    @PutMapping("/{categoryCode}/status")
    @PreAuthorize(CATEGORY_UPDATE_STATUS_EXPRESSION)
    @Operation(summary = "变更商品分类状态")
    public CommonResult<Boolean> status(
            @Parameter(description = "平台类目永久编码", example = "CAT_01ABC")
            @PathVariable String categoryCode,
            @Valid @RequestBody CatalogStatusSwitchReqVO request) {
        commandService.changeStatus(categoryCode, request.requireSwitchTarget(), getLoginUserId());
        return success(true);
    }

    @DeleteMapping("/{categoryCode}")
    @PreAuthorize(CATEGORY_DELETE_EXPRESSION)
    @Operation(summary = "删除商品分类", description = "存在子分类或未归档 SPU 引用时拒绝删除。")
    public CommonResult<Boolean> delete(
            @Parameter(description = "平台类目永久编码", example = "CAT_01ABC")
            @PathVariable String categoryCode) {
        commandService.delete(categoryCode, getLoginUserId());
        return success(true);
    }

    @Data
    @Schema(description = "商品分类保存请求")
    public static class CategorySaveReqVO {
        @Size(max = 32) @Schema(description = "分类编码；创建时可空") private String categoryCode;
        @NotBlank @Size(max = 128) @Schema(description = "分类名称") private String categoryName;
        @Size(max = 32)
        @Schema(description = "父分类编码。创建时为空表示根分类；更新时三态：不传或 null 表示不变更父级，"
                + "空字符串表示显式挂到根，非空编码表示挂到该父级")
        private String parentCategoryCode;
        @Schema(description = "同级排序") private Integer sort;
        @Schema(description = "前端展示可见性：1显示，0隐藏") private Short visible;
        @Schema(description = "创建状态：0草稿，10启用") private Short status;
    }
}
