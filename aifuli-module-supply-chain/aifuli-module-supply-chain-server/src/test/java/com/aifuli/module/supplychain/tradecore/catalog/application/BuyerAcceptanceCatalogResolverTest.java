package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.BuyerListingDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BuyerAcceptanceCatalogResolverTest {
    private final BuyerProductMapper products = mock(BuyerProductMapper.class);
    private final BuyerListingMapper listings = mock(BuyerListingMapper.class);
    private final BuyerAcceptanceCatalogResolver resolver = new BuyerAcceptanceCatalogResolver(products, listings);

    @Test
    void removedBpdAndOldAliasResolveSameManualIdentityWithoutChangingRequestedCode() {
        BuyerProductDO product = BuyerProductDO.builder().id(7L).buyerId(1L).platformSkuId(3L)
                .buyerProductCode("BPD_7").status((short) 2).build();
        BuyerListingDO listing = BuyerListingDO.builder().id(9L).listingNo("LST_9")
                .buyerId(1L).platformSkuId(3L).buyerSkuCode("外部旧码").managementMode("MANUAL").build();
        when(products.selectByCode("BPD_7")).thenReturn(product);
        when(listings.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(listing);
        when(listings.selectByBuyerAndSkuCode(1L, "外部旧码")).thenReturn(listing);
        var bpd = resolver.resolve(1L, "BPD_7");
        var old = resolver.resolve(1L, "外部旧码");
        assertThat(bpd.listingId()).isEqualTo(9L);
        assertThat(bpd.buyerProductId()).isEqualTo(7L);
        assertThat(bpd.requestedCode()).isEqualTo("BPD_7");
        assertThat(old.listingId()).isEqualTo(bpd.listingId());
        assertThat(old.requestedCode()).isEqualTo("外部旧码");
        verify(listings, never()).updateById(any(BuyerListingDO.class));
        verify(products).selectByCode("BPD_7");
    }

    @Test
    void crossBuyerBpdNeverBecomesCurrentBuyerProduct() {
        when(products.selectByCode("BPD_OTHER")).thenReturn(BuyerProductDO.builder()
                .id(7L).buyerId(2L).platformSkuId(3L).buyerProductCode("BPD_OTHER").build());
        assertThat(resolver.resolve(1L, "BPD_OTHER").available()).isFalse();
    }

    @Test
    void conflictingDualPathsFailInsteadOfPickingEitherSku() {
        when(products.selectByCode("BPD_7")).thenReturn(BuyerProductDO.builder()
                .id(7L).buyerId(1L).platformSkuId(3L).buyerProductCode("BPD_7").build());
        when(listings.selectByBuyerAndSkuCode(1L, "BPD_7")).thenReturn(BuyerListingDO.builder()
                .id(10L).buyerId(1L).platformSkuId(4L).buyerSkuCode("BPD_7").build());
        when(listings.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(
                BuyerListingDO.builder().id(9L).buyerId(1L).platformSkuId(3L).buyerSkuCode("old").build());
        assertThatThrownBy(() -> resolver.resolve(1L, "BPD_7")).isInstanceOf(ServiceException.class);
    }

    @Test
    void missingListingKeepsBpdIdentityAndUnmodifiedRawCode() {
        when(products.selectByCode(" BPD_7 ")).thenReturn(BuyerProductDO.builder()
                .id(7L).buyerId(1L).platformSkuId(3L).buyerProductCode("BPD_7").build());
        var identity = resolver.resolve(1L, " BPD_7 ");
        assertThat(identity.available()).isFalse();
        assertThat(identity.buyerProductId()).isEqualTo(7L);
        assertThat(identity.requestedCode()).isEqualTo(" BPD_7 ");
        verify(products).selectByCode(" BPD_7 ");
    }
}
