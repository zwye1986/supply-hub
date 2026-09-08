package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 启用类目内存组树。
 * 设计见 {@code docs/superpowers/specs/2026-09-02-supply-chain-admin-frontend-api-design.md#43-类目树}。
 */
class CategoryQueryServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void nestsEnabledChildrenUnderParent() {
        ProductCategoryMapper mapper = mock(ProductCategoryMapper.class);
        ProductCategoryDO l1 = ProductCategoryDO.builder()
                .id(1L).categoryCode("RIGHTS").categoryName("权益").categoryLevel((short) 1)
                .parentId(null).sort(0).status(CatalogEntityStatus.ACTIVE.getCode()).build();
        ProductCategoryDO l2 = ProductCategoryDO.builder()
                .id(2L).categoryCode("VOUCHER").categoryName("卡券").categoryLevel((short) 2)
                .parentId(1L).sort(0).status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(mapper.selectList(any())).thenReturn(List.of(l1, l2));
        List<CategoryQueryService.CategoryNode> tree = new CategoryQueryService(mapper).tree();
        assertThat(tree).hasSize(1);
        assertThat(tree.getFirst().id()).isEqualTo(1L);
        assertThat(tree.getFirst().children()).extracting(CategoryQueryService.CategoryNode::id)
                .containsExactly(2L);
    }
}
