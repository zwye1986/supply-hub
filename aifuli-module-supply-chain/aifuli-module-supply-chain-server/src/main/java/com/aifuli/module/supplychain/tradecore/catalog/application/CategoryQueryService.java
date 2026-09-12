package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CategoryVisibility;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_NOT_FOUND;

/**
 * 管理端启用类目树查询。子节点嵌在 {@code children}，不提供 {@code /tree} 路径。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#43-类目树}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryQueryService {

    private final ProductCategoryMapper categoryMapper;
    private final PlatformSpuMapper spuMapper;

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
        return assembleTree(byId, CategoryNode::parentId, CategoryNode::children);
    }

    /**
     * 查询商品中心管理树。该方法不会改变 {@link #tree()} 的启用选项契约。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#42-商品分类管理categoryadmincontroller}。
     */
    public List<ManagementCategoryNode> managementTree() {
        List<ProductCategoryDO> rows = categoryMapper.selectList(new LambdaQueryWrapper<ProductCategoryDO>()
                .ne(ProductCategoryDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                .orderByAsc(ProductCategoryDO::getSort)
                .orderByAsc(ProductCategoryDO::getId));
        Map<Long, ProductCategoryDO> rowById = new LinkedHashMap<>();
        rows.forEach(row -> rowById.put(row.getId(), row));
        Map<Long, Long> productCounts = productCounts();
        // effectiveVisible 依赖祖先链，必须在建节点前算好，否则建树后还要重新替换 children 中的节点引用。
        Map<Long, Boolean> visibilityCache = new HashMap<>();
        Map<Long, ManagementCategoryNode> byId = new LinkedHashMap<>();
        for (ProductCategoryDO row : rows) {
            byId.put(row.getId(), new ManagementCategoryNode(row.getId(), row.getCategoryCode(),
                    row.getCategoryName(), row.getCategoryLevel(), row.getParentId(), row.getSort(),
                    row.getVisible(), row.getStatus(), effectiveVisible(row, rowById, visibilityCache),
                    productCounts.getOrDefault(row.getId(), 0L), new ArrayList<>()));
        }
        log.info("查询商品分类管理树 rowCount={}", rows.size());
        return assembleTree(byId, ManagementCategoryNode::parentId, ManagementCategoryNode::children);
    }

    /**
     * 查询分类管理详情，只投影稳定业务字段，禁止把数据库主键、乐观锁版本和操作人 ID 暴露为 REST 契约。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#1-核心决策与设计基线} d16。
     */
    public CategoryDetail detail(String categoryCode) {
        log.info("查询商品分类详情 categoryCode={}", categoryCode);
        ProductCategoryDO category = categoryMapper.selectByCode(categoryCode);
        if (category == null || category.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()) {
            throw exception(CATEGORY_NOT_FOUND);
        }
        return new CategoryDetail(category.getCategoryCode(), category.getCategoryName(), category.getParentId(),
                category.getCategoryLevel(), category.getSort(), category.getVisible(), category.getStatus(),
                category.getCreatedAt(), category.getUpdatedAt());
    }

    /**
     * 按 parentId 把扁平节点组装成树。父节点不在集合中的节点视为根，保证停用或缺失父级的节点不会丢失。
     * 入参 {@code byId} 需保持查询顺序（sort、id），以便同级排序稳定。
     */
    private static <T> List<T> assembleTree(Map<Long, T> byId, Function<T, Long> parentIdOf,
                                            Function<T, List<T>> childrenOf) {
        List<T> roots = new ArrayList<>();
        for (T node : byId.values()) {
            Long parentId = parentIdOf.apply(node);
            if (parentId == null || !byId.containsKey(parentId)) {
                roots.add(node);
            } else {
                childrenOf.apply(byId.get(parentId)).add(node);
            }
        }
        return roots;
    }

    private Map<Long, Long> productCounts() {
        Map<Long, Long> counts = new LinkedHashMap<>();
        List<PlatformSpuDO> spus = spuMapper.selectList(new LambdaQueryWrapper<PlatformSpuDO>()
                .ne(PlatformSpuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode()));
        for (PlatformSpuDO spu : spus) {
            if (spu.getPrimaryCategoryId() != null) counts.merge(spu.getPrimaryCategoryId(), 1L, Long::sum);
            if (spu.getSecondaryCategoryIds() != null) {
                spu.getSecondaryCategoryIds().stream().distinct().forEach(id -> counts.merge(id, 1L, Long::sum));
            }
        }
        return counts;
    }

    /**
     * 计算节点对外可见性：自身 {@code visible == 1} 且全部祖先均可见。
     * 结果按节点缓存，避免深树上逐节点重复回溯整条祖先链；进入递归前先写入 {@code false} 占位，
     * 使脏数据形成的父子环判定为不可见而不是栈溢出。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#33-商品分类树管理无限级同级防重与层级重算}。
     */
    private static boolean effectiveVisible(ProductCategoryDO row, Map<Long, ProductCategoryDO> rowById,
                                            Map<Long, Boolean> cache) {
        Boolean cached = cache.get(row.getId());
        if (cached != null) return cached;
        cache.put(row.getId(), false);
        boolean visible = CategoryVisibility.isVisible(row.getVisible());
        if (visible && row.getParentId() != null) {
            ProductCategoryDO parent = rowById.get(row.getParentId());
            visible = parent != null && effectiveVisible(parent, rowById, cache);
        }
        cache.put(row.getId(), visible);
        return visible;
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

    /** 商品分类管理树节点。 */
    public record ManagementCategoryNode(
            @Schema(description = "商品类目内部主键") Long id,
            @Schema(description = "平台类目永久编码") String categoryCode,
            @Schema(description = "类目名称") String categoryName,
            @Schema(description = "类目层级，从1开始") Short categoryLevel,
            @Schema(description = "父类目内部主键，逻辑引用本表id") Long parentId,
            @Schema(description = "同级排序") Integer sort,
            @Schema(description = "前端展示可见性: 1-显示, 0-隐藏") Short visible,
            @Schema(description = "管理状态：0草稿，10启用，20停用，90已归档") Short status,
            @Schema(description = "自身及全部祖先均显示时为true") boolean effectiveVisible,
            @Schema(description = "未归档关联SPU数量") long productCount,
            @Schema(description = "直接子类目") List<ManagementCategoryNode> children) {
    }

    /** 商品分类详情；字段语义与 {@code sc_product_category} DDL COMMENT 一致。 */
    public record CategoryDetail(
            @Schema(description = "平台类目永久编码") String categoryCode,
            @Schema(description = "类目名称") String categoryName,
            @Schema(description = "父类目内部主键，逻辑引用本表id") Long parentId,
            @Schema(description = "类目层级，从1开始") Short categoryLevel,
            @Schema(description = "同级排序") Integer sort,
            @Schema(description = "前端展示可见性：1显示，0隐藏") Short visible,
            @Schema(description = "管理状态：0草稿，10启用，20停用，90已归档") Short status,
            @Schema(description = "分类创建时间，Asia/Shanghai") LocalDateTime createdAt,
            @Schema(description = "分类最后更新时间，Asia/Shanghai") LocalDateTime updatedAt) {
    }
}
