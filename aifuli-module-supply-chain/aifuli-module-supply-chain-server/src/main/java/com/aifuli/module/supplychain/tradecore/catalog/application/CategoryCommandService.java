package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
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
import java.util.ArrayList;
import java.util.List;

import static com.aifuli.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_HAS_CHILDREN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_HAS_PRODUCTS;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_DISABLE_HAS_PRODUCTS;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_NAME_DUPLICATE_UNDER_SAME_PARENT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_NOT_FOUND;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_PARENT_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_PARENT_IMMUTABLE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CONCURRENT_UPDATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_INVALID;

/**
 * 商品分类写服务，负责同级唯一、直接父级编号与创建后父级不可变。
 * 设计见 {@code docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#4-归属与不可变性}。
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
    private final ScopedBusinessCodeService scopedBusinessCodeService;
    private final SupplyChainDatabaseClock databaseClock;
    private final ProductTypeValidationService productTypeValidationService;

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public String create(CreateCommand command) {
        CategoryProductReferences.lockForWrite(categoryMapper);
        ProductCategoryDO parent = hasText(command.parentCategoryCode())
                ? requireLive(command.parentCategoryCode(), true) : null;
        List<String> validatedTypeCodes = validateAndResolveTypeCodes(command.typeCodes(), command.productType());
        String productType = validatedTypeCodes.isEmpty()
                ? (hasText(command.productType()) ? command.productType().trim() : null)
                : validatedTypeCodes.get(0);
        int nodeNo = scopedBusinessCodeService.allocateCategoryNodeNo(command.operatorId());
        String code = parent == null
                ? scopedBusinessCodeService.allocateRoot(ScopedBusinessCodeService.Kind.CAT_ROOT,
                command.categoryCode(), command.operatorId()).code()
                : scopedBusinessCodeService.allocateChild(ScopedBusinessCodeService.Kind.CAT_CHILD,
                parent.getId(), parent.getCategoryNodeNo(), command.categoryCode(), command.operatorId()).code();
        short level = checkedLevel(parent == null ? ROOT_LEVEL : parent.getCategoryLevel().intValue() + 1);
        requireNameAvailableUnderParent(command.categoryName().trim(),
                parent == null ? null : parent.getId(), null);
        LocalDateTime now = databaseClock.now();
        ProductCategoryDO category = ProductCategoryDO.builder()
                .categoryCode(code).categoryNodeNo((short) nodeNo).categoryName(command.categoryName().trim())
                .productType(productType)
                .typeCodes(validatedTypeCodes.isEmpty() ? null : validatedTypeCodes)
                .description(trimToNull(command.description()))
                .parentId(parent == null ? null : parent.getId()).categoryLevel(level)
                .sort(command.sort() == null ? 0 : command.sort())
                .visible(command.visible() == null ? CategoryVisibility.VISIBLE.getCode() : command.visible())
                .status(command.status() == null ? CatalogEntityStatus.DRAFT.getCode() : command.status())
                .version(0L).createdBy(command.operatorId()).createdAt(now)
                .updatedBy(command.operatorId()).updatedAt(now).build();
        insertOrDuplicate(category);
        log.info("创建商品分类 categoryCode={} productType={} typeCodes={} parentCategoryCode={} operatorId={}",
                code, productType, validatedTypeCodes, command.parentCategoryCode(), command.operatorId());
        return code;
    }

    /**
     * 分类永久编码含创建时父段，因此创建后只能提交原父级或省略父级字段。
     * 设计见 {@code docs/superpowers/specs/2026-09-27-supply-chain-business-code-design.md#4-归属与不可变性}。
     */
    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void update(String categoryCode, UpdateCommand command) {
        CategoryProductReferences.lockForWrite(categoryMapper);
        log.info("更新商品分类 categoryCode={} productType={} typeCodes={} parentCategoryCode={} operatorId={}",
                categoryCode, command.productType(), command.typeCodes(), command.parentCategoryCode(), command.operatorId());
        ProductCategoryDO category = requireLive(categoryCode, true);
        boolean hasExplicitType = (command.typeCodes() != null && !command.typeCodes().isEmpty()) || hasText(command.productType());
        List<String> validatedTypeCodes;
        String productType;
        if (hasExplicitType) {
            validatedTypeCodes = validateAndResolveTypeCodes(command.typeCodes(), command.productType());
            productType = validatedTypeCodes.isEmpty()
                    ? (hasText(command.productType()) ? command.productType().trim() : null)
                    : validatedTypeCodes.get(0);
        } else {
            validatedTypeCodes = category.getTypeCodes();
            productType = category.getProductType();
        }
        boolean explicitParent = command.parentCategoryCode() != null;
        ProductCategoryDO parent = explicitParent && hasText(command.parentCategoryCode())
                ? requireLive(command.parentCategoryCode(), true) : null;
        Long targetParentId = category.getParentId();
        if (explicitParent && !java.util.Objects.equals(targetParentId, parent == null ? null : parent.getId())) {
            throw exception(CATEGORY_PARENT_IMMUTABLE);
        }
        requireNameAvailableUnderParent(command.categoryName().trim(), targetParentId, category.getId());
        category.setCategoryName(command.categoryName().trim());
        category.setProductType(productType);
        category.setTypeCodes(validatedTypeCodes);
        category.setDescription(trimToNull(command.description()));
        category.setSort(command.sort() == null ? category.getSort() : command.sort());
        category.setVisible(command.visible() == null ? category.getVisible() : command.visible());
        touch(category, command.operatorId());
        updateOrDuplicate(category);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void changeStatus(String categoryCode, CatalogEntityStatus target, Long operatorId) {
        CategoryProductReferences.lockForWrite(categoryMapper);
        log.info("变更商品分类状态 categoryCode={} targetStatus={} operatorId={}", categoryCode, target, operatorId);
        ProductCategoryDO category = requireLive(categoryCode, true);
        if (!CatalogEntityStatus.fromCode(category.getStatus()).canTransitionTo(target)) {
            throw exception(PRODUCT_STATUS_INVALID);
        }
        if (target == CatalogEntityStatus.INACTIVE) {
            validateNoProductReferences(category, true);
        }
        if (target == CatalogEntityStatus.ARCHIVED) {
            validateDeletable(category);
        }
        category.setStatus(target.getCode());
        touch(category, operatorId);
        updateOrDuplicate(category);
    }

    @Transactional(transactionManager = TRANSACTION_MANAGER, rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void delete(String categoryCode, Long operatorId) {
        CategoryProductReferences.lockForWrite(categoryMapper);
        log.info("删除商品分类 categoryCode={} operatorId={}", categoryCode, operatorId);
        ProductCategoryDO category = requireLive(categoryCode, true);
        validateDeletable(category);
        category.setStatus(CatalogEntityStatus.ARCHIVED.getCode());
        touch(category, operatorId);
        requireUpdated(categoryMapper.updateById(category));
    }

    private void validateDeletable(ProductCategoryDO category) {
        validateNoProductReferences(category, false);
        Long childCount = categoryMapper.selectCount(new LambdaQueryWrapper<ProductCategoryDO>()
                .eq(ProductCategoryDO::getParentId, category.getId())
                .ne(ProductCategoryDO::getStatus, CatalogEntityStatus.ARCHIVED.getCode()));
        if (childCount != null && childCount > 0) {
            throw exception(CATEGORY_HAS_CHILDREN);
        }
    }

    /**
     * 商品引用优先于子分类存在性检查，错误由 HTTP 边界统一记录与渲染，不在此重复打印。
     * 设计见 {@code docs/superpowers/specs/2026-10-22-category-subtree-product-guard-design.md#3-操作与错误契约}。
     */
    private void validateNoProductReferences(ProductCategoryDO category, boolean disabling) {
        List<ProductCategoryDO> categories = new ArrayList<>(categoryMapper.selectList(new LambdaQueryWrapper<>()));
        if (categories.stream().noneMatch(row -> category.getId().equals(row.getId()))) categories.add(category);
        ProductCategoryDO referenced = CategoryProductReferences.firstReferenceWithin(category.getId(), categories,
                CategoryProductReferences.loadLiveSpus(spuMapper));
        if (referenced != null) {
            throw exception(disabling ? CATEGORY_DISABLE_HAS_PRODUCTS : CATEGORY_HAS_PRODUCTS,
                    category.getCategoryName(), category.getCategoryCode(),
                    referenced.getCategoryName(), referenced.getCategoryCode());
        }
    }

    public ProductCategoryDO detail(String categoryCode) {
        return requireLive(categoryCode, false);
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

    private List<String> validateAndResolveTypeCodes(List<String> typeCodes, String productType) {
        List<String> rawCodes = new ArrayList<>();
        if (typeCodes != null && !typeCodes.isEmpty()) {
            rawCodes.addAll(typeCodes);
        } else if (hasText(productType)) {
            rawCodes.add(productType);
        }
        List<String> validated = new ArrayList<>();
        for (String raw : rawCodes) {
            if (hasText(raw)) {
                var typeDO = productTypeValidationService.requireActiveForUpdate(raw);
                if (!validated.contains(typeDO.getTypeCode())) {
                    validated.add(typeDO.getTypeCode());
                }
            }
        }
        return validated;
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

    public record CreateCommand(String categoryCode, String categoryName, String productType,
                                List<String> typeCodes, String description,
                                String parentCategoryCode, Integer sort, Short visible, Short status,
                                Long operatorId) {
        public CreateCommand(String categoryCode, String categoryName, String productType, String description,
                             String parentCategoryCode, Integer sort, Short visible, Short status,
                             Long operatorId) {
            this(categoryCode, categoryName, productType,
                    hasText(productType) ? List.of(productType) : null,
                    description, parentCategoryCode, sort, visible, status, operatorId);
        }
    }

    public record UpdateCommand(String categoryName, String productType, List<String> typeCodes,
                                String description, String parentCategoryCode, Integer sort, Short visible,
                                Long operatorId) {
        public UpdateCommand(String categoryName, String productType, String description,
                             String parentCategoryCode, Integer sort, Short visible, Long operatorId) {
            this(categoryName, productType,
                    hasText(productType) ? List.of(productType) : null,
                    description, parentCategoryCode, sort, visible, operatorId);
        }
    }
}
