package com.aifuli.module.supplychain.tradecore.catalog.application;

import com.aifuli.framework.common.exception.ServiceException;
import com.aifuli.module.supplychain.tradecore.buyer.dal.dataobject.BuyerProductDO;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.BuyerProductMapper;
import com.aifuli.module.supplychain.tradecore.buyer.dal.mysql.ProjectListingMapper;
import com.aifuli.module.supplychain.tradecore.buyer.domain.BuyerProductStatus;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.BuyerListingMapper;
import com.aifuli.module.supplychain.tradecore.catalog.dal.mysql.PlatformSkuMapper;
import com.aifuli.module.supplychain.tradecore.supply.dal.mysql.SupplierOfferMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.aifuli.module.supplychain.error.SupplyChainErrorCodeConstants.PRODUCT_CANNOT_DELETE_HAS_PROJECT_LISTING;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 删除校验必须沿全部 BuyerProduct 查找有效项目商品，不能先过滤分销商品状态。 */
class ProductDeletionValidatorTest {

    @Test
    void removedBuyerProductStillBlocksWhenProjectListingIsAvailable() {
        PlatformSkuMapper skuMapper = mock(PlatformSkuMapper.class);
        SupplierOfferMapper offerMapper = mock(SupplierOfferMapper.class);
        BuyerListingMapper listingMapper = mock(BuyerListingMapper.class);
        BuyerProductMapper buyerProductMapper = mock(BuyerProductMapper.class);
        ProjectListingMapper projectListingMapper = mock(ProjectListingMapper.class);
        when(offerMapper.selectCount(any())).thenReturn(0L);
        when(listingMapper.selectCount(any())).thenReturn(0L);
        when(buyerProductMapper.selectList(any())).thenReturn(List.of(BuyerProductDO.builder()
                .id(31L).platformSkuId(7L).status(BuyerProductStatus.REMOVED.getCode()).build()));
        when(projectListingMapper.selectCount(any())).thenReturn(1L);
        ProductDeletionValidator validator = new ProductDeletionValidator(skuMapper, offerMapper, listingMapper,
                buyerProductMapper, projectListingMapper);

        assertThatThrownBy(() -> validator.validateSku(7L))
                .isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode())
                .isEqualTo(PRODUCT_CANNOT_DELETE_HAS_PROJECT_LISTING.getCode());
    }
}
