package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSpuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSpuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.CatalogEntityStatus;
import org.junit.jupiter.api.Test;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.SKU_NOT_SALEABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SPU 与 SKU 双亲有效可售状态契约。 */
class ProductSellabilityServiceTest {

    private final PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
    private final PlatformSpuMapper spuMapper = mock(PlatformSpuMapper.class);
    private final ProductSellabilityService service = new ProductSellabilityService(skuMapper, spuMapper);

    @Test
    void onlyActiveSpuAndActiveSkuAreSellable() {
        PlatformSkuDO sku = PlatformSkuDO.builder().id(2L).spuId(1L)
                .status(CatalogEntityStatus.ACTIVE.getCode()).build();
        PlatformSpuDO spu = PlatformSpuDO.builder().id(1L).status(CatalogEntityStatus.ACTIVE.getCode()).build();
        when(skuMapper.selectByCode("SKU_A")).thenReturn(sku);
        when(spuMapper.selectById(1L)).thenReturn(spu);

        assertThat(service.requireSellableByCode("SKU_A").sku()).isSameAs(sku);

        spu.setStatus(CatalogEntityStatus.INACTIVE.getCode());
        assertThatThrownBy(() -> service.requireSellableByCode("SKU_A"))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode()).isEqualTo(SKU_NOT_SALEABLE.getCode());
    }
}
