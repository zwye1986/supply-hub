package com.aifuli.module.supplychain.interfaceapi.admin;

import com.aifuli.framework.common.pojo.CommonResult;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryQueryService;
import com.aifuli.module.supplychain.tradecore.catalog.application.CategoryQueryService.CategoryNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.aifuli.framework.common.pojo.CommonResult.success;
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

    @GetMapping
    @PreAuthorize(READ_OPTIONS_EXPRESSION)
    @Operation(summary = "启用类目树",
            description = "返回已启用类目树，子节点嵌在 children。可售品类为一级 id，商品类型为该品类直接子，"
                    + "商品分类为该类型直接子。身份用类目内部主键。不要调用 /tree。")
    public CommonResult<List<CategoryNode>> tree() {
        return success(queryService.tree());
    }
}
