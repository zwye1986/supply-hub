package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoGenerator;
import com.aifuli.module.supplychain.infrastructure.identity.BusinessNoPrefixes;
import com.aifuli.module.supplychain.infrastructure.time.SupplyChainDatabaseClock;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.ProductTypeDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.ProductTypeMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_STATUS_INVALID;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_TYPE_DUPLICATE;
import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_TYPE_HAS_PRODUCTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 商品类型管理应用服务单测。
 * 设计见 {@code docs/superpowers/specs/2026-09-11-supply-chain-product-center-design.md#32-商品类型卡片列表与crud}。
 */
class ProductTypeAdminServiceTest {

    private final ProductTypeMapper typeMapper = mock(ProductTypeMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final BusinessNoGenerator generator = mock(BusinessNoGenerator.class);
    private final SupplyChainDatabaseClock clock = mock(SupplyChainDatabaseClock.class);

    private final ProductTypeAdminService service = new ProductTypeAdminService(
            typeMapper, spuMapper, generator, clock);

    @Test
    void createGeneratesCodeWhenMissingAndDisallowsDuplicateName() {
        when(clock.now()).thenReturn(LocalDateTime.now());
        when(generator.next(BusinessNoPrefixes.PT)).thenReturn("PT_123");
        when(typeMapper.insert(any(ProductTypeDO.class))).thenThrow(new DuplicateKeyException("duplicate"));

        ProductTypeAdminService.CreateCommand cmd = new ProductTypeAdminService.CreateCommand(
                null, "虚拟类型", 10, "测试", 1L);

        assertThatThrownBy(() -> service.create(cmd))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_TYPE_DUPLICATE.getCode());
    }

    @Test
    void deleteBlockedWhenProductsReferenceType() {
        ProductTypeDO type = ProductTypeDO.builder()
                .id(1L)
                .typeCode("PT_WITH_PROD")
                .status(CatalogEntityStatus.ACTIVE.getCode())
                .build();
        when(typeMapper.selectByCodeForUpdate("PT_WITH_PROD")).thenReturn(type);
        when(spuMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> service.delete("PT_WITH_PROD", 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_TYPE_HAS_PRODUCTS.getCode());
    }

    @Test
    void changeStatusOnlyAllowsActiveOrInactive() {
        assertThatThrownBy(() -> service.changeStatus("PT_X", CatalogEntityStatus.ARCHIVED, 1L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_STATUS_INVALID.getCode());
    }
}
