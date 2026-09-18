package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductCategoryDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductCategoryMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import org.junit.jupiter.api.Test;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.CATEGORY_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductClassificationValidationServiceTest {

    private final ProductCategoryMapper categoryMapper = mock(ProductCategoryMapper.class);
    private final ProductClassificationValidationService service =
            new ProductClassificationValidationService(categoryMapper);

    @Test
    void closedEnumNameIsAcceptedWithoutCategoryLookup() {
        assertThat(service.requireWritable("VIRTUAL_CARD", true, 1L)).isEqualTo("VIRTUAL_CARD");
    }

    @Test
    void categoryCodeIsPersistedWhenMatchingPrimaryCategory() {
        when(categoryMapper.selectByCode("E2E_L1_20260903")).thenReturn(ProductCategoryDO.builder()
                .id(1L).categoryCode("E2E_L1_20260903").status(CatalogEntityStatus.ACTIVE.getCode()).build());
        assertThat(service.requireWritable("E2E_L1_20260903", true, 1L)).isEqualTo("E2E_L1_20260903");
    }

    @Test
    void categoryCodeMismatchingPrimaryThrowsCategoryNotFound() {
        when(categoryMapper.selectByCode("E2E_L1_20260903")).thenReturn(ProductCategoryDO.builder()
                .id(1L).categoryCode("E2E_L1_20260903").status(CatalogEntityStatus.ACTIVE.getCode()).build());
        assertThatThrownBy(() -> service.requireWritable("E2E_L1_20260903", true, 2L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(CATEGORY_NOT_FOUND.getCode());
    }

    @Test
    void unknownCodeThrowsCategoryNotFound() {
        when(categoryMapper.selectByCode("NOT_A_CATEGORY")).thenReturn(null);
        assertThatThrownBy(() -> service.requireWritable("NOT_A_CATEGORY", true, null))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(CATEGORY_NOT_FOUND.getCode());
    }
}
