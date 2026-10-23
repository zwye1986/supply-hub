package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_ANCESTOR_UNAVAILABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_PARENT_INVALID;

/**
 * 分类子树商品引用的共享算法及事务写锁协议，不持有跨请求缓存。
 * 管理树按分类归属累加，操作校验返回实际被引用的分类，避免把累加值解释为独立商品数。
 * 设计见 {@code docs/superpowers/specs/2026-10-22-category-subtree-product-guard-design.md#2-统计与引用口径}。
 */
final class CategoryProductReferences {
    private CategoryProductReferences() { }

    /**
     * 所有分类/商品归属写入先按主键锁定分类集合，再取得商品、类型与编号等其它锁。
     * 必须使用 RC，避免锁等待后的一致性读取仍使用旧快照；单测直接调用无 Spring 事务时仅执行 Mapper。
     * 设计见 {@code docs/superpowers/specs/2026-10-22-category-subtree-product-guard-design.md#4-并发与事务}。
     */
    static void lockForWrite(ProductCategoryMapper mapper) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
                .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException("Category reference writes require READ_COMMITTED");
        }
        mapper.selectList(new LambdaQueryWrapper<ProductCategoryDO>()
                .orderByAsc(ProductCategoryDO::getId).last("FOR UPDATE"));
    }

    static List<PlatformSpuDO> loadLiveSpus(PlatformSpuMapper mapper) {
        return mapper.selectList(new LambdaQueryWrapper<PlatformSpuDO>()
                .select(PlatformSpuDO::getId, PlatformSpuDO::getPrimaryCategoryId,
                        PlatformSpuDO::getSecondaryCategoryIds, PlatformSpuDO::getStatus)
                .ne(PlatformSpuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode()));
    }

    /** 同一商品在同一分类的主/辅助引用先合并，不对不同分类的引用合并。 */
    private static Set<Long> categoryIds(PlatformSpuDO spu) {
        Set<Long> ids = new LinkedHashSet<>();
        if (spu.getPrimaryCategoryId() != null) ids.add(spu.getPrimaryCategoryId());
        if (spu.getSecondaryCategoryIds() != null) ids.addAll(spu.getSecondaryCategoryIds());
        ids.remove(null);
        return ids;
    }

    /** 完整树上累加直接引用，再向祖先传播；visited 阻断坏数据环，过滤展示节点在此步骤之后执行。 */
    static Map<Long, Long> subtreeCounts(List<ProductCategoryDO> categories, List<PlatformSpuDO> spus) {
        Map<Long, ProductCategoryDO> byId = index(categories);
        Map<Long, Long> direct = new LinkedHashMap<>();
        for (PlatformSpuDO spu : spus) {
            if (Objects.equals(spu.getStatus(), CatalogEntityStatus.ARCHIVED.getCode())) continue;
            for (Long id : categoryIds(spu)) direct.merge(id, 1L, Long::sum);
        }
        Map<Long, Long> totals = new LinkedHashMap<>();
        direct.forEach((id, count) -> {
            Set<Long> visited = new HashSet<>();
            ProductCategoryDO cursor = byId.get(id);
            while (cursor != null && visited.add(cursor.getId())) {
                totals.merge(cursor.getId(), count, Long::sum);
                cursor = byId.get(cursor.getParentId());
            }
        });
        return totals;
    }

    /** 包含自身、任意深度后代及历史归档桥接节点，返回首个实际被未归档商品引用的分类。 */
    static ProductCategoryDO firstReferenceWithin(Long rootId, List<ProductCategoryDO> categories,
                                                 List<PlatformSpuDO> spus) {
        Map<Long, ProductCategoryDO> byId = index(categories);
        for (PlatformSpuDO spu : spus) {
            if (Objects.equals(spu.getStatus(), CatalogEntityStatus.ARCHIVED.getCode())) continue;
            for (Long id : categoryIds(spu)) {
                ProductCategoryDO referenced = byId.get(id);
                ProductCategoryDO cursor = referenced;
                Set<Long> visited = new HashSet<>();
                while (cursor != null && visited.add(cursor.getId())) {
                    if (Objects.equals(cursor.getId(), rootId)) return referenced;
                    cursor = byId.get(cursor.getParentId());
                }
            }
        }
        return null;
    }

    /**
     * 分类锁内复核绑定目标的完整祖先链；停用/归档祖先不能因叶节点仍启用而被绕过。
     * 草稿商品允许草稿分类；旧引用移出只检查新分类，不阻止清理历史关系。
     */
    static void requireWritableAncestors(ProductCategoryMapper mapper, ProductCategoryDO category,
                                         boolean activeRequired) {
        Map<Long, ProductCategoryDO> byId = index(mapper.selectList(new LambdaQueryWrapper<>()));
        byId.put(category.getId(), category);
        Set<Long> visited = new HashSet<>();
        ProductCategoryDO cursor = category;
        while (cursor != null) {
            if (!visited.add(cursor.getId())) throw exception(CATEGORY_PARENT_INVALID);
            if (Objects.equals(cursor.getStatus(), CatalogEntityStatus.INACTIVE.getCode())
                    || Objects.equals(cursor.getStatus(), CatalogEntityStatus.ARCHIVED.getCode())
                    || (activeRequired && !Objects.equals(cursor.getStatus(), CatalogEntityStatus.ACTIVE.getCode()))) {
                throw exception(CATEGORY_ANCESTOR_UNAVAILABLE, cursor.getCategoryName(), cursor.getCategoryCode());
            }
            Long parentId = cursor.getParentId();
            if (parentId != null && !byId.containsKey(parentId)) throw exception(CATEGORY_PARENT_INVALID);
            cursor = byId.get(parentId);
        }
    }

    private static Map<Long, ProductCategoryDO> index(List<ProductCategoryDO> rows) {
        Map<Long, ProductCategoryDO> result = new LinkedHashMap<>();
        for (ProductCategoryDO row : rows) result.put(row.getId(), row);
        return result;
    }
}
