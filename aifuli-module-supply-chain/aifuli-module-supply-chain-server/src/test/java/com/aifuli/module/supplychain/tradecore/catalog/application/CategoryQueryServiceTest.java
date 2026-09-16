package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CategoryVisibility;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    /** 分类详情只映射管理端所需的稳定业务字段，不透传持久化对象的版本与操作人信息。 */
    @Test
    void detailMapsStableReadModel() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 11, 10, 0);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 12, 11, 30);
        ProductCategoryDO row = category(7L, 1L, (short) 3, CategoryVisibility.VISIBLE,
                "CAT_EQUITY_CARD", "权益卡券");
        row.setSort(20);
        row.setVersion(9L);
        row.setCreatedBy(1001L);
        row.setCreatedAt(createdAt);
        row.setUpdatedBy(1002L);
        row.setUpdatedAt(updatedAt);
        when(categoryMapper.selectByCode("CAT_EQUITY_CARD")).thenReturn(row);

        CategoryQueryService.CategoryDetail detail = service.detail("CAT_EQUITY_CARD");

        assertThat(detail).isEqualTo(new CategoryQueryService.CategoryDetail(
                "CAT_EQUITY_CARD", "权益卡券", 1L, (short) 3, 20,
                CategoryVisibility.VISIBLE.getCode(), CatalogEntityStatus.ACTIVE.getCode(), createdAt, updatedAt));
    }

    @Test
    void detailRejectsMissingAndArchivedCategory() {
        when(categoryMapper.selectByCode("MISSING")).thenReturn(null);
        ProductCategoryDO archived = category(8L, null, (short) 1, CategoryVisibility.HIDDEN);
        archived.setStatus(CatalogEntityStatus.ARCHIVED.getCode());
        when(categoryMapper.selectByCode("ARCHIVED")).thenReturn(archived);

        assertCategoryNotFound("MISSING");
        assertCategoryNotFound("ARCHIVED");
    }

    /** 状态筛选验证：命中的子节点保留其祖先链路以维持树形拓扑，剪除无关兄弟分支与无关根节点。 */
    @Test
    void managementTreeFiltersByStatusWithBranchPreserving() {
        stubCategories(
                category(1L, null, (short) 1, CategoryVisibility.VISIBLE, CatalogEntityStatus.ACTIVE, "CAT_1", "数码"),
                category(2L, 1L, (short) 2, CategoryVisibility.VISIBLE, CatalogEntityStatus.ACTIVE, "CAT_2", "手机"),
                category(3L, 2L, (short) 3, CategoryVisibility.VISIBLE, CatalogEntityStatus.INACTIVE, "CAT_3", "停用型号"),
                category(4L, 1L, (short) 2, CategoryVisibility.VISIBLE, CatalogEntityStatus.ACTIVE, "CAT_4", "电脑"),
                category(5L, null, (short) 1, CategoryVisibility.VISIBLE, CatalogEntityStatus.ACTIVE, "CAT_5", "食品"));

        // 筛选 status=20 (停用)
        List<CategoryQueryService.ManagementCategoryNode> roots =
                service.managementTree(null, CatalogEntityStatus.INACTIVE.getCode());

        assertThat(roots).extracting(CategoryQueryService.ManagementCategoryNode::id).containsExactly(1L);
        CategoryQueryService.ManagementCategoryNode root = roots.getFirst();
        assertThat(root.categoryName()).isEqualTo("数码");
        assertThat(root.children()).extracting(CategoryQueryService.ManagementCategoryNode::id).containsExactly(2L);
        CategoryQueryService.ManagementCategoryNode child = root.children().getFirst();
        assertThat(child.categoryName()).isEqualTo("手机");
        assertThat(child.children()).extracting(CategoryQueryService.ManagementCategoryNode::id).containsExactly(3L);
        assertThat(child.children().getFirst().categoryName()).isEqualTo("停用型号");
    }

    /** 关键字筛选验证：支持分类名称或编码模糊匹配，保留命中节点祖先骨架。 */
    @Test
    void managementTreeFiltersByKeywordMatchingNameOrCode() {
        stubCategories(
                category(1L, null, (short) 1, CategoryVisibility.VISIBLE, CatalogEntityStatus.ACTIVE, "E2E_L1", "权益"),
                category(2L, 1L, (short) 2, CategoryVisibility.VISIBLE, CatalogEntityStatus.ACTIVE, "E2E_L2", "卡券"),
                category(3L, null, (short) 1, CategoryVisibility.VISIBLE, CatalogEntityStatus.ACTIVE, "FOOD_L1", "零食"));

        // 按编码模糊搜索
        List<CategoryQueryService.ManagementCategoryNode> rootsByCode =
                service.managementTree("E2E_L2", null);
        assertThat(rootsByCode).extracting(CategoryQueryService.ManagementCategoryNode::id).containsExactly(1L);
        assertThat(rootsByCode.getFirst().children())
                .extracting(CategoryQueryService.ManagementCategoryNode::id)
                .containsExactly(2L);

        // 按名称模糊搜索
        List<CategoryQueryService.ManagementCategoryNode> rootsByName =
                service.managementTree("卡券", null);
        assertThat(rootsByName).extracting(CategoryQueryService.ManagementCategoryNode::id).containsExactly(1L);
        assertThat(rootsByName.getFirst().children())
                .extracting(CategoryQueryService.ManagementCategoryNode::id)
                .containsExactly(2L);
    }

    /** 组合筛选验证：状态与关键字必须同时满足才算直接命中；无匹配时返回空列表。 */
    @Test
    void managementTreeFiltersByKeywordAndStatusCombined() {
        stubCategories(
                category(1L, null, (short) 1, CategoryVisibility.VISIBLE, CatalogEntityStatus.ACTIVE, "CAT_1", "数码"),
                category(2L, 1L, (short) 2, CategoryVisibility.VISIBLE, CatalogEntityStatus.ACTIVE, "CAT_2", "手机-启用"),
                category(3L, 1L, (short) 2, CategoryVisibility.VISIBLE, CatalogEntityStatus.INACTIVE, "CAT_3", "手机-停用"));

        // 搜 "手机" 且 status=20 (停用)，只应保留 CAT_1 -> CAT_3
        List<CategoryQueryService.ManagementCategoryNode> roots =
                service.managementTree("手机", CatalogEntityStatus.INACTIVE.getCode());
        assertThat(roots).extracting(CategoryQueryService.ManagementCategoryNode::id).containsExactly(1L);
        assertThat(roots.getFirst().children())
                .extracting(CategoryQueryService.ManagementCategoryNode::id)
                .containsExactly(3L);

        // 搜不匹配的条件返回空
        List<CategoryQueryService.ManagementCategoryNode> empty =
                service.managementTree("不存在", (short) 99);
        assertThat(empty).isEmpty();
    }

    private void assertCategoryNotFound(String categoryCode) {
        assertThatThrownBy(() -> service.detail(categoryCode))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(CATEGORY_NOT_FOUND.getCode());
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
        return category(id, parentId, level, visibility, CatalogEntityStatus.ACTIVE, categoryCode, categoryName);
    }

    private static ProductCategoryDO category(Long id, Long parentId, short level, CategoryVisibility visibility,
                                              CatalogEntityStatus status, String categoryCode, String categoryName) {
        return ProductCategoryDO.builder()
                .id(id)
                .categoryCode(categoryCode)
                .categoryName(categoryName)
                .parentId(parentId)
                .categoryLevel(level)
                .sort(0)
                .visible(visibility.getCode())
                .status(status.getCode())
                .build();
    }
}
