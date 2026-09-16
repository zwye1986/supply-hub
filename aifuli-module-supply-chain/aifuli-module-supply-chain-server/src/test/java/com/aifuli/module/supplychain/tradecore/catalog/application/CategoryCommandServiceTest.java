package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_HAS_CHILDREN;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_HAS_PRODUCTS;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_NAME_DUPLICATE_UNDER_SAME_PARENT;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_PARENT_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分类写应用服务单测。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#33-商品分类树管理无限级同级防重与层级重算}。
 */
class CategoryCommandServiceTest {

    private final ProductCategoryMapper categoryMapper = mock(ProductCategoryMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final BusinessNoGenerator generator = mock(BusinessNoGenerator.class);
    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final ProductTypeValidationService productTypeValidationService = mock(ProductTypeValidationService.class);

    private final CategoryCommandService service = new CategoryCommandService(
            categoryMapper, spuMapper, generator, clock, productTypeValidationService);

    @Test
    void createPersistsValidatedProductTypeAndDescription() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 10, 0);
        when(generator.next(BusinessNoPrefixes.CAT)).thenReturn("CAT_001");
        when(clock.now()).thenReturn(now);
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(productTypeValidationService.requireActiveForUpdate(" equity_product "))
                .thenReturn(ProductTypeDO.builder().typeCode("EQUITY_PRODUCT").build());
        when(categoryMapper.insert(any(ProductCategoryDO.class))).thenReturn(1);

        service.create(new CategoryCommandService.CreateCommand(
                null, "权益卡券", " equity_product ", "  分类说明  ", null, 0, (short) 1,
                CatalogEntityStatus.ACTIVE.getCode(), 1L));

        ArgumentCaptor<ProductCategoryDO> captor = ArgumentCaptor.forClass(ProductCategoryDO.class);
        verify(categoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getProductType()).isEqualTo("EQUITY_PRODUCT");
        assertThat(captor.getValue().getDescription()).isEqualTo("分类说明");
    }

    @Test
    void createCalculatesLevelAndRejectsDuplicateNameUnderSameParent() {
        when(clock.now()).thenReturn(LocalDateTime.now());
        when(generator.next(BusinessNoPrefixes.CAT)).thenReturn("CAT_001");
        when(categoryMapper.insert(any(ProductCategoryDO.class))).thenThrow(new DuplicateKeyException("duplicate"));

        CategoryCommandService.CreateCommand cmd = new CategoryCommandService.CreateCommand(
                null, "重复分类", null, null, null, 0, (short) 1,
                CatalogEntityStatus.ACTIVE.getCode(), 1L);

        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(CATEGORY_NAME_DUPLICATE_UNDER_SAME_PARENT.getCode());
    }

    @Test
    void updateDetectsSelfAsParent() {
        ProductCategoryDO self = ProductCategoryDO.builder()
                .id(10L)
                .categoryCode("CAT_SELF")
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .categoryLevel((short) 1)
                .build();
        when(categoryMapper.selectByCodeForUpdate("CAT_SELF")).thenReturn(self);

        CategoryCommandService.UpdateCommand cmd = new CategoryCommandService.UpdateCommand(
                "新名称", null, null, "CAT_SELF", 0, (short) 1, 1L);

        assertThatThrownBy(() -> service.update("CAT_SELF", cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(CATEGORY_PARENT_INVALID.getCode());
    }

    /**
     * 仅改名的更新请求（parentCategoryCode 为 null）不得把类目静默迁到根，也不得重算子树层级。
     * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#33-商品分类树管理无限级同级防重与层级重算}。
     */
    @Test
    void updateWithoutParentCodeKeepsCurrentParentAndLevel() {
        ProductCategoryDO child = ProductCategoryDO.builder()
                .id(20L)
                .categoryCode("CAT_CHILD")
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .parentId(10L)
                .categoryLevel((short) 3)
                .sort(5)
                .visible((short) 1)
                .build();
        when(categoryMapper.selectByCodeForUpdate("CAT_CHILD")).thenReturn(child);
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.updateById(any(ProductCategoryDO.class))).thenReturn(1);
        when(clock.now()).thenReturn(LocalDateTime.now());

        service.update("CAT_CHILD", new CategoryCommandService.UpdateCommand(
                "仅改名", null, null, null, null, null, 1L));

        assertThat(child.getParentId()).isEqualTo(10L);
        assertThat(child.getCategoryLevel()).isEqualTo((short) 3);
        assertThat(child.getCategoryName()).isEqualTo("仅改名");
        verify(categoryMapper, never()).selectList(any());
    }

    /** 显式传空字符串表示挂到根，此时才重算层级。 */
    @Test
    void updateWithBlankParentCodeMovesCategoryToRoot() {
        ProductCategoryDO child = ProductCategoryDO.builder()
                .id(20L)
                .categoryCode("CAT_CHILD")
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .parentId(10L)
                .categoryLevel((short) 2)
                .sort(5)
                .visible((short) 1)
                .build();
        when(categoryMapper.selectByCodeForUpdate("CAT_CHILD")).thenReturn(child);
        when(categoryMapper.selectList(any())).thenReturn(List.of(child));
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.updateById(any(ProductCategoryDO.class))).thenReturn(1);
        when(clock.now()).thenReturn(LocalDateTime.now());

        service.update("CAT_CHILD", new CategoryCommandService.UpdateCommand(
                "挂到根", null, null, "", null, null, 1L));

        assertThat(child.getParentId()).isNull();
        assertThat(child.getCategoryLevel()).isEqualTo((short) 1);
    }

    /** 应用层预检先于唯一索引给出业务错误码。 */
    @Test
    void updateRejectsDuplicateNameUnderSameParentBeforeHittingIndex() {
        ProductCategoryDO child = ProductCategoryDO.builder()
                .id(20L)
                .categoryCode("CAT_CHILD")
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .parentId(10L)
                .categoryLevel((short) 2)
                .build();
        when(categoryMapper.selectByCodeForUpdate("CAT_CHILD")).thenReturn(child);
        when(categoryMapper.selectCount(any())).thenReturn(1L);

        CategoryCommandService.UpdateCommand cmd = new CategoryCommandService.UpdateCommand(
                "已存在的名称", null, null, null, null, null, 1L);

        assertThatThrownBy(() -> service.update("CAT_CHILD", cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(CATEGORY_NAME_DUPLICATE_UNDER_SAME_PARENT.getCode());
        verify(categoryMapper, never()).updateById(any(ProductCategoryDO.class));
    }

    @Test
    void deleteBlockedWhenHasChildren() {
        ProductCategoryDO category = ProductCategoryDO.builder()
                .id(10L)
                .categoryCode("CAT_PARENT")
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .build();
        when(categoryMapper.selectByCodeForUpdate("CAT_PARENT")).thenReturn(category);
        when(categoryMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete("CAT_PARENT", 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(CATEGORY_HAS_CHILDREN.getCode());
    }
}
