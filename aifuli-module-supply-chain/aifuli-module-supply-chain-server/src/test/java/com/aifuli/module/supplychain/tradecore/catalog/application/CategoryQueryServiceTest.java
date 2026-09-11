package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CategoryVisibility;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 分类读应用服务单测，覆盖启用类目树的内存组树，以及管理树的建树与对外可见性继承。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#43-类目树}
 * 与 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#33-商品分类树管理无限级同级防重与层级重算}。
 */
class CategoryQueryServiceTest {

    private final ProductCategoryMapper categoryMapper = mock(ProductCategoryMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);

    private final CategoryQueryService service = new CategoryQueryService(categoryMapper, spuMapper);

    @Test
    void nestsEnabledChildrenUnderParent() {
        stubCategories(
                category(1L, null, (short) 1, CategoryVisibility.VISIBLE, "RIGHTS", "权益"),
                category(2L, 1L, (short) 2, CategoryVisibility.VISIBLE, "VOUCHER", "卡券"));

        List<CategoryQueryService.CategoryNode> tree = service.tree();

        assertThat(tree).hasSize(1);
        assertThat(tree.getFirst().id()).isEqualTo(1L);
        assertThat(tree.getFirst().children()).extracting(CategoryQueryService.CategoryNode::id)
                .containsExactly(2L);
    }

    @Test
    void managementTreeNestsChildrenAndInheritsHiddenAncestor() {
        stubCategories(
                category(1L, null, (short) 1, CategoryVisibility.HIDDEN),
                category(2L, 1L, (short) 2, CategoryVisibility.VISIBLE),
                category(3L, 2L, (short) 3, CategoryVisibility.VISIBLE),
                category(4L, null, (short) 1, CategoryVisibility.VISIBLE));

        List<CategoryQueryService.ManagementCategoryNode> roots = service.managementTree();

        assertThat(roots).extracting(CategoryQueryService.ManagementCategoryNode::id).containsExactly(1L, 4L);
        CategoryQueryService.ManagementCategoryNode hiddenRoot = roots.getFirst();
        assertThat(hiddenRoot.effectiveVisible()).isFalse();
        // 自身 visible=1 但祖先隐藏，整条链对外不可见。
        assertThat(hiddenRoot.children()).singleElement()
                .satisfies(child -> {
                    assertThat(child.id()).isEqualTo(2L);
                    assertThat(child.effectiveVisible()).isFalse();
                    assertThat(child.children()).singleElement()
                            .satisfies(grandchild -> assertThat(grandchild.effectiveVisible()).isFalse());
                });
        assertThat(roots.get(1).effectiveVisible()).isTrue();
    }

    @Test
    void managementTreeTreatsCategoryWithMissingParentAsRoot() {
        // 父级已归档不在结果集内，子节点必须升为根，否则整棵子树在管理页丢失；
        // 但祖先链不完整时对外仍判定为不可见，避免归档父级下的类目继续暴露给前端。
        stubCategories(category(9L, 8L, (short) 2, CategoryVisibility.VISIBLE));

        List<CategoryQueryService.ManagementCategoryNode> roots = service.managementTree();

        assertThat(roots).extracting(CategoryQueryService.ManagementCategoryNode::id).containsExactly(9L);
        assertThat(roots.getFirst().effectiveVisible()).isFalse();
    }

    @Test
    void managementTreeTreatsParentCycleAsInvisibleWithoutOverflow() {
        // 脏数据形成的父子环不得导致栈溢出，判定为不可见即可。
        stubCategories(
                category(1L, 2L, (short) 1, CategoryVisibility.VISIBLE),
                category(2L, 1L, (short) 2, CategoryVisibility.VISIBLE));

        List<CategoryQueryService.ManagementCategoryNode> roots = service.managementTree();

        assertThat(roots).isEmpty();
    }

    private void stubCategories(ProductCategoryDO... rows) {
        when(categoryMapper.selectList(any())).thenReturn(List.of(rows));
        when(spuMapper.selectList(any())).thenReturn(List.of());
    }

    private static ProductCategoryDO category(Long id, Long parentId, short level, CategoryVisibility visibility) {
        return category(id, parentId, level, visibility, "CAT_" + id, "类目" + id);
    }

    private static ProductCategoryDO category(Long id, Long parentId, short level, CategoryVisibility visibility,
                                              String categoryCode, String categoryName) {
        return ProductCategoryDO.builder()
                .id(id)
                .categoryCode(categoryCode)
                .categoryName(categoryName)
                .parentId(parentId)
                .categoryLevel(level)
                .sort(0)
                .visible(visibility.getCode())
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .build();
    }
}
