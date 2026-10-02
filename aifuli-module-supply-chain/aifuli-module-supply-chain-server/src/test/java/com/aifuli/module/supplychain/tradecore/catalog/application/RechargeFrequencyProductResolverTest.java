package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.catalog.domain.FulfillmentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RechargeFrequencyProductResolverTest {
    private final PlatformSkuMapper skus = mock(PlatformSkuMapper.class);
    private final RechargeFrequencyProductResolver resolver = new RechargeFrequencyProductResolver(skus);

    @ParameterizedTest
    @EnumSource(FulfillmentType.class)
    void resolvesRealServerSideSpuAndAppliesOnlyToDirectRecharge(FulfillmentType type) {
        when(skus.selectById(11L)).thenReturn(PlatformSkuDO.builder().id(11L).spuId(7L).fulfillmentType(type.code()).build());
        var identity = resolver.resolve(11L);
        assertThat(identity.platformSpuId()).isEqualTo(7L);
        assertThat(identity.directRecharge()).isEqualTo(type == FulfillmentType.DIRECT_RECHARGE);
        verify(skus).selectById(11L);
    }

    @Test
    void missingSkuOrInvalidSpuFailsClosed() {
        assertThatThrownBy(() -> resolver.resolve(11L)).isInstanceOf(IllegalStateException.class);
        when(skus.selectById(11L)).thenReturn(PlatformSkuDO.builder().id(11L).spuId(0L).build());
        assertThatThrownBy(() -> resolver.resolve(11L)).isInstanceOf(IllegalStateException.class);
    }
}
