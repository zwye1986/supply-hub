package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CategoryVisibility;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_HAS_CHILDREN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_HAS_PRODUCTS;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_NAME_DUPLICATE_UNDER_SAME_PARENT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_PARENT_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CONCURRENT_UPDATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_INVALID;

/**
 * 商品分类写服务，负责同级唯一、防循环和移动时整棵子树层级重算。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#33-商品分类树管理无限级同级防重与层级重算}
 * 与 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#213-分类商品类型与描述036-changeset}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryCommandService {

    private static final String TRANSACTION_MANAGER = "supplyChainTransactionManager";
    /** 根类目层级，对应 {@code sc_product_category.category_level} 的最小取值。 */
    private static final int ROOT_LEVEL = 1;

    private final ProductCategoryMapper categoryMapper;
    private final PlatformSpuMapper spuMapper;
    private final BusinessNoGenerator businessNoGenerator;
    private final SupplyChainDatabaseClock databaseClock;
    private final ProductTypeValidationService productTypeValidationService;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public String create(CreateCommand command) {
        ProductCategoryDO parent = hasText(command.parentCategoryCode())
                ? requireLive(command.parentCategoryCode(), true) : null;
        String productType = hasText(command.productType())
                ? productTypeValidationService.requireActiveForUpdate(command.productType()).getTypeCode() : null;
        String code = hasText(command.categoryCode()) ? command.categoryCode().trim()
                : businessNoGenerator.next(BusinessNoPrefixes.CAT);
        short level = checkedLevel(parent == null ? ROOT_LEVEL : parent.getCategoryLevel().intValue() + 1);
        requireNameAvailableUnderParent(command.categoryName().trim(),
                parent == null ? null : parent.getId(), null);
        LocalDateTime now = databaseClock.now();
        ProductCategoryDO category = ProductCategoryDO.builder()
                .categoryCode(code).categoryName(command.categoryName().trim())
                .productType(productType).description(trimToNull(command.description()))
                .parentId(parent == null ? null : parent.getId()).categoryLevel(level)
                .sort(command.sort() == null ? 0 : command.sort())
                .visible(command.visible() == null ? CategoryVisibility.VISIBLE.getCode() : command.visible())
                .status(command.status() == null ? CatalogEntityStatus.DRAFT.getCode() : command.status())
                .version(0L).createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        insertOrDuplicate(category);
        log.info("创建商品分类 categoryCode={} productType={} parentCategoryCode={} operatorId={}",
                code, productType, command.parentCategoryCode(), command.operatorId());
        return code;
    }

    /**
     * 更新分类。{@code parentCategoryCode} 采用三态语义：{@code null} 不变更父级、{@code ""} 显式挂到根、
     * 非空编码挂到该父级；只有实际变更父级时才做防循环校验与整棵子树层级重算，
     * 避免仅改名或改排序的请求把类目静默迁到根并整体上移子树层级。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#33-商品分类树管理无限级同级防重与层级重算}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void update(String categoryCode, UpdateCommand command) {
        log.info("更新商品分类 categoryCode={} productType={} parentCategoryCode={} operatorId={}",
                categoryCode, command.productType(), command.parentCategoryCode(), command.operatorId());
        ProductCategoryDO category = requireLive(categoryCode, true);
        String productType = hasText(command.productType())
                ? productTypeValidationService.requireActiveForUpdate(command.productType()).getTypeCode()
                : category.getProductType();
        boolean moveParent = command.parentCategoryCode() != null;
        ProductCategoryDO parent = moveParent && hasText(command.parentCategoryCode())
                ? requireLive(command.parentCategoryCode(), true) : null;
        if (parent != null && parent.getId().equals(category.getId())) {
            throw exception(CATEGORY_PARENT_INVALID);
        }
        Long targetParentId = moveParent ? (parent == null ? null : parent.getId()) : category.getParentId();
        short newLevel = category.getCategoryLevel();
        List<ProductCategoryDO> all = List.of();
        if (moveParent) {
            all = liveCategories();
            ensureNoCycle(category.getId(), parent, all);
            newLevel = checkedLevel(parent == null ? ROOT_LEVEL : parent.getCategoryLevel().intValue() + 1);
        }
        int delta = newLevel - category.getCategoryLevel();
        requireNameAvailableUnderParent(command.categoryName().trim(), targetParentId, category.getId());
        category.setCategoryName(command.categoryName().trim());
        category.setProductType(productType);
        category.setDescription(trimToNull(command.description()));
        category.setParentId(targetParentId);
        category.setCategoryLevel(newLevel);
        category.setSort(command.sort() == null ? category.getSort() : command.sort());
        category.setVisible(command.visible() == null ? category.getVisible() : command.visible());
        touch(category, command.operatorId());
        updateOrDuplicate(category);
        if (delta != 0) {
            updateDescendantLevels(category.getId(), delta, all, command.operatorId());
        }
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void changeStatus(String categoryCode, CatalogEntityStatus target, Long operatorId) {
        log.info("变更商品分类状态 categoryCode={} targetStatus={} operatorId={}", categoryCode, target, operatorId);
        ProductCategoryDO category = requireLive(categoryCode, true);
        if (!CatalogEntityStatus.fromCode(category.getStatus()).canTransitionTo(target)) {
            throw exception(PRODUCT_STATUS_INVALID);
        }
        if (target == CatalogEntityStatus.ARCHIVED) {
            validateDeletable(category);
        }
        category.setStatus(target.getCode());
        touch(category, operatorId);
        updateOrDuplicate(category);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class)
    public void delete(String categoryCode, Long operatorId) {
        log.info("删除商品分类 categoryCode={} operatorId={}", categoryCode, operatorId);
        ProductCategoryDO category = requireLive(categoryCode, true);
        validateDeletable(category);
        category.setStatus(CatalogEntityStatus.ARCHIVED.getCode());
        touch(category, operatorId);
        requireUpdated(categoryMapper.updateById(category));
    }

    private void validateDeletable(ProductCategoryDO category) {
        Long childCount = categoryMapper.selectCount(new LambdaQueryWrapper<ProductCategoryDO>()
                .eq(ProductCategoryDO::getParentId, category.getId())
                .ne(ProductCategoryDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode()));
        if (childCount != null && childCount > 0) {
            throw exception(CATEGORY_HAS_CHILDREN);
        }
        boolean referenced = spuMapper.selectList(new LambdaQueryWrapper<PlatformSpuDO>()
                        .ne(PlatformSpuDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode()))
                .stream().anyMatch(spu -> category.getId().equals(spu.getPrimaryCategoryId())
                        || (spu.getSecondaryCategoryIds() != null
                        && spu.getSecondaryCategoryIds().contains(category.getId())));
        if (referenced) {
            throw exception(CATEGORY_HAS_PRODUCTS);
        }
    }

    public ProductCategoryDO detail(String categoryCode) {
        return requireLive(categoryCode, false);
    }

    private void updateDescendantLevels(Long rootId, int delta, List<ProductCategoryDO> all, Long operatorId) {
        Map<Long, List<ProductCategoryDO>> children = new HashMap<>();
        for (ProductCategoryDO node : all) {
            if (node.getParentId() != null) {
                children.computeIfAbsent(node.getParentId(), ignored -> new ArrayList<>()).add(node);
            }
        }
        ArrayDeque<ProductCategoryDO> queue = new ArrayDeque<>(children.getOrDefault(rootId, List.of()));
        while (!queue.isEmpty()) {
            ProductCategoryDO node = queue.removeFirst();
            node.setCategoryLevel(checkedLevel(node.getCategoryLevel().intValue() + delta));
            touch(node, operatorId);
            requireUpdated(categoryMapper.updateById(node));
            queue.addAll(children.getOrDefault(node.getId(), List.of()));
        }
    }

    private static void ensureNoCycle(Long categoryId, ProductCategoryDO parent, List<ProductCategoryDO> all) {
        if (parent == null) return;
        Map<Long, ProductCategoryDO> byId = new HashMap<>();
        all.forEach(node -> byId.put(node.getId(), node));
        Set<Long> visited = new HashSet<>();
        ProductCategoryDO cursor = parent;
        while (cursor != null) {
            if (!visited.add(cursor.getId()) || categoryId.equals(cursor.getId())) {
                throw exception(CATEGORY_PARENT_INVALID);
            }
            cursor = cursor.getParentId() == null ? null : byId.get(cursor.getParentId());
        }
    }

    private List<ProductCategoryDO> liveCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<ProductCategoryDO>()
                .ne(ProductCategoryDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode()));
    }

    private ProductCategoryDO requireLive(String code, boolean forUpdate) {
        ProductCategoryDO category = forUpdate ? categoryMapper.selectByCodeForUpdate(code) : categoryMapper.selectByCode(code);
        if (category == null || category.getStatus() == CatalogEntityStatus.ARCHIVED.getCode()) {
            throw exception(CATEGORY_NOT_FOUND);
        }
        return category;
    }

    /**
     * 同一父级下分类名称防重的应用层预检。{@code uk_sc_category_live_parent_name} 唯一索引只作为并发写入的最终防线，
     * 正常路径必须先在应用层给出明确业务错误码。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#33-商品分类树管理无限级同级防重与层级重算}。
     *
     * @param parentId   目标父级主键；根类目传 {@code null}
     * @param excludedId 更新场景下需要排除的自身主键；创建场景传 {@code null}
     */
    private void requireNameAvailableUnderParent(String categoryName, Long parentId, Long excludedId) {
        Long count = categoryMapper.selectCount(new LambdaQueryWrapper<ProductCategoryDO>()
                .eq(ProductCategoryDO::getCategoryName, categoryName)
                .eq(parentId != null, ProductCategoryDO::getParentId, parentId)
                .isNull(parentId == null, ProductCategoryDO::getParentId)
                .ne(ProductCategoryDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode())
                .ne(excludedId != null, ProductCategoryDO::getId, excludedId));
        if (count != null && count > 0) {
            throw exception(CATEGORY_NAME_DUPLICATE_UNDER_SAME_PARENT);
        }
    }

    private void insertOrDuplicate(ProductCategoryDO category) {
        try {
            categoryMapper.insert(category);
        } catch (DuplicateKeyException duplicate) {
            throw exception(CATEGORY_NAME_DUPLICATE_UNDER_SAME_PARENT);
        }
    }

    private void updateOrDuplicate(ProductCategoryDO category) {
        try {
            requireUpdated(categoryMapper.updateById(category));
        } catch (DuplicateKeyException duplicate) {
            throw exception(CATEGORY_NAME_DUPLICATE_UNDER_SAME_PARENT);
        }
    }

    private void touch(ProductCategoryDO category, Long operatorId) {
        category.setUpdatedBy(operatorId);
        category.setUpdatedAt(databaseClock.now());
    }

    private static short checkedLevel(int level) {
        if (level < 1 || level > Short.MAX_VALUE) {
            throw exception(CATEGORY_PARENT_INVALID);
        }
        return (short) level;
    }

    private static void requireUpdated(int count) {
        if (count != 1) throw exception(PRODUCT_CONCURRENT_UPDATE);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        if (!hasText(value)) {
            return null;
        }
        return value.trim();
    }

    public record CreateCommand(String categoryCode, String categoryName, String productType, String description,
                                String parentCategoryCode, Integer sort, Short visible, Short status,
                                Long operatorId) {
    }

    public record UpdateCommand(String categoryName, String productType, String description,
                                String parentCategoryCode, Integer sort, Short visible, Long operatorId) {
    }
}
