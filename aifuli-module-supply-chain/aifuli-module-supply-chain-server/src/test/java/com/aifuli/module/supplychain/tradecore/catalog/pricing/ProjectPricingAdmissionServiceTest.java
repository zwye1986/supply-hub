package com.aifuli.module.supplychain.tradecore.catalog.pricing;

import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.ProjectListingDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.buyer.domain.ProjectListingStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.dataobject.PlatformSkuDO;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 当前锁后准入与引用矛盾边界；真实停用/受理线性化由StandardProjectHttpClosureIntegrationTest验证。 */
class ProjectPricingAdmissionServiceTest {
    private final ProjectListingMapper listings = mock(ProjectListingMapper.class);
    private final BuyerProductMapper products = mock(BuyerProductMapper.class);
    private final PlatformSkuMapper skus = mock(PlatformSkuMapper.class);
    private final ProjectPricingAdmissionService service = new ProjectPricingAdmissionService(listings, products, skus);
    private final ProjectQuoteKeyContext key = new ProjectQuoteKeyContext(1L, 2L, 4L, "ORD", 1L);
    private final StandardPricingQuoteService.FrozenQuote frozen = new StandardPricingQuoteService.FrozenQuote(
            7L, 9L, 33L, 9800L, 10000L, 10000L, 100L, 10100L, "{}", false, null);
    @BeforeEach
    void setUp() {
        when(products.selectByBuyerAndSku(1L, 4L)).thenReturn(BuyerProductDO.builder().id(6L)
                .status(BuyerProductStatus.REMOVED.getCode()).build());
        when(listings.selectByProjectAndBuyerProduct(2L, 6L)).thenReturn(ProjectListingDO.builder()
                .id(33L).status(ProjectListingStatus.REMOVED.getCode()).build());
        when(listings.selectByIdForUpdate(33L)).thenReturn(ProjectListingDO.builder().id(33L)
                .projectId(2L).buyerProductId(6L).status(ProjectListingStatus.AVAILABLE.getCode()).build());
        when(products.selectByIdForUpdate(6L)).thenReturn(BuyerProductDO.builder().id(6L)
                .buyerId(1L).platformSkuId(4L).status(BuyerProductStatus.ACTIVE.getCode()).build());
        when(skus.selectById(4L)).thenReturn(PlatformSkuDO.builder().id(4L).skuCode("SKU_A").build());
    }
    @Test
    void firstFreezeUsesSnapshotOnlyForIdsThenChecksLockedCurrentStateInOrder() {
        assertThat(service.requireForFirstFreeze(key)).isEqualTo(new ProjectPricingAdmissionService.Admission(33L, "SKU_A"));
        var order = inOrder(listings, products);
        order.verify(products).selectByBuyerAndSku(1L, 4L);
        order.verify(listings).selectByProjectAndBuyerProduct(2L, 6L);
        order.verify(listings).selectByIdForUpdate(33L);
        order.verify(products).selectByIdForUpdate(6L);
    }
    @Test
    void finalAdmissionOnlyUsesFrozenProjectListingThenBuyerProductCurrentLocks() {
        service.requireCurrentProjectAdmission(key, frozen);
        var order = inOrder(listings, products);
        order.verify(listings).selectByIdForUpdate(33L);
        order.verify(products).selectByIdForUpdate(6L);
        verify(products, never()).selectByBuyerAndSku(any(), any());
        verify(listings, never()).selectByProjectAndBuyerProduct(any(), any());
        verifyNoInteractions(skus);
    }
    @Test
    void knownUnavailableIsDedicatedWhileMissingFrozenReferenceIsSystemFailure() {
        when(products.selectByIdForUpdate(6L)).thenReturn(BuyerProductDO.builder().id(6L)
                .buyerId(1L).platformSkuId(4L).status(BuyerProductStatus.REMOVED.getCode()).build());
        assertThatThrownBy(() -> service.requireCurrentProjectAdmission(key, frozen)).isInstanceOf(ProductNotSaleableException.class);
        when(listings.selectByIdForUpdate(33L)).thenReturn(null);
        assertThatThrownBy(() -> service.requireCurrentProjectAdmission(key, frozen))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessageContaining("reference");
    }
    @Test
    void conflictingBuyerSkuReferenceIsNeverDowngradedToUnavailable() {
        when(products.selectByIdForUpdate(6L)).thenReturn(BuyerProductDO.builder().id(6L)
                .buyerId(1L).platformSkuId(44L).status(BuyerProductStatus.ACTIVE.getCode()).build());
        assertThatThrownBy(() -> service.requireCurrentProjectAdmission(key, frozen))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessageContaining("mismatched");
    }
}
