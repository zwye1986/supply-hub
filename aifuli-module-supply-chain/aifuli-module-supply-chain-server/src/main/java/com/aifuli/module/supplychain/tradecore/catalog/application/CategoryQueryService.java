package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端启用类目树查询。子节点嵌在 {@code children}，不提供 {@code /tree} 路径。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#43-类目树}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryQueryService {

    private final ProductCategoryMapper categoryMapper;

    /**
     * 查询全部启用类目，按 sort、id 排序后在内存组装树。
     */
    public List<CategoryNode> tree() {
        List<ProductCategoryDO> rows = categoryMapper.selectList(new LambdaQueryWrapper<ProductCategoryDO>()
                .eq(ProductCategoryDO::getStatus, CatalogEntityStatus.ACTIVE.getCode())
                .orderByAsc(ProductCategoryDO::getSort)
                .orderByAsc(ProductCategoryDO::getId));
        log.info("查询启用类目树 rowCount={}", rows.size());
        Map<Long, CategoryNode> byId = new LinkedHashMap<>();
        for (ProductCategoryDO row : rows) {
            byId.put(row.getId(), new CategoryNode(
                    row.getId(),
                    row.getCategoryCode(),
                    row.getCategoryName(),
                    row.getCategoryLevel(),
                    row.getParentId(),
                    new ArrayList<>()));
        }
        List<CategoryNode> roots = new ArrayList<>();
        for (CategoryNode node : byId.values()) {
            if (node.parentId() == null || !byId.containsKey(node.parentId())) {
                roots.add(node);
            } else {
                byId.get(node.parentId()).children().add(node);
            }
        }
        return roots;
    }

    /**
     * 启用类目树节点。字段注释与 {@code sc_product_category} DDL COMMENT 对齐。
     */
    public record CategoryNode(
            @Schema(description = "商品类目内部主键") Long id,
            @Schema(description = "平台类目永久编码") String categoryCode,
            @Schema(description = "类目名称") String categoryName,
            @Schema(description = "类目层级，从1开始") Short categoryLevel,
            @Schema(description = "父类目内部主键，逻辑引用本表id") Long parentId,
            @Schema(description = "直接子类目") List<CategoryNode> children) {
    }
}
