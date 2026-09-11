package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import org.junit.jupiter.api.Test;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_TYPE_INACTIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 商品类型由数据库动态配置，不以 Java 枚举作为运行时准入。 */
class ProductTypeValidationServiceTest {

    private final ProductTypeMapper mapper = mock(ProductTypeMapper.class);
    private final ProductTypeValidationService service = new ProductTypeValidationService(mapper);

    @Test
    void normalizesDynamicCodeAndRequiresActiveStatus() {
        ProductTypeDO type = ProductTypeDO.builder().typeCode("CUSTOM_DYNAMIC")
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(mapper.selectByCodeForUpdate("CUSTOM_DYNAMIC")).thenReturn(type);
        assertThat(service.requireActiveForUpdate(" custom_dynamic ")).isSameAs(type);

        type.setStatus(CatalogEntityStatus.INACTIVE.getCode());
        assertThatThrownBy(() -> service.requireActiveForUpdate("CUSTOM_DYNAMIC"))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode()).isEqualTo(PRODUCT_TYPE_INACTIVE.getCode());
    }
}
