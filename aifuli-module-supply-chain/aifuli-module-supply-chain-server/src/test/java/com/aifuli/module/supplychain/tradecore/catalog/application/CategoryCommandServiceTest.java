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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分类写应用服务单测。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#33-商品分类树管理无限级同级防重与层级重算}。
 */
class CategoryCommandServiceTest {
    static {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""),
                com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO.class);
    }


    private final ProductCategoryMapper categoryMapper = mock(ProductCategoryMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final BusinessNoGenerator generator = mock(BusinessNoGenerator.class);
    private final com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService scopedBusinessCodeService =
            mock(com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService.class);
    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);
    private final ProductTypeValidationService productTypeValidationService = mock(ProductTypeValidationService.class);

    private final CategoryCommandService service = new CategoryCommandService(
            categoryMapper, spuMapper, generator,
            scopedBusinessCodeService,
            clock, productTypeValidationService);

    @org.junit.jupiter.api.BeforeEach
    void setUpCodes() {
        when(scopedBusinessCodeService.allocateCategoryNodeNo(
                org.mockito.ArgumentMatchers.nullable(Long.class))).thenReturn(1);
        when(scopedBusinessCodeService.allocateRoot(any(),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(Long.class))).thenAnswer(inv ->
                new com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService.Allocation(
                        generator.next(BusinessNoPrefixes.CAT), 1));
    }

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
        assertThat(captor.getValue().getTypeCodes()).containsExactly("EQUITY_PRODUCT");
        assertThat(captor.getValue().getDescription()).isEqualTo("分类说明");
    }

    @Test
    void createPersistsMultipleTypeCodesAndRemark() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 10, 0);
        when(generator.next(BusinessNoPrefixes.CAT)).thenReturn("CAT_002");
        when(clock.now()).thenReturn(now);
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(productTypeValidationService.requireActiveForUpdate("EQUITY_PRODUCT"))
                .thenReturn(ProductTypeDO.builder().typeCode("EQUITY_PRODUCT").build());
        when(productTypeValidationService.requireActiveForUpdate("MOVIE"))
                .thenReturn(ProductTypeDO.builder().typeCode("MOVIE").build());
        when(productTypeValidationService.requireActiveForUpdate("DINING"))
                .thenReturn(ProductTypeDO.builder().typeCode("DINING").build());
        when(categoryMapper.insert(any(ProductCategoryDO.class))).thenReturn(1);

        service.create(new CategoryCommandService.CreateCommand(
                null, "多类型分类", null, List.of("EQUITY_PRODUCT", "MOVIE", "DINING"), "多类型说明", null, 0, (short) 1,
                CatalogEntityStatus.ACTIVE.getCode(), 1L));

        ArgumentCaptor<ProductCategoryDO> captor = ArgumentCaptor.forClass(ProductCategoryDO.class);
        verify(categoryMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        ProductCategoryDO last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getProductType()).isEqualTo("EQUITY_PRODUCT");
        assertThat(last.getTypeCodes()).containsExactly("EQUITY_PRODUCT", "MOVIE", "DINING");
        assertThat(last.getDescription()).isEqualTo("多类型说明");
    }

    /** 新建子分类完整继承父级多选及顺序，请求中的其它类型不能覆盖或追加。 */
    @Test
    void createChildInheritsAllParentTypesDespiteConflictingRequest() {
        ProductCategoryDO parent = ProductCategoryDO.builder()
                .id(10L).categoryCode("CAT_001").categoryNodeNo((short) 7).categoryLevel((short) 1)
                .status(CatalogEntityStatus.ACTIVE.getCode()).productType("MOVIE")
                .typeCodes(List.of("MOVIE", "EQUITY_PRODUCT")).build();
        when(categoryMapper.selectByCodeForUpdate("CAT_001")).thenReturn(parent);
        when(scopedBusinessCodeService.allocateChild(any(), anyLong(), anyInt(), any(), any()))
                .thenReturn(new com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService.Allocation(
                        "CAT_007_001", 1));
        for (String type : List.of("MOVIE", "EQUITY_PRODUCT", "DINING")) {
            when(productTypeValidationService.requireActiveForUpdate(type))
                    .thenReturn(ProductTypeDO.builder().typeCode(type).build());
        }

        service.create(new CategoryCommandService.CreateCommand(
                null, "子分类", "DINING", List.of("DINING"), null, "CAT_001", null, null, null, 1L));

        ArgumentCaptor<ProductCategoryDO> captor = ArgumentCaptor.forClass(ProductCategoryDO.class);
        verify(categoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getProductType()).isEqualTo("MOVIE");
        assertThat(captor.getValue().getTypeCodes()).containsExactly("MOVIE", "EQUITY_PRODUCT");
        assertThat(captor.getValue().getParentId()).isEqualTo(10L);
        assertThat(captor.getValue().getCategoryLevel()).isEqualTo((short) 2);
    }

    /** 历史父分类仅有兼容单值时，子分类不传类型也能继承并写入列表。 */
    @Test
    void createChildInheritsLegacyParentProductTypeWithoutRequestType() {
        ProductCategoryDO parent = ProductCategoryDO.builder()
                .id(10L).categoryCode("CAT_001").categoryNodeNo((short) 7).categoryLevel((short) 1)
                .status(CatalogEntityStatus.ACTIVE.getCode()).productType("EQUITY_PRODUCT").build();
        when(categoryMapper.selectByCodeForUpdate("CAT_001")).thenReturn(parent);
        when(scopedBusinessCodeService.allocateChild(any(), anyLong(), anyInt(), any(), any()))
                .thenReturn(new com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService.Allocation(
                        "CAT_007_001", 1));
        when(productTypeValidationService.requireActiveForUpdate("EQUITY_PRODUCT"))
                .thenReturn(ProductTypeDO.builder().typeCode("EQUITY_PRODUCT").build());

        service.create(new CategoryCommandService.CreateCommand(
                null, "兼容子分类", null, null, null, "CAT_001", null, null, null, 1L));

        ArgumentCaptor<ProductCategoryDO> captor = ArgumentCaptor.forClass(ProductCategoryDO.class);
        verify(categoryMapper).insert(captor.capture());
        assertThat(captor.getValue().getProductType()).isEqualTo("EQUITY_PRODUCT");
        assertThat(captor.getValue().getTypeCodes()).containsExactly("EQUITY_PRODUCT");
    }

    /** 父分类缺少类型时不能使用请求类型补齐父级缺失的业务事实。 */
    @Test
    void createChildRejectsParentWithoutProductTypesEvenWhenRequestHasType() {
        ProductCategoryDO parent = ProductCategoryDO.builder()
                .id(10L).categoryCode("CAT_001").categoryNodeNo((short) 7).categoryLevel((short) 1)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(categoryMapper.selectByCodeForUpdate("CAT_001")).thenReturn(parent);
        when(scopedBusinessCodeService.allocateChild(any(), anyLong(), anyInt(), any(), any()))
                .thenReturn(new com.aifuli.module.supplychain.infrastructure.identity.ScopedBusinessCodeService.Allocation(
                        "CAT_007_001", 1));
        when(productTypeValidationService.requireActiveForUpdate("DINING"))
                .thenReturn(ProductTypeDO.builder().typeCode("DINING").build());

        assertThatThrownBy(() -> service.create(new CategoryCommandService.CreateCommand(
                null, "无继承来源", "DINING", null, "CAT_001", null, null, null, 1L)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_PRODUCT_TYPE_REQUIRED.getCode());
    }

    @Test
    void updatePersistsMultipleTypeCodes() {
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
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.updateById(any(ProductCategoryDO.class))).thenReturn(1);
        when(clock.now()).thenReturn(LocalDateTime.now());
        when(productTypeValidationService.requireActiveForUpdate("MOVIE"))
                .thenReturn(ProductTypeDO.builder().typeCode("MOVIE").build());
        when(productTypeValidationService.requireActiveForUpdate("DINING"))
                .thenReturn(ProductTypeDO.builder().typeCode("DINING").build());

        service.update("CAT_CHILD", new CategoryCommandService.UpdateCommand(
                "更新多类型", null, List.of("MOVIE", "DINING"), "更新说明", null, null, null, 1L));

        assertThat(child.getProductType()).isEqualTo("MOVIE");
        assertThat(child.getTypeCodes()).containsExactly("MOVIE", "DINING");
        assertThat(child.getDescription()).isEqualTo("更新说明");
    }

    @Test
    void createFiltersDuplicatesInTypeCodes() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 10, 0);
        when(generator.next(BusinessNoPrefixes.CAT)).thenReturn("CAT_003");
        when(clock.now()).thenReturn(now);
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(productTypeValidationService.requireActiveForUpdate("EQUITY_PRODUCT"))
                .thenReturn(ProductTypeDO.builder().typeCode("EQUITY_PRODUCT").build());
        when(productTypeValidationService.requireActiveForUpdate("MOVIE"))
                .thenReturn(ProductTypeDO.builder().typeCode("MOVIE").build());
        when(categoryMapper.insert(any(ProductCategoryDO.class))).thenReturn(1);

        service.create(new CategoryCommandService.CreateCommand(
                null, "去重分类", null, List.of("EQUITY_PRODUCT", "EQUITY_PRODUCT", "MOVIE"), "去重说明", null, 0, (short) 1,
                CatalogEntityStatus.ACTIVE.getCode(), 1L));

        ArgumentCaptor<ProductCategoryDO> captor = ArgumentCaptor.forClass(ProductCategoryDO.class);
        verify(categoryMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        ProductCategoryDO last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.getProductType()).isEqualTo("EQUITY_PRODUCT");
        assertThat(last.getTypeCodes()).containsExactly("EQUITY_PRODUCT", "MOVIE");
    }

    @Test
    void updateClearsDescriptionWhenBlank() {
        ProductCategoryDO child = ProductCategoryDO.builder()
                .id(20L)
                .categoryCode("CAT_CHILD")
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .parentId(10L)
                .categoryLevel((short) 2)
                .description("原有旧说明")
                .productType("EQUITY_PRODUCT")
                .typeCodes(List.of("EQUITY_PRODUCT"))
                .build();
        when(categoryMapper.selectByCodeForUpdate("CAT_CHILD")).thenReturn(child);
        when(categoryMapper.selectCount(any())).thenReturn(0L);
        when(categoryMapper.updateById(any(ProductCategoryDO.class))).thenReturn(1);
        when(clock.now()).thenReturn(LocalDateTime.now());

        service.update("CAT_CHILD", new CategoryCommandService.UpdateCommand(
                "清空说明分类", null, null, "   ", null, null, null, 1L));

        assertThat(child.getDescription()).isNull();
        assertThat(child.getProductType()).isEqualTo("EQUITY_PRODUCT");
        assertThat(child.getTypeCodes()).containsExactly("EQUITY_PRODUCT");
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
                .isEqualTo(com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_PARENT_IMMUTABLE.getCode());
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
        verify(categoryMapper).selectList(any()); // 仅分类写锁，不执行子树迁移
    }

    /** 显式传空字符串请求挂到根，必须拒绝。 */
    @Test
    void updateWithBlankParentCodeRejectsMoveToRoot() {
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

        assertThatThrownBy(() -> service.update("CAT_CHILD", new CategoryCommandService.UpdateCommand(
                "挂到根", null, null, "", null, null, 1L)))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_PARENT_IMMUTABLE.getCode());
        assertThat(child.getParentId()).isEqualTo(10L);
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
    @Test
    void disableAndDeleteParentReportGrandchildProductBeforeChildrenRestriction() {
        ProductCategoryDO parent = category(10L, null, "CAT_004", "吃喝玩乐");
        ProductCategoryDO child = category(20L, 10L, "CAT_016_001", "吃");
        ProductCategoryDO grandchild = category(30L, 20L, "CAT_020_001", "孙分类");
        when(categoryMapper.selectByCodeForUpdate("CAT_004")).thenReturn(parent);
        when(categoryMapper.selectList(any())).thenReturn(List.of(parent, child, grandchild));
        when(categoryMapper.selectCount(any())).thenReturn(1L);
        when(spuMapper.selectList(any())).thenReturn(List.of(
                com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO.builder()
                        .primaryCategoryId(99L).secondaryCategoryIds(List.of(30L))
                        .status(CatalogEntityStatus.INACTIVE.getCode()).build()));

        assertThatThrownBy(() -> service.changeStatus("CAT_004", CatalogEntityStatus.INACTIVE, 1L))
                .isInstanceOf(ServiceException.class).hasMessageContaining("禁用")
                .hasMessageContaining("CAT_004").hasMessageContaining("CAT_020_001");
        assertThatThrownBy(() -> service.delete("CAT_004", 1L))
                .isInstanceOf(ServiceException.class).hasMessageContaining("删除")
                .hasMessageContaining("CAT_020_001");
        assertThat(parent.getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
        verify(categoryMapper, never()).updateById(any(ProductCategoryDO.class));
    }

    @Test
    void disableAllowsEmptySubtreeAndArchivedProductDoesNotBlock() {
        ProductCategoryDO parent = category(10L, null, "CAT_PARENT", "父分类");
        ProductCategoryDO child = category(20L, 10L, "CAT_CHILD", "子分类");
        when(categoryMapper.selectByCodeForUpdate("CAT_PARENT")).thenReturn(parent);
        when(categoryMapper.selectList(any())).thenReturn(List.of(parent, child));
        when(spuMapper.selectList(any())).thenReturn(List.of(
                com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO.builder()
                        .primaryCategoryId(20L).status(CatalogEntityStatus.ARCHIVED.getCode()).build()));
        when(categoryMapper.updateById(any(ProductCategoryDO.class))).thenReturn(1);
        when(clock.now()).thenReturn(LocalDateTime.now());
        service.changeStatus("CAT_PARENT", CatalogEntityStatus.INACTIVE, 1L);
        assertThat(parent.getStatus()).isEqualTo(CatalogEntityStatus.INACTIVE.getCode());
        assertThat(child.getStatus()).isEqualTo(CatalogEntityStatus.ACTIVE.getCode());
    }

    private static ProductCategoryDO category(Long id, Long parentId, String code, String name) {
        return ProductCategoryDO.builder().id(id).parentId(parentId).categoryCode(code).categoryName(name)
                .status(CatalogEntityStatus.ACTIVE.getCode()).version(0L).build();
    }

}
